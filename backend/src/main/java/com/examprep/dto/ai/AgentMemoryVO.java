package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长期记忆 VO（学生端与管理端共用）
 */
@Data
@Schema(description = "Agent 长期记忆")
public class AgentMemoryVO {

    @Schema(description = "记忆 ID")
    private Long id;

    @Schema(description = "记忆键", example = "目标考试")
    private String memKey;

    @Schema(description = "记忆值", example = "2027 国考，行测为主")
    private String memValue;

    @Schema(description = "来源：STUDENT / AGENT", example = "STUDENT")
    private String source;

    @Schema(description = "状态：ACTIVE / DISABLED", example = "ACTIVE")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static AgentMemoryVO of(com.examprep.entity.AiAgentMemory memory) {
        AgentMemoryVO vo = new AgentMemoryVO();
        vo.setId(memory.getId());
        vo.setMemKey(memory.getMemKey());
        vo.setMemValue(memory.getMemValue());
        vo.setSource(memory.getSource());
        vo.setStatus(memory.getStatus());
        vo.setCreatedAt(memory.getCreatedAt());
        vo.setUpdatedAt(memory.getUpdatedAt());
        return vo;
    }
}
