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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final com.examprep.service.wiki.WikiGovernanceService wikiGovernance;
    private final ChunkExtractor chunkExtractor;
    private final AiProperties properties;
    private final FastApiClient fastApiClient;

    /**
     * 自查询巡检阈值：用块自己的正文查自己，得分必须 ≥ 该值。
     *
     * <p>实测口径：健康库 0.976~0.985；静默退化的库 0.2~0.5。
     * 取 0.90 而非 0.95 —— 已能明确区分两边，且给短文本留余量。
     */
    private static final double SELF_CHECK_MIN_SCORE = 0.90;

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
        // ★ 顺序很关键：必须先把待失效的块 ID 查出来，再置 INVALID。
        //   置成 INVALID 之后就再也查不出「刚才是哪些块」，
        //   而 Qdrant 侧清理孤儿向量恰好需要这批 ID。
        List<Long> doomedIds = aiChunkMapper.selectActiveIdsBySource(sourceType, sourceId);
        int rows = aiChunkMapper.invalidateBySource(sourceType, sourceId);
        if (rows > 0) {
            log.info("[AI] 来源 {}#{} 已下架，索引块置 INVALID {} 条", sourceType, sourceId, rows);
            // ★ 清理 Qdrant 孤儿向量：payload.status 是入库快照（恒为 ACTIVE），
            //   检索过滤拦不住，不删就会挤占 top-K、把有效结果挤出去。
            deleteOrphanVectors(doomedIds, sourceType + "#" + sourceId);
            wikiGovernance.markStaleByChunkIds(doomedIds,
                    "来源 " + sourceType + "#" + sourceId + " 已下架或更新");
        }
        return rows;
    }

    /**
     * 清理 Qdrant 侧孤儿向量（失败不影响主业务）
     *
     * <p>MySQL 已置 INVALID，Java 侧回表会过滤掉这些块，
     * <b>用户不会看到脏数据</b>；这里做的是「不让脏向量白占 top-K 名额与内存」。
     *
     * @param chunkIds 已失效的块 ID
     * @param scene    日志场景描述（来源 / 分块缩减）
     */
    private void deleteOrphanVectors(List<Long> chunkIds, String scene) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            int deleted = fastApiClient.deleteChunks(chunkIds);
            if (deleted > 0) {
                log.info("[AI] {} 清理孤儿向量 {} 条", scene, deleted);
            }
        } catch (Exception e) {
            // 向量清理是「尽力而为」，绝不能影响下架主流程
            log.warn("[AI] {} 清理孤儿向量异常（已忽略）: {}", scene, e.getMessage());
        }
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
            // 同上：先查 ID 再失效，否则清理向量时无从得知要删哪些
            List<Long> doomedIds = aiChunkMapper.selectActiveIdsByChunkNoGreaterThan(
                    sourceType, sourceId, maxChunkNo);
            int invalidated = aiChunkMapper.invalidateChunkNoGreaterThan(sourceType, sourceId, maxChunkNo);
            if (invalidated > 0) {
                log.info("[AI] 来源 {}#{} 分块数减少，置 INVALID {} 条", sourceType, sourceId, invalidated);
                deleteOrphanVectors(doomedIds, sourceType + "#" + sourceId + " 分块缩减");
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
        return pushVectorIndex(limit, null);
    }

    /**
     * 按来源类型推送待同步块（{@code sourceType} 为 null 时推全部类型）
     *
     * <p>★ 为什么需要按类型：课件（COURSEWARE）语料量大（实测 7308 块），
     * 而检索侧目前还没有 {@code sourceType} 过滤，把它们一起推上去会挤占
     * 题目/知识点的 top-K 名额。因此要能「先推题目，课件等过滤就绪后再推」。
     */
    public Map<String, Object> pushVectorIndex(int limit, String sourceType) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int batch = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 500));
        if (!properties.getFastapi().isEnabled()) {
            stats.put("pushed", false);
            stats.put("reason", "FastAPI 通道未开启（app.ai.fastapi.enabled=false）");
            return stats;
        }
        try {
            List<AiChunk> pending = aiChunkMapper.selectVectorPendingByType(sourceType, batch);
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
            // ★ 按块的真实结果回写状态位，而不是「整批一起置」：
            //   - 被拒收（NaN/Inf/零范数）与「写入后校准不通过」的块必须单独置 2，
            //     否则它们会被误标为已同步，向量库里却是缺的/错的，且再也查不出来。
            Set<Long> badIds = new HashSet<>(result.rejectedIds());
            badIds.addAll(result.mismatchedIds());
            List<Long> okIds = syncedIds.stream().filter(id -> !badIds.contains(id)).toList();
            List<Long> badIdList = syncedIds.stream().filter(badIds::contains).toList();
            if (!okIds.isEmpty()) {
                aiChunkMapper.markVectorSynced(okIds, AiChunk.VECTOR_SYNCED);
            }
            if (!badIdList.isEmpty()) {
                aiChunkMapper.markVectorSynced(badIdList, AiChunk.VECTOR_FAILED);
                log.warn("[AI] 向量写入异常 {} 条已置 vector_synced=2 待重试：拒收 {}、校准不通过 {}；{}",
                        badIdList.size(), result.rejectedIds().size(), result.mismatchedIds().size(),
                        badIdList.subList(0, Math.min(10, badIdList.size())));
            }
            stats.put("synced", result.synced());
            stats.put("skipped", result.skipped());
            stats.put("failed", result.failed());
            stats.put("rejected", result.rejectedIds().size());
            stats.put("mismatched", result.mismatchedIds().size());
            stats.put("tookMs", result.tookMs());
            stats.put("pushed", true);
            return stats;
        } catch (Exception e) {
            // 只打 getMessage() 时，NPE 之类的异常会留下「异常: null」这种无线索日志，
            // 故这里带堆栈输出，便于定位状态位回写失败的真实原因。
            log.warn("[AI] 向量索引推送异常（不影响主业务）: {}", e.getMessage(), e);
            stats.put("pushed", false);
            stats.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
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
    /**
     * 全量同步向量索引（<b>可从「假的状态位」中恢复</b>）
     *
     * <p><b>为什么需要它，而不是直接循环调 pushVectorIndex：</b>
     * {@code selectVectorPending} 的条件是 {@code vector_synced <> 1}。
     * 一旦出现「状态位写成功、向量实际没入库」的假标记（实测遇到过 2433 条），
     * 这些块会被永久跳过，<b>推多少次都推不上去</b>。
     * 所以必须先把标记重置为待同步，再分批推。
     *
     * <p><b>分批</b>：Python 侧单次上限 500，这里 200 一批，逐批调用直到没有待同步。
     * 某批失败不中断整体（保持待同步，下次续推）。
     *
     * @param sourceType 只同步该类型（null = 全部）。用于「先推题目，课件等过滤就绪后再推」
     * @param maxBatches 最多推多少批（每批 200 条），防止单次请求跑太久
     * @return {ok, reset, batches, pushed, failed, remaining, stoppedAtBatch?, reason?}
     */
    public Map<String, Object> fullVectorSync(String sourceType, int maxBatches) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int batchSize = 200;
        int limit = Math.max(1, Math.min(maxBatches <= 0 ? 60 : maxBatches, 200));

        if (!properties.getFastapi().isEnabled()) {
            stats.put("ok", false);
            stats.put("reason", fastApiClient.unavailableReason());
            return stats;
        }

        // ① 重置假标记 —— 本方法存在的核心价值
        int reset = aiChunkMapper.resetVectorPendingByType(sourceType);
        stats.put("reset", reset);
        log.info("[AI] 全量向量同步开始：重置 {} 条为待同步（类型：{}）",
                reset, sourceType == null ? "全部" : sourceType);

        int batches = 0;
        int pushed = 0;
        int failed = 0;
        for (int i = 0; i < limit; i++) {
            List<AiChunk> pending = aiChunkMapper.selectVectorPendingByType(sourceType, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            Map<String, Object> one = pushVectorIndex(batchSize, sourceType);
            batches++;
            if (Boolean.TRUE.equals(one.get("pushed"))) {
                pushed += pending.size();
                failed += one.get("failed") instanceof Number n ? n.intValue() : 0;
            } else {
                stats.put("stoppedAtBatch", batches);
                stats.put("reason", one.get("reason"));
                break;
            }
        }

        boolean hasMore = !aiChunkMapper.selectVectorPendingByType(sourceType, 1).isEmpty();
        stats.put("batches", batches);
        stats.put("pushed", pushed);
        stats.put("failed", failed);
        stats.put("remaining", hasMore ? "仍有待同步（再调一次本接口可续推）" : 0);
        stats.put("ok", !hasMore);
        log.info("[AI] 全量向量同步结束 {}", stats);
        return stats;
    }

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

            // ★ 一致性判据必须【双向】比对，只看 MySQL 的状态位是错的。
            //
            // 踩过的坑（2026-10 实测）：MySQL 报 2535 条 vector_synced=1，
            // 而 Qdrant 实际只有 42 个点 —— 也就是 2493 条「自称已同步但根本不在库里」。
            // 原判据只判 `mySqlSynced >= mySqlActive`，于是报了 consistent=true；
            // 而且 orphan 用 Math.max(0, qdrant - mysql) 把「缺失」这种情况
            // 直接夹成 0，方向完全是反的（真相是缺 2493 条，不是有 0 个孤儿）。
            //
            // 正确口径：
            //   missingInQdrant  = mySqlActive - qdrantSynced  > 0 → 向量丢了（严重，检索会漏）
            //   orphanedInQdrant = qdrantSynced - mySqlActive  > 0 → 孤儿向量（清理范畴）
            // 两侧都要报，且「缺」必须让 consistent=false。
            boolean allMarked = mySqlSynced >= mySqlActive;
            int missingInQdrant = (int) Math.max(0, mySqlActive - qdrantSynced);
            int orphans = (int) Math.max(0, qdrantSynced - mySqlActive);
            result.put("orphanedInQdrant", orphans);
            result.put("missingInQdrant", missingInQdrant);

            // 状态位自洽 ≠ 索引一致。Qdrant 少于 MySQL 视为不一致。
            boolean consistent = allMarked && missingInQdrant == 0;
            result.put("consistent", consistent);
            if (missingInQdrant > 0) {
                result.put("message", "★ 索引不一致：MySQL 有 " + mySqlActive + " 条 ACTIVE 语料，"
                        + "但 Qdrant 只有 " + qdrantSynced + " 个点，缺 " + missingInQdrant + " 条。"
                        + "状态位 vector_synced=1 不可信（回写与实际入库不一致）。"
                        + "修复：POST /api/admin/ai/reindex 重推，或先 rebuild 再全量推");
            } else if (allMarked) {
                result.put("message", orphans > 0
                        ? "索引一致（Qdrant 有 " + orphans + " 个孤儿向量待清理，"
                        + "调 POST /api/admin/ai/reindex 会自动清掉，不影响本次检索一致性）"
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
     * @return 统计 {question, resource, knowledge, invalidated, chunk, skipped,
     *         vector, orphanPurge, selfCheck, costMillis}
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

        // ★ 反向对账：上面的遍历只碰到「当前可索引」的来源，
        //   已转 PENDING/REJECTED/删除的来源的旧块不在其中，会永远留在 ACTIVE。
        //   这一步把它们置 INVALID，reindex 才是真正的「全量重建」而非「只增不减」。
        int invalidated = aiChunkMapper.invalidateUnindexableSources();
        if (invalidated > 0) {
            log.info("[AI] 反向对账：{} 条来源已不可索引的残留块置为 INVALID", invalidated);
        }

        long cost = System.currentTimeMillis() - start;
        long chunkTotal = aiChunkMapper.countActive();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("question", question);
        stats.put("resource", resource);
        stats.put("knowledge", knowledge);
        stats.put("invalidated", invalidated);
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
        // ★ 孤儿向量清理：块置 INVALID 不会连带改动 Qdrant 里的 payload 快照，
        //   孤儿仍会被 status='ACTIVE' 过滤放过、挤占 top-K 名额，必须显式删点。
        try {
            stats.put("orphanPurge", purgeOrphanVectors());
        } catch (Exception e) {
            stats.put("orphanPurge", Map.of("purged", 0, "reason", e.getMessage()));
        }
        // ★ 自查询一致性巡检：把原来靠人工抽查的「向量库是否静默退化」变成
        //   每次重建自动跑（reconcile 只比数量，发现不了这个问题）。
        try {
            stats.put("selfCheck", selfCheckVectorConsistency(20));
        } catch (Exception e) {
            stats.put("selfCheck", Map.of("checked", 0, "skipped", true,
                    "reason", e.getMessage()));
        }
        stats.put("costMillis", cost);
        log.info("[AI] 全量重建完成：{}，耗时 {} ms", stats, cost);
        return stats;
    }

    /**
     * 「自查询一致性」巡检：抽若干语料块，**用它的正文去检索它自己**
     *
     * <p>★ 为什么必须自动化：向量库的静默退化（本地 Qdrant 持久化曾出现
     * 「存储向量与输入不一致」）**只影响检索质量，不影响任何计数**——
     * {@code reconcile} 比对数量会一直显示「索引一致」，
     * 唯一能发现它的手段就是自查询：健康的块得分 0.97+，
     * 退化的库则会掉到 0.2~0.5 且不同查询返回同一批结果。
     *
     * <p>阈值取 {@link #SELF_CHECK_MIN_SCORE} = 0.90 而不是 0.95：
     * 实测健康区间是 0.976~0.985、退化区间是 0.2~0.5，0.90 已经能明确区分，
     * 同时给短文本/前缀影响留出余量，避免噪声告警把真问题淹掉。
     *
     * <p>向量通道不可用时返回 {@code skipped}，**不判失败**（不是库坏了，是通道没开）。
     *
     * @param sampleSize 抽样条数（0 或负数按默认 20）
     * @return {checked, passed, failed, minScore, failedIds} 或 {checked, skipped, reason}
     */
    public Map<String, Object> selfCheckVectorConsistency(int sampleSize) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int size = sampleSize > 0 ? sampleSize : 20;
        if (!properties.getFastapi().isEnabled() || !properties.getVector().isEnabled()) {
            stats.put("checked", 0);
            stats.put("skipped", true);
            stats.put("reason", "向量通道未开启，跳过自查询巡检");
            return stats;
        }
        List<Map<String, Object>> samples;
        try {
            samples = aiChunkMapper.selectRandomActiveSamples(size);
        } catch (Exception e) {
            stats.put("checked", 0);
            stats.put("skipped", true);
            stats.put("reason", "抽样失败：" + e.getMessage());
            return stats;
        }
        int checked = 0;
        int passed = 0;
        double minScore = 1.0;
        List<Long> failedIds = new ArrayList<>();
        for (Map<String, Object> row : samples) {
            Object idValue = row.get("id");
            Object contentValue = row.get("content");
            if (idValue == null || contentValue == null) {
                continue;
            }
            long chunkId = ((Number) idValue).longValue();
            String content = String.valueOf(contentValue);
            try {
                List<FastApiClient.VectorHit> hits =
                        fastApiClient.search(content, 5, null, null);
                checked++;
                double selfScore = hits.stream()
                        .filter(h -> h.chunkId() != null && h.chunkId() == chunkId)
                        .mapToDouble(FastApiClient.VectorHit::score)
                        .findFirst()
                        .orElse(0.0);
                minScore = Math.min(minScore, selfScore);
                if (selfScore >= SELF_CHECK_MIN_SCORE) {
                    passed++;
                } else {
                    failedIds.add(chunkId);
                }
            } catch (Exception e) {
                // 单条异常不影响整体巡检结论
                log.debug("[AI] 自查询巡检单条失败 chunk#{}: {}", chunkId, e.getMessage());
            }
        }
        stats.put("checked", checked);
        stats.put("passed", passed);
        stats.put("failed", failedIds.size());
        stats.put("minScore", checked == 0 ? null : Math.round(minScore * 10000) / 10000.0);
        if (!failedIds.isEmpty()) {
            stats.put("failedIds", failedIds.subList(0, Math.min(10, failedIds.size())));
            log.warn("[AI] 自查询巡检不通过 {}/{} 条（最低分 {}，阈值 {}）——"
                            + "向量库可能已静默退化，请核对后按需删除向量库目录并全量重建：{}",
                    failedIds.size(), checked, stats.get("minScore"), SELF_CHECK_MIN_SCORE,
                    stats.get("failedIds"));
        }
        return stats;
    }

    /**
     * 清理 Qdrant 里的孤儿向量（MySQL 侧已 INVALID、但向量点还在）
     *
     * <p>★ 为什么必须清理：Qdrant 检索按 payload 快照过滤，块被置 {@code INVALID}
     * 并不会改写 Qdrant 里的 {@code payload.status}，于是 {@code /v1/search} 的
     * {@code status='ACTIVE'} 过滤<b>拦不住这些孤儿</b>，它们会占掉 top-K 名额、
     * 把有效结果挤出去。实测 4 组查询各取 top-20，80 个命中里 32 个是已失效块——
     * 这是会实质拉低检索质量的问题，不是存储洁癖。
     *
     * <p><b>幂等</b>：删完把块标为 {@link AiChunk#VECTOR_PURGED}，
     * 下次重建不会再选到；删除本身对不存在的点也是安全的。
     *
     * <p>失败只记录：FastAPI 不可用时保留 {@code vector_synced=1}，下次重建自动重试。
     *
     * @return {purged, batches} 或 {purged, reason}
     */
    public Map<String, Object> purgeOrphanVectors() {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!properties.getFastapi().isEnabled()) {
            stats.put("purged", 0);
            stats.put("reason", "FastAPI 通道未开启（app.ai.fastapi.enabled=false）");
            return stats;
        }
        int batchSize = 500;
        // 上限 20 批（1 万块）：防止「删除成功但状态位回写异常」时陷入死循环
        int maxBatches = 20;
        int purged = 0;
        int batches = 0;
        for (int i = 0; i < maxBatches; i++) {
            List<Long> ids = aiChunkMapper.selectOrphanVectorIds(batchSize);
            if (ids.isEmpty()) {
                break;
            }
            FastApiClient.IndexDeleteResult result = fastApiClient.deleteIndex(ids);
            if (result == null) {
                stats.put("purged", purged);
                stats.put("batches", batches);
                stats.put("reason", fastApiClient.unavailableReason());
                return stats;
            }
            batches++;
            purged += ids.size();
            // 标记为已移除，保证下一次重建不会再选到这批块
            aiChunkMapper.markVectorSynced(ids, AiChunk.VECTOR_PURGED);
        }
        if (purged > 0) {
            log.info("[AI] 孤儿向量清理：删除 {} 个失效块的向量点（{} 批）", purged, batches);
        }
        stats.put("purged", purged);
        stats.put("batches", batches);
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
