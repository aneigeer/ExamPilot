package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行轨迹（详情 / 回放）
 */
@Data
@Schema(description = "Agent 运行轨迹")
public class AgentRunVO {

    private Long id;
    private Long userId;
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
    private String error;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    /** 完整事件数组（不分页，前端直接渲染时间轴） */
    @Schema(description = "完整事件数组")
    private List<Map<String, Object>> steps;
}
