package com.examprep;

import com.examprep.common.SrsScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错题 SRS 调度器单测（纯逻辑，不依赖 Spring / 数据库）
 *
 * <p>把「间隔递增 + 答错重置 + 走完毕业」这三条规则固化下来，
 * 任何人改动调度策略导致行为变化，CI 立刻会红。
 */
class SrsSchedulerTest {

    @Test
    @DisplayName("间隔随阶段递增：1/2/4/7/15/30/60 天")
    void intervalDays_followsExpectedSequence() {
        assertEquals(1, SrsScheduler.intervalDays(0));
        assertEquals(2, SrsScheduler.intervalDays(1));
        assertEquals(4, SrsScheduler.intervalDays(2));
        assertEquals(7, SrsScheduler.intervalDays(3));
        assertEquals(15, SrsScheduler.intervalDays(4));
        assertEquals(30, SrsScheduler.intervalDays(5));
        assertEquals(60, SrsScheduler.intervalDays(6));
    }

    @Test
    @DisplayName("阶段越界时取最长期，不会数组越界")
    void intervalDays_clampsOutOfRange() {
        assertEquals(60, SrsScheduler.intervalDays(7));
        assertEquals(60, SrsScheduler.intervalDays(999));
        assertEquals(1, SrsScheduler.intervalDays(-5));
    }

    @Test
    @DisplayName("间隔严格递增，符合遗忘曲线")
    void intervalDays_isStrictlyIncreasing() {
        for (int stage = 1; stage <= SrsScheduler.totalStages() - 1; stage++) {
            assertTrue(SrsScheduler.intervalDays(stage) > SrsScheduler.intervalDays(stage - 1),
                    "阶段 " + stage + " 的间隔应大于上一阶段");
        }
    }

    @Test
    @DisplayName("答对推进阶段；答错重置为 0")
    void stageTransitions() {
        assertEquals(1, SrsScheduler.nextStageOnCorrect(0));
        assertEquals(4, SrsScheduler.nextStageOnCorrect(3));
        assertEquals(0, SrsScheduler.nextStageOnWrong());
    }

    @Test
    @DisplayName("走完全部阶段才毕业：stage >= 7")
    void graduatedOnlyAfterAllStages() {
        for (int stage = 0; stage < SrsScheduler.totalStages(); stage++) {
            assertFalse(SrsScheduler.graduated(stage), "阶段 " + stage + " 不应判定为已掌握");
        }
        assertTrue(SrsScheduler.graduated(SrsScheduler.totalStages()));
        assertTrue(SrsScheduler.graduated(SrsScheduler.totalStages() + 3));
    }

    @Test
    @DisplayName("连续答对 totalStages 次后恰好毕业（边界）")
    void exactlyTotalStagesCorrectReviewsGraduates() {
        int stage = 0;
        int total = SrsScheduler.totalStages();
        for (int i = 1; i <= total; i++) {
            stage = SrsScheduler.nextStageOnCorrect(stage);
            if (i < total) {
                assertFalse(SrsScheduler.graduated(stage),
                        "第 " + i + " 次答对后（stage=" + stage + "）不应已毕业");
            }
        }
        // 恰好第 total 次答对即到达毕业阶段
        assertTrue(SrsScheduler.graduated(stage),
                "第 " + total + " 次答对后（stage=" + stage + "）应判定为已掌握");
    }
}
