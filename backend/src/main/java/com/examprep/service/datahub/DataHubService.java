package com.examprep.service.datahub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.PageResult;
import com.examprep.entity.AdsDailyMetrics;
import com.examprep.entity.AdsSubjectCoverage;
import com.examprep.entity.DataQualityIssue;
import com.examprep.mapper.AdsDailyMetricsMapper;
import com.examprep.mapper.AdsSubjectCoverageMapper;
import com.examprep.mapper.DataHubMapper;
import com.examprep.mapper.DataQualityIssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据中台只读查询与指标口径字典
 *
 * <p>所有对外数字都能追溯到具体表与过滤条件；口径字典
 * {@link #metricDictionary()} 与 SQL 保持一致，避免「看板一个数、库中另一个数」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataHubService {

    private static final int MAX_TREND_DAYS = 180;
    private static final int MAX_PIPELINES = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final DataHubMapper dataHubMapper;
    private final DataQualityIssueMapper issueMapper;
    private final AdsDailyMetricsMapper dailyMetricsMapper;
    private final AdsSubjectCoverageMapper coverageMapper;

    // ==================================================================
    // 资产总览
    // ==================================================================

    public Map<String, Object> overview() {
        Map<String, Object> raw = dataHubMapper.assetOverview();
        if (raw == null) {
            raw = Map.of();
        }
        long approved = num(raw, "questionApproved");
        long critical = num(raw, "openCriticalTotal");
        long warn = num(raw, "openWarnTotal");
        long info = num(raw, "openInfoTotal");

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("questionTotalPhysical", num(raw, "questionTotalPhysical"));
        vo.put("questionLive", num(raw, "questionLive"));
        vo.put("questionApproved", approved);
        vo.put("questionPending", num(raw, "questionPending"));
        vo.put("deletedQuestion", Math.max(0, num(raw, "questionTotalPhysical") - num(raw, "questionLive")));
        vo.put("chunkTotal", num(raw, "chunkTotal"));
        vo.put("chunkActive", num(raw, "chunkActive"));
        vo.put("chunkVectorSynced", num(raw, "chunkVectorSynced"));
        vo.put("chunkVectorUnsynced", Math.max(0, num(raw, "chunkActive") - num(raw, "chunkVectorSynced")));
        vo.put("subjectTotal", num(raw, "subjectTotal"));
        vo.put("coursewareTotal", num(raw, "coursewareTotal"));
        vo.put("knowledgeTotal", num(raw, "knowledgeTotal"));
        vo.put("openIssueTotal", num(raw, "openIssueTotal"));
        vo.put("openCriticalTotal", critical);
        vo.put("openWarnTotal", warn);
        vo.put("openInfoTotal", info);
        vo.put("lastEtlAt", raw.get("lastEtlAt"));
        vo.put("qualityHealthScore", healthScore(approved, critical, warn, info));
        vo.put("healthFormula", "100 - min(40, critical*5) - min(40, warn/approved*200) - min(10, info*0.5)，下限 0");
        vo.put("openIssueByType", openIssueCountByType());
        return vo;
    }

    /** 质量健康分：0-100，分母用「已过审存活题量」，避免题库规模放大/缩小惩罚 */
    static BigDecimal healthScore(long approved, long critical, long warn, long info) {
        double pc = Math.min(40.0, critical * 5.0);
        double pw = approved <= 0 ? Math.min(40.0, warn * 2.0)
                : Math.min(40.0, warn * 100.0 / approved * 2.0);
        double pi = Math.min(10.0, info * 0.5);
        double score = Math.max(0.0, 100.0 - pc - pw - pi);
        return BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 覆盖矩阵
    // ==================================================================

    /**
     * 科目覆盖矩阵。
     *
     * <p>优先读 ADS 快照；若还没跑过 ETL（表为空），现场按业务表计算一次，
     * 保证看板首次打开不是空白页。
     *
     * @param examCode 只看某个考试类别（空 = 全部）
     * @param gapOnly  只看缺口：0 题科目 / 已过审但向量覆盖不足 80%
     */
    public List<Map<String, Object>> coverage(String examCode, Boolean gapOnly) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AdsSubjectCoverage> snapshot = coverageMapper.selectList(Wrappers.<AdsSubjectCoverage>lambdaQuery()
                .eq(StringUtils.hasText(examCode), AdsSubjectCoverage::getExamCode, examCode)
                .orderByAsc(AdsSubjectCoverage::getExamCode)
                .orderByAsc(AdsSubjectCoverage::getSubjectId));
        if (!snapshot.isEmpty()) {
            for (AdsSubjectCoverage row : snapshot) {
                rows.add(coverageRow(row.getExamCode(), row.getSubjectId(), row.getSubjectName(),
                        row.getLiveQuestionCount(), row.getApprovedQuestionCount(), row.getPendingQuestionCount(),
                        row.getChunkActiveCount(), row.getCoursewareCount(), row.getKnowledgeCount(),
                        row.getCoverageScore(), row.getUpdatedAt()));
            }
        } else {
            for (Map<String, Object> m : dataHubMapper.subjectCoverage()) {
                String code = text(m, "examCode");
                if (StringUtils.hasText(examCode) && !examCode.equals(code)) {
                    continue;
                }
                int approved = (int) num(m, "approvedQuestionCount");
                int chunk = (int) num(m, "chunkActiveCount");
                rows.add(coverageRow(code, num(m, "subjectId"), text(m, "subjectName"),
                        (int) num(m, "liveQuestionCount"), approved, (int) num(m, "pendingQuestionCount"),
                        chunk, (int) num(m, "coursewareCount"), (int) num(m, "knowledgeCount"),
                        DataMetricsEtlService.coverageScore(approved, chunk,
                                (int) num(m, "coursewareCount"), (int) num(m, "knowledgeCount")),
                        null));
            }
        }

        if (Boolean.TRUE.equals(gapOnly)) {
            rows = rows.stream().filter(DataHubService::isGap).toList();
        }
        return rows;
    }

    static boolean isGap(Map<String, Object> row) {
        long live = asLong(row.get("liveQuestionCount"));
        long approved = asLong(row.get("approvedQuestionCount"));
        long chunk = asLong(row.get("chunkActiveCount"));
        if (live <= 0) {
            return true;
        }
        if (approved <= 0) {
            return true;
        }
        return chunk * 100.0 / approved < 80.0;
    }

    private static Map<String, Object> coverageRow(String examCode, Long subjectId, String subjectName,
                                                   Integer live, Integer approved, Integer pending,
                                                   Integer chunk, Integer courseware, Integer knowledge,
                                                   BigDecimal score, LocalDateTime updatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("examCode", examCode == null ? "" : examCode);
        row.put("subjectId", subjectId);
        row.put("subjectName", subjectName);
        row.put("liveQuestionCount", live == null ? 0 : live);
        row.put("approvedQuestionCount", approved == null ? 0 : approved);
        row.put("pendingQuestionCount", pending == null ? 0 : pending);
        row.put("chunkActiveCount", chunk == null ? 0 : chunk);
        row.put("coursewareCount", courseware == null ? 0 : courseware);
        row.put("knowledgeCount", knowledge == null ? 0 : knowledge);
        row.put("coverageScore", score == null ? BigDecimal.ZERO : score);
        row.put("updatedAt", updatedAt);
        return row;
    }

    // ==================================================================
    // 趋势
    // ==================================================================

    /**
     * 近 N 天学习与 AI 调用趋势。
     *
     * <p>缺失日期按 0 补齐，保证前端折线图 X 轴连续、不会因空窗错位。
     */
    public List<Map<String, Object>> trends(Integer days) {
        int n = days == null ? 30 : Math.max(1, Math.min(MAX_TREND_DAYS, days));
        LocalDate from = LocalDate.now().minusDays(n - 1L);
        List<AdsDailyMetrics> rows = dailyMetricsMapper.selectList(Wrappers.<AdsDailyMetrics>lambdaQuery()
                .ge(AdsDailyMetrics::getStatDate, from)
                .orderByAsc(AdsDailyMetrics::getStatDate));
        Map<LocalDate, AdsDailyMetrics> byDate = new LinkedHashMap<>();
        for (AdsDailyMetrics row : rows) {
            byDate.put(row.getStatDate(), row);
        }

        List<Map<String, Object>> series = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate date = from.plusDays(i);
            AdsDailyMetrics row = byDate.get(date);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("statDate", date.toString());
            point.put("activeUsers", row == null ? 0 : nz(row.getActiveUsers()));
            point.put("practiceCount", row == null ? 0 : nz(row.getPracticeCount()));
            point.put("mockCount", row == null ? 0 : nz(row.getMockCount()));
            point.put("avgAccuracy", row == null || row.getAvgAccuracy() == null
                    ? BigDecimal.ZERO : row.getAvgAccuracy());
            point.put("aiAskCount", row == null ? 0 : nz(row.getAiAskCount()));
            point.put("aiRefusedCount", row == null ? 0 : nz(row.getAiRefusedCount()));
            point.put("aiDegradedCount", row == null ? 0 : nz(row.getAiDegradedCount()));
            point.put("questionApprovedTotal", row == null ? 0 : nz(row.getQuestionApprovedTotal()));
            point.put("chunkActiveTotal", row == null ? 0 : nz(row.getChunkActiveTotal()));
            series.add(point);
        }
        return series;
    }

    // ==================================================================
    // 流水线与质量告警
    // ==================================================================

    public List<Map<String, Object>> pipelines(Integer limit) {
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PIPELINES, limit));
        return dataHubMapper.recentJobs(size);
    }

    public PageResult<DataQualityIssue> qualityIssues(String issueType, String severity, String status,
                                                      long page, long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var wrapper = Wrappers.<DataQualityIssue>lambdaQuery()
                .eq(StringUtils.hasText(issueType), DataQualityIssue::getIssueType, issueType)
                .eq(StringUtils.hasText(severity), DataQualityIssue::getSeverity, severity)
                .eq(StringUtils.hasText(status), DataQualityIssue::getStatus, status)
                .orderByDesc(DataQualityIssue::getDetectedAt)
                .orderByDesc(DataQualityIssue::getId);
        var result = issueMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result, i -> i);
    }

    /** 按类型统计 OPEN 数量（看板顶部小卡片用） */
    public Map<String, Long> openIssueCountByType() {
        List<DataQualityIssue> open = issueMapper.selectList(Wrappers.<DataQualityIssue>lambdaQuery()
                .eq(DataQualityIssue::getStatus, "OPEN"));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : DataQualityService.ISSUE_TYPES) {
            counts.put(type, 0L);
        }
        for (DataQualityIssue issue : open) {
            counts.merge(issue.getIssueType(), 1L, Long::sum);
        }
        return counts;
    }

    // ==================================================================
    // 指标口径字典
    // ==================================================================

    /**
     * 系统标准指标口径。
     *
     * <p>每条都写清「表来源 / 过滤条件 / 计算公式」，前端直接渲染成表格，
     * 避免同一名词在不同页面出现两个数。
     */
    public List<Map<String, String>> metricDictionary() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(dict("题目物理总行", "question", "无过滤（含逻辑删除）",
                "COUNT(*)", "资产盘点用，不等于可用题量"));
        list.add(dict("存量题目", "question", "is_deleted = 0",
                "COUNT(*)", "所有可被业务读取的题（含待审核）"));
        list.add(dict("已过审存活题", "question", "is_deleted = 0 AND audit_status = 'APPROVED'",
                "COUNT(*)", "学生端可作答、可组卷的题量"));
        list.add(dict("待审核题", "question", "is_deleted = 0 AND audit_status = 'PENDING'",
                "COUNT(*)", "审核工作量的直接来源"));
        list.add(dict("ACTIVE 向量块", "ai_chunk", "status = 'ACTIVE'",
                "COUNT(*)", "参与 AI 检索的块；INVALID 不计入"));
        list.add(dict("科目向量覆盖块", "ai_chunk",
                "status = 'ACTIVE' AND source_type = 'QUESTION' AND subject_id = 科目",
                "COUNT(*)", "覆盖矩阵只看题目向量；课件/资料块不计入科目题量覆盖"));
        list.add(dict("向量同步块", "ai_chunk", "status = 'ACTIVE' AND vector_synced = 1",
                "COUNT(*)", "已写入 Qdrant 的块；与 ACTIVE 的差值即待同步量"));
        list.add(dict("活跃用户（日）", "practice_record ∪ exam_record ∪ ai_agent_run",
                "DATE(started_at/created_at) = 统计日；ai_agent_run.user_id IS NOT NULL",
                "COUNT(DISTINCT user_id) 三表 UNION 去重", "同一用户当日多行为只算 1 人"));
        list.add(dict("练习场次（日）", "practice_record", "DATE(started_at) = 统计日",
                "COUNT(*)", "按开练时间归属，不按结束时间"));
        list.add(dict("模考场次（日）", "exam_record", "DATE(started_at) = 统计日",
                "COUNT(*)", "含进行中与超时卷"));
        list.add(dict("练习平均正确率（日）", "practice_record", "DATE(started_at) = 统计日",
                "SUM(correct_count) / SUM(answered_count) * 100",
                "以「已答题数」为分母；无人作答记 0"));
        list.add(dict("AI 提问次数（日）", "ai_agent_run", "DATE(created_at) = 统计日",
                "COUNT(*)", "含成功、拒答、失败、进行中"));
        list.add(dict("AI 拒答次数（日）", "ai_agent_run",
                "DATE(created_at) = 统计日 AND status = 'REFUSED'", "COUNT(*)", "域外问题拒答"));
        list.add(dict("AI 降级次数（日）", "ai_agent_run",
                "DATE(created_at) = 统计日 AND degraded = 1", "COUNT(*)", "模型/检索不可用时的兜底回答"));
        list.add(dict("科目覆盖健康度", "ads_subject_coverage", "按 (exam_code, subject_id)",
                "题量 40 + 向量 30 + 课件 15 + 知识点 15（各项封顶）",
                "题量=min(1,approved/50)*40；向量=approved>0?min(1,chunk/approved)*30:0；"
                        + "课件=min(1,courseware/5)*15；知识点=min(1,knowledge/5)*15"));
        list.add(dict("质量健康分", "data_quality_issue + question",
                "status = 'OPEN' 的告警，分母为已过审存活题",
                "100 - min(40,critical*5) - min(40,warn/approved*200) - min(10,info*0.5)",
                "下限 0；高危问题权重最高"));
        list.add(dict("覆盖缺口", "ads_subject_coverage", "见左列公式",
                "live=0 或 approved=0 或 chunkActive/approved < 80%", "看板「缺口筛选」使用同一判据"));
        return list;
    }

    private static Map<String, String> dict(String metric, String source, String filter,
                                            String formula, String note) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("source", source);
        m.put("filter", filter);
        m.put("formula", formula);
        m.put("note", note);
        return m;
    }

    // ==================================================================
    // 数值辅助
    // ==================================================================

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
