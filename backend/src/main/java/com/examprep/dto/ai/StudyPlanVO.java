package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 今日学习计划（规则引擎输出，**不落库**）
 *
 * <p>★ 为什么不落库：计划落库就要维护「计划 ↔ 真实作答」的一致性，
 * 而完成度完全可以从 practice_record 派生。少一张表就少一类不一致。
 */
@Data
@Schema(description = "今日学习计划")
public class StudyPlanVO {

    @Schema(description = "计划日期", example = "2026-10-20")
    private String date;

    @Schema(description = "每日目标题量（用户设定）", example = "20")
    private Integer dailyTarget;

    @Schema(description = "目标科目 ID，null 表示不限")
    private Long subjectId;

    @Schema(description = "计划题量合计")
    private Integer plannedCount;

    @Schema(description = "今日已完成题量（从真实作答派生）")
    private Integer doneCount;

    @Schema(description = "完成率 0~100")
    private Integer completionPercent;

    @Schema(description = "今日任务列表（按优先级排序）")
    private List<Item> items;

    @Schema(description = "计划说明：为什么这样排、哪些没排进来")
    private List<String> notes;

    /**
     * 一条今日任务
     *
     * @param type        REVIEW_DUE（到期错题复习）/ WEAK_POINT（薄弱攻坚）/ FOUNDATION（前置基础）
     * @param knowledgeId 知识点 ID（复习任务为 null）
     * @param knowledgeName 知识点名
     * @param targetCount 任务题量
     * @param doneCount   今日已完成题量
     * @param completed   是否已完成
     * @param reason      为什么给你排这个（可解释性，必须给）
     * @param estimatedMinutes 预估用时（按每题 1.5 分钟估）
     * @param practiceMode 建议的刷题模式（服务端给出，避免客户端各写一套）
     */
    @Data
    @Schema(description = "一条今日任务")
    public static class Item {
        private String type;
        private Long knowledgeId;
        private String knowledgeName;
        private Integer targetCount;
        private Integer doneCount;
        private Boolean completed;
        private String reason;
        private Integer estimatedMinutes;
        private String practiceMode;
    }
}
