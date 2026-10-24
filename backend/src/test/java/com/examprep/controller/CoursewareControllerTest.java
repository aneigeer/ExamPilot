package com.examprep.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.entity.AiChunk;
import com.examprep.entity.Courseware;
import com.examprep.mapper.AiChunkMapper;
import com.examprep.mapper.CoursewareMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 课件浏览接口测试
 *
 * <p><b>为什么这个测试值得写</b>：COURSEWARE 是新增来源类型，
 * 它同时牵连三处容易漏改的地方 ——
 * <ol>
 *   <li>{@code ChunkContext.refPath()} 若不加分支会落到 {@code /home}，
 *       用户点「来源」跳到首页而不是课件原文（<b>不报错、极难发现</b>）；</li>
 *   <li>列表页若逐条 count 块数就是 N+1（634 份课件 = 634 次查询）；</li>
 *   <li>详情页必须只返回 ACTIVE 块，且限流 20 块 —— 课件动辄数万字。</li>
 * </ol>
 * 本测试覆盖：列表分页与过滤、详情带 chunks、id 不存在返回 404、refPath 契约。
 */
@DisplayName("课件浏览：列表分页 / 详情 chunks / 404 / 溯源深链")
class CoursewareControllerTest {

    private CoursewareMapper coursewareMapper;
    private AiChunkMapper aiChunkMapper;
    private CoursewareController controller;

    @BeforeEach
    void setUp() {
        coursewareMapper = mock(CoursewareMapper.class);
        aiChunkMapper = mock(AiChunkMapper.class);
        controller = new CoursewareController(coursewareMapper, aiChunkMapper);
    }

    private static Courseware courseware(long id, String title, String category) {
        Courseware cw = new Courseware();
        cw.setId(id);
        cw.setTitle(title);
        cw.setCategory(category);
        cw.setSubjectId(7L);
        cw.setCharCount(8642);
        cw.setStatus("ACTIVE");
        return cw;
    }

    private static AiChunk chunk(long id, long coursewareId, int chunkNo, String content) {
        AiChunk c = new AiChunk();
        c.setId(id);
        c.setSourceType(AiChunk.SOURCE_COURSEWARE);
        c.setSourceId(coursewareId);
        c.setChunkNo(chunkNo);
        c.setStatus(AiChunk.STATUS_ACTIVE);
        c.setContent(content);
        return c;
    }

    /** 构造 mapper 的 GROUP BY 返回：sourceId → cnt */
    private static List<Map<String, Object>> counts(Object... idCntPairs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < idCntPairs.length; i += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", idCntPairs[i]);
            row.put("cnt", idCntPairs[i + 1]);
            rows.add(row);
        }
        return rows;
    }

    // ==================================================================
    // 列表
    // ==================================================================

    @Test
    @DisplayName("① 列表分页：返回 title/category/chunkCount，块数来自 GROUP BY 而非逐条 count")
    void 列表分页() {
        Page<Courseware> page = new Page<>(1, 2);
        page.setTotal(634);
        page.setRecords(List.of(courseware(1, "数据结构 第三章", "数据结构"),
                courseware(2, "操作系统 进程管理", "操作系统")));
        when(coursewareMapper.selectPage(any(), any())).thenReturn(page);
        // ★ 只应被调用一次（批量统计），若改成逐条 count 这个桩不会命中
        when(coursewareMapper.countChunksGroupBySource(AiChunk.SOURCE_COURSEWARE))
                .thenReturn(counts(1L, 12L, 2L, 8L));

        Result<PageResult<Map<String, Object>>> result = controller.list(null, null, 1, 2);

        assertEquals(200, result.getCode());
        PageResult<Map<String, Object>> data = result.getData();
        assertNotNull(data);
        assertEquals(634L, data.getTotal(), "总数应透传");
        assertEquals(2, data.getList().size());
        assertEquals(12, data.getList().get(0).get("chunkCount"), "第 1 份课件块数");
        assertEquals(8, data.getList().get(1).get("chunkCount"), "第 2 份课件块数");
        assertEquals("数据结构", data.getList().get(0).get("category"));
    }

    @Test
    @DisplayName("①-b 无块课件返回 chunkCount=0（不是 null，手机端不必判空）")
    void 无块课件返回零() {
        Page<Courseware> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(courseware(9, "空课件", "其他")));
        when(coursewareMapper.selectPage(any(), any())).thenReturn(page);
        when(coursewareMapper.countChunksGroupBySource(anyString())).thenReturn(counts());

        Result<PageResult<Map<String, Object>>> result = controller.list(null, null, 1, 20);

        assertEquals(0, result.getData().getList().get(0).get("chunkCount"));
    }

    @Test
    @DisplayName("①-c 分页参数越界自动收敛（page<1 → 1，size>100 → 100）")
    void 分页参数收敛() {
        Page<Courseware> page = new Page<>(1, 100);
        page.setTotal(0);
        page.setRecords(List.of());
        when(coursewareMapper.selectPage(any(), any())).thenReturn(page);
        when(coursewareMapper.countChunksGroupBySource(anyString())).thenReturn(counts());

        Result<PageResult<Map<String, Object>>> result = controller.list(null, null, -5, 9999);

        assertEquals(1L, result.getData().getPage(), "page 应被收敛为 1");
        assertEquals(100L, result.getData().getSize(), "size 应被上限 100 截断");
    }

    // ==================================================================
    // 详情
    // ==================================================================

    @Test
    @DisplayName("② 详情：返回元信息 + 前 20 块，长正文截断到 200 字")
    void 详情返回chunks() {
        when(coursewareMapper.selectById(1L)).thenReturn(courseware(1, "数据结构 第三章", "数据结构"));
        when(coursewareMapper.countChunksGroupBySource(AiChunk.SOURCE_COURSEWARE))
                .thenReturn(counts(1L, 3L));
        String longText = "栈".repeat(500);
        when(aiChunkMapper.selectList(any())).thenReturn(List.of(
                chunk(101, 1, 0, "第一节 栈的定义"),
                chunk(102, 1, 1, longText)));

        Result<Map<String, Object>> result = controller.detail(1L);

        assertEquals(200, result.getCode());
        Map<String, Object> data = result.getData();
        assertEquals("数据结构 第三章", data.get("title"));
        assertEquals(3, data.get("chunkCount"));
        assertEquals(8642, data.get("charCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) data.get("chunks");
        assertEquals(2, chunks.size());
        assertEquals(0, chunks.get(0).get("chunkNo"));
        String preview = (String) chunks.get(1).get("content");
        assertTrue(preview.length() <= 201, "长正文应截断，实际长度 " + preview.length());
        assertTrue(preview.endsWith("…"), "截断后应有省略号");
    }

    @Test
    @DisplayName("②-b id 不存在 → 抛 NOT_FOUND（404），不返回空对象")
    void 不存在的id返回404() {
        when(coursewareMapper.selectById(999L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.detail(999L));
        assertEquals(404, e.getCode(), "应返回 404 而不是 200 + null");
    }

    // ==================================================================
    // 溯源深链契约（这是 COURSEWARE 最容易漏改的地方）
    // ==================================================================

    @Test
    @DisplayName("③ refPath：COURSEWARE 必须指向 /courseware/{id}，不能落到 /home")
    void 课件深链正确() {
        com.examprep.service.ai.ChunkContext ctx = new com.examprep.service.ai.ChunkContext(
                101L, AiChunk.SOURCE_COURSEWARE, 1L, "数据结构 第三章", "正文",
                7L, null, 3, AiChunk.SOURCE_COURSEWARE, 1L, null, null, 0L, 1.0, 1.0);

        assertEquals("/courseware/1", ctx.refPath(),
                "COURSEWARE 若落到 /home，用户点来源会跳首页（不报错、极难发现）");
    }

    @Test
    @DisplayName("③-b 原有三种类型的深链不受影响（回归）")
    void 原有类型深链不变() {
        assertEquals("/questions/5", deepLink("QUESTION", 5L));
        assertEquals("/resources/6", deepLink("RESOURCE", 6L));
        assertEquals("/knowledge/7", deepLink("KNOWLEDGE", 7L));
        assertEquals("/home", deepLink("UNKNOWN_TYPE", 8L), "未知类型兜底仍是 /home");
    }

    private static String deepLink(String refType, Long refId) {
        return new com.examprep.service.ai.ChunkContext(
                1L, refType, refId, "t", "c", 1L, null, 1, refType, refId,
                null, null, 0L, 1.0, 1.0).refPath();
    }
}
