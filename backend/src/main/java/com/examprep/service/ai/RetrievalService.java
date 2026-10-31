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

    /** 允许的来源类型白名单（与 {@code ai_chunk.source_type} 一致；过滤只认这几个值） */
    private static final Set<String> SOURCE_TYPE_WHITELIST =
            Set.of("QUESTION", "KNOWLEDGE", "RESOURCE", "COURSEWARE");

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
        return retrieve(question, subjectId, examCategoryId, weakKpIds, (Set<String>) null);
    }

    /**
     * 检索并重排（带来源类型过滤）
     *
     * @param sourceTypes 只保留这些 {@code source_type} 的块；<b>null 或空 = 不过滤</b>（今天的行为）
     */
    public RetrievalResult retrieve(String question, Long subjectId, Long examCategoryId,
                                    Set<Long> weakKpIds, Set<String> sourceTypes) {
        RetrievalConfig cfg = new RetrievalConfig(
                properties.getRetrieval().getRecallLimit(),
                properties.getRetrieval().getTopK(),
                properties.getRetrieval().getRejectThreshold());
        return retrieve(question, subjectId, examCategoryId, weakKpIds, cfg, sourceTypes);
    }

    /**
     * 检索并重排（可显式指定配置，供单元测试用固定参数复现评测表）
     */
    public RetrievalResult retrieve(String question, Long subjectId, Long examCategoryId,
                                    Set<Long> weakKpIds, RetrievalConfig config) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds, config, null);
    }

    /**
     * 检索并重排（完整参数：配置 + 来源类型过滤）
     *
     * <p><b>过滤为什么走 SQL 而不是 Java 侧过滤</b>：召回是 {@code LIMIT recallLimit} 的，
     * 若先取 50 条再在 Java 里筛掉不需要的类型，向量/字号都会被非目标类型挤占，
     * 过滤后可能一条不剩——SQL 里筛才能真正把配额留给目标类型。
     *
     * @param sourceTypes 只保留这些来源类型；null/空 = 不过滤
     */
    public RetrievalResult retrieve(String question, Long subjectId, Long examCategoryId,
                                    Set<Long> weakKpIds, RetrievalConfig config,
                                    Set<String> sourceTypes) {
        long start = System.currentTimeMillis();
        String sourceTypesCsv = toCsv(sourceTypes);

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
            rows = safeFulltext(andQuery, examCategoryId, subjectId, recallLimit, sourceTypesCsv);
            mode = MODE_FULLTEXT_AND;
        }
        if (rows.isEmpty()) {
            String orQuery = booleanQueryBuilder.buildOrQuery(question);
            if (orQuery != null) {
                rows = safeFulltext(orQuery, examCategoryId, subjectId, recallLimit, sourceTypesCsv);
                mode = MODE_FULLTEXT_OR;
            }
        }
        if (rows.isEmpty()) {
            // 风险 R8 兜底：ngram 不可用 / 布尔查询无命中时走 LIKE
            List<String> terms = booleanQueryBuilder.likeKeywords(question, 5);
            if (!terms.isEmpty()) {
                try {
                    rows = aiChunkMapper.searchByLike(terms, subjectId, recallLimit, sourceTypesCsv);
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

        // ---------- ⑤ Top-K 截断 + 拒答阈值（分数 × 内容覆盖度联合判定）----------
        List<ChunkContext> top = contexts.size() > topK
                ? contexts.subList(0, topK)
                : contexts;
        double best = top.isEmpty() ? 0.0 : top.get(0).finalScore();
        long cost = System.currentTimeMillis() - start;

        // ★ 内容覆盖度：用户问的「实体」在最佳块里出现了多少
        // 与上面的 expansionTerms 覆盖率分工不同：
        //   - expansionTerms 覆盖率含虚词 bigram，只能滤掉「共享一个函数」的噪声；
        //   - contentTerms 覆盖度只算实词组合，用来判断「是不是在答非所问」。
        // 实测 10 条域外问题里，「红烧肉的做法是什么」只共享「做法」，「北京到上海的火车时刻表」
        // 只共享「时刻」——这类共享足以让旧闸门（0.15 分 + 0.25 词项覆盖率）放行，
        // 但在实词覆盖度上只有 0.2~0.33，拦得住。
        List<String> contentTerms = booleanQueryBuilder.contentTerms(question);
        double bestCoverage = contentTerms.isEmpty() ? 1.0 : contentCoverage(top, contentTerms);

        AiProperties.Retrieval rc = properties.getRetrieval();
        boolean weakScore = best < config.rejectThreshold();
        boolean lowCoverage = !contentTerms.isEmpty()
                && bestCoverage < rc.getMinContentCoverage();
        boolean outOfScope = outOfScopeHit(question);

        if (weakScore || lowCoverage || outOfScope) {
            log.info("[AI] 拒答：最高分 {}（阈值 {}），内容覆盖度 {}（阈值 {}），越界词={}，"
                            + "模式 {}，问题「{}」",
                    String.format("%.4f", best), config.rejectThreshold(),
                    String.format("%.2f", bestCoverage), rc.getMinContentCoverage(),
                    outOfScope, mode, question);
            return new RetrievalResult(List.of(), true, mode, contexts.size(), cost,
                    best, bestCoverage);
        }
        log.info("[AI] 检索命中 {} 块（模式 {}，最高分 {}，内容覆盖度 {}，耗时 {} ms）：{}",
                top.size(), mode, String.format("%.4f", best),
                String.format("%.2f", bestCoverage), cost, question);
        return new RetrievalResult(List.copyOf(top), false, mode, contexts.size(), cost,
                best, bestCoverage);
    }

    /**
     * 能力边界判定：问题是否落在「备考知识库」的承诺范围之外
     *
     * <p>命中 {@code app.ai.retrieval.out-of-scope-keywords} 任一即视为越界。
     * 这类问题（天气、汇率、快递、菜谱…）在语料里可能凑出很高的词重叠
     * （行测材料包罗万象），任何「词重叠类」判据都拦不住，只能靠显式边界声明。
     */
    private boolean outOfScopeHit(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        for (String keyword : properties.getRetrieval().getOutOfScopeKeywords()) {
            if (StringUtils.hasText(keyword) && question.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 来源类型集合 → SQL {@code FIND_IN_SET} 用的 CSV
     *
     * <p>空 / null 一律返回 {@code null}，SQL 里 {@code #{sourceTypesCsv} IS NULL} 即「不过滤」——
     * 让「不传过滤」与「过滤集合为空」走同一条 SQL，不产生两套行为。
     * 只放行白名单值，避免脏值被拼进 SQL 的匹配列表。
     */
    static String toCsv(java.util.Collection<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return null;
        }
        return sourceTypes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(v -> v.toUpperCase(java.util.Locale.ROOT))
                .filter(SOURCE_TYPE_WHITELIST::contains)
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    /**
     * 最佳块的内容词覆盖度 = 命中的内容词单元数 / 内容词单元总数
     *
     * <p>在 title + content 上做大小写不敏感子串匹配，取值 [0,1]。
     * 取 Top-K 内**最高**覆盖度而非 Top-1 的覆盖度：排序由五因子决定，
     * 排第一的块可能只是标题命中的索引块，真正覆盖实体的块排在第二三位也很常见，
     * 用 Top-1 会系统性低估（实测会把 3 条可回答问题误判成低覆盖）。
     */
    private double contentCoverage(List<ChunkContext> top, List<String> contentTerms) {
        double bestCoverage = 0.0;
        for (ChunkContext ctx : top) {
            String text = ((ctx.title() == null ? "" : ctx.title()) + " "
                    + (ctx.content() == null ? "" : ctx.content())).toLowerCase();
            int hit = 0;
            for (String term : contentTerms) {
                if (text.contains(term.toLowerCase())) {
                    hit++;
                }
            }
            bestCoverage = Math.max(bestCoverage, (double) hit / contentTerms.size());
        }
        return bestCoverage;
    }

    /** 全文召回包一层 try：ngram 插件缺失 / 索引损坏时降级而不是 500 */
    private List<Map<String, Object>> safeFulltext(String booleanQuery, Long examCategoryId,
                                                   Long subjectId, int recallLimit,
                                                   String sourceTypesCsv) {
        try {
            return aiChunkMapper.searchByFulltext(booleanQuery, examCategoryId, subjectId,
                    recallLimit, sourceTypesCsv);
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
                                  String mode, int recallCount, long costMillis,
                                  double bestScore, double contentCoverage) {

        static RetrievalResult refuse(String mode, int recallCount, long cost) {
            return new RetrievalResult(List.of(), true, mode, recallCount, cost, 0.0, 0.0);
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
