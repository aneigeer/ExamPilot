package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_quota_override")
@Schema(description = "AI 每日 Token 与请求配额单用户覆盖")
public class AiQuotaOverride {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @Schema(description = "每日 Token 上限（NULL 表示沿用角色默认值）")
    private Integer dailyTokenLimit;

    @Schema(description = "每日请求数上限（NULL 表示沿用角色默认值）")
    private Integer dailyRequestLimit;

    @Schema(description = "调整备注")
    private String remark;

    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
