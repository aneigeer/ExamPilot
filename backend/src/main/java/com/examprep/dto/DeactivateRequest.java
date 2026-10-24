package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 账号注销请求：必须二次校验密码，防止他人借已登录设备误操作
 */
@Data
@Schema(description = "账号注销请求")
public class DeactivateRequest {

    @NotBlank(message = "密码不能为空")
    @Schema(description = "当前账号密码（用于注销前的二次确认）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "确认语，须原样传入「注销账号」以防误触")
    private String confirmText;
}
