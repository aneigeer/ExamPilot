package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 基于错题本生成 AI 学习计划的请求参数
 */
@Data
@Schema(description = "AI 错题学习计划请求")
public class AiStudyPlanRequest {

    @Schema(description = "限定科目 ID（不传则综合全部科目错题）", example = "190")
    private Long subjectId;

    @Min(value = 1, message = "计划天数最少 1 天")
    @Max(value = 14, message = "计划天数最多 14 天")
    @Schema(description = "规划天数（1~14，默认 7）", example = "7")
    private Integer days = 7;

    @Min(value = 5, message = "每日目标至少 5 题")
    @Max(value = 100, message = "每日目标最多 100 题")
    @Schema(description = "每日目标题量（5~100，不传沿用用户设置或默认 20）", example = "20")
    private Integer dailyTarget;

    @Size(max = 120, message = "备考目标备注不超过 120 字")
    @Schema(description = "补充备考目标或偏好（可选）", example = "两周后考四级，重点突破阅读长难句")
    private String goalNote;
}
