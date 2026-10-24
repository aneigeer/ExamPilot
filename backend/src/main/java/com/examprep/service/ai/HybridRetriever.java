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
                          int recallCount, double bestScore) {
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
        List<ChunkContext> vectorContexts = retrieveByVector(
                question, subjectId, weakKpIds, safeTopK, applyGate);

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
        return new Outcome(contexts, mode,
                ngram.recallCount() + vectorContexts.size(), best);
    }

    /**
     * 向量通道检索：可选增强，任何异常都收敛成空列表
     *
     * <p>FastAPI / Qdrant / 模型任一不可用时，上层自动降级到 ngram。
     */
    private List<ChunkContext> retrieveByVector(String question, Long subjectId,
                                                Set<Long> weakKpIds, int topK,
                                                boolean applyGate) {
        if (!vectorChannel.isEnabled()) {
            return List.of();
        }
        try {
            if (!vectorChannel.isReady()) {
                log.info("[AI] 向量通道不可用（未开启/熔断/健康检查未通过），静默降级到 ngram 通道");
                return List.of();
            }
            int recall = Math.max(topK, properties.getVector().getRecallLimit());
            // 纯召回模式（评估）不做相似度闸门；生产模式照常过闸
            return applyGate
                    ? vectorChannel.retrieve(question, recall, subjectId)
                    : vectorChannel.retrieveRaw(question, recall, subjectId);
        } catch (Exception e) {
            log.warn("[AI] 向量通道异常（静默降级到 ngram 通道）: {}", e.getMessage());
            return List.of();
        }
    }
}
