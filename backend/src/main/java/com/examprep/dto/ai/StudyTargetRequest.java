package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 设定学习目标请求
 */
@Data
@Schema(description = "设定学习目标")
public class StudyTargetRequest {

    @Schema(description = "每日目标题量；超出 [5,100] 会被钳制而不是报错", example = "20")
    @Min(value = 1, message = "目标题量至少 1")
    @Max(value = 1000, message = "目标题量过大")
    private Integer dailyTarget;

    @Schema(description = "目标科目 ID；不传/为 null 表示不限科目")
    private Long subjectId;
}
