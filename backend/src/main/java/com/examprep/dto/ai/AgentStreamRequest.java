package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 流式会话请求
 */
@Data
@Schema(description = "Agent 流式会话请求")
public class AgentStreamRequest {

    @Schema(description = "学生问题", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "我的薄弱知识点有哪些？")
    @NotBlank(message = "请输入问题")
    @Size(max = 200, message = "问题过长（上限 200 字）")
    private String question;

    @Schema(description = "科目 ID，可选")
    private Long subjectId;

    @Schema(description = "考试类别 ID，可选")
    private Long examCategoryId;

    @Schema(description = "会话 ID，可选；不传由服务端生成，后续轮次复用该值即可获得多轮记忆",
            example = "s-3f9a1c2b7d4e")
    @Size(max = 32, message = "会话 ID 过长（上限 32 字符）")
    private String sessionId;

    @Schema(hidden = true)
    private java.util.List<String> approvedTools;
}
