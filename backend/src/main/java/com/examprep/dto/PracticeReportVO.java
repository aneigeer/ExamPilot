package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 练习报告
 */
@Data
@Schema(description = "练习报告")
public class PracticeReportVO {

    @Schema(description = "练习 ID")
    private Long practiceId;

    @Schema(description = "科目名称")
    private String subjectName;

    @Schema(description = "练习模式")
    private String mode;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "正确数")
    private Integer correctCount;

    @Schema(description = "正确率（0-100，保留一位小数）")
    private BigDecimal accuracy;

    @Schema(description = "状态：ONGOING / FINISHED")
    private String status;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Schema(description = "答题明细（仅已作答的题）")
    private List<Item> details;

    @Data
    @Schema(description = "答题明细项")
    public static class Item {

        private Long questionId;
        private String type;
        private String typeName;
        private String stem;
        private String userAnswer;
        private Boolean correct;
        private String selfEval;
        private BigDecimal scoreGot;
        private String correctAnswer;
        private String analysis;
    }
}
