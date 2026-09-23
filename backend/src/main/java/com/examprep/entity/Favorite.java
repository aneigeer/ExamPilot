package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("favorite")
@Schema(description = "收藏（题目 / 资料通用）")
public class Favorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Schema(description = "对象类型：QUESTION / RESOURCE")
    private String targetType;

    private Long targetId;

    private LocalDateTime createdAt;
}
