package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
@Schema(description = "评论")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "对象类型：RESOURCE / QUESTION")
    private String targetType;

    private Long targetId;
    private Long userId;
    private String content;

    @Schema(description = "父评论 ID，0 为顶层")
    private Long parentId;

    @Schema(description = "点赞数")
    private Integer likeCount;

    private LocalDateTime createdAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
