package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 异步任务提交请求
 */
@Data
@Schema(description = "异步任务提交请求")
public class AsyncJobRequest {

    @Schema(description = "任务类型：EVAL_RETRIEVAL / EVAL_AGENT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "任务类型不能为空")
    private String type;

    @Schema(description = "任务参数（可选）")
    private Map<String, Object> payload;
}
