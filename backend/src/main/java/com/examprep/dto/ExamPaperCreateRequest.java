package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建固定试卷（套卷）请求
 *
 * <p>固定套卷与随机组卷的区别：题目由管理员按 {@code questions} 显式指定，
 * 每题分值可单独设置，并带 {@code paperType} / {@code examCode} / {@code year} 等元信息，
 * 便于学生端按类别与年份检索。
 */
@Data
@Schema(description = "创建固定试卷请求")
public class ExamPaperCreateRequest {

    @NotBlank(message = "试卷名称不能为空")
    @Size(max = 120, message = "试卷名称最长 120 字")
    @Schema(description = "试卷名称", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2023 年 6 月四级真题（第一套）")
    private String name;

    @Schema(description = "科目 ID（可选，不传写 0 表示不限科目）", example = "5")
    private Long subjectId;

    @Schema(description = "试卷类型：OFFICIAL 官方套卷 / CUSTOM 自建套卷；不传默认 CUSTOM",
            example = "OFFICIAL")
    private String paperType;

    @Schema(description = "考试类别编码，如 CET4 / GUOKAO / POSTGRAD_MATH1", example = "CET4")
    private String examCode;

    @Min(value = 1980, message = "年份最小 1980")
    @Max(value = 2100, message = "年份最大 2100")
    @Schema(description = "真题年份（非真题不传）", example = "2023")
    private Integer year;

    @Min(value = 5, message = "时长至少 5 分钟")
    @Max(value = 180, message = "时长最多 180 分钟")
    @Schema(description = "考试时长（分钟），默认 30", example = "120")
    private Integer durationMinutes;

    @Size(max = 500, message = "试卷说明最长 500 字")
    @Schema(description = "试卷说明")
    private String description;

    @NotEmpty(message = "至少选择一道题")
    @Valid
    @Schema(description = "题目列表（按数组顺序作为卷面题序）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Item> questions;

    @Data
    @Schema(description = "试卷题目")
    public static class Item {

        @Schema(description = "题目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long questionId;

        @Min(value = 1, message = "每题分值至少 1 分")
        @Max(value = 100, message = "每题分值最多 100 分")
        @Schema(description = "本题分值；不传则取题目自身分值（题目分值也为空时按 1 分）")
        private Integer score;
    }
}
