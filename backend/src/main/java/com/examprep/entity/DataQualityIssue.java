package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据质量异常
 *
 * <p>{@code issueKey} 是幂等键：同一问题重复巡检只更新 detail / severity，
 * 不会产生重复行；问题消失后由巡检自动置 RESOLVED。
 */
@Data
@TableName("data_quality_issue")
@Schema(description = "数据质量异常")
public class DataQualityIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "幂等键，如 DUPLICATE_STEM:question:123")
    private String issueKey;

    @Schema(description = "问题类型：DUPLICATE_STEM/MISSING_ANSWER/NO_CHUNK/ORPHAN_VECTOR/OCR_BACKLOG/EMPTY_SUBJECT")
    private String issueType;

    @Schema(description = "严重级别：CRITICAL/WARN/INFO")
    private String severity;

    private String targetTable;
    private Long targetId;
    private String detail;

    @Schema(description = "状态：OPEN/RESOLVED")
    private String status;

    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;
}
