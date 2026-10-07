package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 薄弱知识点（AI 学情画像）
 */
@Data
@Schema(description = "薄弱知识点")
public class KnowledgeWeakVO {

    @Schema(description = "知识点 ID")
    private Long knowledgeId;

    @Schema(description = "知识点名称", example = "指针")
    private String knowledgeName;

    @Schema(description = "科目 ID")
    private Long subjectId;

    @Schema(description = "该知识点的有效作答次数", example = "5")
    private Integer attempts;

    @Schema(description = "加权掌握度 ∈ [0,1]，越大越掌握", example = "0.42")
    private Double mastery;

    @Schema(description = "★ 薄弱度 ∈ [0,1] = (1 - mastery) × confidence，越大越薄弱；已按降序排列",
            example = "0.38")
    private Double weakness;

    @Schema(description = "置信度 = MIN(1, attempts / 3)，防止「只做 1 题就判薄弱」",
            example = "1.0")
    private Double confidence;

    @Schema(description = "最近作答时间")
    private String lastAnsweredAt;
}
