package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 智能诊断组卷请求
 */
@Data
@Schema(description = "智能诊断组卷请求")
public class DiagnosticPaperRequest {

    @Min(value = 10, message = "至少 10 题")
    @Max(value = 50, message = "最多 50 题")
    @Schema(description = "题数，默认 20", example = "20")
    private Integer count = 20;

    @Min(value = 10, message = "时长至少 10 分钟")
    @Max(value = 180, message = "时长最多 180 分钟")
    @Schema(description = "考试时长（分钟），默认 30", example = "30")
    private Integer durationMinutes = 30;

    @Schema(description = "限定科目（可选）")
    private Long subjectId;
}
