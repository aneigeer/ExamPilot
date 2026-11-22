package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.dto.wiki.WikiPageVO;
import com.examprep.dto.wiki.WikiRevisionVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.wiki.WikiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Wiki 管理端（蒸馏触发 / 草稿审核 / 页面下架 / 向量重建）
 */
@Tag(name = "15. Wiki 管理", description = "LLM Wiki 蒸馏与审核（管理端）")
@RestController
@RequestMapping("/admin/wiki")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
public class WikiAdminController {

    private final WikiService wikiService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.examprep.service.OperationLogService operationLogService;

    public void setOperationLogService(com.examprep.service.OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    private void audit(String action, String targetType, Long targetId, String detail) {
        if (operationLogService != null) {
            operationLogService.record("AI_GOVERNANCE", action, targetType, targetId,
                    detail + ",traceId=" + com.examprep.common.TraceContext.of(null));
        }
    }

    @Operation(summary = "Wiki 概览", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(wikiService.stats());
    }

    @Operation(summary = "页面列表（全状态）", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。")
    @GetMapping("/pages")
    public Result<PageResult<WikiPageVO>> pages(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "PUBLISHED / OFFLINE，不传全部") @RequestParam(required = false) String status,
            @Parameter(description = "VERIFIED / AUTO_DISTILLED / STALE_REVIEW / RETIRED")
            @RequestParam(required = false) String trustTier,
            @RequestParam(required = false) String keyword) {
        return Result.success(wikiService.adminPages(page, size, status, trustTier, keyword));
    }

    @Operation(summary = "待审 / 历史草稿列表", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。")
    @GetMapping("/revisions")
    public Result<PageResult<WikiRevisionVO>> revisions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "PENDING / APPROVED / REJECTED，默认 PENDING")
            @RequestParam(defaultValue = "PENDING") String status) {
        return Result.success(wikiService.revisions(page, size, status));
    }

    @Operation(summary = "审核通过草稿", description = """
            **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

            草稿 → 正式页（版本 +1）→ 重建导航链接 → 同步 Qdrant `wiki_pages`。
            向量同步失败不影响发布：Wiki 仍可凭 MySQL ngram 命中。
            """)
    @PostMapping("/revisions/{id}/approve")
    public Result<Map<String, Object>> approve(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Long reviewerId = SecurityUtils.currentUserIdOrNull();
        String note = body == null ? null : String.valueOf(body.getOrDefault("note", ""));
        Map<String, Object> data = wikiService.approve(id, reviewerId, note);
        audit("WIKI_APPROVE", "WIKI_REVISION", id, "reviewerId=" + reviewerId);
        return Result.success("已发布", data);
    }

    @Operation(summary = "驳回草稿", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。")
    @PostMapping("/revisions/{id}/reject")
    public Result<Map<String, Object>> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Long reviewerId = SecurityUtils.currentUserIdOrNull();
        String note = body == null ? null : String.valueOf(body.getOrDefault("note", ""));
        Map<String, Object> data = wikiService.reject(id, reviewerId, note);
        audit("WIKI_REJECT", "WIKI_REVISION", id, "reviewerId=" + reviewerId);
        return Result.success("已驳回", data);
    }

    @Operation(summary = "下架页面", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。同时删除向量点。")
    @PostMapping("/pages/{id}/offline")
    public Result<Map<String, Object>> offline(@PathVariable Long id) {
        Map<String, Object> data = wikiService.offline(id);
        audit("WIKI_OFFLINE", "WIKI_PAGE", id, "status=OFFLINE");
        return Result.success("已下架", data);
    }

    @Operation(summary = "标记退役",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。立即退出热层，直到重新审核发布。")
    @PostMapping("/pages/{id}/retire")
    public Result<Map<String, Object>> retire(@PathVariable Long id,
                                              @RequestParam(required = false) String reason) {
        String effectiveReason = reason == null ? "人工退役" : reason;
        Map<String, Object> data = wikiService.retire(id, effectiveReason);
        audit("WIKI_RETIRE", "WIKI_PAGE", id, "reason=" + effectiveReason);
        return Result.success("已退役", data);
    }

    @Operation(summary = "标记待重新蒸馏",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。把页面置为 STALE_REVIEW 并触发一次蒸馏。")
    @PostMapping("/pages/{id}/redistill")
    public Result<Map<String, Object>> redistill(@PathVariable Long id) {
        Map<String, Object> data = wikiService.retireStale(id, "人工要求重新蒸馏");
        audit("WIKI_REDISTILL", "WIKI_PAGE", id, "trustTier=STALE_REVIEW");
        return Result.success("已标记待重新蒸馏", data);
    }

    @Operation(summary = "触发一次蒸馏", description = """
            **访问级别：ROLE:AUDITOR / ROLE:ADMIN**。

            仅入队 `WIKI_DISTILL`（exam:wiki），LLM 蒸馏由 Python worker 异步执行。
            同类型任务同时只允许一个，重复点击返回 429。
            """)
    @PostMapping("/distill")
    public Result<Map<String, Object>> distill(
            @Parameter(description = "ALL / SUBJECT") @RequestParam(defaultValue = "ALL") String scope,
            @Parameter(description = "scope=SUBJECT 时必填") @RequestParam(required = false) Long subjectId) {
        Long userId = SecurityUtils.currentUserIdOrNull();
        var task = wikiService.enqueueDistill(scope, subjectId, userId);
        audit("WIKI_DISTILL_ENQUEUE", "ASYNC_TASK", task.getId(), "scope=" + scope + ",subjectId=" + subjectId);
        return Result.success("已入队", Map.of(
                "taskId", task.getId(),
                "type", task.getType(),
                "stream", task.getStream(),
                "status", task.getStatus()));
    }

    @Operation(summary = "重建 Wiki 向量索引", description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。")
    @PostMapping("/vector/rebuild")
    public Result<Map<String, Object>> rebuildVector() {
        return Result.success("重建完成", wikiService.rebuildIndex());
    }
}
