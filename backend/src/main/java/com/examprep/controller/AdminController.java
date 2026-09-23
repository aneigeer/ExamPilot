package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.AuditRequest;
import com.examprep.dto.DashboardVO;
import com.examprep.dto.QuestionVO;
import com.examprep.dto.ReportHandleRequest;
import com.examprep.dto.ResourceVO;
import com.examprep.dto.UserVO;
import com.examprep.entity.Announcement;
import com.examprep.entity.OperationLog;
import com.examprep.entity.Report;
import com.examprep.security.SecurityUtils;
import com.examprep.service.AdminService;
import com.examprep.service.AuditService;
import com.examprep.service.NoticeService;
import com.examprep.service.OperationLogService;
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

import java.util.List;
import java.util.Map;

/**
 * 后台管理接口
 *
 * 权限约定：
 * - 审核相关（仪表盘 / 资料审核 / 举报处理）：AUDITOR 或 ADMIN
 * - 系统管理（用户 / 日志 / 公告）：仅 ADMIN
 */
@Tag(name = "09. 后台管理", description = "仪表盘、资料审核、举报处理、用户管理、日志、公告管理")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AuditService auditService;
    private final NoticeService noticeService;
    private final OperationLogService operationLogService;

    // ==================================================================
    // 仪表盘
    // ==================================================================

    @Operation(summary = "仪表盘统计",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。用户数、题目数、资料数、待审核数、待处理举报数、累计下载量。")
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<DashboardVO> dashboard() {
        return Result.success(adminService.dashboard());
    }

    @Operation(summary = "资料类型分布", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。统计图用。")
    @GetMapping("/stats/resource-types")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Long>> resourceTypeStats() {
        return Result.success(auditService.resourceTypeDistribution());
    }

    // ==================================================================
    // 资料审核
    // ==================================================================

    @Operation(summary = "待审核资料列表", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**")
    @GetMapping("/audits/resources")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<ResourceVO>> pendingResources(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        return Result.success(auditService.pagePendingResources(page, size, type, keyword));
    }

    @Operation(summary = "已审核资料列表",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。`status` 可传 APPROVED / REJECTED / OFFLINE。")
    @GetMapping("/audits/resources/audited")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<ResourceVO>> auditedResources(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return Result.success(auditService.pageAuditedResources(page, size, status));
    }

    @Operation(summary = "审核通过",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。**审核员不能审核自己上传的资料**，违反返回 4001。通过后自动通知上传人。")
    @PostMapping("/audits/resources/{id}/approve")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> approveResource(@PathVariable Long id,
                                        @RequestBody(required = false) AuditRequest request) {
        Long auditorId = SecurityUtils.requireUserId();
        auditService.approveResource(id, auditorId, request == null ? null : request.getReason());
        return Result.success("已通过审核", null);
    }

    @Operation(summary = "审核驳回", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。**必须填写原因**，会自动通知上传人。")
    @PostMapping("/audits/resources/{id}/reject")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> rejectResource(@PathVariable Long id,
                                       @Valid @RequestBody AuditRequest request) {
        Long auditorId = SecurityUtils.requireUserId();
        auditService.rejectResource(id, auditorId, request.getReason());
        return Result.success("已驳回", null);
    }

    @Operation(summary = "下架资料", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。下架后公开入口不可见，已下载用户保留历史记录。")
    @PostMapping("/audits/resources/{id}/offline")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> offlineResource(@PathVariable Long id,
                                        @RequestBody(required = false) AuditRequest request) {
        Long auditorId = SecurityUtils.requireUserId();
        auditService.offlineResource(id, auditorId, request == null ? null : request.getReason());
        return Result.success("已下架", null);
    }

    // ==================================================================
    // 题目审核
    // ==================================================================

    @Operation(summary = "待审核题目列表",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。可按题型与关键词筛选。")
    @GetMapping("/audits/questions")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<QuestionVO>> pendingQuestions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "题型，如 SINGLE_CHOICE") @RequestParam(required = false) String type,
            @Parameter(description = "题干关键词") @RequestParam(required = false) String keyword) {
        return Result.success(auditService.pagePendingQuestions(page, size, type, keyword));
    }

    @Operation(summary = "题目审核通过",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。审核员不能审核自己创建的题目（返回 4001）。")
    @PostMapping("/audits/questions/{id}/approve")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> approveQuestion(@PathVariable Long id,
                                        @RequestBody(required = false) AuditRequest request) {
        auditService.approveQuestion(id, SecurityUtils.requireUserId(),
                request == null ? null : request.getReason());
        return Result.success("已通过审核", null);
    }

    @Operation(summary = "题目审核驳回",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。必须填写原因。")
    @PostMapping("/audits/questions/{id}/reject")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> rejectQuestion(@PathVariable Long id,
                                       @Valid @RequestBody AuditRequest request) {
        auditService.rejectQuestion(id, SecurityUtils.requireUserId(), request.getReason());
        return Result.success("已驳回", null);
    }

    // ==================================================================
    // 举报处理
    // ==================================================================

    @Operation(summary = "举报列表", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。`status` 可传 PENDING / VALID / INVALID。")
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<PageResult<Report>> reports(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return Result.success(auditService.pageReports(page, size, status));
    }

    @Operation(summary = "处理举报",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。成立可选同时下架被举报资料，处理结果自动通知举报人。同一举报不能重复处理。")
    @PostMapping("/reports/{id}/handle")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Void> handleReport(@PathVariable Long id,
                                     @Valid @RequestBody ReportHandleRequest request) {
        Long handlerId = SecurityUtils.requireUserId();
        auditService.handleReport(id, handlerId, request);
        return Result.success("已处理", null);
    }

    // ==================================================================
    // 用户管理（仅 ADMIN）
    // ==================================================================

    @Operation(summary = "用户列表", description = "**访问级别：ROLE:ADMIN**。学号已脱敏，支持按角色与关键词筛选。")
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<UserVO>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "角色：STUDENT / AUDITOR / ADMIN") @RequestParam(required = false) String role,
            @Parameter(description = "昵称或学号关键词") @RequestParam(required = false) String keyword) {
        return Result.success(adminService.pageUsers(page, size, role, keyword));
    }

    @Operation(summary = "启用 / 禁用用户", description = "**访问级别：ROLE:ADMIN**。不能禁用管理员账号。")
    @PutMapping("/users/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateUserStatus(@PathVariable Long id,
                                         @Parameter(description = "1 启用 / 0 禁用") @RequestParam Integer status) {
        adminService.updateUserStatus(id, status);
        return Result.success(status == 1 ? "已启用" : "已禁用", null);
    }

    @Operation(summary = "角色分布", description = "**访问级别：ROLE:ADMIN**。统计图用。")
    @GetMapping("/stats/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Long>> roleStats() {
        return Result.success(adminService.roleDistribution());
    }

    // ==================================================================
    // 操作日志（仅 ADMIN）
    // ==================================================================

    @Operation(summary = "操作日志", description = "**访问级别：ROLE:ADMIN**。可按模块与用户筛选。")
    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<OperationLog>> logs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "模块：AUTH / AUDIT / USER / RESOURCE / EXAM") @RequestParam(required = false) String module,
            @Parameter(description = "用户 ID") @RequestParam(required = false) Long userId) {
        return Result.success(operationLogService.page(page, size, module, userId));
    }

    // ==================================================================
    // 公告管理（仅 ADMIN）
    // ==================================================================

    @Operation(summary = "公告管理列表", description = "**访问级别：ROLE:ADMIN**。含未发布草稿。")
    @GetMapping("/announcements")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Announcement>> listAnnouncements() {
        return Result.success(noticeService.listAllAnnouncements());
    }

    @Operation(summary = "发布公告", description = "**访问级别：ROLE:ADMIN**")
    @PostMapping("/announcements")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Announcement> createAnnouncement(
            @Parameter(description = "标题") @RequestParam String title,
            @Parameter(description = "内容") @RequestParam String content,
            @Parameter(description = "是否置顶") @RequestParam(required = false) Boolean top) {
        Long publisherId = SecurityUtils.requireUserId();
        return Result.success("公告已发布", noticeService.createAnnouncement(publisherId, title, content, top));
    }

    @Operation(summary = "编辑公告", description = "**访问级别：ROLE:ADMIN**")
    @PutMapping("/announcements/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateAnnouncement(@PathVariable Long id,
                                           @RequestParam String title,
                                           @RequestParam String content,
                                           @RequestParam(required = false) Boolean top) {
        noticeService.updateAnnouncement(id, title, content, top);
        return Result.success("已更新", null);
    }

    @Operation(summary = "删除公告", description = "**访问级别：ROLE:ADMIN**")
    @DeleteMapping("/announcements/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteAnnouncement(@PathVariable Long id) {
        noticeService.deleteAnnouncement(id);
        return Result.success("已删除", null);
    }
}
