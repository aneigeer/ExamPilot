package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据流水线任务（数据中台 ODS/ETL 层）
 */
@Data
@TableName("data_pipeline_job")
@Schema(description = "数据流水线任务")
public class DataPipelineJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务类型：IMPORT_QUESTIONS/OCR_BATCH/VECTOR_SYNC/CALIBRATION/METRICS_ETL")
    private String jobType;

    @Schema(description = "触发来源：manual / scheduler / 批次标识")
    private String sourceRef;

    @Schema(description = "状态：RUNNING/SUCCESS/FAILED")
    private String status;

    private Integer totalRows;
    private Integer insertedRows;
    private Integer dedupRows;
    private Integer failedRows;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
