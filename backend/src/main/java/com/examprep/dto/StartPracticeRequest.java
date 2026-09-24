package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "开始练习请求")
public class StartPracticeRequest {

    @NotNull(message = "科目不能为空")
    @Schema(description = "科目 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Long subjectId;

    @Schema(description = "知识点 ID（可选，限定范围）")
    private Long knowledgeId;

    @Schema(description = "练习模式", allowableValues = {"SEQUENCE", "RANDOM", "BY_TYPE", "WRONG_BOOK"},
            example = "RANDOM")
    private String mode = "RANDOM";

    @Schema(description = "按题型练习时的题型（mode=BY_TYPE 时必填）", example = "SINGLE_CHOICE")
    private String questionType;

    @Min(value = 1, message = "难度最小为 1")
    @Max(value = 5, message = "难度最大为 5")
    @Schema(description = "按难度筛选（1-5），不传则不限", example = "3")
    private Integer difficulty;

    @Schema(description = "客观/主观筛选：MIXED 混合（默认）/ ONLY_OBJECTIVE 只刷客观 / ONLY_SUBJECTIVE 只刷主观",
            allowableValues = {"MIXED", "ONLY_OBJECTIVE", "ONLY_SUBJECTIVE"}, example = "MIXED")
    private String objectiveFilter;

    @Min(value = 1, message = "题数至少 1")
    @Max(value = 50, message = "单次练习最多 50 题")
    @Schema(description = "本次题数，默认 10，上限 50", example = "5")
    private Integer count = 10;
}
