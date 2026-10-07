package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /** 是否大模型生成器（extractive / none 不算） */
    public static boolean isLlmGenerator(String generator) {
        return GENERATOR_DASHSCOPE.equals(generator) || GENERATOR_ZHIPU.equals(generator);
    }
}
