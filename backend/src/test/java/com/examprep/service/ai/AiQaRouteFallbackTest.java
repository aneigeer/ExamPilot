package com.examprep.service.ai;

import com.examprep.config.ObservabilityProperties;
import com.examprep.dto.ai.AskRequest;
import com.examprep.service.wiki.WikiQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由过滤在真实链路里的两个保命行为
 *
 * <p>这两条都不是「看起来对」，而是**踩过坑才补的**：
 * <ol>
 *   <li><b>降级路由不得用于过滤</b>：Python 的 SIM_LOW 会返回 {@code degraded=true}
 *       却仍带 {@code source_types=[QUESTION,KNOWLEDGE]}。照用会把课件里能答对的内容整段滤掉
 *       （实测「分块大小怎么选」→ {@code refused=true} 且引用无关行测题）；</li>
 *   <li><b>过滤后为空要放宽一次</b>：skill 隔离必然带来「答案在别的类型里」的漏检，
 *       放宽一次把「真没有」与「被过滤掉」区分开，避免把可回答问题问死。</li>
 * </ol>
 *
 * <p><b>为什么直接测这两个方法而不是走 {@code ask()}</b>：{@code ask()} 后半段还要过生成、
 * L3 校验、落库，用 mock 拼到底只会测出「mock 之间的连线」，反而看不出这两个行为。
 * 这里断言的是**真正传给 {@code retrieve} 的参数**（第 6 参）与**调用次数**——即链路事实。
 */
@DisplayName("AiQaService：降级不过滤 / 空结果放宽一次")
class AiQaRouteFallbackTest {

    private AiQaService service;
    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = mock(HybridRetriever.class);
        // 只关心路由/检索两件事，其余依赖给 mock（本类测试路径不会用到它们）
        service = new AiQaService(retriever,
                mock(AiRouteClient.class),
                mock(KnowledgeProfileService.class),
                mock(ExtractiveAnswerClient.class),
                mock(AnswerPostProcessor.class),
                mock(PromptBuilder.class),
                mock(ChunkSyncService.class),
                new AiProperties(),
                mock(WikiQueryService.class),
                mock(VectorChannel.class),
                List.of(),
                new ObservabilityProperties());
    }

    private static AskRequest request() {
        AskRequest req = new AskRequest();
        req.setQuestion("分块大小怎么选");
        return req;
    }

    private static ChunkContext chunk(long id, String type) {
        return new ChunkContext(id, type, id, "标题" + id, "正文" + id,
                null, null, 3, type, id, null, null, 0, 30.0, 1.0);
    }

    private static HybridRetriever.Outcome outcome(List<ChunkContext> contexts) {
        double best = contexts.isEmpty() ? 0.0 : contexts.get(0).finalScore();
        return HybridRetriever.Outcome.of(contexts, "VECTOR_HYBRID", contexts.size(), best, 0.7);
    }

    /** 降级路由（自带 filter）→ 传给检索的第 6 参必须是 null */
    @Test
    @DisplayName("降级路由：实际传给 retrieve 的 sourceTypes 为 null（不是它给的过滤）")
    void degradedRoutePassesNullFilterToRetriever() {
        AiRouteClient.RouteResult degraded = new AiRouteClient.RouteResult(
                "COURSE_QA", List.of("QUESTION", "KNOWLEDGE"), 0.57, 0.01, true, "SIM_LOW");

        Set<String> actual = service.routeSourceTypes(degraded);

        assertNull(actual, "降级路由的判断不可信，必须退化为「不过滤」");
        // 再走一遍 seam，确认「实际传给 retrieve 的第 6 参」也是 null
        when(retriever.retrieve(anyString(), any(), any(), any(), eq((Set<String>) null)))
                .thenReturn(outcome(List.of(chunk(1, "COURSEWARE"))));
        service.retrieveWithRouteFallback("分块大小怎么选", request(), Set.of(), degraded, actual);

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(retriever).retrieve(anyString(), any(), any(), any(), captor.capture());
        assertNull(captor.getValue(), "真实调用链上第 6 参必须为 null");
    }

    /** 过滤后为空 → 用 null 重试一次，返回放宽后的结果 */
    @Test
    @DisplayName("过滤后命中 0 条：放宽一次（第二次第 6 参为 null），返回放宽结果")
    void emptyFilteredResultWidensOnce() {
        AiRouteClient.RouteResult normal = new AiRouteClient.RouteResult(
                "COURSE_QA", List.of("QUESTION", "KNOWLEDGE"), 0.93, 0.2, false, "ROUTER_OK");
        Set<String> filter = service.routeSourceTypes(normal);

        when(retriever.retrieve(anyString(), any(), any(), any(), eq(filter)))
                .thenReturn(outcome(List.of()));
        when(retriever.retrieve(anyString(), any(), any(), any(), isNull(Set.class)))
                .thenReturn(outcome(List.of(chunk(7, "COURSEWARE"), chunk(8, "COURSEWARE"),
                        chunk(9, "QUESTION"))));

        HybridRetriever.Outcome result =
                service.retrieveWithRouteFallback("分块大小怎么选", request(), Set.of(), normal, filter);

        verify(retriever, times(2)).retrieve(anyString(), any(), any(), any(), any());
        assertEquals(3, result.contexts().size(), "应返回放宽后的 3 条，而不是空结果直接拒答");
    }

    /** 两次都空 → 不递归第三次，把空结果交给上层正常拒答 */
    @Test
    @DisplayName("放宽后仍为空：只重试一次，不递归（避免把一次问答拖成 N 次检索）")
    void emptyAfterWidenDoesNotRetryAgain() {
        AiRouteClient.RouteResult normal = new AiRouteClient.RouteResult(
                "COURSE_QA", List.of("QUESTION"), 0.9, 0.2, false, "ROUTER_OK");
        Set<String> filter = service.routeSourceTypes(normal);

        when(retriever.retrieve(anyString(), any(), any(), any(), any()))
                .thenReturn(outcome(List.of()));

        HybridRetriever.Outcome result =
                service.retrieveWithRouteFallback("不存在的主题", request(), Set.of(), normal, filter);

        verify(retriever, times(2)).retrieve(anyString(), any(), any(), any(), any());
        assertTrue(result.contexts().isEmpty(), "两次都空应如实返回空结果（交给上层拒答）");
    }

    /** 本来就没过滤（路由关闭/降级）→ 不得多打一次检索 */
    @Test
    @DisplayName("没有过滤时不会触发放宽：检索只调用 1 次（性能与行为都不该变）")
    void noWidenWhenNoFilter() {
        AiRouteClient.RouteResult disabled = new AiRouteClient.RouteResult(
                "COURSE_QA", List.of(), 0.0, 0.0, true, "ROUTER_DISABLED");
        when(retriever.retrieve(anyString(), any(), any(), any(), isNull(Set.class)))
                .thenReturn(outcome(List.of()));

        service.retrieveWithRouteFallback("任意问题", request(), Set.of(), disabled,
                service.routeSourceTypes(disabled));

        verify(retriever, times(1)).retrieve(anyString(), any(), any(), any(), any());
    }
}
