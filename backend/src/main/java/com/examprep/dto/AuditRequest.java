package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "审核操作请求")
public class AuditRequest {

    @Schema(description = "驳回或下架原因（通过时可不填）",
            example = "资料内容与标题不符，且未标明来源，请补充后重新提交")
    private String reason;
}
