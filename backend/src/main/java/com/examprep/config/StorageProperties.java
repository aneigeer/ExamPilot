package com.examprep.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（T10，{@code app.ai.storage.*}）
 *
 * <p>E1-09：application.yml 的 storage 块位于 {@code app.ai} 下（键为 {@code app.ai.storage.*}），
 * 旧前缀 {@code app.storage} 导致 MinIO 配置全部不生效、只能取本类 Java 默认值。
 * 此处改为与 yml 一致；{@code AiProperties.Storage} 绑定同一组键仅用于能力探测展示，互不影响。
 *
 * <p><b>设计铁律：上传永远不能失败。</b>
 * MinIO 不可用（Docker 没起 / 网络不通 / 桶建不出来）时自动落本地磁盘，
 * 上传功能完全不受影响。{@code enabled=false} 时行为与引入 MinIO 之前完全一致。
 *
 * <p>密钥默认值留空（对齐 {@code app.jwt.secret} 的安全规范），空值即视为未启用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.storage")
public class StorageProperties {

    /** 是否启用 MinIO。false = 纯本地磁盘（默认，保证零风险） */
    private boolean enabled = false;

    /**
     * 存储提供方：minio / local
     *
     * <p>用于对外展示（{@code /ai/capability} 的 {@code storageProvider}）与排障，
     * 实际走哪条路由 {@link #enabled} + 运行时可达性共同决定。
     */
    private String provider = "local";

    /** MinIO 服务地址 */
    private String endpoint = "http://127.0.0.1:9000";

    /** 访问密钥；为空视为未启用 MinIO */
    private String accessKey = "";

    /** 密钥；为空视为未启用 MinIO */
    private String secretKey = "";

    /** 桶名 */
    private String bucket = "exam-prep";

    /** 启动时自动建桶（幂等，MinIO 建桶需要单独权限） */
    private boolean autoCreateBucket = true;

    /** 连接 / 读写超时（毫秒） */
    private int timeoutMillis = 3000;

    /** 连续失败多少次熔断（熔断期间直接走本地，不再尝试连 MinIO） */
    private int circuitBreakerThreshold = 3;

    /** 熔断持续时间（毫秒） */
    private long circuitBreakerOpenMillis = 60_000L;

    /**
     * 双写：MinIO 为主时，是否同时在本地磁盘留一份
     *
     * <p>迁移期建议 true —— 好处是 MinIO 挂掉时本地仍有副本可读回落；
     * 代价是磁盘占用翻倍（当前 uploads 仅 128KB，可忽略）。
     */
    private boolean dualWriteLocal = true;

    /** 存储键前缀，用于 {@code file_record.stored_path} 识别（{@code minio://bucket/key}） */
    public static final String SCHEME = "minio://";

    /** 是否具备启用 MinIO 的最小配置（两个密钥都必须非空） */
    public boolean isConfigured() {
        return enabled
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()
                && endpoint != null && !endpoint.isBlank();
    }

    /**
     * 构造 {@code stored_path} 的 MinIO 形式
     *
     * @param objectKey 对象键，如 {@code images/20261005/xxx.png}
     */
    public String toStoredPath(String objectKey) {
        return SCHEME + bucket + "/" + objectKey;
    }

    /** 判断 {@code stored_path} 是否为 MinIO 路径 */
    public static boolean isMinioPath(String storedPath) {
        return storedPath != null && storedPath.startsWith(SCHEME);
    }

    /**
     * 从 {@code minio://bucket/key} 中解析出对象键
     *
     * @return 对象键；非 MinIO 路径返回 null
     */
    public static String parseObjectKey(String storedPath) {
        if (!isMinioPath(storedPath)) {
            return null;
        }
        String rest = storedPath.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        return slash >= 0 && slash < rest.length() - 1 ? rest.substring(slash + 1) : null;
    }
}
