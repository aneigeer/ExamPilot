package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow")
@Schema(description = "关注（课程 / 科目）")
public class Follow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Schema(description = "目标类型：COURSE / SUBJECT")
    private String targetType;

    private Long targetId;

    private LocalDateTime createdAt;
}
