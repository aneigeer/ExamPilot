package com.examprep.service.ai;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * 来源类型（{@code ai_chunk.source_type}）的唯一白名单与归一化工具
 *
 * <p><b>为什么要单独抽出来</b>：白名单原本在 {@code RetrievalService} 与
 * {@code HybridRetriever} 各写了一份 {@code Set.of(...)}。将来加类型（比如把 WIKI 纳入检索）
 * 只改一处时，另一处会**静默失效**——过滤「看起来还在」，实际漏了一种类型，
 * 这种 bug 只在特定 skill 下才出现，最难排查。
 *
 * <p><b>为什么必须白名单而不是透传</b>：这些值会拼进 SQL 的
 * {@code FIND_IN_SET(source_type, ?)}，脏值/注入面必须在这里就被收口。
 */
public final class AiSourceTypes {

    /** 允许的来源类型（与 {@code ai_chunk.source_type} 取值一致） */
    public static final Set<String> WHITELIST =
            Set.of("QUESTION", "KNOWLEDGE", "RESOURCE", "COURSEWARE");

    private AiSourceTypes() {
    }

    /**
     * 归一化：去空白 → 转大写 → 只留白名单 → 去重保序（排序保证 SQL 参数稳定可缓存）
     *
     * @return 归一化后的集合；输入 null/空/全部非法时返回 {@code null}（= 不过滤）
     */
    public static Set<String> normalize(Collection<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return null;
        }
        List<String> cleaned = sourceTypes.stream()
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(WHITELIST::contains)
                .distinct()
                .sorted()
                .toList();
        return cleaned.isEmpty() ? null : new LinkedHashSet<>(cleaned);
    }

    /**
     * 归一化后拼成 {@code FIND_IN_SET} 用的 CSV
     *
     * <p><b>必须是 {@code null} 而不是空串</b>：SQL 里
     * {@code FIND_IN_SET(x, '')} 返回 0 → 整表被过滤成空，
     * 这与「不过滤」在语义上正好相反，且不会报错，只会「一条都查不到」。
     *
     * @return 形如 {@code COURSEWARE,QUESTION}；输入 null/空/全部非法时返回 {@code null}
     */
    public static String toCsv(Collection<String> sourceTypes) {
        Set<String> normalized = normalize(sourceTypes);
        return normalized == null ? null : String.join(",", normalized);
    }
}
