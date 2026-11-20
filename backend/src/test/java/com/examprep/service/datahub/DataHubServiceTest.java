package com.examprep.service.datahub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.AdsDailyMetrics;
import com.examprep.entity.AdsSubjectCoverage;
import com.examprep.entity.DataPipelineJob;
import com.examprep.entity.DataQualityIssue;
import com.examprep.mapper.AdsDailyMetricsMapper;
import com.examprep.mapper.AdsSubjectCoverageMapper;
import com.examprep.mapper.DataPipelineJobMapper;
import com.examprep.mapper.DataQualityIssueMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据中台验证（WS-2 / T2.4）
 *
 * <p><b>为什么用真库而不是全 mock</b>：本工作流最容易出错的地方全在 SQL 里——
 * {@code is_deleted = 0} 漏写、COUNT 空窗口返回 NULL、窗口函数分组错、唯一键没生效导致
 * 重复巡检翻倍。这些在 mock 掉 mapper 的单测里一个都发现不了。
 * 因此这里直接跑真 MySQL，并用 JdbcTemplate 独立复算做对照。
 *
 * <p><b>零污染</b>：类级 {@code @Transactional}，测试结束整体回滚；
 * 断言用的是「数据库当前真实规模」的相对关系（physical > live、二次巡检 created=0），
 * 不写死任何条数，换一台机器也能过。
 */
@DisplayName("数据中台：指标口径 / ETL 幂等 / 巡检幂等 / 写接口鉴权")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class DataHubServiceTest {

    @Autowired
    private DataMetricsEtlService etlService;

    @Autowired
    private DataQualityService qualityService;

    @Autowired
    private DataHubService dataHubService;

    @Autowired
    private AdsDailyMetricsMapper dailyMetricsMapper;

    @Autowired
    private AdsSubjectCoverageMapper coverageMapper;

    @Autowired
    private DataQualityIssueMapper issueMapper;

    @Autowired
    private DataPipelineJobMapper jobMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    // ==================================================================
    // 指标口径
    // ==================================================================

    @Test
    @DisplayName("① ETL 只统计 is_deleted=0 且 APPROVED 的题量，并落 METRICS_ETL 流水")
    void etl口径过滤逻辑删除() {
        LocalDate today = LocalDate.now();
        etlService.run(List.of(today), "test:etl");

        long physical = count("SELECT COUNT(*) FROM question");
        long live = count("SELECT COUNT(*) FROM question WHERE is_deleted = 0");
        long approved = count("SELECT COUNT(*) FROM question WHERE is_deleted = 0 AND audit_status = 'APPROVED'");
        long activeChunks = count("SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE'");

        // 前置条件：题库里确实存在逻辑删除行，否则这条断言没有意义
        assertTrue(physical > live, "测试库应存在 is_deleted=1 的题目，当前 physical=" + physical + " live=" + live);

        AdsDailyMetrics row = dailyMetricsMapper.selectOne(Wrappers.<AdsDailyMetrics>lambdaQuery()
                .eq(AdsDailyMetrics::getStatDate, today));
        assertNotNull(row, "ETL 后应存在当日快照");
        assertEquals((int) approved, row.getQuestionApprovedTotal().intValue(),
                "已过审题量必须排除逻辑删除行");
        assertEquals((int) activeChunks, row.getChunkActiveTotal().intValue(),
                "ACTIVE 块总量口径不一致");
        assertTrue(row.getAiRefusedCount() <= row.getAiAskCount(), "拒答数不应大于提问数");

        DataPipelineJob job = jobMapper.selectOne(Wrappers.<DataPipelineJob>lambdaQuery()
                .eq(DataPipelineJob::getJobType, "METRICS_ETL")
                .orderByDesc(DataPipelineJob::getId)
                .last("LIMIT 1"));
        assertNotNull(job);
        assertEquals("SUCCESS", job.getStatus());
        assertNotNull(job.getFinishedAt());
        assertTrue(job.getTotalRows() > 0);
    }

    @Test
    @DisplayName("② 重复触发 ETL：日快照不翻倍、覆盖矩阵行数稳定")
    void etl重复触发幂等() {
        LocalDate today = LocalDate.now();
        etlService.run(List.of(today), "test:etl-1");
        int coverageAfterFirst = coverageMapper.selectCount(null).intValue();
        long dailyAfterFirst = dailyMetricsMapper.selectCount(Wrappers.<AdsDailyMetrics>lambdaQuery()
                .eq(AdsDailyMetrics::getStatDate, today));

        etlService.run(List.of(today), "test:etl-2");
        long dailyAfterSecond = dailyMetricsMapper.selectCount(Wrappers.<AdsDailyMetrics>lambdaQuery()
                .eq(AdsDailyMetrics::getStatDate, today));
        int coverageAfterSecond = coverageMapper.selectCount(null).intValue();

        assertEquals(1L, dailyAfterFirst, "同一天只应有一条日指标");
        assertEquals(1L, dailyAfterSecond, "重复 ETL 不得新增第二条日指标");
        assertEquals(coverageAfterFirst, coverageAfterSecond, "重复 ETL 后覆盖矩阵行数应稳定");
        assertTrue(coverageAfterSecond > 0, "覆盖矩阵应有真实科目数据");
    }

    @Test
    @DisplayName("③ 覆盖矩阵：健康度 0-100，题量与库内口径一致，缺口筛选只返回缺口")
    void 覆盖矩阵口径与缺口筛选() {
        etlService.run(List.of(LocalDate.now()), "test:coverage");

        List<AdsSubjectCoverage> rows = coverageMapper.selectList(null);
        assertFalse(rows.isEmpty());
        long approvedSum = 0;
        for (AdsSubjectCoverage row : rows) {
            assertTrue(row.getLiveQuestionCount() >= row.getApprovedQuestionCount(),
                    "存活题量应 >= 已过审题量：" + row.getSubjectId());
            assertTrue(row.getLiveQuestionCount() >= row.getPendingQuestionCount());
            assertTrue(row.getCoverageScore().doubleValue() >= 0
                    && row.getCoverageScore().doubleValue() <= 100,
                    "覆盖健康度必须落在 0-100：" + row.getCoverageScore());
            approvedSum += row.getApprovedQuestionCount();
        }
        long approvedTotal = count(
                "SELECT COUNT(*) FROM question WHERE is_deleted = 0 AND audit_status = 'APPROVED'");
        assertTrue(approvedSum <= approvedTotal,
                "各科目已过审之和不应超过全库已过审（未归类科目会少算）");
        assertTrue(approvedSum > 0, "覆盖矩阵应统计到真实题量");

        List<Map<String, Object>> gaps = dataHubService.coverage(null, true);
        for (Map<String, Object> gap : gaps) {
            long live = ((Number) gap.get("liveQuestionCount")).longValue();
            long approved = ((Number) gap.get("approvedQuestionCount")).longValue();
            long chunk = ((Number) gap.get("chunkActiveCount")).longValue();
            assertTrue(live <= 0 || approved <= 0 || chunk * 100.0 / approved < 80.0,
                    "缺口筛选混入了非缺口科目：" + gap);
        }
    }

    @Test
    @DisplayName("④ 趋势：缺失日期补 0，返回长度等于请求天数且以今天结尾")
    void 趋势补齐缺失日期() {
        etlService.run(List.of(LocalDate.now()), "test:trend");
        List<Map<String, Object>> series = dataHubService.trends(7);
        assertEquals(7, series.size());
        assertEquals(LocalDate.now().toString(), series.get(6).get("statDate"));
        assertEquals(LocalDate.now().minusDays(6).toString(), series.get(0).get("statDate"));
        assertTrue(series.get(6).containsKey("activeUsers"));
    }

    // ==================================================================
    // 质量巡检
    // ==================================================================

    @Test
    @DisplayName("⑤ 巡检幂等：二次触发 created=0、无重复 issue_key，已修复项自动 RESOLVED")
    void 巡检幂等且自动销号() {
        long before = issueMapper.selectCount(null);

        // 预埋一条「早已修复」的 OPEN 问题，巡检后应被自动销号
        DataQualityIssue stale = new DataQualityIssue();
        stale.setIssueKey("MISSING_ANSWER:question:987654321");
        stale.setIssueType("MISSING_ANSWER");
        stale.setSeverity("CRITICAL");
        stale.setTargetTable("question");
        stale.setTargetId(987654321L);
        stale.setDetail("测试预埋：该题号不存在，巡检应自动销号");
        stale.setStatus("OPEN");
        stale.setDetectedAt(LocalDateTime.now().minusDays(3));
        issueMapper.insert(stale);

        DataQualityService.ScanResult first = qualityService.scan();
        long afterFirst = issueMapper.selectCount(null);
        assertTrue(first.active() > 0, "真实题库应能巡检出问题（重复题干 / 空科目 / 孤儿向量）");
        assertEquals(DataQualityService.ISSUE_TYPES.size(), first.byType().size());

        assertEquals("RESOLVED", issueMapper.selectById(stale.getId()).getStatus(),
                "本次未再命中的历史 OPEN 项必须自动销号");

        DataQualityService.ScanResult second = qualityService.scan();
        long afterSecond = issueMapper.selectCount(null);

        assertEquals(0, second.created(), "第二次巡检不得新增行（幂等）");
        assertEquals(afterFirst, afterSecond, "第二次巡检不得改变问题总行数");
        assertEquals(first.active(), second.active(), "两次巡检活跃问题数应一致");

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM data_quality_issue", Long.class);
        Long distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT issue_key) FROM data_quality_issue", Long.class);
        assertEquals(total, distinct, "issue_key 必须唯一，不能出现重复行");
        assertTrue(afterFirst > before, "预埋的已修复问题与活跃问题都应落库");
    }

    // ==================================================================
    // 权限
    // ==================================================================

    @Test
    @DisplayName("⑥ 审计员：读接口 200，触发 ETL / 巡检 403")
    @WithMockUser(username = "auditor", roles = "AUDITOR")
    void 审计员只读不可触发写操作() throws Exception {
        mockMvc.perform(get("/admin/data-hub/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/data-hub/metric-dictionary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(post("/admin/data-hub/run-etl"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/data-hub/run-quality-scan"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⑦ 管理员：可触发 ETL 并拿到结构化结果")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 管理员可触发写操作() throws Exception {
        mockMvc.perform(post("/admin/data-hub/run-etl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.dailyRows").value(2));
    }

    @Test
    @DisplayName("⑧ 读取接口：overview / pipelines / quality-issues 的别名与分页形状正确")
    void 读取接口形状正确() {
        etlService.run(List.of(LocalDate.now()), "test:read");

        Map<String, Object> overview = dataHubService.overview();
        assertTrue(((Number) overview.get("questionLive")).longValue() > 0);
        assertNotNull(overview.get("qualityHealthScore"));
        assertNotNull(overview.get("openIssueByType"),
                "overview 应带按类型统计的告警数量，供看板小卡片直接渲染");

        List<Map<String, Object>> pipelines = dataHubService.pipelines(5);
        assertFalse(pipelines.isEmpty(), "刚跑过 ETL，流水列表不应为空");
        assertEquals("METRICS_ETL", pipelines.get(0).get("jobType"),
                "列别名必须与 Map key 对齐（jobType 而非 job_type）");

        var issues = dataHubService.qualityIssues("MISSING_ANSWER", null, null, 1, 5);
        assertEquals(1L, issues.getPage().longValue());
        assertNotNull(issues.getList());
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }
}
