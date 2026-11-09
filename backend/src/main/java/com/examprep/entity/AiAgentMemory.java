package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 长期记忆（学生可见、可改、可删）
 *
 * <p><b>与 {@link AiAgentSession#getSummary()} 的分工</b>：
 * <ul>
 *   <li>{@code ai_agent_session.summary}：系统按对话<b>自动</b>滚动的短期摘要，学生不可编辑；</li>
 *   <li>本表：学生<b>主动</b>维护的稳定偏好/背景（目标考试、薄弱模块…），
 *       每轮以「【学生长期记忆】key: value」注入提示词。</li>
 * </ul>
 * 分开存是为了避免学生改自己的记忆时污染对话摘要，也让「删一条记忆」有明确语义。
 *
 * <p><b>逻辑删除</b>：{@code is_deleted=1} 表示已删除。由于存在
 * {@code UNIQUE(user_id, mem_key)}，删除后再添加同 key 必须走「复活」（UPDATE），
 * 不能 INSERT——否则会撞唯一键报 500。相关实现见 {@code AgentMemoryService#create}。
 */
@Data
@TableName("ai_agent_memory")
@Schema(description = "Agent 长期记忆")
public class AiAgentMemory {

    /** 来源：学生手填（DDL 默认值也是 USER） */
    public static final String SOURCE_USER = "USER";

    /** 来源：对话归纳（当前未启用，预留） */
    public static final String SOURCE_AGENT = "AGENT";

    /** 状态：生效（注入提示词） */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：保留但不生效 */
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 记忆键（同一学生唯一） */
    private String memKey;

    /** 记忆值（学生原文，≤200 字） */
    private String memValue;

    /** 来源：USER / AGENT */
    private String source;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0 有效 / 1 已删除（刻意不用 @TableLogic，见类注释） */
    private Integer isDeleted;
}
