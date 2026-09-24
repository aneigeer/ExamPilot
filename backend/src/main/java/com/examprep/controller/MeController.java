package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.MyStatsVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.MyStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的」页面接口
 */
@Tag(name = "16. 我的", description = "我的页面统计")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final MyStatsService myStatsService;

    @Operation(summary = "我的统计",
            description = "**访问级别：AUTH**。App「我的」页顶部统计用：练习次数、正确率、错题数、收藏数、下载数、上传数、未读通知。")
    @GetMapping("/stats")
    public Result<MyStatsVO> stats() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.stats(userId));
    }
}
