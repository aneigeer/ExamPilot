package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "主观题自评请求")
public class SelfEvalRequest {

    @NotNull(message = "题目不能为空")
    @Schema(description = "题目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long questionId;

    @NotBlank(message = "自评结果不能为空")
    @Schema(description = "自评结果：KNOW 会 / UNSURE 模糊 / DONT_KNOW 不会",
            allowableValues = {"KNOW", "UNSURE", "DONT_KNOW"},
            requiredMode = Schema.RequiredMode.REQUIRED, example = "UNSURE")
    private String result;
}
