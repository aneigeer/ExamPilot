package com.examprep.service.ai;

import com.examprep.dto.ai.AskRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.service.wiki.WikiQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拒答输出不变量：{@code refused=true ⇒ answer == REFUSAL_TEXT}
 *
 * <p><b>为什么需要这条</b>（QA 阶段二验收提出的真实症状）：{@code isRefusal()} 是
 * 「sources 空 / 正文含拒答文案 / generator=none」三条件之一命中即 true。
 * 其中「sources 空」会在<b>抽取式兜底也没取到来源</b>时命中——此时正文是有的、
 * 但不可溯源，于是出现 {@code refused=true} 且带非空正文的自相矛盾响应，
 * 前端若按 refused 渲染就会把「没有依据的正文」展示成答案。
 *
 * <p><b>口径选择</b>：归一为 {@code REFUSAL_TEXT}（不是空串）——拒答文案本来就是接口约定，
 * 空串会与「后端 bug 返回空」混淆，排障时反而更难判断。
 *
 * <p><b>没改判据</b>：{@code isRefusal} 的三条件一个字没动，
 * 因为「没有可溯源答案」就是拒答的语义，问题出在<b>输出组装</b>没守住不变量。
 */
@DisplayName("拒答不变量：refused=true 时正文必须为统一拒答文案")
class AiQaRefusalInvariantTest {

    private ExtractiveAnswerClient extractive;
    private HybridRetriever retriever;
    private WikiQueryService wiki;
    private AiQaService service;

    @BeforeEach
    void setUp() {
        extractive = mock(ExtractiveAnswerClient.class);
        retriever = mock(HybridRetriever.class);
        wiki = mock(WikiQueryService.class);
        when(wiki.isEnabled()).thenReturn(false);

        AiRouteClient route = mock(AiRouteClient.class);
        when(route.route(anyString(), any()))
                .thenReturn(AiRouteClient.RouteResult.fallback("ROUTER_DISABLED"));

        AiProperties properties = new AiProperties();
        AnswerPostProcessor processor = new AnswerPostProcessor(extractive);
        service = new AiQaService(retriever, route,
                mock(KnowledgeProfileService.class), extractive, processor,
                new PromptBuilder(properties), mock(ChunkSyncService.class), properties,
                wiki, mock(VectorChannel.class), List.of());
    }

    private static AskRequest request() {
        AskRequest req = new AskRequest();
        req.setQuestion("随便问一个没有答案的问题");
        return req;
    }

    private void stubRetrievalWithOneContext() {
        var ctx = new com.examprep.service.ai.ChunkContext(1L, "QUESTION", 1L, "标题1", "正文1",
                null, null, 3, "QUESTION", 1L, null, null, 0, 30.0, 1.0);
        when(retriever.retrieve(anyString(), any(), any(), any(), any()))
                .thenReturn(HybridRetriever.Outcome.of(List.of(ctx), "VECTOR_HYBRID", 1, 1.0, 0.7));
    }

    @Test
    @DisplayName("抽取式也没取到 sources：refused=true 时正文必须是拒答文案（不是模型/抽取原文）")
    void refusedAnswerIsNormalizedToRefusalText() {
        stubRetrievalWithOneContext();
        // 关键构造：有正文、但没有 sources → isRefusal 的第一个条件命中
        when(extractive.ask(anyString(), any()))
                .thenReturn(new LlmAnswer("这是一段没有来源的抽取正文", List.of(),
                        PromptBuilder.GENERATOR_EXTRACTIVE, 5L, true));

        AskVO vo = service.ask(request(), null);

        assertTrue(vo.getRefused(), "无 sources 应判为拒答");
        assertEquals(PromptBuilder.REFUSAL_TEXT, vo.getAnswer(),
                "拒答时正文必须归一为统一拒答文案，不能把不可溯源的正文返回给用户");
        assertTrue(vo.getSources() == null || vo.getSources().isEmpty());
    }

    @Test
    @DisplayName("正常可溯源答案不受影响：refused=false 时正文原样返回")
    void traceableAnswerIsNotTouched() {
        stubRetrievalWithOneContext();
        when(extractive.ask(anyString(), any()))
                .thenReturn(new LlmAnswer("C 语言 int 通常占 4 字节 [1]。",
                        List.of(SourceRef.of(1L, "标题1", "正文1", "QUESTION", 1L, null)),
                        PromptBuilder.GENERATOR_EXTRACTIVE, 5L, true));

        AskVO vo = service.ask(request(), null);

        assertFalse(vo.getRefused(), "有 sources 的答案不应被误判拒答");
        assertEquals("C 语言 int 通常占 4 字节 [1]。", vo.getAnswer(), "正文不得被改动");
        assertEquals(1, vo.getSources().size());
    }
}
