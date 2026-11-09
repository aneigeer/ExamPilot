package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 步骤检查点（任务中断恢复用）
 *
 * <p><b>为什么只存摘要</b>：检查点的用途是"知道这一轮走到第几步、本来要干什么"，
 * 恢复时只需要原 question + sessionId 重开一轮（Python harness 不支持从第 N 步续跑）。
 * 因此 {@code payloadJson} 只放事件名 / 工具名 / 参数<b>键名</b>，
 * <b>不存问题原文、不存工具返回原文</b>——否则等于在库里复制一份对话内容，
 * 而且 checkpoint 没有跟随 {@code ai_agent_run} 的保留期清理，会变成绕开数据最小化的后门。
 *
 * <p><b>状态机</b>：{@code RECORDED}（正常落库）→ {@code RESUMED}（已被用于一次恢复）。
 * 恢复时按 {@code WHERE status='RECORDED'} 条件更新，影响 0 行即说明已被别人恢复过 → 409。
 * 这样"同一 run 只允许成功恢复一次"由数据库条件更新兜底，不依赖应用层先查后改。
 */
@Data
@TableName("ai_agent_checkpoint")
@Schema(description = "Agent 步骤检查点")
public class AiAgentCheckpoint {

    /** 正常记录的步骤 */
    public static final String STATUS_RECORDED = "RECORDED";

    /** 已被用于一次恢复 */
    public static final String STATUS_RESUMED = "RESUMED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String sessionId;

    /** 第几步（1-based，只对 thought / tool_call / tool_result 计数） */
    private Integer stepNo;

    /** 步骤摘要 JSON（不含原文） */
    private String payloadJson;

    /** RECORDED / RESUMED */
    private String status;

    private LocalDateTime createdAt;
}
