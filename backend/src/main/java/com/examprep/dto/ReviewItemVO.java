package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错题「待复习」项（SRS 复习队列）
 */
@Data
@Schema(description = "待复习的错题项")
public class ReviewItemVO {

    @Schema(description = "题目 ID")
    private Long questionId;

    @Schema(description = "题干")
    private String stem;

    @Schema(description = "题型")
    private String type;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "累计错误次数")
    private Integer wrongCount;

    @Schema(description = "复习阶段：连续答对次数")
    private Integer reviewStage;

    @Schema(description = "总阶段数（走完即视为掌握）")
    private Integer totalStages;

    @Schema(description = "最近一次答错时间")
    private LocalDateTime lastWrongAt;

    @Schema(description = "下次复习时间")
    private LocalDateTime nextReviewAt;
}
