package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
@Schema(description = "站内通知")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "接收人")
    private Long userId;

    @Schema(description = "类型：AUDIT_RESULT 审核结果 / REPORT_RESULT 举报处理 / SYSTEM 系统")
    private String type;

    private String title;
    private String content;

    @Schema(description = "关联对象类型")
    private String refType;

    @Schema(description = "关联对象 ID")
    private Long refId;

    @Schema(description = "已读时间，null 表示未读")
    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
