package com.examprep.service.wiki;

import com.examprep.common.BizException;
import com.examprep.dto.wiki.WikiRevisionRequest;
import com.examprep.entity.AiChunk;
import com.examprep.entity.WikiPage;
import com.examprep.entity.WikiPageRevision;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.WikiDistillMapper;
import com.examprep.mapper.WikiPageLinkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.examprep.mapper.WikiPageRevisionMapper;
import com.examprep.service.ai.FastApiClient;
import com.examprep.service.ai.agent.AsyncJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiki 管理：草稿校验 / 审核发布 / 链接重建 / 蒸馏输入。
 */
@DisplayName("Wiki 管理服务")
class WikiServiceTest {

    private WikiPageMapper pageMapper;
    private WikiPageRevisionMapper revisionMapper;
    private WikiPageLinkMapper linkMapper;
    private WikiDistillMapper distillMapper;
    private AiChunkMapper chunkMapper;
    private WikiProperties properties;
    private AsyncJobService asyncJobService;
    private FastApiClient fastApiClient;
    private WikiService service;

    @BeforeEach
    void setUp() {
        pageMapper = mock(WikiPageMapper.class);
        revisionMapper = mock(WikiPageRevisionMapper.class);
        linkMapper = mock(WikiPageLinkMapper.class);
        distillMapper = mock(WikiDistillMapper.class);
        chunkMapper = mock(AiChunkMapper.class);
        properties = new WikiProperties();
        asyncJobService = mock(AsyncJobService.class);
        fastApiClient = mock(FastApiClient.class);
        service = new WikiService(pageMapper, mock(WikiGovernanceService.class), revisionMapper, linkMapper, distillMapper,
                chunkMapper, properties, asyncJobService, fastApiClient, new ObjectMapper());
    }

    private WikiRevisionRequest request(long... chunkIds) {
        WikiRevisionRequest request = new WikiRevisionRequest();
        request.setSlug("kp-5");
        request.setTitle("函数");
        request.setSubjectId(5L);
        request.setKnowledgeId(5L);
        request.setSummary("函数是完成特定任务的独立代码块，通过函数名调用。");
        request.setContent("## 函数\n函数是完成特定任务的独立代码块，通过函数名调用。");
        request.setKeyPoints(List.of("函数是代码块", "可以接收参数"));
        request.setSourceChunkIds(Arrays.stream(chunkIds).boxed().toList());
        request.setModel("qwen2.5-7b");
        request.setQualityScore(0.66);
        return request;
    }

    private AiChunk activeChunk(long id) {
        AiChunk chunk = new AiChunk();
        chunk.setId(id);
        chunk.setStatus(AiChunk.STATUS_ACTIVE);
        return chunk;
    }

    @Test
    @DisplayName("来源块全部无效 → 拒绝写入草稿")
    void rejectsInvalidSourceChunks() {
        when(chunkMapper.selectByIds(List.of(999L))).thenReturn(List.of());

        BizException e = assertThrows(BizException.class,
                () -> service.createRevision(request(999L)));
        assertTrue(e.getMessage().contains("无效"));
        verify(revisionMapper, never()).insert(any(WikiPageRevision.class));
    }

    @Test
    @DisplayName("无待审稿时新建 PENDING；有则更新，不堆重复稿")
    void upsertsPendingRevision() {
        when(chunkMapper.selectByIds(List.of(101L))).thenReturn(List.of(activeChunk(101L)));
        when(pageMapper.selectBySlug("kp-5")).thenReturn(null);
        when(revisionMapper.selectPendingBySlug("kp-5")).thenReturn(null);
        doAnswer(invocation -> {
            WikiPageRevision revision = invocation.getArgument(0);
            revision.setId(8L);
            return 1;
        }).when(revisionMapper).insert(any(WikiPageRevision.class));

        Map<String, Object> created = service.createRevision(request(101L));
        assertEquals(false, created.get("updated"));
        assertEquals(8L, created.get("revisionId"));
        verify(revisionMapper).insert(any(WikiPageRevision.class));

        WikiPageRevision pending = new WikiPageRevision();
        pending.setId(8L);
        pending.setSlug("kp-5");
        when(revisionMapper.selectPendingBySlug("kp-5")).thenReturn(pending);
        Map<String, Object> updated = service.createRevision(request(101L));
        assertEquals(true, updated.get("updated"));
        verify(revisionMapper).updateById(any(WikiPageRevision.class));
        verify(revisionMapper, times(1)).insert(any(WikiPageRevision.class));
    }

    @Test
    @DisplayName("审核通过：草稿复制为正式页、版本 +1、重建链接、同步向量")
    void approvePublishesPage() {
        WikiPageRevision revision = new WikiPageRevision();
        revision.setId(9L);
        revision.setSlug("kp-5");
        revision.setTitle("函数");
        revision.setSubjectId(5L);
        revision.setKnowledgeId(5L);
        revision.setSummary("摘要");
        revision.setContent("正文");
        revision.setKeyPoints("[\"要点1\"]");
        revision.setSourceChunkIds("[101]");
        revision.setLinksJson("[{\"to_slug\":\"kp-6\",\"relation\":\"RELATED\",\"weight\":0.7}]");
        revision.setQualityScore(new BigDecimal("0.66"));
        revision.setStatus(WikiPageRevision.STATUS_PENDING);

        WikiPage target = new WikiPage();
        target.setId(11L);
        target.setSlug("kp-6");
        target.setTitle("数组");
        target.setSubjectId(5L);
        target.setKnowledgeId(6L);
        target.setStatus(WikiPage.STATUS_PUBLISHED);

        when(revisionMapper.selectById(9L)).thenReturn(revision);
        when(pageMapper.selectBySlug("kp-5")).thenReturn(null);
        doAnswer(invocation -> {
            WikiPage page = invocation.getArgument(0);
            page.setId(10L);
            return 1;
        }).when(pageMapper).insert(any(WikiPage.class));
        when(revisionMapper.markApproved(eq(9L), eq(10L), any(), any())).thenReturn(1);
        when(pageMapper.selectBySlug("kp-6")).thenReturn(target);
        when(pageMapper.selectPublishedBySubject(5L)).thenReturn(List.of(target));
        when(linkMapper.insertIgnore(anyLong(), anyLong(), anyString(), anyDouble())).thenReturn(1);
        when(fastApiClient.syncWikiPages(any())).thenReturn(
                new FastApiClient.WikiIndexResult(1, 0, 0, 12));

        Map<String, Object> data = service.approve(9L, 1L, "ok");
        assertEquals(10L, data.get("pageId"));
        assertEquals(true, data.get("created"));
        assertEquals(true, data.get("vectorIndexed"));
        verify(pageMapper).insert(any(WikiPage.class));
        verify(revisionMapper).markApproved(eq(9L), eq(10L), eq(1L), eq("ok"));
        verify(linkMapper).deleteByFrom(10L);
        verify(linkMapper).insertIgnore(eq(10L), eq(11L), eq("RELATED"), anyDouble());
        verify(fastApiClient).syncWikiPages(any());
    }

    @Test
    @DisplayName("蒸馏输入：按知识点聚类并携带代表块")
    void buildsDistillInput() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("knowledgeId", 5L);
        row.put("subjectId", 5L);
        row.put("knowledgeName", "函数");
        row.put("chunkCount", 12L);
        when(distillMapper.selectKnowledgeClusters(null, 2, 10)).thenReturn(List.of(row));

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", 101L);
        chunk.put("title", "函数定义");
        chunk.put("content", "函数是完成特定任务的独立代码块。");
        chunk.put("sourceType", "KNOWLEDGE");
        when(distillMapper.selectClusterChunks(5L, 6))
                .thenReturn(List.of(chunk, chunk));
        when(pageMapper.selectCatalog(200)).thenReturn(List.of());

        Map<String, Object> data = service.distillInput("ALL", null, 10, 6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) data.get("clusters");
        assertEquals(1, clusters.size());
        assertEquals("kp-5", clusters.get(0).get("slug"));
        assertEquals("函数", clusters.get(0).get("title"));
        assertFalse(((List<?>) clusters.get(0).get("chunks")).isEmpty());
    }
}
