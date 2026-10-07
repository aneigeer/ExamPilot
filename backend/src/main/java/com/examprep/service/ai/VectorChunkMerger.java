package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 双通道融合器（T09）
 *
 * <p><b>为什么两路并存而不是替换</b>（使用口径 F.2）：
 * <ul>
 *   <li><b>ngram 是字面精确匹配</b>——查「printf」「32位」「C语言」这类专有名词、
 *       代码标识符、数字，字面匹配一定准，向量反而可能因语义相近混入噪声</li>
 *   <li><b>向量是语义近似匹配</b>——查「内存不够用怎么办」能命中「动态分配内存的函数」，
 *       这是 ngram 做不到的</li>
 * </ul>
 * 两者<b>互补而非替代</b>，融合后等于免费得到「混合检索」能力。
 *
 * <p><b>融合算法</b>（设计文档 F.2 ②）：
 * <pre>
 *   1. 两路结果按 chunkId 去重（同一块只保留一条，<b>双通道命中</b>记为 dualHit）
 *   2. 分数量纲对齐：向量分已归一化到 [0,1]（见 VectorChannel.hydrate）；
 *      ngram 分按 maxFt 归一化
 *   3. 双通道命中 → finalScore += dualHitBoost（默认 0.25）
 *   4. 再叠加与 ngram 通道<b>同一套</b>的标题命中 / 知识点 / 时间衰减 / 热度权重
 *   5. 截断 Top-K（K 与 ngram 通道一致，保证两条通道的输出口径可比）
 * </pre>
 *
 * <p><b>关键约束：融合后仍以 Java 侧五因子为唯一权威权重口径</b>，
 * FastAPI 侧的 {@code rerank_score} 只作参考、不参与最终决策，避免两套权重打架。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorChunkMerger {

    private final AiProperties properties;

    /**
     * 融合两路结果
     *
     * @param vectorContexts 向量通道结果（已带归一化分数）
     * @param ngramContexts  ngram 通道结果（ftScore 为原始分，需归一化）
     * @param query          用户问题（用于标题命中计算）
     * @param weakKpIds      用户薄弱知识点（检索加权）
     * @return 融合去重并重排后的上下文，已按 finalScore 降序、已截断 Top-K
     */
    public List<ChunkContext> merge(List<ChunkContext> vectorContexts,
                                    List<ChunkContext> ngramContexts,
                                    String query,
                                    Set<Long> weakKpIds) {
        if (vectorContexts == null) {
            vectorContexts = List.of();
        }
        if (ngramContexts == null) {
            ngramContexts = List.of();
        }
        if (vectorContexts.isEmpty() && ngramContexts.isEmpty()) {
            return List.of();
        }

        // ---------- ① ngram 分归一化 ----------
        double maxFt = ngramContexts.stream()
                .mapToDouble(ChunkContext::ftScore)
                .max().orElse(0.0);

        // ---------- ② 按 chunkId 去重，合并两路分数 ----------
        // 保留插入顺序：向量路在前（它语义更准），ngram 路补齐
        Map<Long, ChunkContext> merged = new LinkedHashMap<>();
        Set<Long> dualHitIds = new HashSet<>();

        for (ChunkContext ctx : vectorContexts) {
            if (ctx.chunkId() != null) {
                merged.put(ctx.chunkId(), ctx);
            }
        }
        for (ChunkContext ctx : ngramContexts) {
            if (ctx.chunkId() == null) {
                continue;
            }
            ChunkContext existing = merged.get(ctx.chunkId());
            if (existing == null) {
                merged.put(ctx.chunkId(), ctx);
            } else {
                // 双通道命中：标记之，并在合并分里取两路较大值
                dualHitIds.add(ctx.chunkId());
                merged.put(ctx.chunkId(), maxOf(existing, ctx));
            }
        }

        // ---------- ③ 五因子重排（与 ngram 通道同一套权重） ----------
        AiProperties.Retrieval r = properties.getRetrieval();
        Set<Long> weak = weakKpIds == null ? Set.of() : weakKpIds;
        List<String> queryTerms = termsOf(query);

        List<ChunkContext> ranked = new ArrayList<>(merged.size());
        for (Map.Entry<Long, ChunkContext> entry : merged.entrySet()) {
            ChunkContext ctx = entry.getValue();
            boolean dualHit = dualHitIds.contains(entry.getKey());

            // 相关度：向量路用归一化分，ngram 路用 ft/maxFt；双通道取较大者
            double ftNorm = ctx.ftScore();
            if (!dualHit && maxFt > 0 && ctx.sourceType() != null) {
                ftNorm = Math.min(1.0, ctx.ftScore() / maxFt);
            } else if (dualHit && maxFt > 0) {
                // 融合分：两路归一化分的均值（双路都相关，可信度更高）
                double ngramNorm = Math.min(1.0, ctx.ftScore() / maxFt);
                ftNorm = (ftNorm + ngramNorm) / 2.0;
            }

            double titleHit = titleHitRatio(ctx.title(), queryTerms);
            double kpBoost = kpBoost(ctx.knowledgeId(), weak);
            double fresh = freshness(ctx.sourceUpdatedAt());
            double hotNorm = Math.log1p(Math.max(0L, ctx.hotCount())) / Math.log1p(1000.0);

            double score = ftNorm * r.getFulltextWeight()
                    + titleHit * r.getTitleWeight()
                    + kpBoost * r.getKnowledgeWeight()
                    + fresh * r.getFreshWeight()
                    + hotNorm * r.getHotWeight();

            // ★ 双通道加成：两路都命中说明既语义相关又字面相关
            if (dualHit) {
                score += properties.getVector().getDualHitBoost();
            }
            ranked.add(ctx.withFinalScore(score));
        }

        ranked.sort(Comparator.comparingDouble(ChunkContext::finalScore).reversed());
        int topK = r.getTopK();
        List<ChunkContext> top = ranked.size() > topK
                ? ranked.subList(0, topK)
                : ranked;
        log.info("[AI] 双通道融合：向量 {} 块 + ngram {} 块 → 去重后 {} 块（双通道命中 {} 块）→ Top-{}",
                vectorContexts.size(), ngramContexts.size(), merged.size(), dualHitIds.size(), top.size());
        return List.copyOf(top);
    }

    /**
     * 判断某个 chunk 是否被双通道命中（供测试与调试用）
     *
     * @return 双通道命中的 chunkId 集合
     */
    public Set<Long> dualHitIds(List<ChunkContext> vectorContexts, List<ChunkContext> ngramContexts) {
        Set<Long> vectorIds = new HashSet<>();
        Set<Long> ngramIds = new HashSet<>();
        if (vectorContexts != null) {
            vectorContexts.stream().map(ChunkContext::chunkId)
                    .filter(java.util.Objects::nonNull).forEach(vectorIds::add);
        }
        if (ngramContexts != null) {
            ngramContexts.stream().map(ChunkContext::chunkId)
                    .filter(java.util.Objects::nonNull).forEach(ngramIds::add);
        }
        vectorIds.retainAll(ngramIds);
        return vectorIds;
    }

    // ==================================================================
    // 打分辅助（口径与 RetrievalService 严格一致）
    // ==================================================================

    /** 取两路中 finalScore 更大的那个（同时保留向量路的归一化语义） */
    private ChunkContext maxOf(ChunkContext a, ChunkContext b) {
        return a.ftScore() >= b.ftScore() ? a : b;
    }

    private double titleHitRatio(String title, List<String> terms) {
        if (title == null || title.isBlank() || terms.isEmpty()) {
            return 0.0;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String term : terms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    private double kpBoost(Long knowledgeId, Set<Long> weakKpIds) {
        if (knowledgeId == null) {
            return 0.0;
        }
        double boost = 1.0;
        if (weakKpIds.contains(knowledgeId)) {
            boost += properties.getProfile().getWeakKpSearchBoost()
                    / Math.max(1e-6, properties.getRetrieval().getKnowledgeWeight());
        }
        return boost;
    }

    private double freshness(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 1.0;
        }
        long ageDays = Math.max(0, Duration.between(updatedAt, LocalDateTime.now()).toDays());
        return Math.pow(properties.getRetrieval().getDecayBase(), ageDays);
    }

    /**
     * 取查询词（简单切分，够用即可）
     *
     * <p>与 {@code BooleanQueryBuilder} 的策略保持一致：这里只需要「词是否出现在标题里」，
     * 不需要构造布尔查询串，所以不重复实现停用词与 bigram 逻辑。
     */
    private List<String> termsOf(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Boolean> seen = new HashMap<>();
        for (String term : query.trim().split("[\\s,，。；;：:、！!？?（）()]+")) {
            if (term.length() >= 2) {
                seen.putIfAbsent(term, Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }
}
