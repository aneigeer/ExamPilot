package com.examprep.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 面试 —— 作答请求
 *
 * <p>字段命名与 Python 侧契约一致（{@code session_id}），理由见
 * {@link InterviewStartRequest}。
 */
@Data
@Schema(description = "AI 面试作答请求")
public class InterviewAnswerRequest {

    /** 会话 ID（由 /interview/start 返回） */
    @NotBlank(message = "会话 ID 不能为空")
    @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 用户作答原文
     *
     * <p>上限 2000 字：面试回答通常三五百字，超过这个量级基本是粘贴了整篇资料，
     * 评分意义不大且会拖垮单次请求耗时。
     */
    @NotBlank(message = "作答内容不能为空")
    @Size(max = 2000, message = "作答内容不能超过 2000 字")
    @Schema(description = "作答内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("answer")
    private String answer;
}
