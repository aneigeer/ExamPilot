package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于用户错题生成的 AI 学习计划
 */
@Data
@Schema(description = "AI 错题学习计划")
public class AiStudyPlanVO {

    @Schema(description = "生成时间")
    private String generatedAt;

    @Schema(description = "生成器标识：ollama / dashscope / zhipu / rule-fallback")
    private String generator;

    @Schema(description = "是否降级为规则引擎生成")
    private boolean degraded;

    @Schema(description = "降级原因（未降级时为空串）")
    private String degradedReason;

    @Schema(description = "规划天数")
    private Integer days;

    @Schema(description = "每日目标题量")
    private Integer dailyTarget;

    @Schema(description = "限定科目 ID（null 表示全部科目）")
    private Long subjectId;

    @Schema(description = "用户未掌握错题总数（当前筛选范围内）")
    private Integer wrongTotal;

    @Schema(description = "今日到期应复习错题数")
    private Integer dueReviewCount;

    @Schema(description = "错题诊断总结")
    private String diagnosis;

    @Schema(description = "高频薄弱知识点")
    private List<WeakSummary> weakPoints = new ArrayList<>();

    @Schema(description = "分日学习计划")
    private List<DayPlan> dailySchedule = new ArrayList<>();

    @Schema(description = "备考提分建议")
    private List<String> tips = new ArrayList<>();

    @Data
    @Schema(description = "薄弱知识点汇总")
    public static class WeakSummary {
        private Long knowledgeId;
        private String knowledgeName;
        private Integer wrongQuestionCount;
        private Integer maxWrongCount;
        private String suggestion;
    }

    @Data
    @Schema(description = "单日计划")
    public static class DayPlan {
        private Integer day;
        private String title;
        private String focus;
        private List<PlanTask> tasks = new ArrayList<>();
    }

    @Data
    @Schema(description = "单日学习任务")
    public static class PlanTask {
        /** WRONG_REVIEW / WEAK_PRACTICE / MOCK_CHECK */
        private String type;
        private String title;
        private Integer targetCount;
        private Integer estimatedMinutes;
        private String reason;
        private List<Long> questionIds = new ArrayList<>();
    }
}
