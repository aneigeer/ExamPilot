package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resource_rating")
@Schema(description = "资料评分")
public class ResourceRating {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resourceId;
    private Long userId;

    @Schema(description = "评分 1-5")
    private Integer score;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
