package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行轨迹
 *
 * <p>数据库生成列 {@code running_user_id} 保证每个用户同时只有一个 RUNNING 会话，
 * 并发闸门由唯一约束兜底，不依赖应用层“先查后插”。
 */
@Data
@TableName("ai_agent_run")
@Schema(description = "Agent 运行轨迹")
public class AiAgentRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_REFUSED = "REFUSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 所属会话（跨轮次记忆的关联键） */
    private String sessionId;

    private String traceId;

    private String question;

    private String status;

    private String answer;

    private String generator;

    private Integer stepCount;

    private Integer toolCalls;

    private Integer totalMs;

    private Boolean degraded;

    /** 完整事件数组（轨迹回放，不分页） */
    private String stepsJson;

    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    /** 生成列，只读，不参与 insert/update */
    @TableField(exist = false)
    private Long runningUserId;
}
