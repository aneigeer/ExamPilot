package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("self_evaluation")
@Schema(description = "主观题自评")
public class SelfEvaluation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long questionId;
    private Long practiceRecordId;

    @Schema(description = "自评结果：KNOW 会 / UNSURE 模糊 / DONT_KNOW 不会")
    private String result;

    private LocalDateTime createdAt;
}
