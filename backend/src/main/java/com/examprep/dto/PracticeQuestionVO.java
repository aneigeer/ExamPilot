package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 练习中的题目（带进度）
 */
@Data
@Schema(description = "练习题目（带进度）")
public class PracticeQuestionVO {

    @Schema(description = "练习 ID")
    private Long practiceId;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "是否已全部答完")
    private Boolean finished;

    @Schema(description = "题目（不含答案，防作弊）")
    private QuestionVO question;
}
