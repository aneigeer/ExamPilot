package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.MasteryVO;
import com.examprep.dto.MyStatsVO;
import com.examprep.dto.PracticeTrendVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.MyStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(summary = "知识点掌握度",
            description = "**访问级别：AUTH**。按知识点聚合已刷题数与正确率（P0-A「简单统计」要求项）。`subjectId` 不传查全部科目。")
    @GetMapping("/mastery")
    public Result<MasteryVO> mastery(
            @io.swagger.v3.oas.annotations.Parameter(description = "科目 ID，不传查全部")
            @RequestParam(required = false) Long subjectId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.mastery(userId, subjectId));
    }

    @Operation(summary = "刷题趋势",
            description = "**访问级别：AUTH**。近 N 天逐日答题数与正确数（无刷题的日期补 0），前端渲染趋势图用。")
    @GetMapping("/practice-trend")
    public Result<PracticeTrendVO> trend(
            @io.swagger.v3.oas.annotations.Parameter(description = "统计天数，默认 7，最大 90")
            @RequestParam(defaultValue = "7") Integer days) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(myStatsService.trend(userId, days));
    }
}
