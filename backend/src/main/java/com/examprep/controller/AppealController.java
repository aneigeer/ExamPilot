package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.entity.Appeal;
import com.examprep.security.SecurityUtils;
import com.examprep.service.AppealService;
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

/**
 * 申诉（P1）—— 用户端
 */
@Tag(name = "19. 申诉", description = "对被下架的资料发起申诉")
@RestController
@RequestMapping("/appeals")
@RequiredArgsConstructor
public class AppealController {

    private final AppealService appealService;

    @Operation(summary = "发起申诉",
            description = "**访问级别：AUTH**。仅资料**上传人本人**可对**被下架（OFFLINE）**的资料申诉；申诉期间资料状态为 APPEALING，公开仍不可见。")
    @PostMapping
    public Result<Appeal> create(
            @Parameter(description = "资料 ID") @RequestParam Long resourceId,
            @Parameter(description = "申诉理由") @RequestParam String reason) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("申诉已提交，等待审核",
                appealService.create(userId, resourceId, reason));
    }

    @Operation(summary = "我的申诉记录", description = "**访问级别：AUTH**")
    @GetMapping("/mine")
    public Result<List<Appeal>> mine() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(appealService.listMine(userId));
    }
}
