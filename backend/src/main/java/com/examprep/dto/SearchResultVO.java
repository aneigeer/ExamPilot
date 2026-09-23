package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "统一搜索结果")
public class SearchResultVO {

    @Schema(description = "关键词")
    private String keyword;

    @Schema(description = "资料命中数")
    private Long resourceTotal;

    @Schema(description = "题目命中数")
    private Long questionTotal;

    @Schema(description = "资料结果")
    private List<ResourceVO> resources;

    @Schema(description = "题目结果（不含答案）")
    private List<QuestionVO> questions;
}
