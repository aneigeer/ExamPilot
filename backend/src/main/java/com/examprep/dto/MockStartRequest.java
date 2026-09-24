package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "开始模拟考试请求")
public class MockStartRequest {

    @NotNull(message = "科目不能为空")
    @Schema(description = "科目 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Long subjectId;

    @Schema(description = "限定题型（不传则混合全部客观题）", example = "SINGLE_CHOICE")
    private String questionType;

    @Min(value = 1, message = "至少 1 题")
    @Max(value = 100, message = "最多 100 题")
    @Schema(description = "题数，默认 20", example = "20")
    private Integer count = 20;

    @Min(value = 5, message = "时长至少 5 分钟")
    @Max(value = 180, message = "时长最多 180 分钟")
    @Schema(description = "考试时长（分钟），默认 30", example = "30")
    private Integer durationMinutes = 30;
}
