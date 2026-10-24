package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错题复习提交结果（SRS 调度后的状态）
 */
@Data
@Schema(description = "错题复习提交结果")
public class ReviewResultVO {

    @Schema(description = "题目 ID")
    private Long questionId;

    @Schema(description = "复习阶段：连续答对次数")
    private Integer reviewStage;

    @Schema(description = "下次复习时间；已掌握时为 null")
    private LocalDateTime nextReviewAt;

    @Schema(description = "是否已掌握（走完全部复习阶段）")
    private Boolean mastered;

    @Schema(description = "给用户的提示语")
    private String message;
}
