package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 知识点掌握度（P0-A「简单统计」要求项）
 */
@Data
@Schema(description = "知识点掌握度")
public class MasteryVO {

    @Schema(description = "科目名称")
    private String subjectName;

    @Schema(description = "逐知识点掌握度")
    private List<Item> items;

    @Data
    @Schema(description = "知识点掌握度项")
    public static class Item {

        private Long knowledgeId;

        @Schema(description = "知识点名称")
        private String knowledgeName;

        @Schema(description = "已刷题数（该知识点下做过的题，按次计）")
        private Long answered;

        @Schema(description = "答对数")
        private Long correct;

        @Schema(description = "正确率（0-100，一位小数）")
        private BigDecimal masteryRate;
    }
}
