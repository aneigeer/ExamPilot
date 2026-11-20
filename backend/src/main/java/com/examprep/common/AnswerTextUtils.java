package com.examprep.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 答案文本提取工具
 *
 * <p>填空题的参考答案存放在 {@code question_answer.answer_content} 的 JSON 里
 * （结构：{@code {"blanks": ["答案1", "答案2"]}}），不在选项表中。
 * 刷题报告与模考报告都需要把多空答案拼成一行展示，故统一在此实现，避免两处各写一遍。
 */
public final class AnswerTextUtils {

    private AnswerTextUtils() {
    }

    /**
     * 从填空答案 JSON 提取参考答案文本（多空用 " | " 连接）
     *
     * @param answerContent {@code question_answer.answer_content} 反序列化后的对象
     * @return 拼接后的答案文本；结构不符或为空时返回 null
     */
    public static String blanksText(Object answerContent) {
        if (answerContent instanceof Map<?, ?> map) {
            Object blanks = map.get("blanks");
            if (blanks instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(String::valueOf).collect(Collectors.joining(" | "));
            }
        }
        return null;
    }
}
