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
@TableName("report")
@Schema(description = "举报")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "对象类型：QUESTION / RESOURCE")
    private String targetType;

    private Long targetId;

    @Schema(description = "举报人")
    private Long reporterId;

    @Schema(description = "举报原因")
    private String reason;

    private String description;

    @Schema(description = "处理状态：PENDING / VALID / INVALID")
    private String status;

    @Schema(description = "处理人")
    private Long handlerId;

    private String handleNote;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
