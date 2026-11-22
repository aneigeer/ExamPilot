package com.examprep.service.wiki;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.WikiPage;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wiki 信任治理：信任分级、源变更标旧、退役与热层准入判断。
 *
 * <p>独立于 {@link WikiService} 是为了避免与 {@code ChunkSyncService} 形成循环依赖：
 * 分块同步只需要「把引用它的页面标旧」，不应该反向依赖整套 Wiki 发布流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiGovernanceService {

    public static final String TIER_VERIFIED = "VERIFIED";
    public static final String TIER_AUTO = "AUTO_DISTILLED";
    public static final String TIER_STALE = "STALE_REVIEW";
    public static final String TIER_RETIRED = "RETIRED";

    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() {
    };

    private final WikiPageMapper pageMapper;
    private final AiChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    /**
     * 聚合来源块的 content_hash（排序后 SHA-256），用于判断「底层知识是否已变化」。
     */
    public String aggregateHash(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>(new HashSet<>(chunkIds));
        List<Map<String, Object>> rows = chunkMapper.selectHashesByIds(ids);
        List<String> hashes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object hash = row.get("contentHash");
            if (hash != null && !String.valueOf(hash).isBlank()) {
                hashes.add(String.valueOf(hash));
            }
        }
        if (hashes.isEmpty()) {
            return null;
        }
        hashes.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("|", hashes).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("[Wiki治理] 聚合 source_hash 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 引用这些块的已发布页面 → STALE_REVIEW（幂等：已是 STALE 的刷新原因）。
     *
     * @return 受影响页面数
     */
    public int markStaleByChunkIds(Collection<Long> chunkIds, String reason) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        Set<Long> changed = new HashSet<>(chunkIds);
        List<WikiPage> pages = pageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getStatus, WikiPage.STATUS_PUBLISHED));
        int affected = 0;
        for (WikiPage page : pages) {
            List<Long> sourceIds = parseIds(page.getSourceChunkIds());
            if (sourceIds.stream().noneMatch(changed::contains)) {
                continue;
            }
            if (TIER_RETIRED.equals(page.getTrustTier())) {
                continue;
            }
            page.setTrustTier(TIER_STALE);
            page.setStaleReason(abbreviate(reason, 250));
            page.setUpdatedAt(LocalDateTime.now());
            pageMapper.updateById(page);
            affected++;
        }
        if (affected > 0) {
            log.info("[Wiki治理] {} 个页面因来源块变更被标记 STALE_REVIEW（{}）", affected, reason);
        }
        return affected;
    }

    /** 人工退役：永久退出热层，直到重新发布。 */
    public boolean retire(Long pageId, String reason) {
        WikiPage page = pageMapper.selectById(pageId);
        if (page == null) {
            return false;
        }
        page.setTrustTier(TIER_RETIRED);
        page.setStaleReason(abbreviate(reason, 250));
        page.setUpdatedAt(LocalDateTime.now());
        pageMapper.updateById(page);
        return true;
    }

    /** 审核通过 / 重新蒸馏发布：恢复 VERIFIED，并清空过期与标旧原因。 */
    public void markVerified(WikiPage page, Collection<Long> chunkIds) {
        page.setTrustTier(TIER_VERIFIED);
        page.setSourceHash(aggregateHash(chunkIds));
        page.setStaleReason(null);
        page.setExpiresAt(null);
    }

    /**
     * 是否允许走热层直出。
     *
     * <p>RETIRED、STALE_REVIEW、已过期页面一律不允许——调用方（WikiQueryService）
     * 会因此回落到实时 RAG，避免返回过时知识。
     */
    public boolean servingAllowed(WikiPage page) {
        if (page == null || !page.isPublished()) {
            return false;
        }
        String tier = page.getTrustTier() == null ? TIER_VERIFIED : page.getTrustTier();
        if (TIER_RETIRED.equals(tier) || TIER_STALE.equals(tier)) {
            return false;
        }
        return page.getExpiresAt() == null || page.getExpiresAt().isAfter(LocalDateTime.now());
    }

    public List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = objectMapper.readValue(json, ID_LIST);
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
