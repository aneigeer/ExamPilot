package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试历史列表项
 *
 * <p>只带列表需要的字段：{@code answeredCount} 是<b>实评题数</b>（含追问轮），
 * 与 {@code totalQuestions}（计划题数）分开，用户一眼能看出「计划 5 题、实评 3 题」。
 */
@Data
@Schema(description = "面试历史列表项")
public class InterviewHistoryVO {

    private Long id;

    @Schema(description = "面试主题（空=随机）")
    private String topic;

    @Schema(description = "计划题数")
    private Integer totalQuestions;

    @Schema(description = "实评题数（含追问轮，与计划题数可能不同）")
    private Integer answeredCount;

    @Schema(description = "总分（未结束为 null）")
    private Double totalScore;

    @Schema(description = "等级（未结束为 null）")
    private String level;

    @Schema(description = "0=进行中 1=已结束")
    private Integer finished;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
