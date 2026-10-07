package com.examprep.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.common.Result;
import com.examprep.config.StorageProperties;
import com.examprep.entity.FileRecord;
import com.examprep.mapper.FileRecordMapper;
import com.examprep.service.MinioStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象存储管理接口（T10）
 *
 * <p>用于把存量本地文件回填进 MinIO。<b>幂等</b>：已迁移的（{@code minio://} 前缀）直接跳过，
 * 重复调用 {@code migrated=0, skipped=N}。
 *
 * <p>迁移完成后<b>不删除本地文件</b>——双轨期保留回落能力。
 */
@Slf4j
@Tag(name = "14. 对象存储管理", description = "MinIO 双轨存储（管理端）")
@RestController
@RequestMapping("/admin/storage")
// ★ 不要加 @RequiredArgsConstructor：本类已显式定义构造器（第 49 行起，用于注入
//   ${app.upload.dir} 并转成绝对路径）。若同时存在 Lombok 生成的构造器与显式构造器，
//   Spring 无法判定注入哪个，会抛 No default constructor found 导致整个应用启动失败。
public class StorageAdminController {

    private final FileRecordMapper fileRecordMapper;
    private final MinioStorageService minioStorageService;
    private final StorageProperties storageProperties;

    private final Path uploadRoot;

    public StorageAdminController(FileRecordMapper fileRecordMapper,
                                  MinioStorageService minioStorageService,
                                  StorageProperties storageProperties,
                                  @Value("${app.upload.dir:./uploads}") String uploadDir) {
        this.fileRecordMapper = fileRecordMapper;
        this.minioStorageService = minioStorageService;
        this.storageProperties = storageProperties;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Operation(summary = "存储状态",
            description = "**访问级别：ROLE:AUDITOR / ROLE:ADMIN**。当前生效的存储提供方与熔断状态。")
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", minioStorageService.provider());
        data.put("minioConfigured", storageProperties.isConfigured());
        data.put("minioAvailable", minioStorageService.available());
        data.put("bucket", storageProperties.getBucket());
        data.put("endpoint", storageProperties.getEndpoint());
        data.put("dualWriteLocal", storageProperties.isDualWriteLocal());
        data.put("circuitOpen", minioStorageService.breakerOpen());
        data.put("circuitRemainingMillis", minioStorageService.breakerRemainingMillis());
        return Result.success(data);
    }

    @Operation(summary = "迁移存量文件到 MinIO",
            description = """
                    **访问级别：ROLE:ADMIN**。把 `file_record` 中仍指向本地磁盘的文件回填到 MinIO。

                    - **幂等**：已是 `minio://` 前缀的记录直接计入 `skipped`
                    - **不删本地**：迁移后本地副本保留，双轨期可回落
                    - **MinIO 不可用时**：`migrated=0`，`failed` 记数，接口仍 200
                    """)
    @PostMapping("/migrate")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public Result<Map<String, Object>> migrate() {
        long start = System.currentTimeMillis();
        int total = 0;
        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        if (!minioStorageService.available()) {
            log.warn("[存储] MinIO 不可用，跳过迁移（不影响功能，上传会自动落本地）");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", 0);
            data.put("migrated", 0);
            data.put("skipped", 0);
            data.put("failed", 0);
            data.put("message", "MinIO 不可用（未配置或熔断中），已跳过迁移；上传会自动落本地磁盘");
            return Result.success(data);
        }
        minioStorageService.ensureBucket();

        List<FileRecord> all = fileRecordMapper.selectList(
                Wrappers.<FileRecord>lambdaQuery().eq(FileRecord::getIsDeleted, 0));
        for (FileRecord record : all) {
            total++;
            // 幂等：已迁移的直接跳过
            if (StorageProperties.isMinioPath(record.getStoredPath())) {
                skipped++;
                continue;
            }
            Path local = uploadRoot.resolve(record.getStoredPath()).normalize();
            if (!local.startsWith(uploadRoot) || !Files.isRegularFile(local)) {
                // 本地文件已不在（可能已清理），跳过而不是报错
                skipped++;
                continue;
            }
            try (InputStream is = Files.newInputStream(local)) {
                boolean ok = minioStorageService.putObject(
                        record.getStoredPath(), is, Files.size(local), record.getMimeType());
                if (ok) {
                    FileRecord update = new FileRecord();
                    update.setId(record.getId());
                    update.setStoredPath(storageProperties.toStoredPath(record.getStoredPath()));
                    fileRecordMapper.updateById(update);
                    migrated++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("[存储] 迁移文件 #{} 失败: {}", record.getId(), e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("migrated", migrated);
        data.put("skipped", skipped);
        data.put("failed", failed);
        data.put("costMillis", System.currentTimeMillis() - start);
        log.info("[存储] 迁移完成 {}", data);
        return Result.success("迁移完成", data);
    }
}
