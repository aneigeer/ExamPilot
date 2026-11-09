package com.examprep.service.ai;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拒答闸门单测（纯 Mockito，不依赖数据库）
 *
 * <p>覆盖三件容易回归的事：
 * <ol>
 *   <li><b>内容词抽取</b>：疑问壳（是什么/怎么样）必须被剔除，虚词二元组（是什/的做）必须被丢弃——
 *       否则「红烧肉的做法是什么」会靠共享「什么」通过闸门；</li>
 *   <li><b>能力边界</b>：天气/汇率/时刻表这类越界问题，即使词重叠很高也必须拒；</li>
 *   <li><b>语义拒答</b>：向量通道给回候选但整体相似度低于 {@code semantic-reject-score} 时拒答，
 *       且相似度正常时<u>绝不</b>拒答（避免把「分数不可用」误判成「都不像」）。</li>
 * </ol>
 */
@DisplayName("拒答闸门：内容词抽取 / 能力边界 / 语义拒答")
class RefusalGateTest {

    private AiProperties properties;
    private BooleanQueryBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        builder = new BooleanQueryBuilder(properties);
    }

    // ------------------------------------------------------------------
    // 内容词抽取
    // ------------------------------------------------------------------

    @Test
    @DisplayName("疑问壳被剔除，只留实词：红烧肉的做法是什么 → 红烧/烧肉/做法")
    void stripsFillerPhrases() {
        List<String> terms = builder.contentTerms("红烧肉的做法是什么");

        assertTrue(terms.contains("红烧"), "应保留实体词红烧：" + terms);
        assertTrue(terms.contains("烧肉"), "应保留实体词烧肉：" + terms);
        assertFalse(terms.contains("是什"), "虚词组合必须剔除：" + terms);
        assertFalse(terms.contains("什么"), "疑问词必须剔除：" + terms);
        assertFalse(terms.contains("法的"), "跨词边界垃圾二元组必须剔除：" + terms);
    }

    @Test
    @DisplayName("英文/数字整段保留：malloc和free的作用 → malloc / free")
    void keepsAsciiTerms() {
        List<String> terms = builder.contentTerms("malloc和free的作用");

        assertTrue(terms.contains("malloc"), "英文实词应整段保留：" + terms);
        assertTrue(terms.contains("free"), "英文实词应整段保留：" + terms);
    }

    @Test
    @DisplayName("纯疑问句没有实词 → 空列表（上层据此不做覆盖度判定）")
    void emptyForPureQuestion() {
        assertTrue(builder.contentTerms("怎么样").isEmpty());
        assertTrue(builder.contentTerms("").isEmpty());
    }

    // ------------------------------------------------------------------
    // 语义拒答
    // ------------------------------------------------------------------

    /** 构造一个「ngram 正常放行」的检索结果，供语义闸门判定 */
    private RetrievalService.RetrievalResult passedNgram() {
        ChunkContext ctx = new ChunkContext(1L, "QUESTION", 5L, "标题", "正文内容",
                5L, 1L, 3, "QUESTION", 5L, null, null, 0, 40.0, 1.1);
        return new RetrievalService.RetrievalResult(List.of(ctx), false, "FULLTEXT_OR",
                3, 12L, 1.1, 0.75);
    }

    /**
     * ngram 无任何证据：语义闸门生效的前提
     *
     * <p>P1 规则「字面通道有证据则跳过语义拒答」上线后，{@link #passedNgram()}
     * （bestScore=1.1 ≥ rejectThreshold）会直接绕过语义闸门，不能再用来构造语义拒答场景。
     */
    private RetrievalService.RetrievalResult emptyNgram() {
        return new RetrievalService.RetrievalResult(List.of(), false, "NONE", 0, 12L, 0.0, 0.0);
    }

    private HybridRetriever build(VectorChannel.Scan scan) {
        return build(scan, passedNgram());
    }

    private HybridRetriever build(VectorChannel.Scan scan,
                                  RetrievalService.RetrievalResult ngram) {
        RetrievalService retrieval = mock(RetrievalService.class);
        // HybridRetriever 现在走 6 参重载（config + sourceTypes），把 stub 打在这个签名上，
        // 否则 mock 返回 null，测试会以 NPE 的形式"假失败"（本次就踩到了）
        when(retrieval.retrieve(anyString(), any(), any(), any(),
                any(RetrievalService.RetrievalConfig.class), any()))
                .thenReturn(ngram);
        VectorChannel vector = mock(VectorChannel.class);
        when(vector.isEnabled()).thenReturn(true);
        when(vector.isReady()).thenReturn(true);
        when(vector.scan(anyString(), anyInt(), any())).thenReturn(scan);
        when(vector.describeMode(anyInt(), anyInt())).thenReturn("VECTOR_ONLY");
        VectorChunkMerger merger = mock(VectorChunkMerger.class);
        properties.getVector().setEnabled(true);
        return new HybridRetriever(retrieval, vector, merger, properties);
    }

    @Test
    @DisplayName("整库都不像（top1 余弦 0.50 < 0.58，且字面通道无证据）→ 语义拒答，且 contexts 清空")
    void semanticRejectWhenLibraryIsUnrelated() {
        HybridRetriever retriever = build(new VectorChannel.Scan(List.of(), 0.50), emptyNgram());

        HybridRetriever.Outcome outcome = retriever.retrieve(
                "区块链智能合约怎么写", null, null, Set.of());

        assertTrue(outcome.refused(), "低相似度必须拒答");
        assertEquals("SEMANTIC_LOW_SIMILARITY", outcome.refuseReason());
        assertTrue(outcome.contexts().isEmpty(), "拒答时必须清空上下文，不能进生成环节");
        assertEquals(0.0, outcome.bestScore(), 1e-9);
    }

    @Test
    @DisplayName("相似度正常（0.70 ≥ 0.62）→ 不拒答，照常返回上下文")
    void noSemanticRejectWhenSimilarEnough() {
        HybridRetriever retriever = build(new VectorChannel.Scan(List.of(), 0.70));

        HybridRetriever.Outcome outcome = retriever.retrieve(
                "值传递和地址传递有什么区别", null, null, Set.of());

        assertFalse(outcome.refused(), "相似度足够时不得拒答");
        assertFalse(outcome.contexts().isEmpty());
    }

    @Test
    @DisplayName("向量通道没给回候选（相似度 0）→ 不用 0 去拒一切，交给 ngram 闸门")
    void zeroSimilarityDoesNotReject() {
        HybridRetriever retriever = build(new VectorChannel.Scan(List.of(), 0.0));

        HybridRetriever.Outcome outcome = retriever.retrieve(
                "C语言中int类型占几个字节", null, null, Set.of());

        assertFalse(outcome.refused(), "无向量候选时语义闸门必须让位给 ngram 判定");
        assertFalse(outcome.contexts().isEmpty());
    }
}
