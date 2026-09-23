package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.QuestionType;
import com.examprep.common.Result;
import com.examprep.dto.QuestionVO;
import com.examprep.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            description = "**访问级别：PUBLIC**。不返回答案与解析。传未开放题型会返回 2002。")
    @GetMapping("/questions")
    public Result<PageResult<QuestionVO>> pageQuestions(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "知识点 ID") @RequestParam(required = false) Long knowledgeId,
            @Parameter(description = "题型，如 SINGLE_CHOICE") @RequestParam(required = false) String type,
            @Parameter(description = "难度 1-5") @RequestParam(required = false) Integer difficulty) {
        return Result.success(questionService.pageQuestions(page, size, subjectId, knowledgeId, type, difficulty));
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
}
