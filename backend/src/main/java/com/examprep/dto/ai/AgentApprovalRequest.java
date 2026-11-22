package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 高风险写操作人工审批请求（HITL）
 */
@Data
@Schema(description = "Agent 高风险写操作人工审批请求")
public class AgentApprovalRequest {

    @Schema(description = "approval_required 事件返回的一次性审批令牌（幂等键）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "缺少审批令牌 approvalToken")
    private String approvalToken;

    @Schema(description = "true=批准并继续执行，false=拒绝并终止", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "请指定是否批准 approved")
    private Boolean approved;
}
