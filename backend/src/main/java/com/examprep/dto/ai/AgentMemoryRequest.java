package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 长期记忆写入请求（新增 / 修改共用）
 *
 * <p>键不可改：语义上「换个键」等于新建一条，允许改键会让唯一键冲突的时机变得难以解释。
 */
@Data
@Schema(description = "长期记忆写入请求")
public class AgentMemoryRequest {

    @Schema(description = "记忆键，1..32 字", example = "目标考试", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "记忆键不能为空")
    @Size(max = 32, message = "记忆键最多 32 字")
    private String memKey;

    @Schema(description = "记忆值，1..200 字", example = "2027 国考，行测为主",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "记忆值不能为空")
    @Size(max = 200, message = "记忆值最多 200 字")
    private String memValue;
}
