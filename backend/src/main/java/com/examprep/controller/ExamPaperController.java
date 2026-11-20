package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.ExamPaperVO;
import com.examprep.dto.MockPaperVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ExamPaperService;
import com.examprep.service.MockExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端：固定试卷（套卷）
 *
 * <p>只暴露已发布（published = 1）的 OFFICIAL / CUSTOM 套卷；
 * 随机组卷产生的试卷属于个人临时卷，不在此列出。
 */
@Tag(name = "17A. 试卷（固定套卷）", description = "已发布套卷列表与开考")
@RestController
@RequestMapping("/exams/papers")
@RequiredArgsConstructor
public class ExamPaperController {

    private final ExamPaperService examPaperService;
    private final MockExamService mockExamService;

    @Operation(summary = "已发布套卷列表",
            description = "**访问级别：AUTH**。支持按 examCode / subjectId / year 筛选，只返回已发布的固定套卷。")
    @GetMapping
    public Result<List<ExamPaperVO>> list(
            @RequestParam(required = false) String examCode,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer limit) {
        return Result.success(examPaperService.listPublished(examCode, subjectId, year, limit));
    }

    @Operation(summary = "基于固定套卷开考",
            description = "**访问级别：AUTH**。基于套卷题目创建一条 exam_record 并开始计时；未发布套卷返回 404。")
    @PostMapping("/{paperId}/start")
    public Result<MockPaperVO> start(@PathVariable Long paperId) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(mockExamService.startFromPaper(userId, paperId));
    }
}
