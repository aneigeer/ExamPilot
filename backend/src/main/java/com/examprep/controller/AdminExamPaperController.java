package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.ExamPaperCreateRequest;
import com.examprep.dto.ExamPaperVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ExamPaperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：固定试卷（套卷）管理
 *
 * <p>权限：审核员与超管均可读写（试卷编排属于内容运营，与资料审核同级）。
 */
@Tag(name = "09A. 后台-试卷管理", description = "固定套卷创建、题目编排、发布/下架")
@RestController
@RequestMapping("/admin/exam-papers")
@RequiredArgsConstructor
public class AdminExamPaperController {

    private final ExamPaperService examPaperService;

    @Operation(summary = "试卷分页列表",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。支持按名称关键字、类型、发布状态筛选。")
    @GetMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<ExamPaperVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String paperType,
            @RequestParam(required = false) Integer published) {
        return Result.success(examPaperService.page(keyword, paperType, published, page, size));
    }

    @Operation(summary = "试卷详情", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。含题目列表与题序。")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<ExamPaperVO> detail(@PathVariable Long id) {
        return Result.success(examPaperService.detail(id));
    }

    @Operation(summary = "创建固定试卷",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。题目按数组顺序编号，总分按每题分值求和；创建后为未发布状态。")
    @PostMapping
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<ExamPaperVO> create(@Valid @RequestBody ExamPaperCreateRequest request) {
        Long operatorId = SecurityUtils.requireUserId();
        return Result.success("创建成功", examPaperService.create(request, operatorId));
    }

    @Operation(summary = "发布试卷", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。发布后学生端可见并可开考。")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<ExamPaperVO> publish(@PathVariable Long id) {
        return Result.success("已发布", examPaperService.publish(id, true));
    }

    @Operation(summary = "下架试卷",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。下架后学生端不可见，已开考记录不受影响。")
    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<ExamPaperVO> unpublish(@PathVariable Long id) {
        return Result.success("已下架", examPaperService.publish(id, false));
    }
}
