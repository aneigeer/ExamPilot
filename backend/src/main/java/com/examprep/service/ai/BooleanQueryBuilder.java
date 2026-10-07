package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 布尔查询串构造器（设计方案 A.7 规则 1-4）
 *
 * <p>构造策略：
 * <ol>
 *   <li>归一化：去首尾空白、长度超限截断（防超长查询拖垮索引）</li>
 *   <li>切 term：按空格 / 标点切分，去重、丢弃停用词与过短 term</li>
 *   <li>AND 优先：{@code +值传 +传递}（精确度优先）</li>
 *   <li>OR 降级：AND 无命中时把中文串展开为 bigram 后取并集（最大化召回）</li>
 *   <li>LIKE 兜底：OR 仍无命中时由 {@code RetrievalService} 走 LIKE 模式</li>
 * </ol>
 *
 * <p><b>★ 为什么必须区分 AND / OR 的切分粒度（已实测）</b>
 * <p>MySQL 的 ngram 解析器把<b>每个 term 内部</b>切成二元组建倒排索引，
 * 但 {@code AGAINST} 会把<b>整个 term</b>当作一个必须整体出现的短语。
 * 因此：
 * <ul>
 *   <li><b>「值传递」+「地址传递」</b> 作为两个 term → {@code +值传递 +地址传递}
 *       能精准命中（实测相关度 38.85，ngram 内部切分不影响）</li>
 *   <li><b>「值传递和地址传递有什么区别」</b> 作为<b>单个</b> term
 *       → 要求全部 bigram 连续出现 → 实测 <b>0 命中</b></li>
 * </ul>
 * <p>结论：<b>AND 模式用「词级 term」，OR 模式用「bigram term」</b>。
 * <ul>
 *   <li>AND：仅当查询串被标点/空格切成短词（≤ {@value #MAX_WORD_LENGTH} 字）时可用；
 *       出现「整句无标点」的长中文串时直接放弃 AND（返回 null），交给 OR</li>
 *   <li>OR：把中文串切成与 ngram 索引<b>完全一致的 bigram</b>，任一命中即召回，
 *       由 MySQL 相关度 + Java 侧五因子重排保证排序质量
 *       （实测 bigram OR 下正确块 38.92 / 次优 8.31 / 噪声 1.31，区分度充足）</li>
 * </ul>
 * 英文 / 数字（{@code int}、{@code 32}、{@code malloc}）ngram 按词边界处理，原样作 term。
 */
@Component
@RequiredArgsConstructor
public class BooleanQueryBuilder {

    /** 切分 term 的分隔符：空白 + 中英文标点 + 换行 */
    private static final String TERM_SPLIT_REGEX =
            "[\\s,，。；;：:、！!？?（）()【】《》<>「」『』\"'`~@#￥$%^&*\\+=|/\\\\~]+";

    /** 停用词：纯虚词进索引会稀释相关性 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "及", "有", "吗", "呢", "吧",
            "请问", "什么", "怎么", "如何", "为什么", "哪些", "哪个", "一下",
            "介绍", "讲讲", "说说", "帮我", "我们", "你们", "他们");

    /** 判定「词级 term」的最大长度：超过则认为是整句而非词 */
    private static final int MAX_WORD_LENGTH = 4;

    /** 单个中文串最多产出多少 bigram（防止超长查询产生几百个 term 拖垮索引） */
    private static final int MAX_BIGRAMS_PER_RUN = 16;

    /** OR 模式最多 term 数（超过则截断，保证查询串在索引压力范围内） */
    private static final int MAX_OR_TERMS = 24;

    private final AiProperties properties;

    /**
     * 归一化并切分出<b>词级</b> term（去重、保序）
     *
     * <p>供 LIKE 兜底与标题命中计算使用；中文长串原样保留（不做 bigram 展开）。
     *
     * @param query 用户原始问题
     * @return 有效 term 列表；无可用 term 时返回空 list（上层直接拒答）
     */
    public List<String> terms(String query) {
        List<String> segments = segments(query);
        Set<String> result = new LinkedHashSet<>();
        for (String segment : segments) {
            result.add(segment);
        }
        return new ArrayList<>(result);
    }

    /**
     * 归一化 + 切词（不含 bigram 展开）
     *
     * <p>步骤：去首尾空白 → 长度超限截断 → 按标点/空白切分 → 去停用词 → 丢弃过短 term。
     */
    public List<String> segments(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.trim();
        int maxLen = properties.getRetrieval().getMaxQueryLength();
        if (normalized.length() > maxLen) {
            normalized = normalized.substring(0, maxLen);
        }
        int minLen = properties.getRetrieval().getMinTermLength();

        List<String> result = new ArrayList<>();
        for (String raw : normalized.split(TERM_SPLIT_REGEX)) {
            String segment = raw.trim();
            if (segment.length() < minLen || STOP_WORDS.contains(segment)) {
                continue;
            }
            result.add(segment);
        }
        return result;
    }

    /**
     * 构造 AND 模式的布尔查询串（首选）
     *
     * <p><b>词级 term</b>。若查询串是「整句无标点的长中文串」，
     * AND 语义必然 0 命中（ngram 短语要求），此时返回 null 让上层直接走 OR。
     *
     * @return 形如 {@code +值传递 +地址传递}；不适用时返回 null
     */
    public String buildAndQuery(String query) {
        List<String> segments = segments(query);
        if (segments.isEmpty()) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (String segment : segments) {
            if (containsCjk(segment) && segment.length() > MAX_WORD_LENGTH) {
                // 整句长中文串：AND 不可用，交给 OR 的 bigram 策略
                return null;
            }
            words.add(segment);
        }
        return join(words, "+");
    }

    /**
     * 构造 OR 模式的布尔查询串（AND 无命中时降级用）
     *
     * <p><b>bigram term</b>：中文串切成与 ngram 索引一致的二元组，
     * 英文 / 数字原样保留。任一命中即召回，由相关度排序。
     *
     * @return 形如 {@code 值传 传递 地址}；无有效 term 时返回 null
     */
    public String buildOrQuery(String query) {
        List<String> terms = expansionTerms(query);
        return terms.isEmpty() ? null : join(terms, "");
    }

    /**
     * OR 模式实际使用的 term 列表（bigram 展开后）
     *
     * <p>供 {@code RetrievalService} 计算<b>词项覆盖率</b>——
     * bigram OR 语义是「任一命中即召回」，精度低，必须在 Java 侧补一道覆盖率闸门，
     * 否则「量子力学波函数如何求解」会因命中 bigram「函数」而被误召回。
     */
    public List<String> expansionTerms(String query) {
        List<String> segments = segments(query);
        if (segments.isEmpty()) {
            return List.of();
        }
        List<String> expanded = new ArrayList<>();
        for (String segment : segments) {
            if (containsCjk(segment) && segment.length() > MAX_WORD_LENGTH) {
                expanded.addAll(bigrams(segment));
            } else {
                expanded.add(segment);
            }
        }
        if (expanded.size() > MAX_OR_TERMS) {
            return new ArrayList<>(expanded.subList(0, MAX_OR_TERMS));
        }
        return expanded;
    }

    /**
     * LIKE 降级用的关键词
     *
     * <p>bigram（2 字）做 LIKE 噪声大，优先取 ≥3 字的词级 term；
     * 若全是大 bigram 则取前若干个（宁可噪声大也不能完全不召回）。
     */
    public List<String> likeKeywords(String query, int limit) {
        List<String> segments = segments(query);
        if (segments.isEmpty()) {
            return List.of();
        }
        List<String> longTerms = segments.stream().filter(s -> s.length() >= 3).toList();
        if (!longTerms.isEmpty()) {
            return longTerms.size() > limit ? longTerms.subList(0, limit) : longTerms;
        }
        // 全部是 2 字词 → 用 bigram 兜底
        List<String> bigrams = new ArrayList<>();
        for (String segment : segments) {
            bigrams.addAll(bigrams(segment));
        }
        return bigrams.size() > limit ? bigrams.subList(0, limit) : bigrams;
    }

    /** 中文串 → 重叠 bigram（与 ngram 索引口径一致） */
    private List<String> bigrams(String cjkRun) {
        List<String> result = new ArrayList<>();
        int size = Math.max(2, properties.getRetrieval().getMinTermLength());
        for (int i = 0; i + size <= cjkRun.length() && result.size() < MAX_BIGRAMS_PER_RUN; i++) {
            result.add(cjkRun.substring(i, i + size));
        }
        return result;
    }

    private String join(List<String> terms, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(prefix).append(term);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }
}
