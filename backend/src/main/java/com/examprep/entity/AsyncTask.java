package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务（Redis Stream 任务状态）
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED。
 * 数据库生成列 {@code active_type} 保证同类型同时只有一条活动任务，
 * 唯一约束是防重复提交的最终依据（应用层查询只做快速失败）。
 */
@Data
@TableName("async_task")
@Schema(description = "异步任务")
public class AsyncTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务类型：EVAL_RETRIEVAL / EVAL_AGENT")
    private String type;

    @Schema(description = "Redis Stream 名称")
    private String stream;

    @Schema(description = "PENDING/RUNNING/SUCCESS/FAILED/CANCELLED")
    private String status;

    @Schema(description = "进度百分比 0-100")
    private Integer progress;

    @Schema(description = "任务参数 JSON")
    private String payload;

    /** 客户端幂等键：同键的 PENDING/RUNNING/SUCCESS 任务直接复用 */
    private String idempotencyKey;

    @Schema(description = "任务结果 JSON")
    private String result;

    @Schema(description = "失败原因")
    private String error;

    private Long createdBy;

    private LocalDateTime createdAt;

    /** 开始执行时间；PENDING 为 null（恢复逻辑只处理非 null 的 RUNNING） */
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 生成列，只读，不参与 insert/update */
    @TableField(exist = false)
    private String activeType;

    public boolean isTerminal() {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }
}
