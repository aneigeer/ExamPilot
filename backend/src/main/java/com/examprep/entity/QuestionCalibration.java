package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试题难度与区分度校准结果
 *
 * <p>一题一行；{@code questionId} 直接作主键，重复校准按主键覆盖。
 */
@Data
@TableName("question_calibration")
@Schema(description = "试题校准")
public class QuestionCalibration {

    @TableId(type = IdType.INPUT)
    private Long questionId;

    @Schema(description = "累计有效作答次数（练习 + 模考）")
    private Integer attemptCount;

    private Integer correctCount;

    @Schema(description = "实测正确率 0-1")
    private BigDecimal observedAccuracy;

    @Schema(description = "校准难度 1(易)-5(难)")
    private Integer calibratedDifficulty;

    @Schema(description = "区分度：高分组 27% 正确率 - 低分组 27% 正确率；样本不足为 NULL")
    private BigDecimal discriminationIndex;

    private LocalDateTime updatedAt;
}
