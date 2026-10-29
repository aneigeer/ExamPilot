package com.examprep.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * AI 面试 —— 开始请求
 *
 * <p><b>字段命名与 Python AI 服务契约严格一致（snake_case）</b>：
 * 三层（Android ↔ Java ↔ Python）共用同一套字段名，
 * 任何一层做驼峰转换都会让另一层拿到 {@code null}（{@code subject_id} 传丢后
 * 面试官会跨科目抽题，用户看到的是「问的完全不是我要的科目」）。
 *
 * @see com.examprep.controller.AiInterviewController
 */
@Data
@Schema(description = "AI 面试开始请求")
public class InterviewStartRequest {

    /**
     * 面试主题，可为空
     *
     * <p>为空时由 AI 服务随机挑一个主题（覆盖已入库的面试 Q&amp;A 语料）。
     */
    @Schema(description = "面试主题，为空则随机", example = "RAG")
    @JsonProperty("topic")
    private String topic;

    /** 题目数量，默认 3，最多 10（再多单场面试耗时过长，且评分会退化） */
    @Min(value = 1, message = "题数不能少于 1")
    @Max(value = 10, message = "题数不能超过 10")
    @Schema(description = "题目数量，1-10，默认 3", example = "3")
    @JsonProperty("total")
    private Integer total = 3;

    /** 限定科目，可为空（为空即全科目抽题） */
    @Schema(description = "限定科目 ID，可空")
    @JsonProperty("subject_id")
    private Long subjectId;
}
