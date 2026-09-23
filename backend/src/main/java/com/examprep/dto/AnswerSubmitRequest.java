package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "提交答案请求")
public class AnswerSubmitRequest {

    @NotNull(message = "题目不能为空")
    @Schema(description = "题目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long questionId;

    @NotBlank(message = "答案不能为空")
    @Schema(description = """
            用户答案。格式按题型：
            - 单选 / 判断：单个选项标识，如 `C`
            - 多选：逗号分隔，如 `A,C`
            - 填空：多个空用竖线分隔，如 `printf` 或 `答案1|答案2`
            - 简答：自由文本
            """, requiredMode = Schema.RequiredMode.REQUIRED)
    private String userAnswer;
}
