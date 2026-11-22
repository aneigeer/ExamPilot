package com.examprep.service.wiki;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.WikiPage;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiki 信任治理：源变更标旧、热层准入、退役与重新发布恢复。
 */
@DisplayName("Wiki 治理：STALE / RETIRED / 过期页面不走热层")
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class WikiGovernanceTest {

    @Autowired
    private WikiPageMapper pageMapper;

    @Autowired
    private AiChunkMapper chunkMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private WikiGovernanceService service() {
        return new WikiGovernanceService(pageMapper, chunkMapper, objectMapper);
    }

    private WikiPage insertPage(String slug, String status, String tier,
                                LocalDateTime expiresAt, String chunkIds) {
        WikiPage page = new WikiPage();
        page.setSlug(slug + "-" + System.nanoTime());
        page.setTitle("治理测试页");
        page.setSummary("摘要");
        page.setContent("正文");
        page.setKeyPoints("[]");
        page.setSourceChunkIds(chunkIds);
        page.setStatus(status);
        page.setTrustTier(tier);
        page.setExpiresAt(expiresAt);
        page.setVersion(1);
        page.setPublishedAt(LocalDateTime.now());
        page.setCreatedAt(LocalDateTime.now());
        page.setUpdatedAt(LocalDateTime.now());
        pageMapper.insert(page);
        return page;
    }

    @Test
    @DisplayName("① 引用块变更 → STALE_REVIEW，热层立即停用")
    void sourceChangeMarksStale() {
        WikiPage page = insertPage("gov-stale", WikiPage.STATUS_PUBLISHED,
                WikiGovernanceService.TIER_VERIFIED, null, "[101,102]");
        int affected = service().markStaleByChunkIds(List.of(102L), "来源块已更新");

        WikiPage reloaded = pageMapper.selectById(page.getId());
        assertEquals(1, affected);
        assertEquals(WikiGovernanceService.TIER_STALE, reloaded.getTrustTier());
        assertTrue(reloaded.getStaleReason().contains("来源块"));
        assertFalse(service().servingAllowed(reloaded));
    }

    @Test
    @DisplayName("② 退役页面永不直出；重新发布恢复 VERIFIED 并清空标旧原因")
    void retireAndRepublish() {
        WikiPage page = insertPage("gov-retire", WikiPage.STATUS_PUBLISHED,
                WikiGovernanceService.TIER_VERIFIED, null, "[]");
        WikiGovernanceService svc = service();
        assertTrue(svc.retire(page.getId(), "内容过时"));

        WikiPage retired = pageMapper.selectById(page.getId());
        assertEquals(WikiGovernanceService.TIER_RETIRED, retired.getTrustTier());
        assertFalse(svc.servingAllowed(retired));

        svc.markVerified(retired, List.of());
        pageMapper.updateById(retired);
        pageMapper.update(null, Wrappers.<WikiPage>lambdaUpdate()
                .eq(WikiPage::getId, retired.getId())
                .set(WikiPage::getStaleReason, null)
                .set(WikiPage::getExpiresAt, null));
        WikiPage republished = pageMapper.selectById(page.getId());
        assertEquals(WikiGovernanceService.TIER_VERIFIED, republished.getTrustTier());
        assertNull(republished.getStaleReason());
        assertTrue(svc.servingAllowed(republished));
    }

    @Test
    @DisplayName("③ 已过期页面不走热层")
    void expiredPageNotServed() {
        WikiPage page = insertPage("gov-expired", WikiPage.STATUS_PUBLISHED,
                WikiGovernanceService.TIER_VERIFIED, LocalDateTime.now().minusDays(1), "[]");
        assertFalse(service().servingAllowed(page));

        page.setExpiresAt(LocalDateTime.now().plusDays(1));
        assertTrue(service().servingAllowed(page));
    }

    @Test
    @DisplayName("④ 只标记命中来源块的页面，其他页面不受影响")
    void onlyReferencingPagesMarked() {
        WikiPage target = insertPage("gov-target", WikiPage.STATUS_PUBLISHED,
                WikiGovernanceService.TIER_VERIFIED, null, "[201]");
        WikiPage other = insertPage("gov-other", WikiPage.STATUS_PUBLISHED,
                WikiGovernanceService.TIER_VERIFIED, null, "[202]");

        service().markStaleByChunkIds(List.of(201L), "测试");

        assertEquals(WikiGovernanceService.TIER_STALE,
                pageMapper.selectById(target.getId()).getTrustTier());
        assertEquals(WikiGovernanceService.TIER_VERIFIED,
                pageMapper.selectById(other.getId()).getTrustTier());
        assertTrue(pageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getStatus, WikiPage.STATUS_PUBLISHED)) > 0);
    }
}
