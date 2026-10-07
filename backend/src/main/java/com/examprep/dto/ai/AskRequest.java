package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 问答请求
 */
@Data
@Schema(description = "AI 问答请求")
public class AskRequest {

    @NotBlank(message = "请输入你的问题")
    @Size(max = 200, message = "问题长度不能超过 200 字")
    @Schema(description = "用户问题", example = "值传递和地址传递有什么区别", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    @Schema(description = "限定科目，不传则全科目检索")
    private Long subjectId;

    @Schema(description = "限定考试类别，不传则不隔离")
    private Long examCategoryId;
}
