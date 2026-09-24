package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "模拟考试答题请求")
public class MockAnswerRequest {

    @NotNull(message = "题目不能为空")
    @Schema(description = "题目 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long questionId;

    @NotBlank(message = "答案不能为空")
    @Schema(description = "用户答案（格式同刷题：单选 C / 多选 A,C / 填空 答案1|答案2）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String userAnswer;
}
