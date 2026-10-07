package com.examprep.service.ai;

import java.util.List;

/**
 * 生成结果
 *
 * <p><b>不变量</b>：{@code content} 与 {@code sources} 永不为 null；
 * {@code sources} 无来源时为空 list（App 端据此隐藏来源区，而不是崩溃）。
 *
 * @param content     答案正文
 * @param sources     溯源列表（★ 强制溯源）
 * @param generator   生成器名称 dashscope / zhipu / extractive / none（拒答时为 none）
 * @param costMillis  耗时（毫秒）
 * @param degraded    是否处于降级状态（true 表示主生成器不可用，已走兜底）
 */
public record LlmAnswer(
        String content,
        List<SourceRef> sources,
        String generator,
        long costMillis,
        boolean degraded
) {

    /** 兜底构造：content 与 sources 均非 null */
    public static LlmAnswer of(String content, List<SourceRef> sources,
                               String generator, long costMillis) {
        return new LlmAnswer(content == null ? "" : content,
                sources == null ? List.of() : sources,
                generator, costMillis, false);
    }

    /** 拒答结果：无来源、生成器标记 none */
    public static LlmAnswer refuse(String content) {
        return new LlmAnswer(content, List.of(), "none", 0L, false);
    }

    /** 是否拒答（无任何溯源来源） */
    public boolean isRefusal() {
        return sources.isEmpty();
    }
}
