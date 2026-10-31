package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prompt 构造器（设计方案 B.3 的 L2 防线：强制编号引用 + 禁推测约束）
 *
 * <p>核心约束：把检索到的语料块编号为 {@code [1][2][3]}，
 * 并明确要求模型<b>每句话标注来源编号</b>、<b>资料不足时只输出「未找到相关资料」</b>。
 *
 * <p>注意：L2 只是辅助，<b>真正的硬约束在 L3（{@link AnswerPostProcessor}）</b>——
 * 代码层校验「正文中的 [n] 是否在 sources 中存在」，不信任模型自述。
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final AiProperties properties;

    /** 生成器名称：extractive（抽取式兜底不走大模型，用不到 system prompt） */
    public static final String GENERATOR_DASHSCOPE = "dashscope";
    public static final String GENERATOR_ZHIPU = "zhipu";
    public static final String GENERATOR_EXTRACTIVE = "extractive";
    public static final String GENERATOR_NONE = "none";

    /*
     * 其他已经存在、且必须被当作「大模型生成」的 generatorName（列出来是文档，不是白名单本体）：
     *   ollama      —— 本地主模型 OllamaLlmClient（当前现场的默认生成器）
     *   fastapi     —— 独立 AI 微服务代理 FastApiLlmClient
     *   agent-llm   —— Agent 定稿路径（Python harness 回传）
     * wiki 不走本判定：Wiki 热层命中直接返回预编译页，不经过 L3 校验与 degraded 计算。
     */

    /**
     * 非大模型生成器（<b>反向白名单</b>）：只有这两个是真的「不是模型生成的」
     *
     * <p><b>★ 为什么必须反选而不是正向枚举</b>（这是一次真实事故的教训）：
     * 原实现是 {@code generator.equals("dashscope") || generator.equals("zhipu")}，
     * 结果本地主模型 {@code ollama}、Agent 定稿 {@code agent-llm}、微服务代理 {@code fastapi}
     * 全部被判成「非 LLM」，导致：
     * <ol>
     *   <li>{@code AnswerPostProcessor.verify()} 首行就早退 → <b>L3 四类引用校验一条没执行</b>，
     *       模型编造的 {@code [9]}（上下文只有 3 条）能原样返回给用户；</li>
     *   <li>{@code degraded = answer.degraded() || !isLlmGenerator(...)} 对 ollama 恒为 true →
     *       所有本地模型回答都被标成降级，管理端无法区分「模型答案 / 规则修正 / 抽取式」。</li>
     * </ol>
     * 正向枚举的毛病是「新增一个生成器就静默漏一次」，而漏掉的后果是<b>幻觉防线失效</b>；
     * 反选则是「除非明确不是模型，否则一律当模型校验」——失败方向朝向安全。
     */
    private static final Set<String> NON_LLM_GENERATORS = Set.of(
            GENERATOR_EXTRACTIVE, GENERATOR_NONE);

    /** 拒答标准文案（前后端统一，避免各端自己拼） */
    public static final String REFUSAL_TEXT = "未找到相关资料";

    /**
     * 构造 system prompt
     */
    public String systemPrompt() {
        return properties.getPrompt().getSystemTemplate();
    }

    /**
     * 构造 user prompt：问题 + 编号上下文
     *
     * @param question 用户问题原文
     * @param contexts 检索命中的语料块（已按相关度降序）
     */
    public String userPrompt(String question, List<ChunkContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【参考资料】\n");
        for (int i = 0; i < contexts.size(); i++) {
            ChunkContext ctx = contexts.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append("标题：").append(nullToEmpty(ctx.title())).append('\n')
                    .append("内容：").append(nullToEmpty(ctx.content())).append('\n');
        }
        sb.append('\n').append("【问题】\n").append(question == null ? "" : question.trim()).append('\n');
        sb.append("\n请依据上述参考资料作答并在句末标注来源编号。");
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 是否大模型生成器（<b>除 extractive / none 之外都算</b>）
     *
     * <p>取值口径：{@code dashscope / zhipu / ollama / fastapi / agent-llm} 都返回 true。
     * 空/空白按「非模型」处理（调用方拿不到生成器名时不该硬按模型路径校验，
     * 那种情况本身就该被当作降级）。
     */
    public static boolean isLlmGenerator(String generator) {
        if (generator == null || generator.isBlank()) {
            return false;
        }
        return !NON_LLM_GENERATORS.contains(generator.trim().toLowerCase(Locale.ROOT));
    }
}
