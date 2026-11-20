package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.ExamHistoryVO;
import com.examprep.dto.MockAnswerRequest;
import com.examprep.dto.MockPaperVO;
import com.examprep.dto.MockReportVO;
import com.examprep.dto.MockStartRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.MockExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟考试（P1）
 */
@Tag(name = "18. 模拟考试", description = "随机组卷、计时、交卷、成绩报告")
@RestController
@RequestMapping("/exams/mock")
@RequiredArgsConstructor
public class MockExamController {

    private final MockExamService mockExamService;

    @Operation(summary = "开始模拟考试",
            description = """
                    **访问级别：AUTH**。随机抽取**客观题**组卷（单选/多选/判断/填空），开始计时。

                    - 返回试卷、题目列表（不含答案）与 `deadline` / `remainingSeconds`
                    - 超过截止时间后端会自动交卷判分（状态 EXPIRED）
                    """)
    @PostMapping("/start")
    public Result<MockPaperVO> start(@Valid @RequestBody MockStartRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(mockExamService.start(userId, request));
    }

    @Operation(summary = "我的模考历史",
            description = """
                    **访问级别：AUTH**。返回当前登录用户历次模拟考试，按开考时间倒序。

                    - 含试卷名、试卷类型、状态、得分/总分、得分率、开考/交卷时间
                    - 严格按 `userId` 隔离，只能看到自己的记录
                    """)
    @GetMapping("/history")
    public Result<PageResult<ExamHistoryVO>> history(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(mockExamService.history(userId, page, size));
    }

    @Operation(summary = "取当前试卷", description = "**访问级别：AUTH**。含剩余时间与已答进度；超时会自动交卷。")
    @GetMapping("/{recordId}")
    public Result<MockPaperVO> paper(@PathVariable Long recordId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(mockExamService.getPaper(userId, recordId));
    }

    @Operation(summary = "答题",
            description = "**访问级别：AUTH**。**同一题可反复提交覆盖**（模拟考试允许改答案），交卷时统一判分。")
    @PostMapping("/{recordId}/answer")
    public Result<Integer> answer(@PathVariable Long recordId,
                                  @Valid @RequestBody MockAnswerRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("已记录", mockExamService.answer(userId, recordId, request));
    }

    @Operation(summary = "交卷",
            description = "**访问级别：AUTH**。统一自动判分并返回成绩报告。交过卷再交会报错。")
    @PostMapping("/{recordId}/submit")
    public Result<MockReportVO> submit(@PathVariable Long recordId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("交卷成功", mockExamService.submit(userId, recordId));
    }

    @Operation(summary = "成绩报告", description = "**访问级别：AUTH**。得分、得分率、逐题明细。")
    @GetMapping("/{recordId}/report")
    public Result<MockReportVO> report(@PathVariable Long recordId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(mockExamService.getReport(userId, recordId));
    }
}
