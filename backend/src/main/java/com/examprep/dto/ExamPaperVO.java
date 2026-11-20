package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试卷视图（管理端列表 / 详情，学生端套卷列表共用）
 */
@Data
@Schema(description = "试卷")
public class ExamPaperVO {

    private Long id;

    @Schema(description = "试卷名称")
    private String name;

    @Schema(description = "试卷类型：RANDOM / OFFICIAL / CUSTOM")
    private String paperType;

    @Schema(description = "考试类别编码")
    private String examCode;

    @Schema(description = "真题年份")
    private Integer year;

    private Long subjectId;

    @Schema(description = "科目名称")
    private String subjectName;

    @Schema(description = "考试时长（分钟）")
    private Integer durationMinutes;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "题目数")
    private Integer questionCount;

    @Schema(description = "是否发布：0 否，1 是")
    private Integer published;

    @Schema(description = "试卷说明")
    private String description;

    private Long createdBy;
    private LocalDateTime createdAt;

    @Schema(description = "题目列表（仅详情接口返回；列表接口为 null）")
    private List<Item> questions;

    @Data
    @Schema(description = "试卷题目")
    public static class Item {

        @Schema(description = "试卷题目关联 ID")
        private Long id;

        private Long questionId;

        @Schema(description = "卷面题序，从 1 开始")
        private Integer sort;

        @Schema(description = "本题分值")
        private Integer score;

        @Schema(description = "题型")
        private String type;

        @Schema(description = "题型中文名")
        private String typeName;

        @Schema(description = "题干（详情接口返回）")
        private String stem;

        @Schema(description = "审核状态")
        private String auditStatus;
    }
}
