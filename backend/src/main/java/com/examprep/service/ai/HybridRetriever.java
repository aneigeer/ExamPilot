package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 生产检索链路的唯一入口（向量 + ngram + 双通道融合）
 *
 * <p>Agent 工具与 {@link AiQaService} 都调用这里，保证「评估看到的检索」
 * 与「用户真实问答走的检索」是同一条链路，避免两套实现漂移。
 *
 * <p>拒答判定不在本类：本类只返回 {@code bestScore}，由调用方决定是否拒答。
 * 这样离线评估可以「关闸门看纯召回」，也可以「开闸门看产品行为」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final RetrievalService retrievalService;
    private final VectorChannel vectorChannel;
    private final VectorChunkMerger vectorChunkMerger;
    private final AiProperties properties;

    /**
     * 检索结果
     *
     * @param contexts    已融合重排、按分数降序的上下文
     * @param mode        检索模式描述（向量 / ngram）
     * @param recallCount 两路召回总数
     * @param bestScore   最高最终分（空结果时为 0）
     */
    public record Outcome(List<ChunkContext> contexts, String mode,
                          int recallCount, double bestScore,
                          boolean refused, String refuseReason, double vectorBestSimilarity) {

        /** 未触发语义拒答时的便捷构造 */
        static Outcome of(List<ChunkContext> contexts, String mode, int recallCount,
                          double bestScore, double vectorBest) {
            return new Outcome(contexts, mode, recallCount, bestScore,
                    false, "", vectorBest);
        }
    }

    /** 按生产配置（Top-K 取 app.ai.retrieval.top-k，开启覆盖率闸门）检索 */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds,
                properties.getRetrieval().getTopK(), true, null);
    }

    /**
     * 按生产配置检索，并限定来源类型（A3）
     *
     * @param sourceTypes 只保留这些 {@code source_type} 的块；<b>null 或空 = 不过滤</b>
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, Set<String> sourceTypes) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds,
                properties.getRetrieval().getTopK(), true, sourceTypes);
    }

    /**
     * 指定 Top-K 检索（离线评估需要 Top-10，生产固定 Top-5）
     *
     * @param topK 最终返回条数；向量召回池至少与之同量级
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, int topK) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds, topK, true, null);
    }

    /**
     * @param applyGate false 时关闭 ngram 覆盖率闸门（仅离线评估的纯召回模式使用）
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, int topK, boolean applyGate) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds, topK, applyGate, null);
    }

    /**
     * 完整入口：Top-K + 闸门开关 + 来源类型过滤
     *
     * <p><b>过滤在两路各做一次，但只在各自该做的地方做</b>：
     * <ul>
     *   <li>ngram 通道 → 交给 SQL（{@code FIND_IN_SET}）。因为召回是 {@code LIMIT recallLimit} 的，
     *       先取后筛会让目标类型被其它类型挤掉配额；</li>
     *   <li>向量通道 → 在 Java 侧按 {@code source_type} 筛（Qdrant 的 payload 过滤目前没做
     *       类型维度，回表后 ctx 自带 source_type，这里筛一次成本极低）。</li>
     * </ul>
     * 两路用的是**同一份白名单**，不会出现「一路过滤一路不过滤」的半吊子状态。
     *
     * @param sourceTypes 只保留这些来源类型；null/空 = 不过滤（行为与之前完全一致）
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, int topK, boolean applyGate,
                           Set<String> sourceTypes) {
        int safeTopK = Math.max(1, topK);
        Set<String> typeFilter = normalizeSourceTypes(sourceTypes);
        VectorChannel.Scan scan = scanByVector(question, subjectId, safeTopK, applyGate);
        List<ChunkContext> vectorContexts = scan.contexts();
        if (typeFilter != null && !vectorContexts.isEmpty()) {
            // 向量通道无类型维度过滤：回表后按同一白名单筛，保证两路口径一致
            int before = vectorContexts.size();
            vectorContexts = vectorContexts.stream()
                    .filter(ctx -> ctx.sourceType() != null
                            && typeFilter.contains(ctx.sourceType().toUpperCase(java.util.Locale.ROOT)))
                    .toList();
            if (vectorContexts.size() != before) {
                log.debug("[AI] 向量通道按 sourceTypes={} 过滤 {}→{} 条",
                        typeFilter, before, vectorContexts.size());
            }
        }

        RetrievalService.RetrievalResult ngram = retrievalService.retrieve(
                question, subjectId, examCategoryId, weakKpIds,
                new RetrievalService.RetrievalConfig(
                        properties.getRetrieval().getRecallLimit(),
                        safeTopK,
                        properties.getRetrieval().getRejectThreshold(),
                        applyGate),
                typeFilter);

        List<ChunkContext> contexts;
        String mode;
        if (!vectorContexts.isEmpty()) {
            contexts = vectorChunkMerger.merge(vectorContexts, ngram.contexts(),
                    question, weakKpIds, safeTopK);
            mode = vectorChannel.describeMode(vectorContexts.size(), ngram.contexts().size());
        } else {
            contexts = ngram.contexts();
            mode = ngram.mode();
        }

        double best = contexts.isEmpty() ? 0.0 : contexts.get(0).finalScore();

        // ★ 语义拒答：词法闸门拦不住「实体存在但没有一段文字在回答」
        //（「区块链智能合约怎么写」词法覆盖度 0.75，余弦只有 0.5957）。
        // 只在向量通道**真的给回了候选**时判定——bestSimilarity=0 意味着通道不可用/无命中，
        // 此时应由 ngram 侧闸门决定，绝不能用 0 去拒掉一切。
        // ★ 语义闸门用「过滤之后」的最高相似度（架构师复核指出的问题）
        // 超集最大值 ≥ 子集最大值：若沿用未过滤的 best，一条被 skill 过滤掉的**高相似**块
        // 会掩盖目标类型侧真实的低相似（例：COURSEWARE_QA 时一条 0.75 的 QUESTION 块
        // 让课件侧 0.50 不被拒），结果是用勉强相关的块作答。
        // 过滤后为空时（没有可评估的目标类型候选）退回未过滤上界：那种「0 分」不代表
        // 「整库都不像」，拿去拒答会把问题问死。
        double vectorBestUnfiltered = scan.bestSimilarity();
        double vectorBest = vectorContexts.isEmpty()
                ? vectorBestUnfiltered
                : vectorContexts.stream().mapToDouble(ChunkContext::ftScore)
                        .max().orElse(vectorBestUnfiltered);
        if (vectorBest != vectorBestUnfiltered) {
            log.debug("[AI] 语义闸门使用过滤后 top1 余弦 {}（未过滤上界 {}）",
                    String.format("%.4f", vectorBest), String.format("%.4f", vectorBestUnfiltered));
        }
        boolean lexicalHasEvidence = !ngram.contexts().isEmpty()
                && ngram.bestScore() >= properties.getRetrieval().getRejectThreshold();
        boolean semanticReject = applyGate
                && properties.getVector().isEnabled()
                && !lexicalHasEvidence
                && vectorBest > 0
                && vectorBest < properties.getRetrieval().getSemanticRejectScore();
        if (semanticReject) {
            log.info("[AI] 语义拒答：top1 余弦 {} < {}（整库都不像这个问题），问题「{}」",
                    String.format("%.4f", vectorBest),
                    properties.getRetrieval().getSemanticRejectScore(), question);
            return new Outcome(List.of(), mode, ngram.recallCount() + vectorContexts.size(),
                    0.0, true, "SEMANTIC_LOW_SIMILARITY", vectorBest);
        }
        return Outcome.of(contexts, mode, ngram.recallCount() + vectorContexts.size(), best,
                vectorBest);
    }

    /**
     * 归一化来源类型：委托 {@link AiSourceTypes}（<b>白名单只有一份</b>）；null/空 → null（不过滤）
     */
    private static Set<String> normalizeSourceTypes(Set<String> sourceTypes) {
        return AiSourceTypes.normalize(sourceTypes);
    }

    /**
     * 向量通道检索：可选增强，任何异常都收敛成空列表
     *
     * <p>FastAPI / Qdrant / 模型任一不可用时，上层自动降级到 ngram。
     */
    private VectorChannel.Scan scanByVector(String question, Long subjectId, int topK,
                                            boolean applyGate) {
        if (!vectorChannel.isEnabled()) {
            return new VectorChannel.Scan(List.of(), 0.0);
        }
        try {
            if (!vectorChannel.isReady()) {
                log.info("[AI] 向量通道不可用（未开启/熔断/健康检查未通过），静默降级到 ngram 通道");
                return new VectorChannel.Scan(List.of(), 0.0);
            }
            int recall = Math.max(topK, properties.getVector().getRecallLimit());
            if (!applyGate) {
                // 纯召回模式（离线评估）：不过相似度闸门，但要保留原始最高相似度
                List<ChunkContext> raw = vectorChannel.retrieveRaw(question, recall, subjectId);
                double best = raw.stream().mapToDouble(ChunkContext::ftScore)
                        .max().orElse(0.0);
                return new VectorChannel.Scan(raw, best);
            }
            return vectorChannel.scan(question, recall, subjectId);
        } catch (Exception e) {
            log.warn("[AI] 向量通道异常（静默降级到 ngram 通道）: {}", e.getMessage());
            return new VectorChannel.Scan(List.of(), 0.0);
        }
    }
}
