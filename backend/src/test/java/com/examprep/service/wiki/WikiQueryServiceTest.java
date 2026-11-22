package com.examprep.service.wiki;

import com.examprep.entity.WikiPage;
import com.examprep.mapper.WikiPageLinkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.examprep.service.ai.FastApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

/**
 * Wiki 热层检索：向量 / ngram / 图遍历三路合并 + 命中阈值。
 */
@DisplayName("Wiki 热层检索")
class WikiQueryServiceTest {

    private WikiPageMapper pageMapper;
    private WikiPageLinkMapper linkMapper;
    private FastApiClient fastApiClient;
    private WikiProperties properties;
    private WikiService wikiService;
    private WikiGovernanceService governance;
    private WikiQueryService service;

    @BeforeEach
    void setUp() {
        pageMapper = mock(WikiPageMapper.class);
        linkMapper = mock(WikiPageLinkMapper.class);
        fastApiClient = mock(FastApiClient.class);
        wikiService = mock(WikiService.class);
        governance = mock(WikiGovernanceService.class);
        when(governance.servingAllowed(any())).thenReturn(true);
        properties = new WikiProperties();
        properties.setLinkExpand(false);
        service = new WikiQueryService(pageMapper, linkMapper, fastApiClient,
                properties, wikiService, governance, new ObjectMapper());
    }

    private WikiPage page(long id, String slug, String title) {
        WikiPage page = new WikiPage();
        page.setId(id);
        page.setSlug(slug);
        page.setTitle(title);
        page.setSummary(title + "的摘要");
        page.setContent(title + "的正文");
        page.setStatus(WikiPage.STATUS_PUBLISHED);
        return page;
    }

    @Test
    @DisplayName("向量命中且超过阈值 → 返回 WIKI")
    void vectorHitAboveThreshold() {
        WikiPage page = page(1L, "kp-5", "函数");
        when(wikiService.countPublished()).thenReturn(1L);
        when(fastApiClient.searchWiki(eq("函数是什么"), anyInt(), eq(5L)))
                .thenReturn(List.of(new FastApiClient.WikiVectorHit(1L, 0.78, null)));
        when(pageMapper.searchFulltext(anyString(), eq(5L), anyInt())).thenReturn(List.of());
        when(pageMapper.selectById(1L)).thenReturn(page);

        WikiQueryService.WikiHit hit = service.query("函数是什么", 5L);
        assertNotNull(hit);
        assertEquals("VECTOR", hit.channel());
        assertEquals(1L, hit.page().getId());
        assertTrue(hit.score() >= properties.getHitThreshold());
    }

    @Test
    @DisplayName("ft_score≈0 时靠标题覆盖率仍能命中（小语料冷启动）")
    void ngramFallbackHitByTitleCoverage() {
        WikiPage page = page(2L, "kp-6", "数组");
        // 单页 Wiki 时 MATCH 的 IDF 接近 0，这是线上实测值
        page.setFtScore(0.00000006);
        when(wikiService.countPublished()).thenReturn(1L);
        when(fastApiClient.searchWiki(anyString(), anyInt(), eq(5L))).thenReturn(List.of());
        when(pageMapper.searchFulltext(anyString(), eq(5L), anyInt())).thenReturn(List.of(page));
        when(pageMapper.selectById(2L)).thenReturn(page);

        WikiQueryService.WikiHit hit = service.query("数组下标", 5L);
        assertNotNull(hit);
        assertEquals("NGRAM", hit.channel());
        assertTrue(hit.score() >= properties.getHitThreshold());
    }

    @Test
    @DisplayName("字面完全不重叠且向量分低时不命中")
    void unrelatedQueryMissesEvenWithFulltextRows() {
        WikiPage page = page(2L, "kp-6", "数组");
        page.setFtScore(0.0);
        when(wikiService.countPublished()).thenReturn(1L);
        when(fastApiClient.searchWiki(anyString(), anyInt(), eq(5L)))
                .thenReturn(List.of(new FastApiClient.WikiVectorHit(2L, 0.39, null)));
        when(pageMapper.searchFulltext(anyString(), eq(5L), anyInt())).thenReturn(List.of(page));

        assertNull(service.query("量子力学波函数", 5L));
    }

    @Test
    @DisplayName("最高分低于阈值 → 热层不命中，返回 null（交给 RAG）")
    void belowThresholdMisses() {
        when(wikiService.countPublished()).thenReturn(1L);
        when(fastApiClient.searchWiki(anyString(), anyInt(), eq(5L)))
                .thenReturn(List.of(new FastApiClient.WikiVectorHit(1L, 0.42, null)));
        when(pageMapper.searchFulltext(anyString(), eq(5L), anyInt())).thenReturn(List.of());

        assertNull(service.query("完全无关的问题", 5L));
    }

    @Test
    @DisplayName("图遍历：1 跳关联页按衰减分补位")
    void linkExpansionAddsRelatedPage() {
        WikiPage parent = page(1L, "kp-5", "函数");
        WikiPage related = page(2L, "kp-6", "数组");
        properties.setLinkExpand(true);
        when(wikiService.countPublished()).thenReturn(2L);
        when(fastApiClient.searchWiki(anyString(), anyInt(), eq(5L)))
                .thenReturn(List.of(new FastApiClient.WikiVectorHit(1L, 0.9, null)));
        when(pageMapper.searchFulltext(anyString(), eq(5L), anyInt())).thenReturn(List.of());
        when(linkMapper.selectLinkedPages(1L)).thenReturn(List.of(related));
        when(pageMapper.selectById(1L)).thenReturn(parent);
        when(pageMapper.selectById(2L)).thenReturn(related);

        List<WikiQueryService.WikiHit> hits = service.search("函数", 5L, 2);
        assertEquals(2, hits.size());
        WikiQueryService.WikiHit linked = hits.stream()
                .filter(hit -> hit.page().getId() == 2L).findFirst().orElseThrow();
        assertEquals("LINK", linked.channel());
        assertTrue(linked.viaLink());
        assertTrue(linked.score() < hits.get(0).score());
    }

    @Test
    @DisplayName("没有已发布页面时直接短路，不访问向量库")
    void emptyWikiShortCircuits() {
        when(wikiService.countPublished()).thenReturn(0L);
        assertTrue(service.search("任意问题", null, 3).isEmpty());
    }
}
