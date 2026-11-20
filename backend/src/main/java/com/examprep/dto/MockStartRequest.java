package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "开始模拟考试请求")
public class MockStartRequest {

    /**
     * 科目 ID（可选）。
     *
     * <p>2026-11-11 起改为可选：{@code subjectId} 与 {@code examCode} 都不传时表示
     * 「全库随机组卷」——从全部 APPROVED 客观题中等概率抽取，不默认限定任何科目。
     * exam_paper.subject_id 为 NOT NULL，全库随机时后端写 0（0 = 不限科目）。
     */
    @Schema(description = "科目 ID（可选）；与 examCode 都不传 = 全库随机组卷", example = "5")
    private Long subjectId;

    @Schema(description = "限定题型（不传则混合全部客观题）", example = "SINGLE_CHOICE")
    private String questionType;

    @Min(value = 1980, message = "年份最小 1980")
    @Max(value = 2100, message = "年份最大 2100")
    @Schema(description = "限定真题年份，如 2023；不传则不限年份（全部随机）", example = "2023")
    private Integer year;

    @Schema(description = "限定考试类别编码，如 CET4 / GUOKAO / POSTGRAD_MATH1 / INTERVIEW；"
            + "不传则不限类别。与 year 同时传入按 AND 生效",
            example = "CET4")
    private String examCode;

    @Min(value = 1, message = "至少 1 题")
    @Max(value = 100, message = "最多 100 题")
    @Schema(description = "题数，默认 20", example = "20")
    private Integer count = 20;

    @Min(value = 5, message = "时长至少 5 分钟")
    @Max(value = 180, message = "时长最多 180 分钟")
    @Schema(description = "考试时长（分钟），默认 30", example = "30")
    private Integer durationMinutes = 30;
}
