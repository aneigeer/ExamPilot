package com.examprep.service.wiki;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.wiki.WikiGraphVO;
import com.examprep.dto.wiki.WikiPageDetailVO;
import com.examprep.dto.wiki.WikiPageVO;
import com.examprep.dto.wiki.WikiRevisionRequest;
import com.examprep.dto.wiki.WikiRevisionVO;
import com.examprep.entity.AiChunk;
import com.examprep.entity.AsyncTask;
import com.examprep.entity.WikiPage;
import com.examprep.entity.WikiPageLink;
import com.examprep.entity.WikiPageRevision;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.WikiDistillMapper;
import com.examprep.mapper.WikiPageLinkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.examprep.mapper.WikiPageRevisionMapper;
import com.examprep.service.ai.FastApiClient;
import com.examprep.service.ai.agent.AsyncJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wiki 主题页管理（蒸馏入参 / 草稿审核 / 发布 / 导航图 / 向量索引）
 *
 * <p><b>双引擎的「沉淀侧」</b>：
 * <pre>
 *   ai_chunk（RAG 即时可用）
 *        │  每日异步蒸馏 WIKI_DISTILL
 *        ▼
 *   wiki_page_revision（LLM 草稿，PENDING）
 *        │  人工审核通过
 *        ▼
 *   wiki_page（PUBLISHED，问答热层直达） + wiki_page_link（导航图）
 *        │  审核后同步
 *        ▼
 *   Qdrant wiki_pages（摘要向量，加速热层召回）
 * </pre>
 *
 * <p>LLM 草稿永远不直接写 {@code wiki_page}——本地小模型会编造，人工审核是发布前的最后一道闸门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiService {

    private final WikiPageMapper pageMapper;
    private final WikiGovernanceService governance;
    private final WikiPageRevisionMapper revisionMapper;
    private final WikiPageLinkMapper linkMapper;
    private final WikiDistillMapper distillMapper;
    private final AiChunkMapper chunkMapper;
    private final WikiProperties properties;
    private final AsyncJobService asyncJobService;
    private final FastApiClient fastApiClient;
    private final ObjectMapper objectMapper;

    private volatile long publishedCountCache = -1L;
    private volatile long publishedCountAt = 0L;

    // ==================================================================
    // 查询（学生端）
    // ==================================================================

    /** 已发布页面分页 */
    public PageResult<WikiPageVO> listPages(long page, long size, Long subjectId, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        IPage<WikiPage> result = pageMapper.selectPublished(
                new Page<>(safePage, safeSize), subjectId, trim(keyword));
        return PageResult.of(result, this::toPageVO);
    }

    /** 页面详情（正文 + 关联页） */
    public WikiPageDetailVO detail(Long id) {
        WikiPage page = requirePublished(id);
        return toDetailVO(page);
    }

    public WikiPageDetailVO detailBySlug(String slug) {
        WikiPage page = pageMapper.selectBySlug(slug);
        if (page == null || !page.isPublished()) {
            throw new BizException(ResultCode.NOT_FOUND, "Wiki 页面不存在或已下架");
        }
        return toDetailVO(page);
    }

    /** 导航图（节点 + 有向边） */
    public WikiGraphVO graph(Long subjectId) {
        List<WikiPage> pages = subjectId == null
                ? pageMapper.selectAllPublished()
                : pageMapper.selectPublishedBySubject(subjectId);
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiPage page : pages) {
            ids.add(page.getId());
        }
        Map<Long, Integer> degrees = new LinkedHashMap<>();
        List<WikiGraphVO.Edge> edges = new ArrayList<>();
        for (WikiPageLink link : linkMapper.selectAllLinks()) {
            if (!ids.contains(link.getFromPageId()) || !ids.contains(link.getToPageId())) {
                continue;
            }
            edges.add(new WikiGraphVO.Edge(link.getFromPageId(), link.getToPageId(),
                    link.getRelation(), link.getWeight() == null ? 0.5 : link.getWeight().doubleValue()));
            degrees.merge(link.getFromPageId(), 1, Integer::sum);
            degrees.merge(link.getToPageId(), 1, Integer::sum);
        }
        List<WikiGraphVO.Node> nodes = new ArrayList<>();
        for (WikiPage page : pages) {
            nodes.add(new WikiGraphVO.Node(page.getId(), page.getSlug(), page.getTitle(),
                    page.getSubjectId(), page.getKnowledgeId(),
                    page.getQualityScore() == null ? 0.0 : page.getQualityScore().doubleValue(),
                    degrees.getOrDefault(page.getId(), 0)));
        }
        return new WikiGraphVO(nodes, edges);
    }

    public WikiPage requirePublished(Long id) {
        WikiPage page = pageMapper.selectById(id);
        if (page == null || !page.isPublished()) {
            throw new BizException(ResultCode.NOT_FOUND, "Wiki 页面不存在或已下架");
        }
        return page;
    }

    // ==================================================================
    // 管理端：页面 / 草稿 / 审核
    // ==================================================================

    public PageResult<WikiPageVO> adminPages(long page, long size, String status, String keyword) {
        return adminPages(page, size, status, null, keyword);
    }

    public PageResult<WikiPageVO> adminPages(long page, long size, String status,
                                             String trustTier, String keyword) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        IPage<WikiPage> result = pageMapper.selectAdmin(
                new Page<>(safePage, safeSize), trim(status), trim(trustTier), trim(keyword));
        return PageResult.of(result, this::toPageVO);
    }

    public PageResult<WikiRevisionVO> revisions(long page, long size, String status) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        var query = Wrappers.<WikiPageRevision>lambdaQuery()
                .eq(StringUtils.hasText(status), WikiPageRevision::getStatus, status)
                .orderByDesc(WikiPageRevision::getId);
        IPage<WikiPageRevision> result = revisionMapper.selectPage(new Page<>(safePage, safeSize), query);
        return PageResult.of(result, this::toRevisionVO);
    }

    /**
     * 审核通过：草稿 → 正式页（版本 +1）→ 重建链接 → 同步向量
     */
    public Map<String, Object> approve(Long revisionId, Long reviewerId, String note) {
        WikiPageRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null) {
            throw new BizException(ResultCode.NOT_FOUND, "草稿不存在");
        }
        if (!WikiPageRevision.STATUS_PENDING.equals(revision.getStatus())) {
            throw new BizException(ResultCode.PARAM_ERROR, "草稿已处理，不能重复审核");
        }
        WikiPage page = pageMapper.selectBySlug(revision.getSlug());
        boolean created = page == null;
        if (created) {
            page = new WikiPage();
            page.setSlug(revision.getSlug());
            page.setVersion(1);
            page.setCreatedAt(LocalDateTime.now());
        } else {
            page.setVersion((page.getVersion() == null ? 0 : page.getVersion()) + 1);
        }
        page.setTitle(revision.getTitle());
        page.setSubjectId(revision.getSubjectId());
        page.setKnowledgeId(revision.getKnowledgeId());
        page.setSummary(revision.getSummary());
        page.setContent(revision.getContent());
        page.setKeyPoints(revision.getKeyPoints());
        page.setSourceChunkIds(revision.getSourceChunkIds());
        page.setStatus(WikiPage.STATUS_PUBLISHED);
        governance.markVerified(page, governance.parseIds(revision.getSourceChunkIds()));
        page.setModel(revision.getModel());
        page.setQualityScore(revision.getQualityScore());
        page.setPublishedAt(LocalDateTime.now());
        page.setUpdatedAt(LocalDateTime.now());
        if (created) {
            try {
                pageMapper.insert(page);
            } catch (DuplicateKeyException e) {
                // 并发发布同一 slug：退回更新路径
                page = pageMapper.selectBySlug(revision.getSlug());
                if (page == null) {
                    throw new BizException(ResultCode.SERVER_ERROR, "页面创建失败");
                }
                page.setVersion((page.getVersion() == null ? 0 : page.getVersion()) + 1);
                pageMapper.updateById(page);
            }
        } else {
            pageMapper.updateById(page);
        }

        // updateById 默认忽略 null，标旧原因/过期时间必须显式清空
        pageMapper.update(null, Wrappers.<WikiPage>lambdaUpdate()
                .eq(WikiPage::getId, page.getId())
                .set(WikiPage::getStaleReason, null)
                .set(WikiPage::getExpiresAt, null));

        int updated = revisionMapper.markApproved(revisionId, page.getId(), reviewerId, trim(note));
        if (updated == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "草稿状态已变更，请刷新后重试");
        }
        rebuildLinks(page, revision);
        boolean indexed = syncPageVector(page);
        invalidateCount();
        log.info("[Wiki] 发布页面 {}（v{}，来源草稿 {}，向量同步 {}）",
                page.getSlug(), page.getVersion(), revisionId, indexed);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pageId", page.getId());
        data.put("slug", page.getSlug());
        data.put("version", page.getVersion());
        data.put("created", created);
        data.put("vectorIndexed", indexed);
        return data;
    }

    /** 源块变更 → 引用页面标旧（供 ChunkSyncService 低开销调用） */
    public int markStaleByChunkIds(java.util.Collection<Long> chunkIds, String reason) {
        return governance.markStaleByChunkIds(chunkIds, reason);
    }

    /** 人工退役：退出热层，直到重新发布 */
    public Map<String, Object> retire(Long pageId, String reason) {
        WikiPage page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Wiki 页面不存在");
        }
        governance.retire(pageId, reason);
        invalidateCount();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pageId", pageId);
        data.put("trustTier", WikiGovernanceService.TIER_RETIRED);
        data.put("reason", reason);
        return data;
    }

    /** 人工要求重新蒸馏：置 STALE_REVIEW（热层立即停用），后续由 worker 重新生成草稿。 */
    public Map<String, Object> retireStale(Long pageId, String reason) {
        WikiPage page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BizException(ResultCode.NOT_FOUND, "Wiki 页面不存在");
        }
        page.setTrustTier(WikiGovernanceService.TIER_STALE);
        page.setStaleReason(reason);
        page.setUpdatedAt(java.time.LocalDateTime.now());
        pageMapper.updateById(page);
        invalidateCount();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pageId", pageId);
        data.put("trustTier", WikiGovernanceService.TIER_STALE);
        data.put("reason", reason);
        return data;
    }

    public Map<String, Object> reject(Long revisionId, Long reviewerId, String note) {
        WikiPageRevision revision = revisionMapper.selectById(revisionId);
        if (revision == null) {
            throw new BizException(ResultCode.NOT_FOUND, "草稿不存在");
        }
        if (revisionMapper.markRejected(revisionId, reviewerId, trim(note)) == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "草稿已处理，不能重复审核");
        }
        return Map.of("revisionId", revisionId, "status", WikiPageRevision.STATUS_REJECTED);
    }

    /** 页面下架（保留草稿与历史，向量点删除防止热层继续命中） */
    public Map<String, Object> offline(Long pageId) {
        WikiPage page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BizException(ResultCode.NOT_FOUND, "页面不存在");
        }
        page.setStatus(WikiPage.STATUS_OFFLINE);
        pageMapper.updateById(page);
        int deleted = fastApiClient.deleteWikiPages(List.of(pageId));
        invalidateCount();
        return Map.of("pageId", pageId, "status", WikiPage.STATUS_OFFLINE, "vectorDeleted", deleted);
    }

    /** 重建全部 Wiki 页面的向量索引 */
    public Map<String, Object> rebuildIndex() {
        List<WikiPage> pages = pageMapper.selectAllPublished();
        int synced = 0;
        int failed = 0;
        for (int from = 0; from < pages.size(); from += 50) {
            List<WikiPage> batch = pages.subList(from, Math.min(from + 50, pages.size()));
            List<FastApiClient.WikiPagePayload> payloads = new ArrayList<>();
            for (WikiPage page : batch) {
                payloads.add(payloadOf(page));
            }
            FastApiClient.WikiIndexResult result = fastApiClient.syncWikiPages(payloads);
            if (result == null) {
                failed += batch.size();
            } else {
                synced += result.synced();
                failed += result.failed();
            }
        }
        return Map.of("pages", pages.size(), "synced", synced, "failed", failed);
    }

    /** 管理端概览 */
    public Map<String, Object> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("published", pageMapper.countPublished());
        data.put("pendingRevisions", revisionMapper.countPending());
        data.put("vectorCollection", properties.getVector().getCollection());
        return data;
    }

    /**
     * 已发布页面数（带 30s 缓存 + 缺表降级）
     *
     * <p>★ 必须吞掉「表不存在」异常：`app.wiki.enabled` 默认 true，而迁移脚本需要
     * 手动执行；若用户忘了跑 `sql/migration-wiki.sql`，这里抛异常会直接打断 `/ai/ask`，
     * 让原本可用的 RAG 问答也 500。**Wiki 是增强层，任何故障都不允许影响主链路。**
     */
    public long countPublished() {
        long now = System.currentTimeMillis();
        if (publishedCountCache >= 0 && now - publishedCountAt < 30_000) {
            return publishedCountCache;
        }
        try {
            publishedCountCache = pageMapper.countPublished();
        } catch (Exception e) {
            log.debug("[Wiki] 页面统计不可用（迁移脚本未执行？），热层按空库处理：{}", e.getMessage());
            publishedCountCache = 0L;
        }
        publishedCountAt = now;
        return publishedCountCache;
    }

    public void invalidateCount() {
        publishedCountCache = -1L;
        publishedCountAt = 0L;
    }

    // ==================================================================
    // 蒸馏：任务入队 + 输入导出 + 草稿入库
    // ==================================================================

    /** 手动 / 定时入队 WIKI_DISTILL（走独立 stream exam:wiki） */
    public AsyncTask enqueueDistill(String scope, Long subjectId, Long createdBy) {
        String safeScope = StringUtils.hasText(scope) ? scope.trim().toUpperCase() : "ALL";
        if (!"ALL".equals(safeScope) && !"SUBJECT".equals(safeScope)) {
            throw new BizException(ResultCode.PARAM_ERROR, "scope 只能是 ALL / SUBJECT");
        }
        if ("SUBJECT".equals(safeScope) && subjectId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "scope=SUBJECT 时必须提供 subjectId");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", safeScope);
        payload.put("subjectId", subjectId);
        payload.put("limit", properties.getDistill().getLimit());
        payload.put("maxChunks", properties.getDistill().getMaxChunks());
        return asyncJobService.submit("WIKI_DISTILL", payload, createdBy);
    }

    /**
     * 导出蒸馏输入（Python 消费）
     *
     * <p>聚类只做「按知识点分组」这一件事；跨文档综合交给 LLM。
     */
    public Map<String, Object> distillInput(String scope, Long subjectId,
                                            Integer limit, Integer maxChunks) {
        int safeLimit = clamp(limit == null ? properties.getDistill().getLimit() : limit, 1, 50);
        int safeChunks = clamp(maxChunks == null ? properties.getDistill().getMaxChunks() : maxChunks, 2, 12);
        int minChunks = Math.min(properties.getDistill().getMinChunks(), safeChunks);
        Long filterSubject = "SUBJECT".equalsIgnoreCase(scope) ? subjectId : subjectId;

        List<Map<String, Object>> rows = distillMapper.selectKnowledgeClusters(
                filterSubject, minChunks, safeLimit);
        List<Map<String, Object>> clusters = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long knowledgeId = longValue(row.get("knowledgeId"));
            Long subject = longValue(row.get("subjectId"));
            String name = stringValue(row.get("knowledgeName"));
            if (knowledgeId == null || !StringUtils.hasText(name)) {
                continue;
            }
            List<Map<String, Object>> chunks = distillMapper.selectClusterChunks(knowledgeId, safeChunks);
            List<Map<String, Object>> cleaned = new ArrayList<>();
            for (Map<String, Object> chunk : chunks) {
                Long chunkId = longValue(chunk.get("id"));
                String content = stringValue(chunk.get("content"));
                if (chunkId == null || !StringUtils.hasText(content)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", chunkId);
                item.put("title", stringValue(chunk.get("title")));
                item.put("content", content.length() <= 1500 ? content : content.substring(0, 1500));
                item.put("sourceType", stringValue(chunk.get("sourceType")));
                item.put("sourceId", longValue(chunk.get("sourceId")));
                cleaned.add(item);
            }
            if (cleaned.size() < minChunks) {
                continue;
            }
            Map<String, Object> cluster = new LinkedHashMap<>();
            cluster.put("slug", "kp-" + knowledgeId);
            cluster.put("title", name);
            cluster.put("subjectId", subject);
            cluster.put("knowledgeId", knowledgeId);
            cluster.put("chunkCount", longValue(row.get("chunkCount")));
            cluster.put("chunks", cleaned);
            clusters.add(cluster);
        }

        List<Map<String, Object>> catalog = new ArrayList<>();
        for (WikiPage page : pageMapper.selectCatalog(200)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slug", page.getSlug());
            item.put("title", page.getTitle());
            item.put("subjectId", page.getSubjectId());
            catalog.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope);
        data.put("subjectId", subjectId);
        data.put("clusters", clusters);
        data.put("catalog", catalog);
        return data;
    }

    /**
     * 接收 Python 蒸馏结果，写入/更新待审草稿
     *
     * <p>同一 slug 同时只保留一条 PENDING：重复蒸馏走更新，避免积压重复稿。
     */
    public Map<String, Object> createRevision(WikiRevisionRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getSlug())
                || !StringUtils.hasText(request.getTitle())
                || !StringUtils.hasText(request.getSummary())
                || !StringUtils.hasText(request.getContent())) {
            throw new BizException(ResultCode.PARAM_ERROR, "slug/title/summary/content 不能为空");
        }
        List<Long> chunkIds = request.getSourceChunkIds() == null
                ? List.of() : dedupe(request.getSourceChunkIds());
        if (chunkIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "source_chunk_ids 不能为空（必须可溯源）");
        }
        List<AiChunk> chunks = chunkMapper.selectByIds(chunkIds);
        Set<Long> activeIds = new HashSet<>();
        for (AiChunk chunk : chunks) {
            if (chunk != null && chunk.isActive()) {
                activeIds.add(chunk.getId());
            }
        }
        List<Long> validIds = chunkIds.stream().filter(activeIds::contains).toList();
        if (validIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "source_chunk_ids 全部无效（块不存在或已失效）");
        }

        WikiPage existingPage = pageMapper.selectBySlug(request.getSlug());
        WikiPageRevision pending = revisionMapper.selectPendingBySlug(request.getSlug());

        WikiPageRevision revision = pending == null ? new WikiPageRevision() : pending;
        revision.setPageId(existingPage == null ? null : existingPage.getId());
        revision.setSlug(request.getSlug().trim());
        revision.setTitle(request.getTitle().trim());
        revision.setSubjectId(request.getSubjectId());
        revision.setKnowledgeId(request.getKnowledgeId());
        revision.setSummary(request.getSummary().trim());
        revision.setContent(request.getContent());
        revision.setKeyPoints(WikiJsonSupport.write(objectMapper, request.getKeyPoints()));
        revision.setSourceChunkIds(WikiJsonSupport.write(objectMapper, validIds));
        revision.setLinksJson(WikiJsonSupport.write(objectMapper, request.getLinks()));
        revision.setModel(WikiJsonSupport.text(request.getModel(), 64));
        revision.setQualityScore(quality(request.getQualityScore()));
        revision.setTaskId(request.getTaskId());
        revision.setStatus(WikiPageRevision.STATUS_PENDING);
        if (pending == null) {
            revision.setCreatedAt(LocalDateTime.now());
            try {
                revisionMapper.insert(revision);
            } catch (DuplicateKeyException e) {
                // 并发蒸馏同一 slug：改用更新已有待审稿
                WikiPageRevision concurrent = revisionMapper.selectPendingBySlug(request.getSlug());
                if (concurrent == null) {
                    throw new BizException(ResultCode.SERVER_ERROR, "草稿写入失败");
                }
                revision.setId(concurrent.getId());
                revisionMapper.updateById(revision);
            }
        } else {
            revisionMapper.updateById(revision);
        }
        return Map.of("revisionId", revision.getId(), "slug", revision.getSlug(),
                "updated", pending != null, "sourceChunks", validIds.size());
    }

    // ==================================================================
    // 内部转换
    // ==================================================================

    private void rebuildLinks(WikiPage page, WikiPageRevision revision) {
        linkMapper.deleteByFrom(page.getId());
        Set<Long> linked = new HashSet<>();
        // ① LLM 提议的链接（只认已发布的真实 slug，拒绝悬空边）
        JsonNode links = readJson(revision.getLinksJson());
        if (links != null && links.isArray()) {
            for (JsonNode node : links) {
                String toSlug = node.path("toSlug").asText(node.path("to_slug").asText(""));
                if (!StringUtils.hasText(toSlug) || toSlug.equals(page.getSlug())) {
                    continue;
                }
                WikiPage target = pageMapper.selectBySlug(toSlug.trim());
                if (target == null || !target.isPublished() || !linked.add(target.getId())) {
                    continue;
                }
                String relation = node.path("relation").asText(WikiPageLink.RELATION_RELATED);
                double weight = node.path("weight").asDouble(0.6);
                linkMapper.insertIgnore(page.getId(), target.getId(),
                        safeRelation(relation), clampWeight(weight));
            }
        }
        // ② 同科目同级链接：保证导航图不会出现孤岛（最多 3 条）
        if (page.getSubjectId() != null) {
            List<WikiPage> siblings = new ArrayList<>(pageMapper.selectPublishedBySubject(page.getSubjectId()));
            siblings.removeIf(item -> item.getId().equals(page.getId()));
            long anchor = page.getKnowledgeId() == null ? 0L : page.getKnowledgeId();
            siblings.sort(Comparator.comparingLong(item ->
                    Math.abs((item.getKnowledgeId() == null ? 0L : item.getKnowledgeId()) - anchor)));
            int count = 0;
            for (WikiPage sibling : siblings) {
                if (count >= 3 || !linked.add(sibling.getId())) {
                    continue;
                }
                linkMapper.insertIgnore(page.getId(), sibling.getId(),
                        WikiPageLink.RELATION_SIBLING, 0.4);
                count++;
            }
        }
    }

    private boolean syncPageVector(WikiPage page) {
        if (!properties.getVector().isAutoIndex()) {
            return false;
        }
        return fastApiClient.syncWikiPages(List.of(payloadOf(page))) != null;
    }

    private FastApiClient.WikiPagePayload payloadOf(WikiPage page) {
        return new FastApiClient.WikiPagePayload(page.getId(), page.getTitle(), page.getSummary(),
                WikiJsonSupport.stringList(objectMapper, page.getKeyPoints()),
                page.getSubjectId(), page.getKnowledgeId(), page.getVersion());
    }

    private WikiPageVO toPageVO(WikiPage page) {
        WikiPageVO vo = new WikiPageVO();
        vo.setId(page.getId());
        vo.setSlug(page.getSlug());
        vo.setTitle(page.getTitle());
        vo.setSubjectId(page.getSubjectId());
        vo.setKnowledgeId(page.getKnowledgeId());
        vo.setSummary(page.getSummary());
        vo.setKeyPoints(WikiJsonSupport.stringList(objectMapper, page.getKeyPoints()));
        vo.setSourceChunkIds(WikiJsonSupport.longList(objectMapper, page.getSourceChunkIds()));
        vo.setVersion(page.getVersion());
        vo.setTrustTier(page.getTrustTier());
        vo.setStaleReason(page.getStaleReason());
        vo.setExpiresAt(page.getExpiresAt());
        vo.setQualityScore(page.getQualityScore() == null ? null : page.getQualityScore().doubleValue());
        vo.setUpdatedAt(page.getUpdatedAt());
        return vo;
    }

    private WikiPageDetailVO toDetailVO(WikiPage page) {
        WikiPageDetailVO vo = new WikiPageDetailVO();
        vo.setId(page.getId());
        vo.setSlug(page.getSlug());
        vo.setTitle(page.getTitle());
        vo.setSubjectId(page.getSubjectId());
        vo.setKnowledgeId(page.getKnowledgeId());
        vo.setSummary(page.getSummary());
        vo.setContent(page.getContent());
        vo.setKeyPoints(WikiJsonSupport.stringList(objectMapper, page.getKeyPoints()));
        vo.setSourceChunkIds(WikiJsonSupport.longList(objectMapper, page.getSourceChunkIds()));
        vo.setVersion(page.getVersion());
        vo.setTrustTier(page.getTrustTier());
        vo.setStaleReason(page.getStaleReason());
        vo.setExpiresAt(page.getExpiresAt());
        vo.setQualityScore(page.getQualityScore() == null ? null : page.getQualityScore().doubleValue());
        vo.setUpdatedAt(page.getUpdatedAt());

        Map<Long, WikiPage> related = new LinkedHashMap<>();
        for (WikiPage item : linkMapper.selectLinkedPages(page.getId())) {
            related.putIfAbsent(item.getId(), item);
        }
        for (WikiPage item : linkMapper.selectBacklinks(page.getId())) {
            related.putIfAbsent(item.getId(), item);
        }
        vo.setRelatedPages(related.values().stream().map(this::toPageVO).toList());
        return vo;
    }

    private WikiRevisionVO toRevisionVO(WikiPageRevision revision) {
        WikiRevisionVO vo = new WikiRevisionVO();
        vo.setId(revision.getId());
        vo.setPageId(revision.getPageId());
        vo.setSlug(revision.getSlug());
        vo.setTitle(revision.getTitle());
        vo.setSubjectId(revision.getSubjectId());
        vo.setKnowledgeId(revision.getKnowledgeId());
        vo.setSummary(revision.getSummary());
        vo.setContent(revision.getContent());
        vo.setKeyPoints(WikiJsonSupport.stringList(objectMapper, revision.getKeyPoints()));
        vo.setSourceChunkIds(WikiJsonSupport.longList(objectMapper, revision.getSourceChunkIds()));
        vo.setModel(revision.getModel());
        vo.setQualityScore(revision.getQualityScore() == null ? null : revision.getQualityScore().doubleValue());
        vo.setStatus(revision.getStatus());
        vo.setTaskId(revision.getTaskId());
        vo.setReviewerId(revision.getReviewerId());
        vo.setReviewNote(revision.getReviewNote());
        vo.setCreatedAt(revision.getCreatedAt());
        vo.setReviewedAt(revision.getReviewedAt());
        return vo;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private JsonNode readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Long> dedupe(List<Long> ids) {
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private static BigDecimal quality(Double score) {
        double safe = score == null ? 0.0 : Math.max(0.0, Math.min(1.0, score));
        return BigDecimal.valueOf(safe).setScale(4, RoundingMode.HALF_UP);
    }

    private static double clampWeight(double weight) {
        return Math.max(0.0, Math.min(1.0, weight));
    }

    private static String safeRelation(String relation) {
        if (WikiPageLink.RELATION_SIBLING.equalsIgnoreCase(relation)) {
            return WikiPageLink.RELATION_SIBLING;
        }
        if (WikiPageLink.RELATION_PREREQUISITE.equalsIgnoreCase(relation)) {
            return WikiPageLink.RELATION_PREREQUISITE;
        }
        return WikiPageLink.RELATION_RELATED;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static String trim(String text) {
        return text == null ? null : text.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
