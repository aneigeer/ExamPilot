package com.examprep.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 配置口径一致性护栏
 *
 * <p><b>为什么需要这个测试</b>：拒答阈值同时存在「代码默认值」与「application.yml」两处。
 * 只要有人只改一边，就会出现「单测跑在 A 上、生产跑在 B 上」——两个口径各测各的，
 * 结论互相矛盾，而且不会有任何报错。这不是假设，是真发生过的：
 * <ul>
 *   <li>{@code min-term-coverage}：代码默认 0.25、yml 配 0.5（差一倍，闸门严格程度完全不同）</li>
 *   <li>{@code semantic-reject-score}：**只写在代码里**，yml 无此键 —— 阈值不可见、不可审计，
 *       调参得改代码；而它恰是拒答能力的唯一语义防线</li>
 * </ul>
 *
 * <p>本测试守两件事：
 * <ol>
 *   <li><b>yml 必须显式声明这些键</b>（防止「只写在代码里」：只比对生效值的话，
 *       键缺失时 Spring 会回落到默认值，比较必然相等 → 测试形同虚设）</li>
 *   <li><b>生效值 == 代码默认值</b>（防止两边数值漂移）</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("AI 配置一致性：代码默认值 vs application.yml")
class AiConfigConsistencyTest {

    @Autowired
    private AiProperties properties;

    /** 从 classpath 读取 application.yml 原文，用于确认键确实被声明 */
    private static String yml() throws IOException {
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertTrue(resource.exists(), "找不到 application.yml");
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("min-term-coverage：yml 显式声明且与代码默认值一致")
    void minTermCoverageMatchesYml() throws IOException {
        String text = yml();
        assertTrue(text.contains("min-term-coverage:"),
                "application.yml 未声明 min-term-coverage（会静默回落到代码默认值）");

        double effective = properties.getRetrieval().getMinTermCoverage();
        double codeDefault = new AiProperties().getRetrieval().getMinTermCoverage();
        assertEquals(codeDefault, effective, 1e-9,
                "min-term-coverage 代码默认值(" + codeDefault + ") 与 yml 生效值(" + effective
                        + ") 不一致 —— 单测与生产会跑在不同的闸门上");
        // 语料 3092 块标定：0.5 是两端都守住的值；越界说明有人凭感觉改了阈值
        assertTrue(effective >= 0.45 && effective <= 0.55,
                "min-term-coverage=" + effective + " 超出标定区间 [0.45,0.55]，请重新标定并更新注释");
    }

    @Test
    @DisplayName("min-content-coverage：yml 显式声明且与代码默认值一致")
    void minContentCoverageMatchesYml() throws IOException {
        String text = yml();
        assertTrue(text.contains("min-content-coverage:"),
                "application.yml 未声明 min-content-coverage（会静默回落到代码默认值）");

        double effective = properties.getRetrieval().getMinContentCoverage();
        double codeDefault = new AiProperties().getRetrieval().getMinContentCoverage();
        assertEquals(codeDefault, effective, 1e-9,
                "min-content-coverage 代码默认值(" + codeDefault + ") 与 yml 生效值(" + effective + ") 不一致");
        assertTrue(effective >= 0.40 && effective <= 0.50,
                "min-content-coverage=" + effective + " 超出标定区间 [0.40,0.50]（域内最低 0.50 / 域外 0.33）");
    }

    @Test
    @DisplayName("semantic-reject-score：yml 显式声明且与代码默认值一致（当前拒答唯一语义防线）")
    void semanticRejectScoreMatchesYml() throws IOException {
        String text = yml();
        assertTrue(text.contains("semantic-reject-score:"),
                "application.yml 未声明 semantic-reject-score —— 阈值不可审计，"
                        + "且键缺失时生效值会静默等于代码默认值（本测试就白测了）");

        double effective = properties.getRetrieval().getSemanticRejectScore();
        double codeDefault = new AiProperties().getRetrieval().getSemanticRejectScore();
        assertEquals(codeDefault, effective, 1e-9,
                "semantic-reject-score 代码默认值(" + codeDefault + ") 与 yml 生效值(" + effective + ") 不一致");
        // 拒答线必须严格高于「向量通道采信线」，否则语义闸门永远不触发
        double acceptLine = properties.getRetrieval().getMinVectorScore();
        assertTrue(effective > acceptLine,
                "semantic-reject-score(" + effective + ") 必须 > min-vector-score(" + acceptLine
                        + ")，否则语义拒答永不生效");
        assertTrue(effective > 0 && effective < 1, "余弦阈值必须在 (0,1)");
        assertNotNull(properties.getRetrieval().getOutOfScopeKeywords(), "越界词表不得为 null");
    }

    @Test
    @DisplayName("越界词表：能力边界必须非空（否则天气/汇率类问题全靠词重叠，拦不住）")
    void outOfScopeKeywordsConfigured() {
        var keywords = properties.getRetrieval().getOutOfScopeKeywords();
        assertTrue(keywords != null && keywords.size() >= 10,
                "越界词表过小（" + (keywords == null ? 0 : keywords.size()) + "），请检查配置");
        assertTrue(keywords.contains("天气"), "越界词表应含「天气」（实测它是唯一能拦住该类的词）");
    }
}
