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
    @Autowired
    private VectorChannel vectorChannel;

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

    /**
     * 生产链路的拒答评测（向量通道在线才断言）
     *
     * <p><b>为什么是「守卫式」而不是无条件断言</b>：语义拒答依赖 Qdrant + embedding，
     * CI / 干净环境里通常没有；无条件断言会让离线环境红一片。守卫写法保证：
     * <ul>
     *   <li>向量在线 → 真跑 10 条域外，断言拒答率 ≥90%（实测 9/10，唯一放行的是
     *       「如何训练一个深度神经网络」，它命中了 Wiki 主题页，属主题命中而非误判）</li>
     *   <li>向量离线 → 打印跳过原因，由 {@code RetrievalServiceTest.拒答用例}
     *       继续守离线的词法闸门（8/10）</li>
     * </ul>
     * 这样「离线能力」与「在线能力」两条线都有确切的数字，不会用在线成绩掩盖离线退化。
     */
    @Test
    @DisplayName("拒答评测（生产链路；向量通道离线时跳过）")
    void refusalRateOnProductionPath() {
        if (!vectorChannel.isEnabled() || !vectorChannel.isReady()) {
            System.out.println("[跳过] 向量通道未就绪（enabled=" + vectorChannel.isEnabled()
                    + "），本用例只在线时断言；离线拒答由 RetrievalServiceTest 守。");
            return;
        }
        List<String> noise = List.of(
                "量子纠缠实验怎么做", "如何训练一个深度神经网络", "红烧肉的做法是什么",
                "北京到上海的火车时刻表", "如何用酵母发面蒸馒头", "如何申请护照出国旅游",
                "量子力学波函数如何求解", "区块链智能合约怎么写", "大熊猫吃什么",
                "明天上海的天气怎么样");
        int refused = 0;
        System.out.println();
        System.out.println("── 拒答评测（生产链路：向量 + ngram + 语义闸门）──");
        for (String q : noise) {
            HybridRetriever.Outcome outcome = hybridRetriever.retrieve(q, null, null, Set.of());
            boolean ok = outcome.refused();
            if (ok) {
                refused++;
            }
            System.out.printf("  %-8s %-22s 模式=%-16s 语义相似度=%.3f%n",
                    ok ? "[拒答]" : "[放行]", q, outcome.mode(),
                    outcome.vectorBestSimilarity());
        }
        System.out.printf("生产链路拒答准确率 = %d/%d = %.0f%%%n%n",
                refused, noise.size(), 100.0 * refused / noise.size());
        assertTrue(refused >= 9,
                "生产链路拒答准确率 = " + refused + "/" + noise.size() + "，低于目标 90%");
    }
}
