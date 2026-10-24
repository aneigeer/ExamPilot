package com.examprep.service.ai;

import com.examprep.mapper.AiChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索服务：召回 + 五因子重排 + 拒答判定（设计方案 A.2 / A.7）
 *
 * <p><b>五步流水线</b>：
 * <ol>
 *   <li>全文召回：{@code MATCH ... AGAINST ... IN BOOLEAN MODE}，AND 优先</li>
 *   <li>AND 为 0 → 降级 OR；OR 仍为 0 → 降级 LIKE（对应风险 R8）</li>
 *   <li>相关度归一化：{@code ftNorm = ft / maxFt}</li>
 *   <li>Java 侧重排：全文 + 标题命中 + 知识点 + 时间衰减 + 热度</li>
 *   <li>Top-K 截断（K=5）；最高分低于阈值 → 拒答</li>
 * </ol>
 *
 * <p><b>拒答阈值是反幻觉的第一道闸门</b>（比 prompt 约束强得多）：
 * 没有检索到足够相关的语料，就根本不进生成环节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final AiChunkMapper aiChunkMapper;
    private final BooleanQueryBuilder booleanQueryBuilder;
    private final ChunkSyncService chunkSyncService;
    private final AiProperties properties;

    /** 召回模式：全文 AND */
    public static final String MODE_FULLTEXT_AND = "FULLTEXT_AND";
    /** 召回模式：全文 OR（AND 无命中时降级） */
    public static final String MODE_FULLTEXT_OR = "FULLTEXT_OR";
    /** 召回模式：LIKE（ngram 不可用或全文仍无命中时的最终兜底） */
    public static final String MODE_LIKE = "LIKE";
    /** 召回模式：无命中 */
    public static final String MODE_NONE = "NONE";

    /**
     * 检索并重排
     *
     * @param question       用户问题原文
     * @param subjectId      科目过滤，null 表示全科目
     * @param examCategoryId 考试类别过滤，null 表示不隔离
     * @param weakKpIds      用户薄弱知识点 ID 集合，命中则额外加权（游客传空集）
     * @return 命中结果（含是否拒答、召回模式、耗时）
     */
    public RetrievalResult retrieve(String question, Long subjectId, Long examCategoryId,
                                    Set<Long> weakKpIds) {
        long start = System.currentTimeMillis();
        RetrievalConfig cfg = new RetrievalConfig(
                properties.getRetrieval().getRecallLimit(),
                properties.getRetrieval().getTopK(),
                properties.getRetrieval().getRejectThreshold());
        return retrieve(question, subjectId, examCategoryId, weakKpIds, cfg);
    }

    /**
     * 检索并重排（可显式指定配置，供单元测试用固定参数复现评测表）
     */
    public RetrievalResult retrieve(String question, Long subjectId, Long examCategoryId,
                                    Set<Long> weakKpIds, RetrievalConfig config) {
        long start = System.currentTimeMillis();

        if (!StringUtils.hasText(question)) {
            return RetrievalResult.refuse(MODE_NONE, 0, System.currentTimeMillis() - start);
        }
        Set<Long> weak = weakKpIds == null ? Set.of() : weakKpIds;
        int recallLimit = config.recallLimit();
        int topK = config.topK();

        // ---------- ① 全文召回：AND → OR → LIKE 三级降级 ----------
        String mode = MODE_NONE;
        List<Map<String, Object>> rows = List.of();

        String andQuery = booleanQueryBuilder.buildAndQuery(question);
        if (andQuery != null) {
            rows = safeFulltext(andQuery, examCategoryId, subjectId, recallLimit);
            mode = MODE_FULLTEXT_AND;
        }
        if (rows.isEmpty()) {
            String orQuery = booleanQueryBuilder.buildOrQuery(question);
            if (orQuery != null) {
                rows = safeFulltext(orQuery, examCategoryId, subjectId, recallLimit);
                mode = MODE_FULLTEXT_OR;
            }
        }
        if (rows.isEmpty()) {
            // 风险 R8 兜底：ngram 不可用 / 布尔查询无命中时走 LIKE
            List<String> terms = booleanQueryBuilder.likeKeywords(question, 5);
            if (!terms.isEmpty()) {
                try {
                    rows = aiChunkMapper.searchByLike(terms, subjectId, recallLimit);
                    mode = MODE_LIKE;
                } catch (Exception e) {
                    log.warn("[AI] LIKE 降级召回失败: {}", e.getMessage());
                }
            }
        }
        if (rows.isEmpty()) {
            return RetrievalResult.refuse(MODE_NONE, 0, System.currentTimeMillis() - start);
        }

        // ---------- ② 相关度归一化：ftNorm = ft / maxFt ----------
        double maxFt = rows.stream()
                .mapToDouble(r -> toDouble(r.get("ft_score")))
                .max().orElse(0.0);

        // ---------- ②.5 词项覆盖率闸门 ----------
        // bigram OR 语义是「任一 bigram 命中即召回」，精度低。实测「量子力学波函数如何求解」
        // 会因命中 bigram「函数」被误召回，因此这里按「命中 bigram 数 / 查询 bigram 总数」
        // 计算覆盖率，低于阈值直接剔除——这是 ngram OR 模式必要的精度补偿。
        List<String> expansionTerms = booleanQueryBuilder.expansionTerms(question);
        double minCoverage = properties.getRetrieval().getMinTermCoverage();
        if (config.coverageGate() && !expansionTerms.isEmpty() && maxFt > 0) {
            int before = rows.size();
            rows = rows.stream()
                    .filter(r -> coverage(r, expansionTerms) >= minCoverage)
                    .toList();
            if (rows.size() < before) {
                log.debug("[AI] 覆盖率闸门剔除 {}/{} 块（阈值 {}）",
                        before - rows.size(), before, minCoverage);
            }
            if (rows.isEmpty()) {
                return RetrievalResult.refuse(MODE_NONE, before, System.currentTimeMillis() - start);
            }
            // 剔除后重新归一化，避免最高分被剔除项拉高
            maxFt = rows.stream()
                    .mapToDouble(r -> toDouble(r.get("ft_score")))
                    .max().orElse(0.0);
        }

        // ---------- ③④ 五因子重排 ----------
        List<ChunkContext> contexts = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            contexts.add(toContext(row, maxFt, question, weak));
        }
        contexts.sort(Comparator.comparingDouble(ChunkContext::finalScore).reversed());

        // ---------- ⑤ Top-K 截断 + 拒答阈值 ----------
        List<ChunkContext> top = contexts.size() > topK
                ? contexts.subList(0, topK)
                : contexts;
        double best = top.isEmpty() ? 0.0 : top.get(0).finalScore();
        long cost = System.currentTimeMillis() - start;

        if (best < config.rejectThreshold()) {
            log.info("[AI] 拒答：最高分 {} < 阈值 {}（模式 {}，问题「{}」）",
                    String.format("%.4f", best), config.rejectThreshold(), mode, question);
            return RetrievalResult.refuse(mode, contexts.size(), cost);
        }
        log.info("[AI] 检索命中 {} 块（模式 {}，最高分 {}，耗时 {} ms）：{}",
                top.size(), mode, String.format("%.4f", best), cost, question);
        return new RetrievalResult(List.copyOf(top), false, mode, contexts.size(), cost);
    }

    /** 全文召回包一层 try：ngram 插件缺失 / 索引损坏时降级而不是 500 */
    private List<Map<String, Object>> safeFulltext(String booleanQuery, Long examCategoryId,
                                                   Long subjectId, int recallLimit) {
        try {
            return aiChunkMapper.searchByFulltext(booleanQuery, examCategoryId, subjectId, recallLimit);
        } catch (Exception e) {
            log.warn("[AI] 全文召回失败（将降级）：{} | query={}", e.getMessage(), booleanQuery);
            return List.of();
        }
    }

    /**
     * 词项覆盖率 = 命中 term 数 / 查询 term 总数
     *
     * <p>在 title + content 上做大小写不敏感的子串匹配。
     * 这是 bigram OR 模式必要的精度补偿：OR 只要命中一个 bigram 就会召回，
     * 必须要求「命中相当比例的 bigram」才算真正相关。
     */
    private double coverage(Map<String, Object> row, List<String> terms) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        String text = (str(row.get("title")) + " " + str(row.get("content"))).toLowerCase();
        int hit = 0;
        for (String term : terms) {
            if (text.contains(term.toLowerCase())) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    /**
     * 行 → 上下文，并完成五因子打分
     *
     * <pre>
     * finalScore = ftNorm × 1.00            ① 全文相关度（已归一化）
     *            + titleHit  × 0.30        ② 标题命中比例
     *            + kpBoost   × 0.25(+.15)  ③ 知识点加权（用户薄弱再加权）
     *            + fresh     × 0.15        ④ 时间衰减 0.85^ageDays
     *            + hot       × 0.10        ⑤ 热度 log(1+热度)
     * </pre>
     */
    private ChunkContext toContext(Map<String, Object> row, double maxFt,
                                   String question, Set<Long> weakKpIds) {
        double ftRaw = toDouble(row.get("ft_score"));
        double ftNorm = maxFt <= 0 ? 0.0 : Math.min(1.0, ftRaw / maxFt);

        String title = str(row.get("title"));
        String content = str(row.get("content"));
        Long knowledgeId = toLong(row.get("knowledge_id"));
        LocalDateTime updatedAt = toDateTime(row.get("source_updated_at"));

        // ② 标题命中：term 出现在 title 中的比例
        double titleHit = titleHitRatio(title, question);

        // ③ 知识点加权：命中知识点 +0.25；用户薄弱知识点再 +weakKpSearchBoost
        double kpBoost = 0.0;
        if (knowledgeId != null) {
            kpBoost = 1.0;
            if (weakKpIds.contains(knowledgeId)) {
                kpBoost += properties.getProfile().getWeakKpSearchBoost() / 0.25;
            }
        }

        // ④ 时间衰减：0.85 ^ ageDays（越旧分越低）
        double fresh = freshness(updatedAt);

        // ⑤ 热度：log(1 + hot)，归一到 0-1（以 1000 次为饱和参考）
        long hot = toLong(row.get("hot_count"));
        double hotNorm = Math.log1p(hot) / Math.log1p(1000.0);

        AiProperties.Retrieval r = properties.getRetrieval();
        double finalScore = ftNorm * r.getFulltextWeight()
                + titleHit * r.getTitleWeight()
                + kpBoost * r.getKnowledgeWeight()
                + fresh * r.getFreshWeight()
                + hotNorm * r.getHotWeight();

        return new ChunkContext(
                toLong(row.get("id")),
                str(row.get("source_type")),
                toLong(row.get("source_id")),
                title,
                content,
                toLong(row.get("subject_id")),
                knowledgeId,
                (int) toDouble(row.get("difficulty"), 3),
                str(row.get("ref_type")),
                toLong(row.get("ref_id")),
                (int) toDouble(row.get("page_no"), 0) == 0 ? null : (int) toDouble(row.get("page_no"), 0),
                updatedAt,
                hot,
                ftRaw,
                finalScore);
    }

    /** 标题命中率：query 的有效 term 有多少出现在标题里 */
    private double titleHitRatio(String title, String question) {
        List<String> terms = booleanQueryBuilder.terms(question);
        if (terms.isEmpty() || !StringUtils.hasText(title)) {
            return 0.0;
        }
        String lowerTitle = title.toLowerCase();
        int hit = 0;
        for (String term : terms) {
            if (lowerTitle.contains(term.toLowerCase())) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    /** 时间衰减：0.85 ^ ageDays；updatedAt 为空时给满分（新内容优先） */
    private double freshness(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 1.0;
        }
        long ageDays = Math.max(0, Duration.between(updatedAt, LocalDateTime.now()).toDays());
        return Math.pow(properties.getRetrieval().getDecayBase(), ageDays);
    }

    /**
     * 补齐热度：全文召回 SQL 不 join resource（性能考虑），
     * 这里按 refType 分批补一次，保证五因子里的热度项有真实数据。
     */
    private static double toDouble(Object value) {
        return toDouble(value, 0.0);
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 检索结果
     *
     * @param contexts      命中块（已按 finalScore 降序、已截断 Top-K）；拒答时为空 list
     * @param refused      是否拒答
     * @param mode         召回模式 FULLTEXT_AND / FULLTEXT_OR / LIKE / NONE
     * @param recallCount  召回（重排前）的候选数
     * @param costMillis   检索阶段耗时
     */
    public record RetrievalResult(List<ChunkContext> contexts, boolean refused,
                                  String mode, int recallCount, long costMillis) {

        static RetrievalResult refuse(String mode, int recallCount, long cost) {
            return new RetrievalResult(List.of(), true, mode, recallCount, cost);
        }

        /** 索引块总数（供 capability 展示语料规模） */
        public long indexSize() {
            return contexts.size();
        }
    }

    /**
     * 检索参数（供单元测试固定参数复现评测表）
     *
     * @param recallLimit    召回条数
     * @param topK           最终返回条数
     * @param rejectThreshold 拒答阈值
     */
    public record RetrievalConfig(int recallLimit, int topK, double rejectThreshold,
                                  boolean coverageGate) {

        /** 生产默认：开启 ngram 覆盖率闸门 */
        public RetrievalConfig(int recallLimit, int topK, double rejectThreshold) {
            this(recallLimit, topK, rejectThreshold, true);
        }

        public static RetrievalConfig defaults() {
            return new RetrievalConfig(50, 5, 0.15, true);
        }
    }

    /** 便捷方法：取出用户薄弱知识点（供 AiQaService 在无画像服务时降级为不加权） */
    public static Set<Long> emptyWeakSet() {
        return new LinkedHashSet<>();
    }

    /** 索引统计（供 capability 接口） */
    public Map<String, Object> indexStats() {
        return chunkSyncService.indexStats();
    }
}
