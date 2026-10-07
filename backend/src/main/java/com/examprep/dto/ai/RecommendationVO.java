package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 个性化推荐项
 */
@Data
@Schema(description = "个性化推荐项")
public class RecommendationVO {

    @Schema(description = "推荐类型：QUESTION 题目 / RESOURCE 资料", example = "QUESTION")
    private String type;

    @Schema(description = "业务 ID")
    private Long refId;

    @Schema(description = "标题（题干或资料名）")
    private String title;

    @Schema(description = "★ 推荐理由，任何情况下非空。冷启动时为 GENERAL_POPULAR",
            example = "你在「指针」知识点错 2 次，该题同属此知识点")
    private String reason;

    @Schema(description = "关联知识点 ID")
    private Long knowledgeId;

    @Schema(description = "关联知识点名称")
    private String knowledgeName;

    @Schema(description = "该知识点薄弱度 ∈ [0,1]")
    private Double weakness;

    @Schema(description = "综合排序分（薄弱度 × 0.6 + 难度匹配度 × 0.4）")
    private Double score;

    @Schema(description = "跳转路径", example = "/questions/12")
    private String refPath;
}
