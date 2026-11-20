package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 离线评估运行记录（检索侧 / Agent 侧）
 */
@Data
@TableName("ai_eval_run")
@Schema(description = "离线评估运行记录")
public class AiEvalRun {

    public static final String LAYER_RETRIEVAL = "RETRIEVAL";
    public static final String LAYER_AGENT = "AGENT";
    public static final String LAYER_REFUSAL = "REFUSAL";
    public static final String LAYER_GRADING = "GRADING";
    public static final String LAYER_STUDY_PLAN = "STUDY_PLAN";

    /** 所有合法的评测层（写回与门禁汇总都按此校验） */
    public static final java.util.List<String> LAYERS = java.util.List.of(
            LAYER_RETRIEVAL, LAYER_AGENT, LAYER_REFUSAL, LAYER_GRADING, LAYER_STUDY_PLAN);

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 async_task.id（评估任务由队列触发时写入） */
    private Long taskId;

    @Schema(description = "RETRIEVAL / AGENT")
    private String layer;

    private String status;

    private Integer total;

    private Integer passed;

    /** 指标汇总 JSON（Recall@K / MRR / 通过率等） */
    private String metricsJson;

    /** 逐条结果 JSON */
    private String detailJson;

    private String reportPath;

    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
