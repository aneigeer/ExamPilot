package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.wiki.WikiHitVO;
import com.examprep.entity.WikiPage;
import com.examprep.mapper.WrongBookMapper;
import com.examprep.service.QuestionService;
import com.examprep.service.StudyPlanService;
import com.examprep.service.ai.AiProperties;
import com.examprep.service.ai.AiQaService;
import com.examprep.service.ai.AnswerPostProcessor;
import com.examprep.service.ai.ExtractiveAnswerClient;
import com.examprep.service.ai.HybridRetriever;
import com.examprep.service.ai.KnowledgeProfileService;
import com.examprep.service.ai.LlmClient;
import com.examprep.service.ai.PromptBuilder;
import com.examprep.service.ai.agent.AsyncJobService;
import com.examprep.service.wiki.WikiQueryService;
import com.examprep.service.wiki.WikiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-10 修复回归：Agent 定稿接口的拒答不变量 + Wiki 工具的相关性闸门
 *
 * <p>真实症状：函数专题问题经 {@code /v1/agent/run} 返回 {@code refused=false}，
 * 但正文是「未找到相关资料[1][2][3]。」——因为草稿里的引用编号能被
 * {@code LlmClient.resolveSources} 解析出 sources，于是旧的
 * {@code refused = sources 为空} 判据失效。本测试锁住三点：
 * <ol>
 *   <li>拒答正文必须归一为统一文案且 sources 清空；</li>
 *   <li>可溯源答案不受影响；</li>
 *   <li>Wiki 检索工具按 {@code hitThreshold()} 过滤低分命中。</li>
 * </ol>
 */
@DisplayName("F-10：Agent 定稿拒答不变量与 Wiki 相关性闸门")
class AgentInternalControllerAnswerTest {

    private AgentInternalController controller;
    private WikiInternalController wikiController;
    private WikiQueryService wikiQueryService;
    private AnswerPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AnswerPostProcessor(mock(ExtractiveAnswerClient.class));
        controller = new AgentInternalController(
                mock(HybridRetriever.class),
                mock(KnowledgeProfileService.class),
                mock(WrongBookMapper.class),
                mock(StudyPlanService.class),
                mock(QuestionService.class),
                mock(AiQaService.class),
                processor,
                mock(AsyncJobService.class),
                mock(AiProperties.class),
                mock(ObjectMapper.class),
                List.<LlmClient>of());

        wikiQueryService = mock(WikiQueryService.class);
        wikiController = new WikiInternalController(mock(WikiService.class), wikiQueryService);
    }

    private static Map<String, Object> context(long id, String title, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chunkId", id);
        item.put("sourceType", "WIKI");
        item.put("sourceId", id);
        item.put("title", title);
        item.put("content", content);
        item.put("refType", "WIKI");
        item.put("refId", id);
        item.put("score", 0.9);
        return item;
    }

    private static Map<String, Object> answerBody(String question, String draft, List<Map<String, Object>> contexts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("draftAnswer", draft);
        body.put("contexts", contexts);
        return body;
    }

    @Test
    @DisplayName("草稿含拒答文案 + 引用编号：refused=true、正文归一、sources 清空")
    void refusalDraftIsNormalized() {
        List<Map<String, Object>> contexts = List.of(
                context(11L, "函数基础", "函数定义与调用"),
                context(12L, "参数传递", "值传递与地址传递"),
                context(13L, "递归", "递归与栈"));

        Map<String, Object> data = controller.answer(
                answerBody("函数专题的 Wiki 页面讲了什么", "未找到相关资料[1][2][3]。", contexts)).getData();

        assertTrue((Boolean) data.get("refused"), "正文含拒答文案必须判为拒答");
        assertEquals(PromptBuilder.REFUSAL_TEXT, data.get("answer"), "拒答正文必须归一为统一文案");
        assertTrue(((List<?>) data.get("sources")).isEmpty(), "拒答时 sources 必须为空");
    }

    @Test
    @DisplayName("可溯源草稿：refused=false、正文原样、sources 保留")
    void traceableDraftIsKept() {
        List<Map<String, Object>> contexts = List.of(
                context(11L, "函数基础", "函数定义与调用"),
                context(12L, "参数传递", "值传递与地址传递"),
                context(13L, "递归", "递归与栈"));
        String draft = "值传递是复制一份，地址传递是传地址 [1]。";

        Map<String, Object> data = controller.answer(
                answerBody("值传递和地址传递有什么区别", draft, contexts)).getData();

        assertFalse((Boolean) data.get("refused"), "有可溯源引用的答案不应判为拒答");
        assertEquals(draft, data.get("answer"), "正文不得被改动");
        assertEquals(1, ((List<?>) data.get("sources")).size(), "引用 [1] 对应一条来源");
    }

    @Test
    @DisplayName("Wiki 工具：只返回达到阈值的命中，低于阈值的丢弃")
    void wikiSearchAppliesThreshold() {
        WikiPage high = mock(WikiPage.class);
        WikiPage low = mock(WikiPage.class);
        when(wikiQueryService.hitThreshold()).thenReturn(0.55);
        when(wikiQueryService.search(anyString(), any(), anyInt())).thenReturn(List.of(
                new WikiQueryService.WikiHit(high, 0.8, "NGRAM", false),
                new WikiQueryService.WikiHit(low, 0.3, "VECTOR", false)));
        when(wikiQueryService.toVO(any())).thenAnswer(invocation -> {
            WikiQueryService.WikiHit hit = invocation.getArgument(0);
            WikiHitVO vo = new WikiHitVO();
            vo.setId(hit.score() >= 0.55 ? 1L : 2L);
            vo.setTitle(hit.score() >= 0.55 ? "函数专题" : "跨学科页面");
            vo.setScore(hit.score());
            vo.setChannel(hit.channel());
            return vo;
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", "函数专题的 Wiki 页面讲了什么");
        Result<Map<String, Object>> result = wikiController.search(body);

        @SuppressWarnings("unchecked")
        List<WikiHitVO> hits = (List<WikiHitVO>) result.getData().get("hits");
        assertEquals(1, hits.size(), "低分命中必须被阈值拦掉");
        assertEquals("函数专题", hits.get(0).getTitle());
    }

    @Test
    @DisplayName("Wiki 工具：全部命中低于阈值时返回空列表，不抛异常")
    void wikiSearchReturnsEmptyWhenAllBelowThreshold() {
        when(wikiQueryService.hitThreshold()).thenReturn(0.55);
        when(wikiQueryService.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(new WikiQueryService.WikiHit(mock(WikiPage.class), 0.2, "VECTOR", false)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", "函数专题的 Wiki 页面讲了什么");
        Result<Map<String, Object>> result = wikiController.search(body);

        assertTrue(((List<?>) result.getData().get("hits")).isEmpty());
    }
}
