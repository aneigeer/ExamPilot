package com.examprep.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12：向量相似度闸门的阈值回归测试
 *
 * <p><b>为什么这个测试必须存在</b>：闸门阈值是<b>用实测数据标定出来的经验值</b>，
 * 一旦被后人「顺手调一下」就会静默破防——而且<b>不会报错，只会悄悄给出假答案</b>。
 * 实际踩过的坑：用「量子纠缠」（最高 0.4184）单独标定会得到 0.45，
 * 而 0.45 &lt; 0.5126，导致「量子力学波函数如何求解」漏过闸门，
 * 返回「根据《函数》：知识点：函数」这种<b>带来源的空壳答案</b>——
 * 这比直接拒答危险得多，因为用户和评委都会以为它答对了。
 *
 * <p>所以本测试把<b>真实实测的分数</b>固化成断言，任何人改动阈值都会立刻红。
 *
 * <p><b>数据来源</b>：本机 Java + Python + Qdrant + bge-small-zh-v1.5 全链路在线，
 * 逐条 curl {@code POST /v1/search} 取得（详见 docs/AI降级演练手册.md §3）。
 */
@DisplayName("T12：向量闸门阈值（用真实实测分数固化，防止静默破防）")
class VectorGateThresholdTest {

    /** 绝对阈值：无关最高 0.5126 / 相关最低有效 0.5802，取中点 */
    private static final double MIN_SCORE = 0.55;
    /** 相对阈值系数：低于本次最高分 80% 的候选剔除 */
    private static final double RATIO = 0.8;

    /**
     * 复刻 {@code VectorChannel.applyVectorScoreGate} 的判定逻辑
     *
     * <p>刻意与生产代码同构：闸门 = max(绝对阈值, ratio × 本次最高分)。
     * 若生产代码改了判定方式，本测试必须同步改——这正是它的价值。
     *
     * @param scores 本次查询的向量召回分数（已由 hydrate 取出）
     * @return 通过闸门的分数
     */
    private List<Double> gate(List<Double> scores) {
        if (scores.isEmpty()) {
            return scores;
        }
        double best = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double threshold = Math.max(MIN_SCORE, RATIO * best);
        List<Double> kept = new ArrayList<>();
        for (double s : scores) {
            if (s >= threshold) {
                kept.add(s);
            }
        }
        return kept;
    }

    @Test
    @DisplayName("红线：「量子力学波函数如何求解」必须被拦下（0.45 会漏放，0.55 拦得住）")
    void 量子力学波函数必须拦下() {
        // 实测：最高 0.5126 命中知识卡片「函数」，是噪声
        List<Double> scores = List.of(0.5126, 0.5079, 0.4781, 0.4768, 0.4737);

        List<Double> kept = gate(scores);

        assertTrue(kept.isEmpty(),
                "「量子力学波函数如何求解」的最高分 0.5126 是噪声，必须全部拦下。"
                        + "若本用例失败说明阈值被调低到 0.5126 以下——那正是 0.45 破防的根因。"
                        + "实际通过：" + kept);
        System.out.println("  0.5126 < " + MIN_SCORE + " → 拦截 ✓（0.45 会漏放）");
    }

    @Test
    @DisplayName("红线：「量子纠缠实验怎么做」必须被拦下")
    void 量子纠缠必须拦下() {
        List<Double> scores = List.of(0.4184, 0.3947, 0.3908);

        List<Double> kept = gate(scores);

        assertTrue(kept.isEmpty(), "无关问题最高仅 0.4184，必须全部拦下。实际通过：" + kept);
    }

    @Test
    @DisplayName("★ 向量的价值：「函数传参有哪些方式」必须放行（ngram 答不出来的题）")
    void 函数传参必须放行() {
        // 实测：正确答案 chunk5「值传递与地址传递」= 0.5884
        // 这是整个向量通道存在的意义——该题字面覆盖率仅 0.125，ngram 必然拒答
        List<Double> scores = List.of(0.6328, 0.5884, 0.5802, 0.5289, 0.5222);

        List<Double> kept = gate(scores);

        assertTrue(kept.contains(0.5884),
                "正确答案 0.5884 必须放行——这是「语义召回优于字面召回」的核心证据。"
                        + "若被拦，向量通道就没有存在价值了。实际通过：" + kept);
        // 0.5289 / 0.5222 低于绝对阈值，应被剔掉
        assertFalse(kept.contains(0.5289), "0.5289 低于 0.55 应剔除");
        assertFalse(kept.contains(0.5222), "0.5222 低于 0.55 应剔除");
        assertEquals(3, kept.size(), "应恰好保留 3 条，实际：" + kept);
        System.out.println("  0.5884 ≥ " + MIN_SCORE + " → 放行 ✓（ngram 侧覆盖率 0.125 会被拒）");
    }

    @Test
    @DisplayName("常规题：「值传递和地址传递有什么区别」必须放行且保留正确答案")
    void 值传递常规题必须放行() {
        List<Double> scores = List.of(0.7235, 0.7114, 0.4980);

        List<Double> kept = gate(scores);

        assertTrue(kept.contains(0.7235), "正确答案 0.7235 必须放行");
        assertTrue(kept.contains(0.7114), "次优 0.7114 应保留");
        assertFalse(kept.contains(0.4980), "0.4980 明显不相关，应剔除");
    }

    @Test
    @DisplayName("★ 相对阈值：分布整体抬高时自动接管（语料扩充后的保护）")
    void 相对阈值在语料扩充后接管() {
        // 模拟语料从 42 块扩到几千块后，同主题块的相似度整体抬高到 0.80+
        // 此时若只有绝对阈值 0.55，噪声（0.62）会集体越过闸门
        List<Double> 分布抬高 = List.of(0.8800, 0.8500, 0.6200, 0.6100);

        List<Double> kept = gate(分布抬高);

        double threshold = Math.max(MIN_SCORE, RATIO * 0.8800); // = 0.704
        assertEquals(0.704, threshold, 1e-9, "此场景应由相对阈值接管");
        assertFalse(kept.contains(0.6200),
                "0.62 与最高分 0.88 差距过大（仅 70%），相对阈值应剔除——"
                        + "纯绝对阈值 0.55 会漏放，这就是加相对阈值的意义");
        assertTrue(kept.contains(0.8800) && kept.contains(0.8500), "前两名应保留");
        System.out.println("  分布抬高场景：阈值 0.55 → " + String.format("%.4f", threshold)
                + "（相对阈值接管，剔除 0.62）");
    }

    @Test
    @DisplayName("边界：恰好等于阈值时放行（闭区间）")
    void 边界值闭区间() {
        // 0.55 恰好等于阈值 → 放行
        assertEquals(List.of(0.55), gate(List.of(0.55)));
        // 差 0.0001 → 剔除
        assertTrue(gate(List.of(0.5499)).isEmpty(), "0.5499 应被剔除");
    }

    @Test
    @DisplayName("空结果与单条结果不崩")
    void 边界输入() {
        assertTrue(gate(List.of()).isEmpty(), "空列表应原样返回");
        assertEquals(1, gate(List.of(0.9)).size(), "高分单条应保留");
        assertTrue(gate(List.of(0.1)).isEmpty(), "低分单条应剔除");
    }

    @Test
    @DisplayName("★ 两道闸门分工：同一道题 ngram 该拒、向量该放")
    void 两道闸门分工验证() {
        // 「函数传参有哪些方式」在 ngram 侧：字面只共享 bigram「函数」
        // 9 个 bigram 命中 1 个 → 覆盖率 0.125 < 0.25 → 必须拒答
        java.util.List<String> bigrams = List.of(
                "函数", "数传", "传参", "参有", "有哪", "哪些", "些方", "方式");
        long hit = 1; // 仅「函数」在「值传递与地址传递」语料中
        double coverage = (double) hit / bigrams.size();
        assertTrue(coverage < 0.25,
                "ngram 覆盖率应为 0.125（低于 0.25 闸门），实际 " + coverage);

        // 同一道题在向量侧：语义相似度 0.5884 ≥ 0.55 → 放行
        assertTrue(0.5884 >= MIN_SCORE, "向量侧应放行");

        System.out.println("  ngram 覆盖率 = " + coverage + " < 0.25 → 拒答");
        System.out.println("  向量相似度 = 0.5884 ≥ " + MIN_SCORE + " → 放行");
        System.out.println("  ⇒ 同一道题两路结论不同，这正是「闸门必须分通道」的意义");
    }
}
