package com.examprep.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索抽象回归：Agent 工具与生产问答共用同一条链路。
 */
@DisplayName("HybridRetriever：向量 + ngram 统一入口")
@SpringBootTest
@ActiveProfiles("local")
class HybridRetrieverTest {

    @Autowired
    private HybridRetriever hybridRetriever;

    @Test
    @DisplayName("已知问题能召回，Top-K 不超过指定值")
    void retrievesKnownQuestion() {
        HybridRetriever.Outcome outcome = hybridRetriever.retrieve(
                "C语言中int类型占几个字节", 5L, null, Set.of(), 10);

        assertNotNull(outcome);
        assertFalse(outcome.contexts().isEmpty());
        assertTrue(outcome.contexts().size() <= 10);
        assertTrue(outcome.bestScore() > 0.0);
        assertNotNull(outcome.mode());
        assertTrue(outcome.recallCount() >= outcome.contexts().size());
    }

    @Test
    @DisplayName("指定 Top-K 生效（评估需要 Top-10）")
    void topKIsRespected() {
        HybridRetriever.Outcome top3 = hybridRetriever.retrieve(
                "C语言 函数 指针", null, null, Set.of(), 3);
        List<ChunkContext> contexts = top3.contexts();
        assertTrue(contexts.size() <= 3);
    }
}
