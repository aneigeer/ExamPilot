package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "模拟考试试卷")
public class MockPaperVO {

    @Schema(description = "考试记录 ID")
    private Long recordId;

    @Schema(description = "试卷 ID")
    private Long paperId;

    @Schema(description = "试卷名称")
    private String paperName;

    @Schema(description = "时长（分钟）")
    private Integer durationMinutes;

    @Schema(description = "交卷截止时间")
    private LocalDateTime deadline;

    @Schema(description = "剩余秒数")
    private Long remainingSeconds;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "状态：ONGOING / FINISHED / EXPIRED")
    private String status;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "题目列表（不含答案）")
    private List<QuestionVO> questions;
}
