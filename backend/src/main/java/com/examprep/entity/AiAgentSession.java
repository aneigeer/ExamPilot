package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话（分层记忆的短期/跨轮次载体）
 *
 * <p>一个会话下有多条 {@link AiAgentRun}。每轮结束后 Java 把
 * 「用户问题 + 助手答案」确定性追加到 {@code summary}（只做滚动转录，
 * 不额外调用 LLM），下一轮由 Python 的 Context Manager 读取并压缩。
 */
@Data
@TableName("ai_agent_session")
@Schema(description = "Agent 会话")
public class AiAgentSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "会话稳定标识（客户端可复用）")
    private String sessionId;

    private Long userId;

    /** 最近若干轮问答的滚动摘要（上限 2000 字） */
    private String summary;

    private Integer turnCount;

    private String lastQuestion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
