package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "举报处理请求")
public class ReportHandleRequest {

    @NotBlank(message = "处理结论不能为空")
    @Schema(description = "结论：VALID 举报成立 / INVALID 举报不成立",
            allowableValues = {"VALID", "INVALID"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(description = "处理说明")
    private String note;

    @Schema(description = "举报成立时是否同时下架被举报资料", example = "true")
    private Boolean offlineTarget;
}
