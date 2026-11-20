package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.Result;
import com.examprep.dto.GradeRequest;
import com.examprep.dto.QuestionVO;
import com.examprep.service.QuestionService;
import com.examprep.service.ai.AiGradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题库接口（浏览模式，PUBLIC）
 */
@Tag(name = "03. 题库", description = "题目列表与详情（P0 开放 5 种核心题型）")
@RestController
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final AiGradingService aiGradingService;

    @Operation(summary = "已开放题型列表",
            description = "**访问级别：PUBLIC**。返回 P0 已开放的 5 种题型，前端可用于渲染筛选器。")
    @GetMapping("/questions/types")
    public Result<List<TypeItem>> listTypes() {
        List<TypeItem> list = QuestionType.OPEN_TYPES.stream()
                .map(t -> new TypeItem(t, QuestionType.labelOf(t)))
                .toList();
        return Result.success(list);
    }

    @Operation(summary = "题目分页列表",
            description = """
                    **访问级别：PUBLIC**。不返回答案与解析。传未开放题型会返回 2002。

                    可选筛选（多个同时传入时按 AND 叠加）：
                    - `year` 真题年份；`examCode` 考试类别编码；`keyword` 题干模糊匹配
                    - 不传 = 不加该条件（全部题目）
                    """)
    @GetMapping("/questions")
    public Result<PageResult<QuestionVO>> pageQuestions(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "知识点 ID") @RequestParam(required = false) Long knowledgeId,
            @Parameter(description = "题型，如 SINGLE_CHOICE") @RequestParam(required = false) String type,
            @Parameter(description = "难度 1-5") @RequestParam(required = false) Integer difficulty,
            @Parameter(description = "真题年份，如 2023") @RequestParam(required = false) Integer year,
            @Parameter(description = "考试类别编码，如 CET4 / GUOKAO / POSTGRAD_MATH1")
                @RequestParam(required = false) String examCode,
            @Parameter(description = "题干关键词（模糊匹配）") @RequestParam(required = false) String keyword) {
        return Result.success(questionService.pageQuestions(page, size, subjectId, knowledgeId,
                type, difficulty, year, examCode, keyword));
    }

    @Operation(summary = "有题目的年份清单",
            description = "**访问级别：PUBLIC**。返回有 APPROVED 题目的年份（倒序）及该年题目数，供前端渲染「按年份作答」筛选器。")
    @GetMapping("/questions/years")
    public Result<List<QuestionService.YearItem>> listYears() {
        return Result.success(questionService.listYears());
    }

    @Operation(summary = "考试类别清单",
            description = "**访问级别：PUBLIC**。返回 exam_code + 中文名 + 题目数（按题数倒序），供前端渲染「按考试类别作答」筛选器。")
    @GetMapping("/questions/exam-categories")
    public Result<List<QuestionService.ExamCategoryItem>> listExamCategories() {
        return Result.success(questionService.listExamCategories());
    }

    @Operation(summary = "题库统计",
            description = "**访问级别：PUBLIC**。总题数 / 按考试类别分布 / 按年份分布，口径与题目列表一致（APPROVED + 独立题）。")
    @GetMapping("/questions/stats")
    public Result<QuestionService.QuestionStats> questionStats() {
        return Result.success(questionService.questionStats());
    }

    @Operation(summary = "题目详情",
            description = "**访问级别：PUBLIC**。含选项、答案与解析，用于题库浏览复习。")
    @GetMapping("/questions/{id}")
    public Result<QuestionVO> getQuestion(@PathVariable Long id) {
        return Result.success(questionService.getQuestionDetail(id));
    }

    @io.swagger.v3.oas.annotations.media.Schema(description = "题型项")
    public record TypeItem(String value, String label) {
    }

    @Operation(summary = "主观题 AI 判分",
            description = """
                    **访问级别：登录用户**。

                    对照参考答案批改学生作答，返回**得分点命中 / 遗漏 + 一句话理由 + 参考得分率**。
                    - **只给等级与理由，不冒充精确分数**：单轮 Prompt 无法做到 Rubric 级精度
                    - **绝不阻塞作答**：大模型不可用时返回 `available=false`，前端回退到自评
                    - 作答过短（< 4 字）直接返回 `level=INVALID`，不浪费一次模型调用
                    """)
    @PostMapping("/questions/{id}/grade")
    public Result<AiGradingService.GradeResult> grade(
            @Parameter(description = "题目 ID") @PathVariable Long id,
            @Valid @RequestBody GradeRequest request) {
        AiGradingService.GradeResult result = aiGradingService.grade(id,
                request.getAnswer() == null ? "" : request.getAnswer());
        return Result.success(result.available() ? "判分完成" : "AI 判分暂不可用", result);
    }
}
