package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.security.SecurityUtils;
import com.examprep.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 课程 / 科目关注（P1）
 */
@Tag(name = "20. 关注", description = "关注课程、科目")
@RestController
@RequestMapping("/follows")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @Operation(summary = "关注 / 取消关注",
            description = "**访问级别：AUTH**。切换式接口，返回 `following` 为操作后的状态。")
    @PostMapping
    public Result<Map<String, Object>> toggle(
            @Parameter(description = "目标类型：COURSE / SUBJECT") @RequestParam String targetType,
            @Parameter(description = "目标 ID") @RequestParam Long targetId) {
        Long userId = SecurityUtils.requireUserId();
        boolean following = followService.toggle(userId, targetType, targetId);
        return Result.success(following ? "已关注" : "已取消关注", Map.of("following", following));
    }

    @Operation(summary = "是否已关注", description = "**访问级别：PUBLIC**（未登录恒为 false）。")
    @GetMapping("/check")
    public Result<Map<String, Object>> check(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return Result.success(Map.of("following", followService.isFollowing(userId, targetType, targetId)));
    }

    @Operation(summary = "我的关注列表", description = "**访问级别：AUTH**。返回目标名称（课程含授课教师）。")
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @Parameter(description = "目标类型：COURSE / SUBJECT") @RequestParam String targetType) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(followService.listFollows(userId, targetType));
    }
}
