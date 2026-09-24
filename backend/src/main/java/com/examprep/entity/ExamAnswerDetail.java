package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("exam_answer_detail")
public class ExamAnswerDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordId;
    private Long questionId;
    private String userAnswer;
    private Integer isCorrect;

    @Schema(description = "本题得分")
    private BigDecimal scoreGot;

    private LocalDateTime answeredAt;
}
