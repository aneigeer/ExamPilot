package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wrong_book")
@Schema(description = "错题本")
public class WrongBook {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long questionId;

    @Schema(description = "来源：PRACTICE 答错 / SELF_EVAL 自评不会")
    private String source;

    @Schema(description = "错误次数")
    private Integer wrongCount;

    private LocalDateTime lastWrongAt;

    @Schema(description = "是否已掌握：0 否，1 是（重做答对后置 1）")
    private Integer mastered;

    private LocalDateTime createdAt;
}
