package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.ResourceVO;
import com.examprep.dto.SearchResultVO;
import com.examprep.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一搜索接口
 */
@Tag(name = "11. 搜索", description = "资料与题目统一搜索")
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "统一搜索",
            description = """
                    **访问级别：PUBLIC**。

                    一次搜索同时返回**资料**与**题目**两类结果。
                    - `scope` 可限定 `ALL`（默认）/ `RESOURCE` / `QUESTION`
                    - 题目结果**不含答案**，避免搜出答案直接抄
                    """)
    @GetMapping("/search")
    public Result<SearchResultVO> search(
            @Parameter(description = "关键词") @RequestParam String keyword,
            @Parameter(description = "范围：ALL / RESOURCE / QUESTION") @RequestParam(required = false) String scope,
            @Parameter(description = "每类返回条数") @RequestParam(defaultValue = "10") long limit) {
        return Result.success(searchService.search(keyword, scope, limit));
    }

    @Operation(summary = "搜索资料（分页）", description = "**访问级别：PUBLIC**。用于「查看更多搜索结果」。")
    @GetMapping("/search/resources")
    public Result<PageResult<ResourceVO>> searchResources(
            @Parameter(description = "关键词") @RequestParam String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(searchService.searchResources(keyword, page, size));
    }
}
