package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.CommentVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 评论、点赞、资料评分
 */
@Tag(name = "15. 评论与评分", description = "评论、评论点赞、资料评分")
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "评论列表",
            description = "**访问级别：PUBLIC**。登录后返回 `liked` / `mine` 字段，游客访问这两项为 false。")
    @GetMapping("/comments")
    public Result<List<CommentVO>> list(
            @Parameter(description = "对象类型：RESOURCE / QUESTION") @RequestParam String targetType,
            @Parameter(description = "对象 ID") @RequestParam Long targetId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return Result.success(commentService.listComments(targetType, targetId, userId));
    }

    @Operation(summary = "发表评论", description = "**访问级别：AUTH**。`parentId` 非 0 表示回复某条评论。")
    @PostMapping("/comments")
    public Result<CommentVO> add(
            @RequestParam String targetType,
            @RequestParam Long targetId,
            @Parameter(description = "评论内容，最多 1000 字") @RequestParam String content,
            @Parameter(description = "父评论 ID，回复时填") @RequestParam(required = false) Long parentId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("评论成功",
                commentService.addComment(userId, targetType, targetId, content, parentId));
    }

    @Operation(summary = "删除评论", description = "**访问级别：AUTH**。只能删除自己的评论，否则 403。")
    @DeleteMapping("/comments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        commentService.deleteComment(userId, id);
        return Result.success("已删除", null);
    }

    @Operation(summary = "点赞 / 取消点赞评论",
            description = "**访问级别：AUTH**。**同一接口切换状态**，返回 `liked` 表示操作后的状态。")
    @PostMapping("/comments/{id}/like")
    public Result<Map<String, Object>> like(@PathVariable Long id) {
        Long userId = SecurityUtils.requireUserId();
        boolean liked = commentService.toggleCommentLike(userId, id);
        return Result.success(liked ? "已点赞" : "已取消点赞", Map.of("liked", liked));
    }

    @Operation(summary = "给资料评分",
            description = "**访问级别：AUTH**。评分 1-5，**一人一评**，重复评分会覆盖并重新计算平均分。返回最新平均分。")
    @PostMapping("/resources/{id}/rating")
    public Result<Map<String, Object>> rate(@PathVariable Long id,
                                            @Parameter(description = "评分 1-5") @RequestParam Integer score) {
        Long userId = SecurityUtils.requireUserId();
        BigDecimal avg = commentService.rateResource(userId, id, score);
        return Result.success("评分成功", Map.of("ratingAvg", avg));
    }

    @Operation(summary = "我的评分", description = "**访问级别：PUBLIC**（登录后才有值）。资料详情页回显用。")
    @GetMapping("/resources/{id}/rating/mine")
    public Result<Map<String, Object>> myRating(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        Integer score = commentService.myRating(userId, id);
        return Result.success(Map.of("score", score == null ? 0 : score));
    }
}
