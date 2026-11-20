package com.examprep.service.assessment;

import com.examprep.entity.DataPipelineJob;
import com.examprep.entity.QuestionCalibration;
import com.examprep.mapper.AssessmentMapper;
import com.examprep.mapper.DataPipelineJobMapper;
import com.examprep.mapper.QuestionCalibrationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 试题校准规则测试（WS-3 / T3.1）
 *
 * <p>核心命题：难度分档边界、区分度样本门槛、重复校准幂等（不新增行、清理过期行）。
 * SQL 侧「哪些作答算有效样本」由 {@code AssessmentMapper.calibrationAggregates} 负责，
 * 已在数据中台的真库测试中间接验证；这里专注服务层规则。
 */
@DisplayName("试题校准：难度分档 / 区分度门槛 / 幂等")
class QuestionCalibrationServiceTest {

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<QuestionCalibration> captor =
            ArgumentCaptor.forClass(QuestionCalibration.class);

    @Test
    @DisplayName("① 难度分档与区分度：0.9→1 级、0.5→3 级、0.2→5 级，负区分度保留负值")
    void 难度分档与区分度() {
        AssessmentMapper assessmentMapper = mock(AssessmentMapper.class);
        QuestionCalibrationMapper calibrationMapper = mock(QuestionCalibrationMapper.class);
        DataPipelineJobMapper jobMapper = mock(DataPipelineJobMapper.class);

        when(assessmentMapper.calibrationAggregates(anyInt())).thenReturn(List.of(
                row(1L, 40, 36, 0.95, 0.85, 5),
                row(2L, 12, 6, null, null, 1),
                row(3L, 35, 7, 0.10, 0.40, 4)
        ));
        when(calibrationMapper.selectById(any())).thenReturn(null);
        when(calibrationMapper.selectList(any())).thenReturn(List.of());
        stubJobInsert(jobMapper);

        QuestionCalibrationService service =
                new QuestionCalibrationService(assessmentMapper, calibrationMapper, jobMapper);
        QuestionCalibrationService.CalibrationResult result = service.recalibrate("test");

        verify(calibrationMapper, times(3)).insert(captor.capture());
        List<QuestionCalibration> inserted = captor.getAllValues();
        assertEquals(3, result.calibrated());

        QuestionCalibration q1 = inserted.get(0);
        assertEquals(1, q1.getCalibratedDifficulty());
        assertEquals(0, new BigDecimal("0.9000").compareTo(q1.getObservedAccuracy()));
        assertEquals(0, new BigDecimal("0.1000").compareTo(q1.getDiscriminationIndex()));

        QuestionCalibration q2 = inserted.get(1);
        assertEquals(3, q2.getCalibratedDifficulty());
        assertNull(q2.getDiscriminationIndex(), "样本不足 30 次时区分度必须为 NULL，而不是 0");

        QuestionCalibration q3 = inserted.get(2);
        assertEquals(5, q3.getCalibratedDifficulty());
        assertEquals(0, new BigDecimal("-0.3000").compareTo(q3.getDiscriminationIndex()),
                "负区分度要保留负值，供异常题清单识别");
    }

    @Test
    @DisplayName("② 分档边界：0.85/0.70/0.50/0.30 分别落在 1/2/3/4 级")
    void 分档边界() {
        assertEquals(1, QuestionCalibrationService.difficultyOf(0.85));
        assertEquals(2, QuestionCalibrationService.difficultyOf(0.70));
        assertEquals(3, QuestionCalibrationService.difficultyOf(0.50));
        assertEquals(4, QuestionCalibrationService.difficultyOf(0.30));
        assertEquals(5, QuestionCalibrationService.difficultyOf(0.2999));
        assertEquals(1, QuestionCalibrationService.difficultyOf(1.0));
    }

    @Test
    @DisplayName("③ 重复校准：已有行走 update，不新增；不再满足样本门槛的历史行被清理")
    void 重复校准幂等并清理过期行() {
        AssessmentMapper assessmentMapper = mock(AssessmentMapper.class);
        QuestionCalibrationMapper calibrationMapper = mock(QuestionCalibrationMapper.class);
        DataPipelineJobMapper jobMapper = mock(DataPipelineJobMapper.class);

        when(assessmentMapper.calibrationAggregates(anyInt())).thenReturn(List.of(
                row(1L, 20, 10, 0.8, 0.5, 3)
        ));
        QuestionCalibration existing = new QuestionCalibration();
        existing.setQuestionId(1L);
        existing.setAttemptCount(18);
        when(calibrationMapper.selectById(1L)).thenReturn(existing);

        QuestionCalibration stale = new QuestionCalibration();
        stale.setQuestionId(999L);
        // 第一次 selectList（upsert 前无）不会被调用；清理阶段返回 1 条过期行
        when(calibrationMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(existing, stale)));
        stubJobInsert(jobMapper);

        QuestionCalibrationService service =
                new QuestionCalibrationService(assessmentMapper, calibrationMapper, jobMapper);
        QuestionCalibrationService.CalibrationResult result = service.recalibrate("test");

        verify(calibrationMapper, never()).insert(any(QuestionCalibration.class));
        verify(calibrationMapper).updateById(existing);
        verify(calibrationMapper).deleteById(999L);
        assertEquals(1, result.updated());
        assertEquals(1, result.removed());
        assertEquals(20, existing.getAttemptCount(), "已有行应被本次聚合结果覆盖");
    }

    private static void stubJobInsert(DataPipelineJobMapper jobMapper) {
        doAnswer(inv -> {
            DataPipelineJob job = inv.getArgument(0);
            job.setId(88L);
            return 1;
        }).when(jobMapper).insert(any(DataPipelineJob.class));
    }

    private static Map<String, Object> row(Long questionId, int attempts, int correct,
                                           Double high, Double low, int perfLevels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", questionId);
        m.put("attemptCount", attempts);
        m.put("correctCount", correct);
        m.put("highAccuracy", high);
        m.put("lowAccuracy", low);
        m.put("perfLevels", perfLevels);
        return m;
    }
}
