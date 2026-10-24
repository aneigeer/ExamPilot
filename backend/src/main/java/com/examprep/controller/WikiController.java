package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.wiki.WikiGraphVO;
import com.examprep.dto.wiki.WikiPageDetailVO;
import com.examprep.dto.wiki.WikiPageVO;
import com.examprep.service.wiki.WikiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wiki 主题页（学生端，匿名可读）
 *
 * <p>与 RAG 问答共用同一份已发布页面：问答命中 Wiki 热层时，
 * App 可通过 {@code refPath=/wiki/{id}} 直接跳到对应主题页继续阅读。
 */
@Tag(name = "14. Wiki 知识库", description = "主题页浏览与导航图")
@RestController
@RequestMapping("/wiki")
@RequiredArgsConstructor
public class WikiController {

    private final WikiService wikiService;

    @Operation(summary = "Wiki 主题页列表", description = "**访问级别：PUBLIC**。按更新时间倒序。")
    @GetMapping("/pages")
    public Result<PageResult<WikiPageVO>> pages(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，最大 50") @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "科目 ID，不传查全部") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "标题 / 摘要关键词") @RequestParam(required = false) String keyword) {
        return Result.success(wikiService.listPages(page, size, subjectId, keyword));
    }

    @Operation(summary = "Wiki 主题页详情", description = "**访问级别：PUBLIC**。含 Markdown 正文与关联页。")
    @GetMapping("/pages/{id}")
    public Result<WikiPageDetailVO> detail(@PathVariable Long id) {
        return Result.success(wikiService.detail(id));
    }

    @Operation(summary = "按 slug 取 Wiki 主题页", description = "**访问级别：PUBLIC**。")
    @GetMapping("/pages/slug/{slug}")
    public Result<WikiPageDetailVO> detailBySlug(@PathVariable String slug) {
        return Result.success(wikiService.detailBySlug(slug));
    }

    @Operation(summary = "Wiki 导航图", description = "**访问级别：PUBLIC**。返回节点与有向边，供 App 渲染知识图谱。")
    @GetMapping("/graph")
    public Result<WikiGraphVO> graph(
            @Parameter(description = "科目 ID，不传返回全图") @RequestParam(required = false) Long subjectId) {
        return Result.success(wikiService.graph(subjectId));
    }
}
