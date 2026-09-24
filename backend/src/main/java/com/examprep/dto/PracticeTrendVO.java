package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 刷题趋势（P0-B 简单统计图表）
 */
@Data
@Schema(description = "刷题趋势")
public class PracticeTrendVO {

    @Schema(description = "统计天数")
    private Integer days;

    @Schema(description = "逐日数据（无刷题的日期补 0）")
    private List<Item> items;

    @Data
    @AllArgsConstructor
    @Schema(description = "趋势项")
    public static class Item {

        @Schema(description = "日期，yyyy-MM-dd")
        private String date;

        @Schema(description = "当日答题数")
        private Long answered;

        @Schema(description = "当日正确数")
        private Long correct;
    }
}
