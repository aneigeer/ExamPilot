package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 资料预览结果
 */
@Data
@Schema(description = "资料预览（图片分页）")
public class PreviewVO {

    @Schema(description = "资料总页数")
    private Integer totalPages;

    @Schema(description = "本次可见页数")
    private Integer visiblePages;

    @Schema(description = "是否为游客视角（受限前 3 页）")
    private Boolean guest;

    @Schema(description = "提示语")
    private String tip;

    @Schema(description = "预览图列表")
    private List<PageItem> pages;

    @Data
    @AllArgsConstructor
    @Schema(description = "预览图项")
    public static class PageItem {

        @Schema(description = "页 ID")
        private Long pageId;

        @Schema(description = "页码")
        private Integer pageNo;

        @Schema(description = "图片访问地址")
        private String url;
    }
}
