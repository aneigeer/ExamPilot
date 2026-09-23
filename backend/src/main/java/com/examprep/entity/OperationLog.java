package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_log")
@Schema(description = "操作日志")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Schema(description = "操作人（冗余，防止用户删除后无法追溯）")
    private String username;

    @Schema(description = "模块：AUTH / AUDIT / USER / EXAM / RESOURCE")
    private String module;

    @Schema(description = "动作：LOGIN / CREATE / UPDATE / DELETE / AUDIT")
    private String action;

    private String targetType;
    private Long targetId;
    private String detail;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
