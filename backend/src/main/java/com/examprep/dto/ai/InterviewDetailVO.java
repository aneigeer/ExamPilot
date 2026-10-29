package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情（只读复盘用）
 *
 * <p>{@code qaList} 按题号与作答时间升序：同一题号的追问轮排在主作答之后，
 * 客户端可以直接按顺序渲染成「先答 → 追问 → 再答」的对话。
 */
@Data
@Schema(description = "面试详情")
public class InterviewDetailVO {

    private Long id;

    private String topic;

    private Integer totalQuestions;

    private Integer answeredCount;

    private Double totalScore;

    private String level;

    private Integer finished;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Schema(description = "逐题记录（题号升序，追问紧随主作答）")
    private List<Qa> qaList;

    /** 单条作答 */
    @Data
    @Schema(description = "面试单题作答")
    public static class Qa {

        private Long id;

        private Integer questionNo;

        private String question;

        private String answer;

        private Double score;

        private String verdict;

        @Schema(description = "命中要点")
        private List<String> hitPoints;

        @Schema(description = "遗漏要点")
        private List<String> missPoints;

        private String reference;

        @Schema(description = "1=同题追问轮")
        private Integer isFollowUp;

        private LocalDateTime createdAt;
    }
}
