package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 答题明细
 *
 * 开始练习时会为每道题预插一条记录（user_answer 为 null），
 * 用户提交后回填 user_answer / is_correct / score_got。
 * 「取下一题」= 查该练习下 user_answer IS NULL 的第一条。
 */
@Data
@TableName("answer_detail")
@Schema(description = "答题明细")
public class AnswerDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "练习记录 ID")
    private Long practiceRecordId;

    @Schema(description = "题目 ID")
    private Long questionId;

    @Schema(description = "用户答案（JSON 或纯文本；null 表示未作答）")
    private String userAnswer;

    @Schema(description = "是否正确（主观题为 null，由自评折算）")
    private Integer isCorrect;

    @Schema(description = "自评结果：KNOW / UNSURE / DONT_KNOW")
    private String selfEval;

    @Schema(description = "得分（自评折算）")
    private BigDecimal scoreGot;

    private LocalDateTime answeredAt;
}
