package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.entity.FileRecord;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    @Operation(summary = "访问预览图",
            description = "**访问级别：PUBLIC**。返回图片流。页数限制在 `/resources/{id}/preview` 已做控制。")
    @GetMapping("/files/preview/{pageId}")
    public void previewImage(@Parameter(description = "预览图页 ID") @PathVariable Long pageId,
                             HttpServletResponse response) throws IOException {
        ResourcePage page = resourceService.getPage(pageId);
        Path path = fileStorageService.getUploadRoot().resolve(page.getImagePath()).normalize();
        if (!Files.exists(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "预览图文件缺失");
            return;
        }
        // 访问者水印标识：脱敏学号 / 游客 —— 截图传播可追溯来源
        LoginUser loginUser = SecurityUtils.getLoginUserOrNull();
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
        Path path = fileStorageService.getUploadRoot().resolve(file.getStoredPath()).normalize();
        if (!Files.exists(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件缺失");
            return;
        }
        String encodedName = URLEncoder.encode(file.getOriginalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + encodedName);
        response.setContentLengthLong(file.getFileSize() == null ? 0 : file.getFileSize());
        Files.copy(path, response.getOutputStream());
    }
}
