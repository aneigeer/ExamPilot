package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.KnowledgeNodeVO;
import com.examprep.dto.SubjectVO;
import com.examprep.entity.ExamCategory;
import com.examprep.entity.Subject;
import com.examprep.service.ExamService;
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
 * 考试知识体系接口（全部为 PUBLIC，游客可用）
 */
@Tag(name = "02. 考试知识体系", description = "考试类别、科目、知识点树")
@RestController
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;

    @Operation(summary = "考试类别列表", description = "**访问级别：PUBLIC**。返回校内课程 / CET4 / CET6 / 计算机二级。")
    @GetMapping("/exams")
    public Result<List<ExamCategory>> listExams() {
        return Result.success(examService.listExamCategories());
    }

    @Operation(summary = "科目列表", description = "**访问级别：PUBLIC**。可不传 examCategoryId 查全部。计数为已审核内容的实时统计。")
    @GetMapping("/subjects")
    public Result<List<SubjectVO>> listSubjects(
            @Parameter(description = "考试类别 ID，不传则查全部")
            @RequestParam(required = false) Long examCategoryId) {
        return Result.success(examService.listSubjects(examCategoryId));
    }

    @Operation(summary = "科目详情", description = "**访问级别：PUBLIC**")
    @GetMapping("/subjects/{id}")
    public Result<Subject> getSubject(@PathVariable Long id) {
        return Result.success(examService.getSubject(id));
    }

    @Operation(summary = "知识点树", description = "**访问级别：PUBLIC**。返回树形结构：章节 → 知识点 → 考点。")
    @GetMapping("/subjects/{id}/knowledge-tree")
    public Result<List<KnowledgeNodeVO>> getKnowledgeTree(@PathVariable Long id) {
        return Result.success(examService.getKnowledgeTree(id));
    }
}
