package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.FileRecord;
import com.examprep.entity.ResourcePage;
import com.examprep.mapper.FileRecordMapper;
import com.examprep.mapper.ResourcePageMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件存储服务
 *
 * 职责：
 * 1. 接收上传、校验类型与大小、MD5 去重、落盘
 * 2. 把 PDF / 图片转成预览图（供「游客只看前 3 页」使用）
 * 3. 生成与校验带时效的下载签名
 */
@Slf4j
@Service
public class FileStorageService {

    /** 允许的图片类型（App 端） */
    private final Set<String> allowedImageTypes;
    /** 允许的文档类型（管理端 Web） */
    private final Set<String> allowedDocTypes = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "txt");

    private final FileRecordMapper fileRecordMapper;
    private final ResourcePageMapper resourcePageMapper;

    private final Path uploadRoot;
    private final long imageMaxSize;
    private final String signSecret;

    public FileStorageService(FileRecordMapper fileRecordMapper,
                              ResourcePageMapper resourcePageMapper,
                              @Value("${app.upload.dir:./uploads}") String uploadDir,
                              @Value("${app.upload.image-max-size:10485760}") long imageMaxSize,
                              @Value("${app.upload.allowed-image-types:jpg,jpeg,png,webp,gif}") String allowedImageTypes,
                              @Value("${app.jwt.secret}") String jwtSecret) {
        this.fileRecordMapper = fileRecordMapper;
        this.resourcePageMapper = resourcePageMapper;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.imageMaxSize = imageMaxSize;
        this.allowedImageTypes = Arrays.stream(allowedImageTypes.split(","))
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
        this.signSecret = jwtSecret;
    }

    public Path getUploadRoot() {
        return uploadRoot;
    }

    // ==================================================================
    // 上传
    // ==================================================================

    /** 上传图片（App 端唯一入口） */
    public FileRecord storeImage(MultipartFile file, Long uploaderId) {
        String ext = extOf(file.getOriginalFilename());
        if (!allowedImageTypes.contains(ext)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持图片格式：" + String.join(" / ", allowedImageTypes));
        }
        if (file.getSize() > imageMaxSize) {
            throw new BizException(ResultCode.FILE_TOO_LARGE,
                    "单张图片不能超过 " + (imageMaxSize / 1024 / 1024) + "MB");
        }
        return store(file, uploaderId, "images");
    }

    /** 上传文档（管理端 Web） */
    public FileRecord storeDocument(MultipartFile file, Long uploaderId) {
        String ext = extOf(file.getOriginalFilename());
        if (!allowedDocTypes.contains(ext)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持文档格式：" + String.join(" / ", allowedDocTypes));
        }
        return store(file, uploaderId, "documents");
    }

    private FileRecord store(MultipartFile file, Long uploaderId, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.UPLOAD_EMPTY);
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed";
        String ext = extOf(originalName);

        String md5;
        try (InputStream is = file.getInputStream()) {
            md5 = DigestUtils.md5DigestAsHex(is);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }

        // 按日期分目录，避免单目录文件过多
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String relative = subDir + "/" + datePart + "/" + fileName;

        try {
            Path target = uploadRoot.resolve(relative).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw new BizException("非法的文件路径");
            }
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());

            FileRecord record = new FileRecord();
            record.setOriginalName(originalName);
            record.setStoredPath(relative);
            record.setMd5(md5);
            record.setFileSize(file.getSize());
            record.setMimeType(file.getContentType());
            record.setExt(ext);
            record.setUploaderId(uploaderId);
            fileRecordMapper.insert(record);

            log.info("文件上传成功 id={} name={} size={} md5={}", record.getId(), originalName, file.getSize(), md5);
            return record;
        } catch (IOException e) {
            throw new BizException("保存文件失败：" + e.getMessage());
        }
    }

    // ==================================================================
    // 预览图生成
    // ==================================================================

    /**
     * 为资料生成预览图
     *
     * - PDF：用 PDFBox 逐页渲染
     * - 图片：直接作为第 1 页
     * 其他文档类型暂不支持自动预览（P1 可接入 LibreOffice 转换）
     */
    public int buildPreviewPages(FileRecord fileRecord, Long resourceId) {
        Path source = uploadRoot.resolve(fileRecord.getStoredPath());
        if (!Files.exists(source)) {
            log.warn("源文件不存在，跳过预览图生成：{}", source);
            return 0;
        }

        // 先清掉旧的
        resourcePageMapper.delete(Wrappers.<ResourcePage>lambdaQuery()
                .eq(ResourcePage::getResourceId, resourceId));

        List<ResourcePage> pages = new ArrayList<>();
        try {
            if ("pdf".equalsIgnoreCase(fileRecord.getExt())) {
                pages = pdfToPages(source, resourceId);
            } else if (allowedImageTypes.contains(fileRecord.getExt())) {
                pages = List.of(imageToPage(source, resourceId));
            } else {
                log.info("文档类型 {} 暂不支持自动生成预览图", fileRecord.getExt());
                return 0;
            }
        } catch (Exception e) {
            log.error("生成预览图失败 resourceId={}", resourceId, e);
            return 0;
        }

        pages.forEach(resourcePageMapper::insert);
        log.info("资源 {} 生成预览图 {} 页", resourceId, pages.size());
        return pages.size();
    }

    private List<ResourcePage> pdfToPages(Path pdfPath, Long resourceId) throws IOException {
        List<ResourcePage> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int total = document.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                // 96 DPI：清晰度与体积的折中
                BufferedImage image = renderer.renderImageWithDPI(i, 96);
                String relative = "preview/" + resourceId + "/page-" + (i + 1) + ".jpg";
                Path target = uploadRoot.resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                ImageIO.write(image, "jpg", target.toFile());

                ResourcePage page = new ResourcePage();
                page.setResourceId(resourceId);
                page.setPageNo(i + 1);
                page.setImagePath(relative);
                page.setImageWidth(image.getWidth());
                page.setImageHeight(image.getHeight());
                page.setIsThumbnail(0);
                pages.add(page);
            }
        }
        return pages;
    }

    private ResourcePage imageToPage(Path imagePath, Long resourceId) throws IOException {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        ResourcePage page = new ResourcePage();
        page.setResourceId(resourceId);
        page.setPageNo(1);
        page.setImagePath(uploadRoot.relativize(imagePath).toString().replace('\\', '/'));
        if (image != null) {
            page.setImageWidth(image.getWidth());
            page.setImageHeight(image.getHeight());
        }
        page.setIsThumbnail(0);
        return page;
    }

    // ==================================================================
    // 下载签名（短时效 URL，防止链接被转发盗链）
    // ==================================================================

    public String sign(Long fileId, long ttlMillis) {
        long expireAt = System.currentTimeMillis() + ttlMillis;
        String raw = fileId + ":" + expireAt;
        return raw + ":" + hmac(raw);
    }

    public boolean verifySign(Long fileId, String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        String[] parts = token.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            if (!String.valueOf(fileId).equals(parts[0])) {
                return false;
            }
            long expireAt = Long.parseLong(parts[1]);
            if (System.currentTimeMillis() > expireAt) {
                log.debug("下载签名已过期 fileId={}", fileId);
                return false;
            }
            return hmac(parts[0] + ":" + parts[1]).equals(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private String extOf(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
