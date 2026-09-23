package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.ResourceType;
import com.examprep.common.Result;
import com.examprep.dto.DownloadVO;
import com.examprep.dto.PreviewVO;
import com.examprep.dto.ResourceSubmitRequest;
import com.examprep.dto.ResourceVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 资料库接口
 */
@Tag(name = "06. 资料库", description = "资料浏览、预览、下载、上传、举报")
@RestController
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @Operation(summary = "资料类型列表", description = "**访问级别：PUBLIC**。前端渲染筛选标签用。")
    @GetMapping("/resources/types")
    public Result<List<TypeItem>> listTypes() {
        List<TypeItem> list = Arrays.stream(ResourceType.values())
                .map(t -> new TypeItem(t.name(), t.getLabel()))
                .toList();
        return Result.success(list);
    }

    @Operation(summary = "资料分页列表",
            description = "**访问级别：PUBLIC**。仅返回审核通过的资料。支持按考试类别 / 科目 / 课程 / 类型 / 关键词筛选。")
    @GetMapping("/resources")
    public Result<PageResult<ResourceVO>> pageResources(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "考试类别 ID") @RequestParam(required = false) Long examCategoryId,
            @Parameter(description = "科目 ID") @RequestParam(required = false) Long subjectId,
            @Parameter(description = "课程 ID") @RequestParam(required = false) Long courseId,
            @Parameter(description = "资料类型") @RequestParam(required = false) String type,
            @Parameter(description = "标题关键词") @RequestParam(required = false) String keyword) {
        return Result.success(resourceService.pageResources(page, size, examCategoryId, subjectId, courseId, type, keyword));
    }

    @Operation(summary = "我的上传", description = "**访问级别：AUTH**。含未通过审核的资料。")
    @GetMapping("/resources/mine")
    public Result<List<ResourceVO>> listMine() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(resourceService.listMine(userId));
    }

    @Operation(summary = "资料详情", description = "**访问级别：PUBLIC**。仅审核通过的资料可见，同时累加浏览量。")
    @GetMapping("/resources/{id}")
    public Result<ResourceVO> getResource(@PathVariable Long id) {
        return Result.success(resourceService.getResource(id));
    }

    @Operation(summary = "资料预览",
            description = """
                    **访问级别：PUBLIC**（游客可访问）。

                    - **游客**（无令牌）：仅返回前 **3 页**低清图，响应中 `guest=true` 并带提示语
                    - **登录用户**：返回全部页

                    返回的是**图片地址列表**，App 端用图片组件分页展示，不用 PDF 阅读器。
                    """)
    @GetMapping("/resources/{id}/preview")
    public Result<PreviewVO> preview(@PathVariable Long id) {
        // 游客没有登录态，这里取到的是 null
        Long userId = SecurityUtils.currentUserIdOrNull();
        return Result.success(resourceService.getPreview(id, userId));
    }

    @Operation(summary = "下载资料",
            description = "**访问级别：AUTH**。返回**短时效签名地址**（10 分钟），防止链接被转发盗链。同一用户重复下载同一资料不重复计数。")
    @PostMapping("/resources/{id}/download")
    public Result<DownloadVO> download(@PathVariable Long id, HttpServletRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(resourceService.download(id, userId, request.getRemoteAddr()));
    }

    @Operation(summary = "提交资料",
            description = "**访问级别：AUTH**。先调 `/files/image`（App）或 `/files/document`（管理端）拿到 `fileId`，再提交。提交后状态为 `PENDING` 待审核。")
    @PostMapping("/resources")
    public Result<ResourceVO> submit(@Valid @RequestBody ResourceSubmitRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success("已提交，等待审核", resourceService.submit(userId, request));
    }

    @Operation(summary = "举报资料", description = "**访问级别：AUTH**。审核员核验后处理。")
    @PostMapping("/resources/{id}/report")
    public Result<Void> report(@PathVariable Long id,
                               @Parameter(description = "举报原因") @RequestParam String reason,
                               @Parameter(description = "补充说明") @RequestParam(required = false) String description) {
        Long userId = SecurityUtils.requireUserId();
        resourceService.report(userId, "RESOURCE", id, reason, description);
        return Result.success("举报已提交，我们会尽快核验", null);
    }

    @Schema(description = "资料类型项")
    public record TypeItem(String value, String label) {
    }
}
