package com.examprep.controller;

import com.examprep.common.FileTypeGuard;
import com.examprep.common.Result;
import com.examprep.entity.FileRecord;
import com.examprep.entity.Resource;
import com.examprep.entity.ResourcePage;
import com.examprep.security.LoginUser;
import com.examprep.security.SecurityUtils;
import com.examprep.service.FileStorageService;
import com.examprep.service.ResourceService;
import com.examprep.service.WatermarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件上传与访问接口
 */
@Slf4j
@Tag(name = "07. 文件", description = "上传、预览图访问、签名下载")
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;
    private final ResourceService resourceService;
    private final WatermarkService watermarkService;

    /** 游客可预览的最大页数（与 ResourceService 的预览口径一致） */
    @Value("${app.preview.guest-pages:3}")
    private int guestPages;

    @Operation(summary = "上传图片",
            description = "**访问级别：AUTH**。**这是 App 端唯一的上传入口**。仅接受 jpg/jpeg/png/webp/gif，单张不超过 10MB。返回 `fileId` 用于提交资料。")
    @PostMapping(value = "/files/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadImage(@RequestPart("file") MultipartFile file) {
        Long userId = SecurityUtils.requireUserId();
        FileRecord record = fileStorageService.storeImage(file, userId);
        return Result.success(Map.of(
                "fileId", record.getId(),
                "fileName", record.getOriginalName(),
                "size", record.getFileSize(),
                "md5", record.getMd5()
        ));
    }

    @Operation(summary = "上传文档",
            description = "**访问级别：AUTH**。**供管理端 Web 使用**（App 端不支持选文件）。支持 pdf/doc/docx/xls/xlsx/ppt/pptx/zip/txt。")
    @PostMapping(value = "/files/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadDocument(@RequestPart("file") MultipartFile file) {
        Long userId = SecurityUtils.requireUserId();
        FileRecord record = fileStorageService.storeDocument(file, userId);
        return Result.success(Map.of(
                "fileId", record.getId(),
                "fileName", record.getOriginalName(),
                "size", record.getFileSize(),
                "md5", record.getMd5()
        ));
    }

    @Operation(summary = "上传音频（听力题）",
            description = "**访问级别：AUTH**。支持 mp3/wav/m4a/aac/ogg，单个不超过 50MB。返回 fileId 供创建听力题使用。")
    @PostMapping(value = "/files/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadAudio(@RequestPart("file") MultipartFile file) {
        Long userId = SecurityUtils.requireUserId();
        FileRecord record = fileStorageService.storeAudio(file, userId);
        return Result.success(Map.of(
                "fileId", record.getId(),
                "fileName", record.getOriginalName(),
                "size", record.getFileSize()
        ));
    }

    @Operation(summary = "访问听力音频",
            description = "**访问级别：AUTH（本人 / ADMIN / AUDITOR）**。返回音频流，播放器直接播放。"
                    + "非本人且非审核员一律返回 404（不提示文件是否存在，避免 fileId 枚举探测）。")
    @GetMapping("/files/audio/{fileId}")
    public void audio(@Parameter(description = "音频文件 ID") @PathVariable Long fileId,
                      HttpServletResponse response) throws IOException {
        FileRecord file = fileStorageService.getFile(fileId);

        // ---- P0-1 归属校验：此前任何登录用户凭自增 fileId 即可顺序读取他人音频 ----
        // 用 404 而非 403：403 会暴露「该 id 确实存在」，等于把 fileId 枚举变成存在性探测。
        Long currentUserId = SecurityUtils.currentUserIdOrNull();
        boolean owner = currentUserId != null && currentUserId.equals(file.getUploaderId());
        if (!owner && !SecurityUtils.isAuditorOrAdmin()) {
            log.info("[audio] denied fileId={} uploaderId={} visitor={}",
                    fileId, file.getUploaderId(), currentUserId == null ? "anonymous" : currentUserId);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "音频缺失");
            return;
        }

        // E1-08：统一走带 uploadRoot 边界校验的读取入口，禁止 stored_path 越界读取任意文件
        InputStream in = fileStorageService.openForRead(file);
        if (in == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "音频缺失");
            return;
        }
        // ---- P0-2 响应类型安全：按扩展名取服务端权威 MIME，不回显客户端声明的 Content-Type ----
        response.setContentType(FileTypeGuard.canonicalAudioMime(file.getExt()));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Accept-Ranges", "bytes");
        try (in) {
            in.transferTo(response.getOutputStream());
        }
    }

    @Operation(summary = "访问预览图",
            description = "**访问级别：PUBLIC（受控）**。未过审资源仅 ADMIN/AUDITOR 或上传者本人可看（其余 404）；"
                    + "已过审资源对游客仅开放前 guest-pages 页，超出返回 403 提示登录。")
    @GetMapping("/files/preview/{pageId}")
    public void previewImage(@Parameter(description = "预览图页 ID") @PathVariable Long pageId,
                             HttpServletResponse response) throws IOException {
        ResourcePage page = resourceService.getPage(pageId);
        Resource resource = resourceService.getResourceOrNull(page.getResourceId());
        if (resource == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
        boolean approved = "APPROVED".equals(resource.getAuditStatus());

        // ---- 访问控制：先鉴权，再读文件 ----
        if (!approved) {
            // 未过审资源：仅 ADMIN/AUDITOR 或上传者本人可看，其余一律 404（不提示存在性）
            boolean privileged = loginUser != null
                    && (SecurityUtils.isAuditorOrAdmin()
                        || loginUser.getUserId().equals(resource.getUploaderId()));
            if (!privileged) {
                log.info("[preview] denied pageId={} auditStatus={} visitor={}",
                        pageId, resource.getAuditStatus(), loginUser == null ? "guest" : loginUser.getUserId());
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } else if (loginUser == null && page.getPageNo() != null && page.getPageNo() > guestPages) {
            // 已过审资源：游客只能看前 guestPages 页，与 /resources/{id}/preview 的口径一致
            log.info("[preview] guest page limit pageId={} pageNo={} guestPages={}",
                    pageId, page.getPageNo(), guestPages);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":3006,\"message\":\"游客仅可预览前 "
                    + guestPages + " 页，请登录后查看更多\",\"data\":null}");
            return;
        }

        // E1-08：预览图路径同样做根目录边界校验（越界/MinIO 路径返回 null → 404）
        Path path = fileStorageService.resolveReadablePath(page.getImagePath());
        if (path == null || !Files.exists(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览图文件缺失");
            return;
        }
        // 访问者水印标识：脱敏学号 / 游客 —— 截图传播可追溯来源（loginUser 已在上方鉴权处取得）
        String visitorLabel = loginUser == null ? null
                : WatermarkService.maskStudentNo(loginUser.getStudentNo());

        BufferedImage image = ImageIO.read(path.toFile());
        log.info("[preview] pageId={} file={} image={}", pageId, path.getFileName(),
                image == null ? "NULL" : image.getWidth() + "x" + image.getHeight() + " type=" + image.getType());
        if (image == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览图文件损坏");
            return;
        }
        BufferedImage watermarked = watermarkService.apply(image, visitorLabel);
        log.info("[preview] watermarked={}x{} type={}",
                watermarked.getWidth(), watermarked.getHeight(), watermarked.getType());

        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        // 水印因人而异，禁止缓存，避免不同用户串图
        response.setHeader("Cache-Control", "no-store");
        boolean written = ImageIO.write(watermarked, "jpg", response.getOutputStream());
        log.info("[preview] ImageIO.write = {}", written);
        response.flushBuffer();
    }

    @Operation(summary = "签名下载",
            description = "**访问级别：PUBLIC（凭签名）**。token 由 `/resources/{id}/download` 下发，10 分钟内有效，过期需重新获取。")
    @GetMapping("/files/download/{fileId}")
    public void download(@Parameter(description = "文件 ID") @PathVariable Long fileId,
                         @Parameter(description = "签名令牌") @RequestParam String token,
                         HttpServletResponse response) throws IOException {
        FileRecord file = resourceService.requireDownloadableFile(fileId, token);
        // E1-08：统一读取入口（本地越界 / MinIO 均安全处理）
        InputStream in = fileStorageService.openForRead(file);
        if (in == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件缺失");
            return;
        }
        String encodedName = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + encodedName);
        response.setContentLengthLong(file.getFileSize() == null ? 0 : file.getFileSize());
        try (in) {
            in.transferTo(response.getOutputStream());
        }
    }
}
