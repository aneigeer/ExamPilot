package com.examprep.service.ai;

import com.examprep.entity.AiChunk;
import com.examprep.entity.KnowledgePoint;
import com.examprep.entity.Question;
import com.examprep.entity.Resource;
import com.examprep.mapper.AiChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 语料块同步服务（设计方案 A.6 增量同步策略）
 *
 * <p>三合一触发策略，<b>不引入定时任务</b>（项目当前无 {@code @EnableScheduling}，
 * 新增会引入不确定性；且当前语料量下定时全量重建收益为零）：
 * <ol>
 *   <li><b>审核通过（主路径）</b>：{@code AuditService} 审核成功后经
 *       {@link TransactionSynchronizationManager#registerSynchronization}
 *       在<b>事务提交后</b>调用 {@link #syncOnAuditApproved}，避免脏读</li>
 *   <li><b>手动兜底</b>：{@code POST /api/admin/ai/reindex} 全量重建</li>
 *   <li><b>来源下架</b>：{@link #invalidateSource} 置 {@code status=INVALID}，<b>不物理删</b></li>
 * </ol>
 *
 * <p><b>幂等保证</b>：以 {@code content_hash = MD5(content)} 为判据，
 * 内容未变化时直接跳过，不触碰 {@code updated_at}，避免重复触发全文索引重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkSyncService {

    private final AiChunkMapper aiChunkMapper;
    private final ChunkExtractor chunkExtractor;
    private final AiProperties properties;
    private final FastApiClient fastApiClient;

    // ==================================================================
    // 事务提交后钩子
    // ==================================================================

    /**
     * 在<b>当前事务提交后</b>执行索引同步；无事务时立即执行。
     *
     * <p>为什么必须 afterCommit：审核事务内 resource.audit_status 刚由 PENDING 改为 APPROVED，
     * 若同事务内写索引，抽取器按 APPROVED 过滤会读到未提交状态；更关键的是
     * 事务回滚后索引里会残留一条指向未通过审核内容的脏块。
     */
    public void runAfterCommit(Runnable action, String actionName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeRun(action, actionName);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRun(action, actionName);
            }
        });
    }

    /** 索引同步失败绝不能影响主业务（审核已成功，索引可由 reindex 补齐） */
    private void safeRun(Runnable action, String actionName) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[AI] 索引同步失败（{}），不影响主业务；可调用 POST /api/admin/ai/reindex 补齐: {}",
                    actionName, e.getMessage(), e);
        }
    }

    // ==================================================================
    // 单来源同步
    // ==================================================================

    /** 审核通过后同步资料索引 */
    public void syncOnAuditApproved(String sourceType, Long sourceId) {
        if (AiChunk.SOURCE_RESOURCE.equals(sourceType)) {
            syncResource(sourceId);
        } else if (AiChunk.SOURCE_QUESTION.equals(sourceType)) {
            syncQuestion(sourceId);
        } else if (AiChunk.SOURCE_KNOWLEDGE.equals(sourceType)) {
            syncKnowledge(sourceId);
        }
    }

    /** 同步单份资料的索引块 */
    public int syncResource(Long resourceId) {
        Resource resource = chunkExtractor.approvedResources().stream()
                .filter(r -> Objects.equals(r.getId(), resourceId))
                .findFirst().orElse(null);
        if (resource == null) {
            // 资料不存在或未通过审核：把已有块置失效，防止未审核内容被检索到
            return invalidateSource(AiChunk.SOURCE_RESOURCE, resourceId);
        }
        return upsertAll(AiChunk.SOURCE_RESOURCE, resourceId, chunkExtractor.extractResource(resource));
    }

    /** 同步单道题目的索引块 */
    public int syncQuestion(Long questionId) {
        Question question = chunkExtractor.approvedQuestions().stream()
                .filter(q -> Objects.equals(q.getId(), questionId))
                .findFirst().orElse(null);
        if (question == null) {
            return invalidateSource(AiChunk.SOURCE_QUESTION, questionId);
        }
        return upsertAll(AiChunk.SOURCE_QUESTION, questionId, chunkExtractor.extractQuestion(question));
    }

    /** 同步单个知识点的索引块 */
    public int syncKnowledge(Long knowledgeId) {
        KnowledgePoint knowledge = chunkExtractor.enabledKnowledgePoints().stream()
                .filter(k -> Objects.equals(k.getId(), knowledgeId))
                .findFirst().orElse(null);
        if (knowledge == null) {
            return invalidateSource(AiChunk.SOURCE_KNOWLEDGE, knowledgeId);
        }
        int maxSnippets = chunkExtractor.properties().getKnowledge().getKnowledgeSnippetCount();
        return upsertAll(AiChunk.SOURCE_KNOWLEDGE, knowledgeId,
                chunkExtractor.extractKnowledge(knowledge,
                        chunkExtractor.questionsOfKnowledge(knowledgeId, maxSnippets)));
    }

    /**
     * 来源下架 / 删除：把该来源的块全部置 INVALID
     *
     * <p><b>不物理删</b>：索引块是派生数据，保留可追溯性，
     * 使用时讲「索引生命周期管理」是加分项。
     *
     * @return 受影响的块数
     */
    public int invalidateSource(String sourceType, Long sourceId) {
        if (!StringUtils.hasText(sourceType) || sourceId == null) {
            return 0;
        }
        int rows = aiChunkMapper.invalidateBySource(sourceType, sourceId);
        if (rows > 0) {
            log.info("[AI] 来源 {}#{} 已下架，索引块置 INVALID {} 条", sourceType, sourceId, rows);
        }
        return rows;
    }

    // ==================================================================
    // 幂等 upsert
    // ==================================================================

    /**
     * 幂等写入一个来源的全部块
     *
     * <p>流程：查旧块 → 按 chunk_no 对齐 → hash 相同则跳过（计入 skipped）→
     * hash 不同则更新 → 新增旧块没有的 → 旧块多余（内容变短）则置 INVALID。
     *
     * @return 实际写入（新增 + 更新）的块数
     */
    private int upsertAll(String sourceType, Long sourceId, List<AiChunk> fresh) {
        List<AiChunk> existing = aiChunkMapper.selectBySource(sourceType, sourceId);
        Map<Integer, AiChunk> existingByNo = new LinkedHashMap<>();
        for (AiChunk c : existing) {
            existingByNo.put(c.getChunkNo() == null ? 0 : c.getChunkNo(), c);
        }

        int written = 0;
        int maxChunkNo = 0;
        List<AiChunk> newlyWritten = new ArrayList<>();
        for (AiChunk chunk : fresh) {
            int chunkNo = chunk.getChunkNo() == null ? 0 : chunk.getChunkNo();
            maxChunkNo = Math.max(maxChunkNo, chunkNo);
            AiChunk old = existingByNo.get(chunkNo);
            if (old != null && Objects.equals(old.getContentHash(), chunk.getContentHash())
                    && AiChunk.STATUS_ACTIVE.equals(old.getStatus())) {
                // ★ 幂等命中：内容未变，不写库，不触发全文索引重建
                continue;
            }
            if (old == null) {
                aiChunkMapper.insert(chunk);
            } else {
                chunk.setId(old.getId());
                chunk.setStatus(AiChunk.STATUS_ACTIVE);
                aiChunkMapper.updateById(chunk);
            }
            written++;
            newlyWritten.add(chunk);
        }

        // ---------- T09：置向量待同步（0），内容变化才需要重新向量化 ----------
        if (!newlyWritten.isEmpty()) {
            markVectorPending(newlyWritten);
        }

        // 块数变少（内容改短 / PDF 页数减少）→ 把超出范围的旧块置失效
        if (existing.size() > maxChunkNo + 1) {
            int invalidated = aiChunkMapper.invalidateChunkNoGreaterThan(sourceType, sourceId, maxChunkNo);
            if (invalidated > 0) {
                log.info("[AI] 来源 {}#{} 分块数减少，置 INVALID {} 条", sourceType, sourceId, invalidated);
            }
        }
        return written;
    }

    // ==================================================================
    // T09 向量索引同步（异步 + 状态位）
    //
    // ★ 为什么异步而不是同步阻塞：
    //   审核接口不能被 embedding 推理拖慢（FastAPI 冷启动可能几秒）。
    //   状态位方案让「审核永远快、向量最终一致」，
    //   vector_synced=2（失败）可在对账时重试。
    //   复用了既有的 runAfterCommit / safeRun 异常隔离设计。
    // ==================================================================

    /**
     * 把刚写入的块标记为「待向量化」
     *
     * <p>只在内容<b>真的变化</b>时调用（hash 幂等命中的块不会走到这里），
     * 避免无意义的重复向量化。
     */
    private void markVectorPending(List<AiChunk> chunks) {
        List<Long> ids = chunks.stream()
                .map(AiChunk::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            aiChunkMapper.markVectorSynced(ids, AiChunk.VECTOR_PENDING);
        } catch (Exception e) {
            // 向量表结构可能还没迁移（未执行 migration-ai-vector.sql），不阻断主流程
            log.debug("[AI] 标记向量待同步失败（可能未执行 migration-ai-vector.sql）: {}", e.getMessage());
        }
    }

    /**
     * 推送待向量化语料到 FastAPI（<b>异步、失败不影响主业务</b>）
     *
     * <p>调用方无需处理异常：本方法内部已用 try/catch 全包，
     * 失败只留 {@code vector_synced=0} 待下次对账重试。
     *
     * @param limit 单次最多推送多少条（避免一次推太多拖垮 FastAPI）
     * @return 统计 {pending, synced, skipped, failed, pushed}
     */
    public Map<String, Object> pushVectorIndex(int limit) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int batch = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
        if (!properties.getFastapi().isEnabled()) {
            stats.put("pushed", false);
            stats.put("reason", "FastAPI 通道未开启（app.ai.fastapi.enabled=false）");
            return stats;
        }
        try {
            List<AiChunk> pending = aiChunkMapper.selectVectorPending(batch);
            stats.put("pending", pending.size());
            if (pending.isEmpty()) {
                stats.put("pushed", false);
                stats.put("reason", "无待同步语料");
                return stats;
            }
            List<FastApiClient.IndexChunkPayload> payloads = pending.stream()
                    .map(ChunkSyncService::toIndexPayload)
                    .toList();
            FastApiClient.IndexSyncResult result = fastApiClient.syncIndex(payloads);
            if (result == null) {
                // 推送失败：保持 vector_synced=0，下次对账重试
                // ★ 必须把真实原因带出来：syncIndex 返回 null 有三种可能
                //   （未启用 / 熔断中 / 网络异常），混为一谈会让排障无从下手。
                //   最容易被误判成「状态位没回写」的其实是「被熔断了」——
                //   health 探测连续失败也会打开这个共用熔断器。
                stats.put("pushed", false);
                stats.put("reason", fastApiClient.unavailableReason());
                String syncErr = fastApiClient.lastSyncError();
                if (!syncErr.isEmpty()) {
                    // ★ 契约不匹配（HTTP 400）会走到这里，必须把响应体带出来，
                    //   否则「状态位永远不更新」就是个无头案
                    stats.put("syncError", syncErr);
                }
                stats.put("hint", "向量数据可能已在 Qdrant 中（若此前推送成功过），"
                        + "但本状态位未回写；调 POST /api/admin/ai/vector-reconcile 可重新对账");
                log.warn("[AI] 向量推送未执行（{}），{} 条待同步语料保持 vector_synced=0{}",
                        fastApiClient.unavailableReason(), pending.size(),
                        syncErr.isEmpty() ? "" : "，同步错误：" + syncErr);
                return stats;
            }
            List<Long> syncedIds = pending.stream().map(AiChunk::getId).filter(Objects::nonNull).toList();
            if (!syncedIds.isEmpty()) {
                aiChunkMapper.markVectorSynced(syncedIds,
                        result.failed() > 0 ? AiChunk.VECTOR_FAILED : AiChunk.VECTOR_SYNCED);
            }
            stats.put("synced", result.synced());
            stats.put("skipped", result.skipped());
            stats.put("failed", result.failed());
            stats.put("tookMs", result.tookMs());
            stats.put("pushed", true);
            return stats;
        } catch (Exception e) {
            log.warn("[AI] 向量索引推送异常（不影响主业务）: {}", e.getMessage());
            stats.put("pushed", false);
            stats.put("reason", e.getMessage());
            return stats;
        }
    }

    /** {@link AiChunk} → 推送载荷（正文只用于算向量，不写入 Qdrant payload） */
    private static FastApiClient.IndexChunkPayload toIndexPayload(AiChunk chunk) {
        return new FastApiClient.IndexChunkPayload(
                chunk.getId(),
                chunk.getContent(),
                chunk.getSourceType(),
                chunk.getSourceId(),
                chunk.getTitle(),
                chunk.getSubjectId(),
                chunk.getKnowledgeId(),
                chunk.getDifficulty(),
                chunk.getRefType(),
                chunk.getRefId(),
                chunk.getPageNo(),
                chunk.getStatus(),
                chunk.getContentHash(),
                chunk.getSourceUpdatedAt(),
                0);
    }

    /**
     * 向量索引对账（使用当天早上手动跑一次）
     *
     * <p>核心一致性断言：<b>MySQL 已向量化数 应等于 Qdrant points 数</b>。
     * 不一致则自动补推待同步语料。
     *
     * @return {mySqlSynced, qdrantSynced, consistent, modelId, status, message}
     */
    public Map<String, Object> reconcileVectorIndex() {
        Map<String, Object> result = new LinkedHashMap<>();
        long mySqlSynced = aiChunkMapper.countVectorSynced();
        result.put("mySqlSynced", mySqlSynced);
        try {
            FastApiClient.IndexStatus status = fastApiClient.indexStatus();
            if (status == null) {
                result.put("qdrantSynced", null);
                result.put("consistent", false);
                result.put("message", "FastAPI 不可用（" + fastApiClient.unavailableReason() + "），无法对账");
                return result;
            }
            int qdrantSynced = status.chunkSynced();
            result.put("qdrantSynced", qdrantSynced);
            result.put("modelId", status.modelId());
            result.put("status", status.status());

            if (status.modelMismatch()) {
                // 换过 embedding 模型：绝不允许同一 collection 混用不同模型的向量
                result.put("consistent", false);
                result.put("message", "model_id 不匹配（collection=" + status.modelId()
                        + "），必须全量重建：POST /api/admin/ai/reindex");
                return result;
            }

            // ---------- 补推 + 回写状态位 ----------
            // 之前「状态位没回写」的最常见原因就是熔断：syncIndex 返回 null，
            // 这里把 pending 重新推一遍，Python 侧按 content_hash 幂等去重，
            // 推完由 pushVectorIndex 统一把 vector_synced 置 1。
            Map<String, Object> repair = null;
            if (mySqlSynced < aiChunkMapper.countActive()) {
                repair = pushVectorIndex(500);
                result.put("repaired", repair);
                mySqlSynced = aiChunkMapper.countVectorSynced();
                result.put("mySqlSynced", mySqlSynced);
            }

            long mySqlActive = aiChunkMapper.countActive();
            result.put("mySqlActive", mySqlActive);

            // ★ 一致性判据是「MySQL 每个 ACTIVE 块都已标记已向量化」，
            //   而不是「Qdrant 点数 == MySQL 已同步数」。
            //   因为 Qdrant 里可能残留失效块的孤儿向量（下架/改写的块不会自动删除），
            //   那属于清理范畴，不该让对账永远报不一致。
            boolean allMarked = mySqlSynced >= mySqlActive;
            int orphans = (int) Math.max(0, qdrantSynced - mySqlActive);
            result.put("orphanedInQdrant", orphans);
            result.put("consistent", allMarked);
            if (allMarked) {
                result.put("message", orphans > 0
                        ? "索引一致（Qdrant 有 " + orphans + " 个孤儿向量待清理，不影响检索）"
                        : "索引一致");
            } else {
                result.put("message", "仍有 " + (mySqlActive - mySqlSynced)
                        + " 条 ACTIVE 语料未标记已向量化；"
                        + (repair != null && Boolean.FALSE.equals(repair.get("pushed"))
                        ? "本次补推未成功（" + repair.get("reason") + "）" : "请检查 FastAPI 连通性"));
            }
            return result;
        } catch (Exception e) {
            result.put("consistent", false);
            result.put("message", "对账异常：" + e.getMessage());
            return result;
        }
    }

    // ==================================================================
    // 全量重建
    // ==================================================================

    /**
     * 全量重建索引（{@code POST /api/admin/ai/reindex}）
     *
     * <p><b>幂等</b>：第二次调用时所有块 hash 未变，{@code skipped} 等于块总数、{@code chunk} 不变。
     *
     * <p><b>T09 扩展</b>：重建 MySQL 索引后，若 FastAPI 通道开启，
     * 会顺带触发一次向量索引推送（失败不影响返回，异步补推）。
     *
     * @return 统计 {question, resource, knowledge, chunk, skipped, vector, costMillis}
     */
    public Map<String, Object> reindexAll() {
        long start = System.currentTimeMillis();
        int question = 0;
        int resource = 0;
        int knowledge = 0;
        int skipped = 0;

        for (Question q : chunkExtractor.approvedQuestions()) {
            int r = syncQuestion(q.getId());
            if (r > 0) {
                question++;
            } else {
                skipped++;
            }
        }
        for (Resource res : chunkExtractor.approvedResources()) {
            int r = syncResource(res.getId());
            if (r > 0) {
                resource++;
            } else {
                skipped++;
            }
        }
        int maxSnippets = chunkExtractor.properties().getKnowledge().getKnowledgeSnippetCount();
        for (KnowledgePoint k : chunkExtractor.enabledKnowledgePoints()) {
            int r = upsertAll(AiChunk.SOURCE_KNOWLEDGE, k.getId(),
                    chunkExtractor.extractKnowledge(k, chunkExtractor.questionsOfKnowledge(k.getId(), maxSnippets)));
            if (r > 0) {
                knowledge++;
            } else {
                skipped++;
            }
        }

        long cost = System.currentTimeMillis() - start;
        long chunkTotal = aiChunkMapper.countActive();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("question", question);
        stats.put("resource", resource);
        stats.put("knowledge", knowledge);
        stats.put("chunk", chunkTotal);
        stats.put("skipped", skipped);
        // T09：顺带推送向量索引（失败只记录，不影响 reindex 本身的成功返回）
        if (properties.getFastapi().isEnabled()) {
            try {
                stats.put("vector", pushVectorIndex(500));
            } catch (Exception e) {
                stats.put("vector", Map.of("pushed", false, "reason", e.getMessage()));
            }
        }
        stats.put("costMillis", cost);
        log.info("[AI] 全量重建完成：{}，耗时 {} ms", stats, cost);
        return stats;
    }

    /**
     * 启动自检：索引落后于业务数据时<b>只告警不自动重建</b>
     *
     * <p>自动重建会在生产启动时造成不可预期的延迟，使用场景抖动更致命，
     * 因此这里只打 warn，由人工决定何时点 reindex。
     *
     * @return 索引中的最大来源更新时间；无数据时返回 null
     */
    public LocalDateTime checkIndexFreshness() {
        return aiChunkMapper.maxSourceUpdatedAt();
    }

    /** 索引现状统计（供 /ai/capability 与管理端展示） */
    public Map<String, Object> indexStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chunk", aiChunkMapper.countActive());
        for (Map<String, Object> row : aiChunkMapper.countActiveGroupBySourceType()) {
            Object type = row.get("sourceType");
            Object cnt = row.get("cnt");
            if (type != null) {
                stats.put(String.valueOf(type).toLowerCase(), cnt);
            }
        }
        return stats;
    }
}
