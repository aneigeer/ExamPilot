package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「我的模考历史」列表项
 *
 * <p>只暴露当前登录用户自己的记录（服务层按 userId 过滤），
 * 供学生端在刷新 / 清除本地存储后仍能回看历次模拟考试。
 */
@Data
@Schema(description = "模拟考试历史记录")
public class ExamHistoryVO {

    @Schema(description = "考试记录 ID（回看/续考用）")
    private Long recordId;

    private Long paperId;

    @Schema(description = "试卷名称")
    private String paperName;

    @Schema(description = "试卷类型：RANDOM / OFFICIAL / CUSTOM")
    private String paperType;

    @Schema(description = "考试类别编码")
    private String examCode;

    @Schema(description = "真题年份")
    private Integer year;

    @Schema(description = "状态：ONGOING / FINISHED / EXPIRED")
    private String status;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "得分")
    private BigDecimal gotScore;

    private Integer correctCount;
    private Integer answeredCount;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "得分率（0-100，一位小数）")
    private BigDecimal scoreRate;

    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime deadline;

    @Schema(description = "是否超时自动交卷")
    private Boolean expired;
}
