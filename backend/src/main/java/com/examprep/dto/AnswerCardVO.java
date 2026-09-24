package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 练习答题卡：一次练习中每道题的状态总览
 */
@Data
@Schema(description = "练习答题卡")
public class AnswerCardVO {

    private Long practiceId;

    @Schema(description = "总题数")
    private Integer totalCount;

    @Schema(description = "已答题数")
    private Integer answeredCount;

    @Schema(description = "正确数")
    private Integer correctCount;

    @Schema(description = "状态：ONGOING / FINISHED")
    private String status;

    @Schema(description = "逐题状态（按作答顺序）")
    private List<Item> items;

    @Data
    @Schema(description = "答题卡项")
    public static class Item {

        @Schema(description = "第几题，从 1 开始")
        private Integer index;

        private Long questionId;

        @Schema(description = "题型中文名")
        private String typeName;

        @Schema(description = "题干（截断至 50 字）")
        private String stem;

        @Schema(description = "是否已作答")
        private Boolean answered;

        @Schema(description = "是否正确（未判分时为 null）")
        private Boolean correct;

        @Schema(description = "自评结果：KNOW / UNSURE / DONT_KNOW")
        private String selfEval;

        @Schema(description = "是否为当前待答的题")
        private Boolean current;
    }
}
