package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.QuestionSaveRequest;
import com.examprep.dto.QuestionVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.QuestionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端题目管理（新增 / 编辑 / 删除）
 */
@Tag(name = "14. 题目管理", description = "管理端题目增删改（审核员及以上）")
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
public class QuestionAdminController {

    private final QuestionAdminService questionAdminService;

    @Operation(summary = "题目列表（含未审核）",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。与公开的 `/questions` 不同，这里能看到待审核与已驳回的题目。")
    @GetMapping
    public Result<PageResult<QuestionVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "题型") @RequestParam(required = false) String type,
            @Parameter(description = "审核状态：PENDING / APPROVED / REJECTED") @RequestParam(required = false) String auditStatus,
            @Parameter(description = "题干关键词") @RequestParam(required = false) String keyword) {
        return Result.success(questionAdminService.pageAll(page, size, subjectId, type, auditStatus, keyword));
    }

    @Operation(summary = "新增题目",
            description = """
                    **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。新题默认状态为 `PENDING` 待审核。

                    **按题型填法：**
                    - 单选 / 判断：`options` 里恰好一个 `isAnswer=true`
                    - 多选：`options` 里多个 `isAnswer=true`
                    - 填空：`answerContent = {"blanks":["printf"]}`，可设 `caseSensitive`
                    - 简答：`answerContent = {"reference":"参考答案","keywords":["关键词"]}` + `gradingPoints`

                    服务端会按题型校验完整性，缺答案会直接拒绝（400）。
                    """)
    @PostMapping
    public Result<QuestionVO> create(@Valid @RequestBody QuestionSaveRequest request) {
        return Result.success("题目已创建，待审核",
                questionAdminService.create(SecurityUtils.requireUserId(), request));
    }

    @Operation(summary = "编辑题目",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。选项与答案整体覆盖式更新。")
    @PutMapping("/{id}")
    public Result<QuestionVO> update(@PathVariable Long id,
                                     @Valid @RequestBody QuestionSaveRequest request) {
        return Result.success("已更新", questionAdminService.update(id, request));
    }

    @Operation(summary = "删除题目", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。逻辑删除，连带清理选项与答案。")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        questionAdminService.delete(id);
        return Result.success("已删除", null);
    }
}
