package com.examprep.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 引用校验的「生成器判定」回归
 *
 * <p><b>背景（阶段二验收实测的事故）</b>：{@code isLlmGenerator()} 原本正向枚举
 * {@code dashscope/zhipu}，于是本地主模型 {@code ollama}、Agent 定稿 {@code agent-llm}、
 * 微服务代理 {@code fastapi} 全部被判成「非 LLM」→ {@code AnswerPostProcessor.verify()} 首行早退，
 * <b>L3 四类规则一条没执行</b>：
 * <pre>
 *   3 条上下文 + "int 占 8 字节，这是常识 [9]。"
 *   → 原样返回，[9] 留在正文里，sources 为空
 * </pre>
 * 同时 {@code degraded = answer.degraded() || !isLlmGenerator(...)} 对 ollama 恒为 true，
 * 所有本地模型回答都被误标降级。
 *
 * <p>本类三条用例分别锁住：<b>伪引用必须被拦</b>、<b>合法引用不得被误标降级</b>、
 * <b>抽取式不得被二次校验</b>。
 */
@DisplayName("L3 校验：生成器判定（ollama/agent-llm 必须在防线内）")
class AnswerPostProcessorGeneratorTest {

    private AnswerPostProcessor processor;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        processor = new AnswerPostProcessor(
                new ExtractiveAnswerClient(properties, new BooleanQueryBuilder(properties)));
    }

    private static ChunkContext chunk(long id, String content) {
        return new ChunkContext(id, "QUESTION", id, "标题" + id, content,
                null, null, 3, "QUESTION", id, null, null, 0, 30.0, 1.0);
    }

    private static List<ChunkContext> threeContexts() {
        return List.of(chunk(1, "值传递传的是实参副本。"),
                chunk(2, "地址传递传的是地址。"),
                chunk(3, "C 语言 int 通常占 4 字节。"));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("ollama 编造 [9]（上下文仅 3 条）→ 规则 2 拦成抽取式，正文不得残留 [9]")
    void fabricatedCitationIsRejectedForOllama() {
        LlmAnswer modelAnswer = new LlmAnswer("int 占 8 字节，这是常识 [9]。",
                List.of(SourceRef.of(3L, "标题3", "C 语言 int 通常占 4 字节。", "QUESTION", 3L, null)),
                "ollama", 120L, false);

        LlmAnswer verified = processor.verify("C语言 int 占几个字节", modelAnswer, threeContexts());

        assertTrue(PromptBuilder.isLlmGenerator("ollama"),
                "ollama 必须被认作大模型生成器，否则 L3 整段被跳过");
        assertEquals(PromptBuilder.GENERATOR_EXTRACTIVE, verified.generator(),
                "越界引用应触发幻觉兜底，切抽取式");
        assertFalse(verified.content().contains("[9]"),
                "残留 [9] 会把伪造引用直接暴露给用户：" + verified.content());
        assertTrue(verified.degraded(), "兜底结果本身应标记为降级（这是真实降级，非误标）");
    }

    @Test
    @DisplayName("ollama 引用合法 → 不被替换，且 degraded 不得因生成器名被误标")
    void legalCitationPassesAndIsNotMarkedDegradedForOllama() {
        LlmAnswer modelAnswer = new LlmAnswer("C 语言 int 通常占 4 字节 [1]。",
                List.of(SourceRef.of(3L, "标题3", "C 语言 int 通常占 4 字节。", "QUESTION", 3L, null)),
                "ollama", 120L, false);

        LlmAnswer verified = processor.verify("C语言 int 占几个字节", modelAnswer, threeContexts());

        assertEquals("ollama", verified.generator(), "合法引用不得被替换成抽取式");
        assertEquals(modelAnswer.content(), verified.content());
        assertFalse(verified.degraded(), "模型答案本身没降级，degraded 应保持 false");

        // 复现 AiQaService:204 / AgentInternalController:233 的组装公式，锁住「不因生成器名误标降级」
        boolean degraded = verified.degraded() || !PromptBuilder.isLlmGenerator(verified.generator());
        assertFalse(degraded, "ollama 生成 + 校验通过 → degraded 必须为 false");
    }

    @Test
    @DisplayName("extractive 直接返回、不校验（也不该被当成模型）")
    void extractiveIsNotVerified() {
        LlmAnswer extractive = new LlmAnswer("C 语言 int 通常占 4 字节 [1]。",
                List.of(SourceRef.of(3L, "标题3", "C 语言 int 通常占 4 字节。", "QUESTION", 3L, null)),
                PromptBuilder.GENERATOR_EXTRACTIVE, 5L, true);

        LlmAnswer verified = processor.verify("任意问题", extractive, threeContexts());

        assertEquals(extractive, verified, "抽取式是原文拼接，不应被 L3 改写");
        assertFalse(PromptBuilder.isLlmGenerator(PromptBuilder.GENERATOR_EXTRACTIVE));
        // extractive 走的是「本来就是兜底」的路径，degraded 由构造时给定
        assertTrue(verified.degraded());
    }

    @Test
    @DisplayName("agent-llm / fastapi 同样在防线内（反向白名单，不是只补 ollama）")
    void agentAndFastapiAreAlsoGuarded() {
        assertTrue(PromptBuilder.isLlmGenerator("agent-llm"));
        assertTrue(PromptBuilder.isLlmGenerator("fastapi"));
        assertTrue(PromptBuilder.isLlmGenerator("DAHSCOPE"), "大小写不应影响判定");
        assertFalse(PromptBuilder.isLlmGenerator(PromptBuilder.GENERATOR_NONE));
        assertFalse(PromptBuilder.isLlmGenerator(null));
        assertFalse(PromptBuilder.isLlmGenerator("  "));

        LlmAnswer agentAnswer = new LlmAnswer("int 占 8 字节 [9]。",
                List.of(SourceRef.of(3L, "标题3", "C 语言 int 通常占 4 字节。", "QUESTION", 3L, null)),
                "agent-llm", 90L, false);
        LlmAnswer verified = processor.verify("C语言 int 占几个字节", agentAnswer, threeContexts());
        assertFalse(verified.content().contains("[9]"), "agent-llm 路径也必须拦伪造引用");
    }
}
