package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_paper")
@Schema(description = "试卷")
public class ExamPaper {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long subjectId;

    @Schema(description = "考试时长（分钟）")
    private Integer durationMinutes;

    private Integer totalScore;
    private Integer questionCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
