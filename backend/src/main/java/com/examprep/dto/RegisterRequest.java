package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自助注册请求
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest {

    @NotBlank(message = "学号不能为空")
    @Size(max = 32, message = "学号长度不能超过 32")
    @Schema(description = "学号（全局唯一）", example = "2021999", requiredMode = Schema.RequiredMode.REQUIRED)
    private String studentNo;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称长度不能超过 50")
    @Schema(description = "昵称", example = "小明", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nickname;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
    @Schema(description = "密码（≥8 位且同时含字母和数字）", example = "Stu2025@x", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @NotNull(message = "学校不能为空")
    @Schema(description = "学校 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long schoolId;

    @Schema(description = "学院 ID（可选，须属于所选学校）", example = "12")
    private Long collegeId;

    @Size(max = 20, message = "年级长度不能超过 20")
    @Schema(description = "年级（可选）", example = "2025")
    private String grade;
}
