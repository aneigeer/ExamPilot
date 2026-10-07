package com.examprep.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.config.StorageProperties;
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
import java.nio.file.StandardCopyOption;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
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
    private final Set<String> allowedAudioTypes = Set.of("mp3", "wav", "m4a", "aac", "ogg");

    private final Set<String> allowedDocTypes = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "txt",
            "mp4", "mov", "avi", "webm", "mp3", "wav", "m4a");

    private final FileRecordMapper fileRecordMapper;
    private final ResourcePageMapper resourcePageMapper;
    private final MinioStorageService minioStorageService;
    private final StorageProperties storageProperties;

    private final Path uploadRoot;
    private final long imageMaxSize;
    private final String signSecret;

    /**
     * 已校验魔数的扩展名集合（jpg/png/gif/webp/pdf）。
     * 局限：zip/mp4 等其他文档类型暂不做魔数校验（各容器格式头部分布复杂，
     * 恶意文件仍可能伪装成这些扩展名），后续可接入 Apache Tika 做统一内容探测。
     */
    private static final Set<String> MAGIC_CHECKED_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf");

    public FileStorageService(FileRecordMapper fileRecordMapper,
                              ResourcePageMapper resourcePageMapper,
                              MinioStorageService minioStorageService,
                              StorageProperties storageProperties,
                              @Value("${app.upload.dir:./uploads}") String uploadDir,
                              @Value("${app.upload.image-max-size:10485760}") long imageMaxSize,
                              @Value("${app.upload.allowed-image-types:jpg,jpeg,png,webp,gif}") String allowedImageTypes,
                              @Value("${app.jwt.secret}") String jwtSecret) {
        this.fileRecordMapper = fileRecordMapper;
        this.resourcePageMapper = resourcePageMapper;
        this.minioStorageService = minioStorageService;
        this.storageProperties = storageProperties;
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
        verifyMagicNumber(file, ext);
        return store(file, uploaderId, "images");
    }

    /** 上传音频（听力题，P1） */
    public FileRecord storeAudio(MultipartFile file, Long uploaderId) {
        String ext = extOf(file.getOriginalFilename());
        if (!allowedAudioTypes.contains(ext)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持音频格式：" + String.join(" / ", allowedAudioTypes));
        }
        if (file.getSize() > 50L * 1024 * 1024) {
            throw new BizException(ResultCode.FILE_TOO_LARGE, "音频不能超过 50MB");
        }
        return store(file, uploaderId, "audios");
    }

    /** 按 ID 取文件记录（音频访问等场景） */
    public FileRecord getFile(Long fileId) {
        FileRecord file = fileRecordMapper.selectById(fileId);
        if (file == null) {
            throw new BizException(ResultCode.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    /** 上传文档（管理端 Web） */
    public FileRecord storeDocument(MultipartFile file, Long uploaderId) {
        String ext = extOf(file.getOriginalFilename());
        if (!allowedDocTypes.contains(ext)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持文档/音视频格式：" + String.join(" / ", allowedDocTypes));
        }
        if (file.getSize() > 200L * 1024 * 1024) {
            throw new BizException(ResultCode.FILE_TOO_LARGE, "单个文件不能超过 200MB");
        }
        verifyMagicNumber(file, ext); // 仅对 pdf 校验，zip/doc 等暂不校验，见 MAGIC_CHECKED_TYPES 注释
        return store(file, uploaderId, "documents");
    }

    /**
     * 落盘前校验文件头魔数，防止把文本/可执行内容改扩展名伪装上传。
     * 仅覆盖 MAGIC_CHECKED_TYPES 中已收录魔数的类型，其余类型直接放行（局限见其注释）。
     */
    private void verifyMagicNumber(MultipartFile file, String ext) {
        if (!MAGIC_CHECKED_TYPES.contains(ext)) {
            return;
        }
        byte[] head;
        try (InputStream is = file.getInputStream()) {
            head = is.readNBytes(12); // 最长需求：webp = "RIFF"(4) + 4 字节占位 + "WEBP"(4)
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }
        String hex = HexFormat.of().formatHex(head).toUpperCase(Locale.ROOT);
        boolean matched = switch (ext) {
            // jpg/jpeg: FF D8 FF；png: 89 50 4E 47；gif: 47 49 46；pdf: 25 50 44 46
            case "jpg", "jpeg" -> hex.startsWith("FFD8FF");
            case "png" -> hex.startsWith("89504E47");
            case "gif" -> hex.startsWith("474946");
            case "webp" -> hex.startsWith("52494646") && hex.length() >= 12
                    && hex.regionMatches(8, "57454250", 0, 8); // RIFF....WEBP
            case "pdf" -> hex.startsWith("25504446");
            default -> false;
        };
        if (!matched) {
            log.warn("文件魔数校验失败 ext={} head={}", ext, hex);
            throw new BizException("文件内容与扩展名不符");
        }
    }

    /**
     * 双轨存储（T10）：<b>MinIO 为主 + 本地磁盘兜底</b>
     *
     * <p><b>铁律：上传永远不能失败。</b>执行顺序：
     * <ol>
     *   <li>MinIO 可用 → 写 MinIO，{@code stored_path = minio://bucket/key}</li>
     *   <li>双写开关开启 → 同时在本地留一份（迁移期可回落）</li>
     *   <li>MinIO 不可用 / 超时 / 熔断 → <b>只落本地</b>，{@code stored_path} 为相对路径</li>
     * </ol>
     * 无论走哪条路，{@code file_record} 都照常写、接口都返回成功。
     */
    private FileRecord store(MultipartFile file, Long uploaderId, String subDir) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.UPLOAD_EMPTY);
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "unnamed";
        String ext = extOf(originalName);

        byte[] bytes;
        try (InputStream is = file.getInputStream()) {
            bytes = is.readAllBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }
        String md5 = DigestUtils.md5DigestAsHex(bytes);

        // 按日期分目录，避免单目录文件过多
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String relative = subDir + "/" + datePart + "/" + fileName;

        boolean minioUsable = minioStorageService.available();
        if (minioUsable) {
            minioStorageService.ensureBucket();
        }

        // ---------- ① 主路径：MinIO ----------
        String storedPath = relative;
        if (minioUsable) {
            try (InputStream is = new ByteArrayInputStream(bytes)) {
                if (minioStorageService.putObject(relative, is, bytes.length, file.getContentType())) {
                    storedPath = storageProperties.toStoredPath(relative);
                }
            } catch (IOException e) {
                log.warn("[存储] 构造 MinIO 流失败，落本地: {}", e.getMessage());
            }
        }

        // ---------- ② 双写：本地也留一份（迁移期可回落）----------
        boolean needLocal = storageProperties.isDualWriteLocal()
                || !StorageProperties.isMinioPath(storedPath);
        if (needLocal) {
            try {
                Path target = uploadRoot.resolve(relative).normalize();
                if (!target.startsWith(uploadRoot)) {
                    throw new BizException("非法的文件路径");
                }
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            } catch (IOException e) {
                if (StorageProperties.isMinioPath(storedPath)) {
                    // 已进 MinIO，本地副本写失败不影响上传成功（只失去回落能力）
                    log.warn("[存储] MinIO 已成功但本地双写失败（不影响上传）: {}", e.getMessage());
                } else {
                    throw new BizException("保存文件失败：" + e.getMessage());
                }
            }
        }

        FileRecord record = new FileRecord();
        record.setOriginalName(originalName);
        record.setStoredPath(storedPath);
        record.setMd5(md5);
        record.setFileSize(file.getSize());
        record.setMimeType(file.getContentType());
        record.setExt(ext);
        record.setUploaderId(uploaderId);
        fileRecordMapper.insert(record);

        log.info("文件上传成功 id={} name={} size={} md5={} 存储={}",
                record.getId(), originalName, file.getSize(), md5,
                StorageProperties.isMinioPath(storedPath) ? "minio" : "本地磁盘");
        return record;
    }

    /**
     * 解析文件物理路径（<b>T10 双读</b>）
     *
     * <p>{@code minio://} 前缀 → 走对象存储；其余 → 本地磁盘。
     * 迁移期存量文件仍是本地相对路径，因此两种前缀会长期共存。
     *
     * @return 本地绝对路径；该文件在 MinIO 时返回 null
     */
    public Path resolveLocalPath(FileRecord fileRecord) {
        if (fileRecord == null || !StringUtils.hasText(fileRecord.getStoredPath())) {
            return null;
        }
        if (StorageProperties.isMinioPath(fileRecord.getStoredPath())) {
            return null;
        }
        Path target = uploadRoot.resolve(fileRecord.getStoredPath()).normalize();
        return target.startsWith(uploadRoot) ? target : null;
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
        // ★ T10 双读：minio:// 走对象存储，其余走本地磁盘
        //   迁移期存量文件仍是本地相对路径，两种形式长期共存
        Path localSource = resolveLocalPath(fileRecord);
        if (localSource != null && Files.exists(localSource)) {
            return renderPreviewPages(localSource, fileRecord, resourceId);
        }

        // 本地没有 → 试 MinIO
        String objectKey = StorageProperties.parseObjectKey(fileRecord.getStoredPath());
        if (objectKey != null) {
            // 优先回落双写的本地副本（MinIO 不可用时仍能预览）
            if (localSource != null && Files.exists(localSource)) {
                return renderPreviewPages(localSource, fileRecord, resourceId);
            }
            try (InputStream is = minioStorageService.getObject(objectKey)) {
                if (is == null) {
                    log.warn("源文件在 MinIO 与本地均不可读，跳过预览图生成：{}",
                            fileRecord.getStoredPath());
                    return 0;
                }
                // 物化到临时文件供 PDFBox / 图片解码使用（PDFBox 需要可定位文件）
                Path temp = Files.createTempFile("minio-preview-", "." + fileRecord.getExt());
                try {
                    Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
                    return renderPreviewPages(temp, fileRecord, resourceId);
                } finally {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // 临时文件删除失败不影响业务
                    }
                }
            } catch (IOException e) {
                log.warn("从 MinIO 读取源文件失败: {}", e.getMessage());
                return 0;
            }
        }

        log.warn("源文件不存在，跳过预览图生成：{}", fileRecord.getStoredPath());
        return 0;
    }

    /**
     * 实际执行预览图渲染（本地路径或临时文件）
     */
    private int renderPreviewPages(Path source, FileRecord fileRecord, Long resourceId) {
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
            // P1-5：先校验过期时间（HMAC 已覆盖 fileId:expireAt，改任何一位都会导致签名不匹配）
            if (System.currentTimeMillis() > expireAt) {
                log.debug("下载签名已过期 fileId={}", fileId);
                return false;
            }
            // 常量时间比较，避免逐字节短路造成时序侧信道
            return MessageDigest.isEqual(
                    hmac(parts[0] + ":" + parts[1]).getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8));
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
