package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("exam_record")
@Schema(description = "考试记录")
public class ExamRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long paperId;
    private Long userId;

    @Schema(description = "状态：ONGOING / FINISHED / EXPIRED")
    private String status;

    private LocalDateTime startedAt;

    @Schema(description = "交卷截止时间")
    private LocalDateTime deadline;

    private LocalDateTime submittedAt;
    private Integer totalScore;

    @Schema(description = "得分")
    private BigDecimal gotScore;

    private Integer correctCount;
    private Integer answeredCount;
}
