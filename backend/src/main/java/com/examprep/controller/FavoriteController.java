package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.QuestionVO;
import com.examprep.dto.ResourceVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 收藏与下载历史
 */
@Tag(name = "12. 收藏与历史", description = "收藏资料/题目、下载历史")
@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "收藏 / 取消收藏",
            description = "**访问级别：AUTH**。**同一接口切换状态**：已收藏则取消，未收藏则收藏。返回值 `favorited` 表示操作后的状态。")
    @PostMapping("/favorites")
    public Result<Map<String, Object>> toggle(
            @Parameter(description = "对象类型：RESOURCE / QUESTION") @RequestParam String targetType,
            @Parameter(description = "对象 ID") @RequestParam Long targetId) {
        Long userId = SecurityUtils.requireUserId();
        boolean favorited = favoriteService.toggle(userId, targetType, targetId);
        return Result.success(favorited ? "已收藏" : "已取消收藏",
                Map.of("favorited", favorited));
    }

    @Operation(summary = "是否已收藏", description = "**访问级别：AUTH**。资料详情页进入时查询用。")
    @GetMapping("/favorites/check")
    public Result<Map<String, Object>> check(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return Result.success(Map.of("favorited",
                favoriteService.isFavorited(userId, targetType, targetId)));
    }

    @Operation(summary = "我收藏的资料", description = "**访问级别：AUTH**")
    @GetMapping("/favorites/resources")
    public Result<List<ResourceVO>> favoriteResources() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(favoriteService.listFavoriteResources(userId));
    }

    @Operation(summary = "我收藏的题目", description = "**访问级别：AUTH**")
    @GetMapping("/favorites/questions")
    public Result<List<QuestionVO>> favoriteQuestions() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(favoriteService.listFavoriteQuestions(userId));
    }

    @Operation(summary = "我的下载历史", description = "**访问级别：AUTH**。按下载时间倒序，分页。")
    @GetMapping("/me/downloads")
    public Result<PageResult<ResourceVO>> downloads(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(favoriteService.listDownloads(userId, page, size));
    }
}
