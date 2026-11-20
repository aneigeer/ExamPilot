package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日级核心指标快照（ADS 层）
 */
@Data
@TableName("ads_daily_metrics")
@Schema(description = "日级核心指标")
public class AdsDailyMetrics {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;
    private Integer activeUsers;
    private Integer practiceCount;
    private Integer mockCount;
    private BigDecimal avgAccuracy;
    private Integer aiAskCount;
    private Integer aiRefusedCount;
    private Integer aiDegradedCount;
    private Integer questionApprovedTotal;
    private Integer chunkActiveTotal;
    private LocalDateTime updatedAt;
}
