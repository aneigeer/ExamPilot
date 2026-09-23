package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_record")
@Schema(description = "审核记录")
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "对象类型：QUESTION / RESOURCE")
    private String targetType;

    private Long targetId;

    @Schema(description = "审核员 ID")
    private Long auditorId;

    @Schema(description = "结果：APPROVED / REJECTED / OFFLINE")
    private String result;

    @Schema(description = "驳回或下架原因")
    private String reason;

    private LocalDateTime createdAt;
}
