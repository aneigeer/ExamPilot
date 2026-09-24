package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟考试成绩报告
 */
@Data
@Schema(description = "模拟考试成绩报告")
public class MockReportVO {

    private Long recordId;

    @Schema(description = "状态：ONGOING / FINISHED / EXPIRED")
    private String status;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "得分")
    private BigDecimal gotScore;

    @Schema(description = "正确数")
    private Integer correctCount;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "得分率（0-100，一位小数）")
    private BigDecimal scoreRate;

    private LocalDateTime deadline;
    private LocalDateTime submittedAt;

    @Schema(description = "是否超时自动交卷")
    private Boolean expired;

    @Schema(description = "逐题明细")
    private List<PracticeReportVO.Item> details;
}
