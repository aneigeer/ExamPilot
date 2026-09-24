package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "代码运行请求")
public class CodeRunRequest {

    @NotNull(message = "题目不能为空")
    @Schema(description = "编程题 ID（用于取测试输入与期望输出）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long questionId;

    @NotBlank(message = "语言不能为空")
    @Schema(description = "语言：C / JAVA", allowableValues = {"C", "JAVA"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String language;

    @NotBlank(message = "代码不能为空")
    @Schema(description = "源代码。Java 要求公共类（若有）命名为 Main；可从 stdin 读取测试输入",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
