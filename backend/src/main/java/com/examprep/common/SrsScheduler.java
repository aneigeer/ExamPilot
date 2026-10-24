package com.examprep.common;

/**
 * 错题「间隔重复」（SRS）调度器 —— 纯逻辑，便于单元测试
 *
 * <p>策略（类艾宾浩斯，按连续答对次数递进）：
 * <pre>
 *   阶段 stage = 连续答对次数
 *   间隔天数 = INTERVALS[stage]（1/2/4/7/15/30/60）
 *   答对：stage + 1，下次复习 = 现在 + 新阶段对应天数
 *   答错：stage 归 0，下次复习 = 现在 + 1 天
 *   stage 走完所有间隔 → 视为已掌握（毕业）
 * </pre>
 */
public final class SrsScheduler {

    /** 各阶段对应的复习间隔（天）。阶段越靠后间隔越长，符合遗忘曲线。 */
    private static final int[] INTERVALS_DAYS = {1, 2, 4, 7, 15, 30, 60};

    private SrsScheduler() {
    }

    /** 阶段对应的间隔天数；阶段越界时取最后一个（最长期） */
    public static int intervalDays(int stage) {
        int index = Math.max(0, Math.min(stage, INTERVALS_DAYS.length - 1));
        return INTERVALS_DAYS[index];
    }

    /** 答对后的新阶段 */
    public static int nextStageOnCorrect(int stage) {
        return Math.max(0, stage) + 1;
    }

    /** 答错后的新阶段（重置） */
    public static int nextStageOnWrong() {
        return 0;
    }

    /** 是否已「毕业」（走完全部间隔，视为已掌握） */
    public static boolean graduated(int stage) {
        return stage >= INTERVALS_DAYS.length;
    }

    /** 间隔表长度（供前端展示"共 N 轮复习"） */
    public static int totalStages() {
        return INTERVALS_DAYS.length;
    }
}
