package com.examprep.service.ai;

import com.examprep.entity.AiChunk;
import com.examprep.mapper.AiChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 向量通道（T09）
 *
 * <p>把「调 FastAPI 拿 chunkId + 分数」补全为「可进 prompt 的 {@link ChunkContext} 列表」。
 *
 * <p><b>★ 必须回表这一步</b>：Qdrant payload 里<b>刻意不存正文</b>
 * （正文唯一来源是 MySQL {@code ai_chunk}，避免两处正文不一致），
 * 所以向量命中后必须按 {@code chunk_id} 回 MySQL 取 {@code content} 才能进 prompt。
 * 这一步同时天然过滤掉了「MySQL 有 id 但内容已变」的脏命中。
 *
 * <p><b>本通道永远是「可选增强」</b>：任何异常都收敛成空列表，
 * 上层据此自动降级到 ngram 通道，<b>不抛异常</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorChannel {

    private final FastApiClient fastApiClient;
    private final AiChunkMapper aiChunkMapper;
    private final AiProperties properties;

    /** 召回模式：双通道融合（向量 + ngram 都命中） */
    public static final String MODE_HYBRID = "VECTOR_HYBRID";
    /** 召回模式：纯向量（ngram 无命中但向量有） */
    public static final String MODE_VECTOR_ONLY = "VECTOR_ONLY";

    /**
     * 向量检索并回表补全
     *
     * @param query    用户问题原文
     * @param topK     召回条数
     * @param subjectId 科目过滤
     * @return 带正文的上下文列表；不可用 / 无命中 / 出错时返回<b>空 list</b>
     */
    public List<ChunkContext> retrieve(String query, int topK, Long subjectId) {
        return applyVectorScoreGate(retrieveRaw(query, topK, subjectId));
    }

    /**
     * 不做相似度闸门的原始向量召回（仅离线评估的纯召回模式使用）
     *
     * <p>生产链路必须走 {@link #retrieve}；本方法用于评估「检索层本身」的召回能力，
     * 避免闸门把待评估的候选提前剔掉。
     */
    public List<ChunkContext> retrieveRaw(String query, int topK, Long subjectId) {
        if (!isEnabled()) {
            return List.of();
        }
        List<FastApiClient.VectorHit> hits =
                fastApiClient.search(query, topK, subjectId, null);
        if (hits.isEmpty()) {
            return List.of();
        }
        return hydrate(hits);
    }

    /**
     * ★ 召回 + <b>最高相似度</b>（语义拒答判定用）
     *
     * <p><b>为什么要单独暴露「原始最高相似度」</b>：{@link #retrieve} 会先过相似度闸门，
     * 低于 {@code min-vector-score} 的候选被整批丢弃，此时调用方只能看到「空结果」，
     * <b>无法区分</b>「向量库真的没有相关内容（相似度 0.4）」与
     * 「有候选但都被闸门否了（相似度 0.5）」——而拒答恰恰需要这个原始数值来判断
     * 「整库都不像」还是「只是不够像」。
     *
     * <p>一次搜索同时拿到两样东西，避免为取分数再打一遍向量服务（那是双倍延迟）。
     *
     * @param query     用户问题
     * @param topK      召回条数
     * @param subjectId 科目过滤
     * @return 过闸后的上下文 + 原始最高余弦相似度；不可用时 {@code (空, 0)}
     */
    public Scan scan(String query, int topK, Long subjectId) {
        List<ChunkContext> raw = retrieveRaw(query, topK, subjectId);
        if (raw.isEmpty()) {
            return new Scan(List.of(), 0.0);
        }
        // hydrate 把余弦相似度原样写进 ftScore（finalScore 是归一化值），取最大值即 top1 相似度
        double best = raw.stream().mapToDouble(ChunkContext::ftScore).max().orElse(0.0);
        return new Scan(applyVectorScoreGate(raw), best);
    }

    /**
     * 向量召回扫描结果
     *
     * @param contexts       过相似度闸门、已回表补全的上下文
     * @param bestSimilarity 原始最高余弦相似度（未过闸前的 top1），无命中时为 0
     */
    public record Scan(List<ChunkContext> contexts, double bestSimilarity) {
    }

    /**
     * ★ 向量通道闸门 = <b>相似度阈值</b>（不是词项覆盖率）
     *
     * <p><b>为什么不能用 ngram 那套词项覆盖率（这是实测踩出来的坑）</b>：
     * <pre>
     *   查询「函数传参有哪些方式」
     *     ngram 覆盖率 = 0.125（只共享 bigram「函数」）→ 被 0.25 闸门剔除  ← 字面检索该剔
     *     向量 score  = 0.5884（语义上就是同一件事）    → <b>必须放行</b>        ← 语义检索的价值
     * </pre>
     * 一道闸门同时用在一路上，结果<b>既挡住假答案，也把向量唯一的优势一起吞掉了</b>——
     * 那向量通道存在的意义就没了。
     *
     * <p><b>两道闸门各管各的，互不否决</b>：
     * <ul>
     *   <li><b>ngram 通道</b> → 词项覆盖率（字面检索，没有词面重叠就不该命中）</li>
     *   <li><b>向量通道</b> → 相似度阈值（语义检索，用词面重叠评判是自相矛盾）</li>
     * </ul>
     * 融合层只处理「两路各自过闸之后」的合格结果，不做二次否决。
     *
     * <p><b>阈值 0.55 的实测标定依据</b>（本机 Java + Python + Qdrant + bge 全链路在线，
     * 逐条 curl 打 {@code POST /v1/search} 取得原始分数）：
     * <pre>
     *   相关：0.7235「值传递和地址传递…」  0.6328 / 0.5884 / 0.5802「函数传参有哪些方式」
     *   无关：0.5126 / 0.5079「量子力学波函数如何求解」  0.4184「量子纠缠实验怎么做」
     * </pre>
     * 无关最高 0.5126，相关最低有效 0.5802 → 阈值 0.55。
     *
     * <p><b>⚠️ 这里有个容易踩的坑：如果只用「量子纠缠」标定会错</b>
     * 它的最高分只有 0.4184，据此会取到 0.45 这样的阈值；而 0.45 &lt; 0.5126，
     * <b>「量子力学波函数如何求解」会漏过闸门，给出「根据《函数》：知识点：函数」这种空壳答案</b>。
     * 该题才是真正的难题（与「函数传参」共享「函数」这个语义锚点），必须一起参与标定。
     *
     * <p><b>⚠️ 语料扩充后必须重新标定</b>：当前仅 42 个 chunk，余弦分被压在
     * 0.47~0.72 的窄带里，阈值余量只有约 0.03，偏脆。语料扩充后分布会明显拉开。
     *
     * @param contexts 向量召回的上下文（{@code ftScore} 此时存的是余弦相似度）
     * @return 通过相似度闸门的上下文
     */
    private List<ChunkContext> applyVectorScoreGate(List<ChunkContext> contexts) {
        if (contexts.isEmpty()) {
            return contexts;
        }
        double minScore = properties.getRetrieval().getMinVectorScore();
        if (minScore <= 0) {
            return contexts; // 阈值为 0 = 关闭本闸门（仅用于调试）
        }

        // ---------- 绝对阈值 + 相对阈值，取较大者 ----------
        // 相对阈值 = ratio × 本次最高分：「不够像最高分的那条，差太远的一律不要」
        // 语料少时绝对阈值兜底，语料多后分布整体抬高时由相对阈值接管，
        // 这样扩充语料后不需要重新调参。
        double best = contexts.stream()
                .mapToDouble(ChunkContext::ftScore)
                .max().orElse(0.0);
        double relative = properties.getRetrieval().getVectorScoreRatio() * best;
        double threshold = Math.max(minScore, relative);

        int before = contexts.size();
        final double gate = threshold;
        List<ChunkContext> kept = contexts.stream()
                .filter(ctx -> ctx.ftScore() >= gate)
                .toList();
        if (kept.size() < before) {
            log.info("[AI] 向量相似度闸门剔除 {}/{} 块（阈值 {} = max(绝对 {}, 相对 {}×最高分 {})，命中分 {}）",
                    before - kept.size(), before, String.format("%.4f", gate),
                    minScore, properties.getRetrieval().getVectorScoreRatio(),
                    String.format("%.4f", best),
                    contexts.stream().map(ChunkContext::ftScore)
                            .map(s -> String.format("%.4f", s)).toList());
        }
        return kept;
    }


    /**
     * 把向量命中补全为 {@link ChunkContext}（回 MySQL 取正文）
     *
     * <p>公开可见，便于融合器复用（ngram 通道不需要回表，它本来就带正文）。
     *
     * @param hits 向量命中列表
     * @return 补全后的上下文；命中全部失效时返回空 list
     */
    public List<ChunkContext> hydrate(List<FastApiClient.VectorHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Long> ids = hits.stream()
                .map(FastApiClient.VectorHit::chunkId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AiChunk> rows;
        try {
            rows = aiChunkMapper.selectByIds(ids);
        } catch (Exception e) {
            log.warn("[AI] 向量命中回表失败（静默降级到 ngram 通道）: {}", e.getMessage());
            return List.of();
        }
        Map<Long, AiChunk> byId = rows.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(AiChunk::getId, Function.identity(), (a, b) -> a));
        Map<Long, FastApiClient.VectorHit> hitById = hits.stream()
                .filter(h -> h.chunkId() != null)
                .collect(Collectors.toMap(FastApiClient.VectorHit::chunkId,
                        Function.identity(), (a, b) -> a));

        AiProperties.Vector cfg = properties.getVector();
        double range = Math.max(1e-6, cfg.getScoreCeiling() - cfg.getScoreFloor());

        List<ChunkContext> contexts = new ArrayList<>(ids.size());
        for (Long id : ids) {
            AiChunk chunk = byId.get(id);
            // 只保留 ACTIVE：来源下架的块不该被向量通道召回
            if (chunk == null || !chunk.isActive()) {
                continue;
            }
            FastApiClient.VectorHit hit = hitById.get(id);
            // 归一化到 [0,1]：Qdrant 余弦 [-1,1] 与 ngram 的 ft_score 不同量纲，必须归一化
            double normalized = Math.max(0.0,
                    Math.min(1.0, (hit.score() - cfg.getScoreFloor()) / range));
            contexts.add(new ChunkContext(
                    chunk.getId(),
                    chunk.getSourceType(),
                    chunk.getSourceId(),
                    chunk.getTitle(),
                    chunk.getContent(),
                    chunk.getSubjectId(),
                    chunk.getKnowledgeId(),
                    chunk.getDifficulty(),
                    chunk.getRefType(),
                    chunk.getRefId(),
                    chunk.getPageNo(),
                    chunk.getSourceUpdatedAt(),
                    hit.hotCount(),
                    hit.rerankScore(),
                    normalized));
        }
        contexts.sort(Comparator.comparingDouble(ChunkContext::finalScore).reversed());
        return List.copyOf(contexts);
    }

    /** 向量通道是否开启（配置层面，不含健康探测） */
    public boolean isEnabled() {
        return properties.getVector().isEnabled() && properties.getFastapi().isEnabled();
    }

    /** 向量通道是否实际可用（配置 + 健康 + 未熔断） */
    public boolean isReady() {
        return fastApiClient.available();
    }

    /**
     * 召回模式描述（写进 {@code AskVO.retrievalMode}，作为降级证据）
     *
     * @param vectorSize 向量通道命中数
     * @param ngramSize  ngram 通道命中数
     */
    public String describeMode(int vectorSize, int ngramSize) {
        if (vectorSize > 0 && ngramSize > 0) {
            return MODE_HYBRID;
        }
        if (vectorSize > 0) {
            return MODE_VECTOR_ONLY;
        }
        return null;
    }

    /** 健康快照（capability 用） */
    public FastApiClient.HealthSnapshot health() {
        return fastApiClient.health();
    }

    /** FastAPI 客户端（capability / 索引同步用） */
    public FastApiClient client() {
        return fastApiClient;
    }
}
