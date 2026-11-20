package com.examprep.service.assessment;

import com.examprep.entity.DataPipelineJob;
import com.examprep.entity.QuestionCalibration;
import com.examprep.mapper.AssessmentMapper;
import com.examprep.mapper.DataPipelineJobMapper;
import com.examprep.mapper.QuestionCalibrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试题难度与区分度动态校准
 *
 * <p>数据来源：练习（answer_detail）与模考（exam_answer_detail）的真实作答，
 * 只统计 {@code user_answer} 非空且 {@code is_correct} 已判定的客观题作答。
 *
 * <p>规则：
 * <ul>
 *   <li>作答次数 >= 10 才重算实测正确率并映射难度 1..5；</li>
 *   <li>作答次数 >= 30 且作答者表现存在 >= 2 个档位时，才计算区分度
 *       （高分组 27% 正确率 - 低分组 27% 正确率）；</li>
 *   <li>结果按 question_id 主键覆盖，重复校准不产生重复行；本次不再满足样本门槛的题会被清理。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionCalibrationService {

    private static final int MIN_ATTEMPTS = 10;
    private static final int MIN_DISCRIMINATION_ATTEMPTS = 30;
    private static final int SCAN_LIMIT = 5000;

    private final AssessmentMapper assessmentMapper;
    private final QuestionCalibrationMapper calibrationMapper;
    private final DataPipelineJobMapper jobMapper;

    /** 一键校准（管理端 / 数据中台流水线均可触发） */
    public CalibrationResult recalibrate(String sourceRef) {
        DataPipelineJob job = new DataPipelineJob();
        job.setJobType("CALIBRATION");
        job.setSourceRef(sourceRef == null ? "manual" : sourceRef);
        job.setStatus("RUNNING");
        job.setTotalRows(0);
        job.setInsertedRows(0);
        job.setDedupRows(0);
        job.setFailedRows(0);
        job.setStartedAt(LocalDateTime.now());
        jobMapper.insert(job);

        try {
            List<Map<String, Object>> rows = assessmentMapper.calibrationAggregates(SCAN_LIMIT);
            Set<Long> seen = new HashSet<>();
            int inserted = 0;
            int updated = 0;
            for (Map<String, Object> row : rows) {
                long questionId = num(row, "questionId");
                int attempts = (int) num(row, "attemptCount");
                int correct = (int) num(row, "correctCount");
                if (questionId <= 0 || attempts < MIN_ATTEMPTS) {
                    continue;
                }
                seen.add(questionId);

                BigDecimal accuracy = BigDecimal.valueOf(correct)
                        .divide(BigDecimal.valueOf(attempts), 4, RoundingMode.HALF_UP);
                BigDecimal discrimination = discrimination(row, attempts);

                QuestionCalibration exist = calibrationMapper.selectById(questionId);
                boolean insert = exist == null;
                QuestionCalibration entity = insert ? new QuestionCalibration() : exist;
                entity.setQuestionId(questionId);
                entity.setAttemptCount(attempts);
                entity.setCorrectCount(correct);
                entity.setObservedAccuracy(accuracy);
                entity.setCalibratedDifficulty(difficultyOf(accuracy.doubleValue()));
                entity.setDiscriminationIndex(discrimination);
                entity.setUpdatedAt(LocalDateTime.now());
                if (insert) {
                    calibrationMapper.insert(entity);
                    inserted++;
                } else {
                    calibrationMapper.updateById(entity);
                    updated++;
                }
            }

            // 清理本次不再满足样本门槛的历史行，避免看板展示过期校准
            int removed = 0;
            for (QuestionCalibration old : calibrationMapper.selectList(null)) {
                if (!seen.contains(old.getQuestionId())) {
                    calibrationMapper.deleteById(old.getQuestionId());
                    removed++;
                }
            }

            job.setStatus("SUCCESS");
            job.setTotalRows(rows.size());
            job.setInsertedRows(inserted);
            job.setDedupRows(rows.size() - inserted);
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            log.info("试题校准完成 job={}：样本 {} 题（新增 {} / 更新 {} / 清理 {}）",
                    job.getId(), seen.size(), inserted, updated, removed);
            return new CalibrationResult(job.getId(), seen.size(), inserted, updated, removed, true);
        } catch (RuntimeException e) {
            job.setStatus("FAILED");
            job.setFailedRows(1);
            job.setErrorSummary(truncate(e.getMessage(), 900));
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            log.error("试题校准失败 job={}", job.getId(), e);
            throw e;
        }
    }

    public List<Map<String, Object>> anomalies(int limit) {
        int size = Math.max(1, Math.min(200, limit));
        return assessmentMapper.anomalyQuestions(size);
    }

    /** 校准分布 + 覆盖率 + 近期模考概览（管理端测评看板） */
    public Map<String, Object> summary(int mockDays) {
        int days = Math.max(1, Math.min(180, mockDays));
        List<Map<String, Object>> distribution = assessmentMapper.calibrationDistribution();
        Map<String, Object> coverage = assessmentMapper.calibrationCoverage();
        Map<String, Object> mock = assessmentMapper.mockOverview(LocalDateTime.now().minusDays(days));

        Map<String, Long> byDifficulty = new java.util.LinkedHashMap<>();
        for (int d = 1; d <= 5; d++) {
            byDifficulty.put(String.valueOf(d), 0L);
        }
        long calibrated = 0;
        for (Map<String, Object> row : distribution) {
            long d = num(row, "difficulty");
            long cnt = num(row, "cnt");
            byDifficulty.put(String.valueOf(d), cnt);
            calibrated += cnt;
        }

        long attempted = num(coverage, "attemptedQuestionTotal");
        Map<String, Object> vo = new java.util.LinkedHashMap<>();
        vo.put("calibratedTotal", calibrated);
        vo.put("attemptedQuestionTotal", attempted);
        vo.put("calibrationRate", attempted <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(calibrated * 100.0 / attempted).setScale(1, RoundingMode.HALF_UP));
        vo.put("difficultyDistribution", byDifficulty);
        vo.put("anomalyTotal", anomalies(200).size());
        vo.put("mockWindowDays", days);
        vo.put("mockTotal", num(mock, "total"));
        vo.put("mockFinished", num(mock, "finished"));
        vo.put("mockFinishRate", num(mock, "total") <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(num(mock, "finished") * 100.0 / num(mock, "total"))
                        .setScale(1, RoundingMode.HALF_UP));
        vo.put("mockAvgScore", round(mock.get("avgScore"), 1));
        vo.put("mockAvgRate", round(mock.get("avgRate"), 1));
        return vo;
    }

    // ==================================================================
    // 规则
    // ==================================================================

    /**
     * 实测正确率 → 难度等级（1 易 - 5 难）。
     * 分界点与命题惯例一致：0.85 / 0.70 / 0.50 / 0.30。
     */
    static int difficultyOf(double accuracy) {
        if (accuracy >= 0.85) {
            return 1;
        }
        if (accuracy >= 0.70) {
            return 2;
        }
        if (accuracy >= 0.50) {
            return 3;
        }
        if (accuracy >= 0.30) {
            return 4;
        }
        return 5;
    }

    private static BigDecimal discrimination(Map<String, Object> row, int attempts) {
        if (attempts < MIN_DISCRIMINATION_ATTEMPTS) {
            return null;
        }
        long perfLevels = num(row, "perfLevels");
        if (perfLevels < 2) {
            return null;
        }
        Double high = decimal(row.get("highAccuracy"));
        Double low = decimal(row.get("lowAccuracy"));
        if (high == null || low == null) {
            return null;
        }
        return BigDecimal.valueOf(high - low).setScale(4, RoundingMode.HALF_UP);
    }

    private static Double decimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static BigDecimal round(Object v, int scale) {
        Double d = decimal(v);
        return d == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(d).setScale(scale, RoundingMode.HALF_UP);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * 校准结果
     *
     * @param jobId      data_pipeline_job 主键
     * @param calibrated 本次校准的题目数
     * @param inserted   新增行数
     * @param updated    更新行数
     * @param removed    清理的过期行数
     * @param success    是否成功
     */
    public record CalibrationResult(Long jobId, int calibrated, int inserted, int updated,
                                    int removed, boolean success) {
    }
}
