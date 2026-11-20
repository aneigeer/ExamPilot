package com.examprep.service;

import com.examprep.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;


/**
 * MinIO 对象存储服务（T10）
 *
 * <p><b>★ 铁律：上传永远不能失败。</b>
 * 本类所有方法<b>不抛异常打断业务</b>：MinIO 未配置 / 不可达 / 超时 / 熔断时，
 * 一律返回「失败」让调用方落本地磁盘。调用方（{@code FileStorageService}）
 * 因此可以无条件把 MinIO 当作「增强」而非「依赖」。
 *
 * <p><b>熔断器复用 AI 模块的 {@code CircuitBreaker}</b>（零新增依赖）：
 * 连续失败 3 次 → 熔断 60s，熔断期间连连接都不发起，直接走本地。
 * 这对「Docker 没起」的场景很关键——否则每次上传都要白等 3 秒超时。
 *
 * <p><b>为什么不用启动即连</b>：MinIO 客户端构造是轻量的，但 {@code bucketExists}
 * 是网络调用。若在 Bean 初始化里做，Docker 没起会导致<b>整个应用启动失败</b>，
 * 这违背「降级不影响主业务」。因此改为首次使用时懒加载 + 熔断保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final StorageProperties properties;

    /** 熔断器：与 AI 通道同构，连续失败 N 次后短路一段时间 */
    private final com.examprep.service.ai.CircuitBreaker breaker =
            new com.examprep.service.ai.CircuitBreaker("minio", 3, 60_000L);

    /** 懒加载的 MinIO 客户端；构造失败时保持 null（永久走本地） */
    private volatile MinioClient client;

    /** 客户端是否已尝试初始化过（避免每次调用都重试构造） */
    private volatile boolean clientInitialized = false;

    // ==================================================================
    // 可用性判定
    // ==================================================================

    /**
     * MinIO 当前是否可用
     *
     * <p>★ 这里<b>必须真的探测一次</b>，不能只看「配置齐全 + 客户端已构造」。
     * 客户端构造是纯本地动作（不联网），所以只判断配置的话，
     * MinIO 服务器没起时也会返回 {@code true}——
     * 实测曾出现「{@code minioAvailable=true} 但 9000 端口根本没服务」，
     * 排障时被这个假阳性带偏。
     *
     * <p>探测失败会计入熔断，因此连续失败会自动短路，后续请求不再白等超时。
     *
     * @return 真正连得上才为 true
     */
    public boolean available() {
        if (!properties.isConfigured()) {
            return false;
        }
        if (breaker.isOpen()) {
            return false;
        }
        if (client() == null) {
            return false;
        }
        return ping();
    }

    /**
     * 懒加载获取客户端
     *
     * @return 就绪的客户端；配置缺失或构造失败返回 null（调用方走本地）
     */
    public MinioClient client() {
        if (!properties.isConfigured()) {
            return null;
        }
        MinioClient local = client;
        if (local != null) {
            return local;
        }
        if (!clientInitialized) {
            synchronized (this) {
                if (!clientInitialized) {
                    // E1-14：标志必须在 client 赋值「之后」才置 true，否则并发线程会跳过同步块
                    // 并读到仍为 null 的 client，误判 MinIO 不可用而回落本地。
                    try {
                        client = buildClient();
                        log.info("[MinIO] 客户端已初始化（{}，桶 {}，双写本地 {}，超时 {}ms）",
                                properties.getEndpoint(), properties.getBucket(),
                                properties.isDualWriteLocal(), properties.getTimeoutMillis());
                    } catch (Exception e) {
                        log.warn("[MinIO] 客户端初始化失败，本对象存储降级为本地磁盘: {}", e.getMessage());
                        client = null;
                    } finally {
                        clientInitialized = true;
                    }
                }
            }
        }
        return client;
    }

    /**
     * 构建 MinIO 客户端（<b>显式设置超时</b>）
     *
     * <p>★ 关键：不设超时的话，MinIO 不可达时用的是 OkHttp 默认值（连接 10s / 读 60s），
     * 用户上传会<b>长时间卡住</b>——这直接违反「上传永远不能失败且不卡顿」的铁律。
     * 实测：不设超时时上传请求会挂到客户端超时才算结束，file_record 都没写进去。
     *
     * <p>显式 OkHttp 连接池参数（设计文档 T10 ②）：
     * {@code maxTotal=50, connectTimeout=3s, readTimeout=30s, writeTimeout=30s}。
     * 这里用配置的 {@code timeout-millis} 作为连接超时，
     * 读写超时取其 10 倍（大文件上传需要更长的写窗口）。
     */
    private MinioClient buildClient() {
        int connectTimeout = Math.max(500, properties.getTimeoutMillis());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(connectTimeout * 10L, TimeUnit.MILLISECONDS)
                .readTimeout(connectTimeout * 10L, TimeUnit.MILLISECONDS)
                .callTimeout(connectTimeout * 10L, TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(50, 5, TimeUnit.MINUTES))
                .build();
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(httpClient)
                .build();
    }

    /**
     * 确保桶存在（幂等）
     *
     * <p>MinIO 建桶需要独立权限，失败不致命——后续 putObject 会暴露真实错误。
     *
     * @return true 表示桶可用
     */
    public boolean ensureBucket() {
        MinioClient minio = client();
        if (minio == null) {
            return false;
        }
        try {
            boolean exists = minio.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists && properties.isAutoCreateBucket()) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                log.info("[MinIO] 已自动创建桶 {}", properties.getBucket());
            }
            breaker.onSuccess();
            return exists || properties.isAutoCreateBucket();
        } catch (ErrorResponseException e) {
            // 桶已存在等 4xx 属于正常情况，不计为失败
            if ("BucketAlreadyOwnedByYou".equals(e.errorResponse().code())
                    || "BucketAlreadyExists".equals(e.errorResponse().code())) {
                breaker.onSuccess();
                return true;
            }
            breaker.onFailure("ensureBucket: " + e.errorResponse().code());
            return false;
        } catch (Exception e) {
            breaker.onFailure("ensureBucket: " + e.getMessage());
            log.warn("[MinIO] 确保桶失败（降级本地）: {}", e.getMessage());
            return false;
        }
    }

    // ==================================================================
    // 对象操作（全部不抛异常，失败返回 false / null）
    // ==================================================================

    /**
     * 上传对象
     *
     * @param objectKey  对象键，如 {@code images/20261005/xxx.png}
     * @param stream     数据流（调用方负责关闭）
     * @param size       字节数
     * @param contentType MIME 类型，可为空
     * @return true 表示已写入 MinIO；false 表示调用方应落本地
     */
    public boolean putObject(String objectKey, InputStream stream, long size, String contentType) {
        MinioClient minio = client();
        if (minio == null) {
            return false;
        }
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(stream, size, -1);
            if (StringUtils.hasText(contentType)) {
                builder.contentType(contentType);
            }
            minio.putObject(builder.build());
            breaker.onSuccess();
            log.debug("[MinIO] 上传成功 {}", objectKey);
            return true;
        } catch (Exception e) {
            breaker.onFailure("putObject " + objectKey + ": " + e.getMessage());
            log.warn("[MinIO] 上传失败，降级本地磁盘: {} → {}", objectKey, e.getMessage());
            return false;
        }
    }

    /**
     * 下载对象
     *
     * @param objectKey 对象键
     * @return 输入流；不可用时返回 null（调用方回落本地）
     */
    public InputStream getObject(String objectKey) {
        MinioClient minio = client();
        if (minio == null) {
            return null;
        }
        try {
            InputStream is = minio.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            breaker.onSuccess();
            return is;
        } catch (Exception e) {
            breaker.onFailure("getObject " + objectKey + ": " + e.getMessage());
            log.warn("[MinIO] 读取失败，尝试回落本地: {} → {}", objectKey, e.getMessage());
            return null;
        }
    }

    /** 对象是否存在 */
    public boolean exists(String objectKey) {
        MinioClient minio = client();
        if (minio == null) {
            return false;
        }
        try {
            minio.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            breaker.onSuccess();
            return true;
        } catch (Exception e) {
            // 不存在是正常情况，不计入熔断
            log.debug("[MinIO] 对象不存在: {}", objectKey);
            return false;
        }
    }

    /** 删除对象（幂等） */
    public boolean removeObject(String objectKey) {
        MinioClient minio = client();
        if (minio == null) {
            return false;
        }
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            breaker.onSuccess();
            return true;
        } catch (Exception e) {
            log.warn("[MinIO] 删除失败: {} → {}", objectKey, e.getMessage());
            return false;
        }
    }

    // ==================================================================
    // 状态（供 /ai/capability 展示）
    // ==================================================================

    /**
     * 当前生效的存储提供方
     *
     * @return minio 或 local；供 App 端与使用展示「文件存在对象存储上」
     */
    public String provider() {
        return available() ? "minio" : "local";
    }

    /** 熔断剩余毫秒；0 表示未熔断 */
    public long breakerRemainingMillis() {
        return breaker.remainingOpenMillis();
    }

    /** 是否处于熔断态 */
    public boolean breakerOpen() {
        return breaker.isOpen();
    }

    /** 供测试注入：重置客户端缓存（不可用场景下复用同一实例） */
    void resetForTest() {
        this.client = null;
        this.clientInitialized = false;
    }

    /**
     * 探测 MinIO 是否可达
     *
     * <p>超时由 {@code MinioClient} 构造时的 OkHttp 连接池控制
     * （见 {@code MinioConfig}），这里只做一次轻量 bucketExists。
     */
    public boolean ping() {
        MinioClient minio = client();
        if (minio == null) {
            return false;
        }
        try {
            minio.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
            breaker.onSuccess();
            return true;
        } catch (Exception e) {
            breaker.onFailure("ping: " + e.getMessage());
            return false;
        }
    }
}
