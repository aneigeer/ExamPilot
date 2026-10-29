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
                properties.getRetrieval().getTopK(), true);
    }

    /**
     * 指定 Top-K 检索（离线评估需要 Top-10，生产固定 Top-5）
     *
     * @param topK 最终返回条数；向量召回池至少与之同量级
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, int topK) {
        return retrieve(question, subjectId, examCategoryId, weakKpIds, topK, true);
    }

    /**
     * @param applyGate false 时关闭 ngram 覆盖率闸门（仅离线评估的纯召回模式使用）
     */
    public Outcome retrieve(String question, Long subjectId, Long examCategoryId,
                           Set<Long> weakKpIds, int topK, boolean applyGate) {
        int safeTopK = Math.max(1, topK);
        VectorChannel.Scan scan = scanByVector(question, subjectId, safeTopK, applyGate);
        List<ChunkContext> vectorContexts = scan.contexts();

        RetrievalService.RetrievalResult ngram = retrievalService.retrieve(
                question, subjectId, examCategoryId, weakKpIds,
                new RetrievalService.RetrievalConfig(
                        properties.getRetrieval().getRecallLimit(),
                        safeTopK,
                        properties.getRetrieval().getRejectThreshold(),
                        applyGate));

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
        double vectorBest = scan.bestSimilarity();
        boolean semanticReject = applyGate
                && properties.getVector().isEnabled()
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
