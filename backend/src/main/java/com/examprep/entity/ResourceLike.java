package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resource_like")
@Schema(description = "资料点赞")
public class ResourceLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resourceId;
    private Long userId;
    private LocalDateTime createdAt;
}
