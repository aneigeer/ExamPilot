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
@TableName("appeal")
@Schema(description = "申诉")
public class Appeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Schema(description = "对象类型：RESOURCE")
    private String targetType;

    private Long targetId;

    @Schema(description = "申诉理由")
    private String reason;

    @Schema(description = "状态：PENDING / APPROVED（恢复公开）/ REJECTED（维持下架）")
    private String status;

    private Long handlerId;
    private String handleNote;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;

    @TableLogic
    @JsonIgnore
    private Integer isDeleted;
}
