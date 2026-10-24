package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 主观题判分请求
 */
@Data
@Schema(description = "主观题 AI 判分请求")
public class GradeRequest {

    @Schema(description = "学生作答原文", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 5000, message = "作答过长（上限 5000 字）")
    private String answer;
}
