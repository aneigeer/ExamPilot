package com.examprep.service.datahub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.entity.AdsDailyMetrics;
import com.examprep.entity.AdsSubjectCoverage;
import com.examprep.entity.DataPipelineJob;
import com.examprep.mapper.AdsDailyMetricsMapper;
import com.examprep.mapper.AdsSubjectCoverageMapper;
import com.examprep.mapper.DataHubMapper;
import com.examprep.mapper.DataPipelineJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日级指标与科目覆盖 ETL
 *
 * <p>从业务表（practice_record / exam_record / ai_agent_run / question / ai_chunk /
 * courseware / knowledge_point）聚合，落 ADS 快照：
 * {@code ads_daily_metrics} 与 {@code ads_subject_coverage}。
 *
 * <p><b>为什么不用一个大事务</b>：ETL 需要把「成功 / 失败」写回 data_pipeline_job。
 * 若整体包在一个事务里，异常回滚会把失败记录一起抹掉，运维就看不到这次失败。
 * 因此每张快照独立 upsert（唯一键保证幂等），任务状态单独落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataMetricsEtlService {

    private final DataHubMapper dataHubMapper;
    private final AdsDailyMetricsMapper dailyMetricsMapper;
    private final AdsSubjectCoverageMapper coverageMapper;
    private final DataPipelineJobMapper jobMapper;

    /** 默认口径：今天 + 昨天（跨零点补昨日数据） */
    public EtlResult runDefault(String sourceRef) {
        LocalDate today = LocalDate.now();
        return run(List.of(today, today.minusDays(1)), sourceRef);
    }

    public EtlResult run(List<LocalDate> dates, String sourceRef) {
        List<LocalDate> targetDates = dates == null || dates.isEmpty()
                ? List.of(LocalDate.now()) : dates.stream().distinct().toList();

        DataPipelineJob job = new DataPipelineJob();
        job.setJobType("METRICS_ETL");
        job.setSourceRef(sourceRef == null ? "manual" : sourceRef);
        job.setStatus("RUNNING");
        job.setTotalRows(0);
        job.setInsertedRows(0);
        job.setDedupRows(0);
        job.setFailedRows(0);
        job.setStartedAt(LocalDateTime.now());
        jobMapper.insert(job);

        try {
            int dailyRows = 0;
            for (LocalDate date : targetDates) {
                upsertDaily(date);
                dailyRows++;
            }
            int coverageRows = refreshCoverage();

            job.setStatus("SUCCESS");
            job.setTotalRows(dailyRows + coverageRows);
            job.setInsertedRows(dailyRows + coverageRows);
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            log.info("指标 ETL 完成 job={}：日指标 {} 天，科目覆盖 {} 行", job.getId(), dailyRows, coverageRows);
            return new EtlResult(job.getId(), dailyRows, coverageRows, List.copyOf(targetDates), true, null);
        } catch (RuntimeException e) {
            job.setStatus("FAILED");
            job.setFailedRows(1);
            job.setErrorSummary(abbreviate(e.getMessage(), 900));
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            log.error("指标 ETL 失败 job={}", job.getId(), e);
            throw e;
        }
    }

    // ==================================================================
    // ads_daily_metrics
    // ==================================================================

    private void upsertDaily(LocalDate date) {
        Map<String, Object> m = dataHubMapper.dailyMetrics(date);
        if (m == null) {
            m = Map.of();
        }
        long correct = num(m, "correctSum");
        long answered = num(m, "answeredSum");
        BigDecimal accuracy = answered <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(answered), 2, RoundingMode.HALF_UP);

        AdsDailyMetrics row = dailyMetricsMapper.selectOne(Wrappers.<AdsDailyMetrics>lambdaQuery()
                .eq(AdsDailyMetrics::getStatDate, date));
        boolean insert = row == null;
        if (insert) {
            row = new AdsDailyMetrics();
            row.setStatDate(date);
        }
        row.setActiveUsers((int) num(m, "activeUsers"));
        row.setPracticeCount((int) num(m, "practiceCount"));
        row.setMockCount((int) num(m, "mockCount"));
        row.setAvgAccuracy(accuracy);
        row.setAiAskCount((int) num(m, "aiAskCount"));
        row.setAiRefusedCount((int) num(m, "aiRefusedCount"));
        row.setAiDegradedCount((int) num(m, "aiDegradedCount"));
        row.setQuestionApprovedTotal((int) num(m, "questionApprovedTotal"));
        row.setChunkActiveTotal((int) num(m, "chunkActiveTotal"));
        row.setUpdatedAt(LocalDateTime.now());
        if (insert) {
            dailyMetricsMapper.insert(row);
        } else {
            dailyMetricsMapper.updateById(row);
        }
    }

    // ==================================================================
    // ads_subject_coverage
    // ==================================================================

    private int refreshCoverage() {
        List<Map<String, Object>> rows = dataHubMapper.subjectCoverage();
        List<AdsSubjectCoverage> existing = coverageMapper.selectList(null);
        Map<String, AdsSubjectCoverage> byKey = new HashMap<>();
        for (AdsSubjectCoverage row : existing) {
            byKey.put(key(row.getExamCode(), row.getSubjectId()), row);
        }

        Set<String> seen = new HashSet<>();
        for (Map<String, Object> m : rows) {
            String examCode = text(m, "examCode");
            long subjectId = num(m, "subjectId");
            String k = key(examCode, subjectId);
            seen.add(k);

            int live = (int) num(m, "liveQuestionCount");
            int approved = (int) num(m, "approvedQuestionCount");
            int pending = (int) num(m, "pendingQuestionCount");
            int chunk = (int) num(m, "chunkActiveCount");
            int courseware = (int) num(m, "coursewareCount");
            int knowledge = (int) num(m, "knowledgeCount");

            AdsSubjectCoverage row = byKey.get(k);
            boolean insert = row == null;
            if (insert) {
                row = new AdsSubjectCoverage();
                row.setExamCode(examCode);
                row.setSubjectId(subjectId);
            }
            row.setSubjectName(text(m, "subjectName"));
            row.setLiveQuestionCount(live);
            row.setApprovedQuestionCount(approved);
            row.setPendingQuestionCount(pending);
            row.setChunkActiveCount(chunk);
            row.setCoursewareCount(courseware);
            row.setKnowledgeCount(knowledge);
            row.setCoverageScore(coverageScore(approved, chunk, courseware, knowledge));
            row.setUpdatedAt(LocalDateTime.now());
            if (insert) {
                coverageMapper.insert(row);
            } else {
                coverageMapper.updateById(row);
            }
        }

        // 科目已删除 / 已停用时清理历史快照，避免矩阵里出现幽灵行
        List<Long> staleIds = new ArrayList<>();
        for (AdsSubjectCoverage row : existing) {
            if (!seen.contains(key(row.getExamCode(), row.getSubjectId()))) {
                staleIds.add(row.getId());
            }
        }
        for (Long id : staleIds) {
            coverageMapper.deleteById(id);
        }
        return rows.size();
    }

    /**
     * 覆盖健康度（0-100），四项加权，口径对外公开在 /metric-dictionary：
     * <pre>
     *   题量 40 分：min(1, approved/50) * 40
     *   向量 30 分：approved > 0 ? min(1, chunkActive/approved) * 30 : 0
     *   课件 15 分：min(1, courseware/5) * 15
     *   知识点 15 分：min(1, knowledge/5) * 15
     * </pre>
     */
    static BigDecimal coverageScore(int approved, int chunkActive, int courseware, int knowledge) {
        double q = Math.min(1.0, approved / 50.0) * 40.0;
        double v = approved <= 0 ? 0.0 : Math.min(1.0, chunkActive / (double) approved) * 30.0;
        double d = Math.min(1.0, courseware / 5.0) * 15.0;
        double k = Math.min(1.0, knowledge / 5.0) * 15.0;
        return BigDecimal.valueOf(q + v + d + k).setScale(2, RoundingMode.HALF_UP);
    }

    private static String key(String examCode, Long subjectId) {
        return (examCode == null ? "" : examCode) + "#" + (subjectId == null ? 0L : subjectId);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * ETL 结果
     *
     * @param jobId         data_pipeline_job 主键
     * @param dailyRows     日指标写入天数
     * @param coverageRows  覆盖矩阵写入行数
     * @param dates         本次统计日期
     * @param success       是否成功
     * @param errorSummary  失败摘要
     */
    public record EtlResult(Long jobId, int dailyRows, int coverageRows, List<LocalDate> dates,
                            boolean success, String errorSummary) {
    }
}
