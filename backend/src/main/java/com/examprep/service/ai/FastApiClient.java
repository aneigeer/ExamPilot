package com.examprep.service.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FastAPI AI 微服务 HTTP 客户端（T09）
 *
 * <p><b>零新增依赖</b>：用 Spring 自带 {@link RestClient}，与访问大模型的方式一致。
 *
 * <p><b>核心设计：所有失败都必须被吞掉并转成「降级」</b>。
 * 本类<b>不向业务层抛异常</b>，只通过返回值表达状态：
 * <ul>
 *   <li>正常 → 返回结果，{@code available=true}</li>
 *   <li>超时 / 5xx / 网络异常 / 熔断 → 返回空结果，{@code available=false}，
 *       计入熔断，<b>上层自动降级到 ngram 通道，问答接口仍 200</b></li>
 * </ul>
 * 这是整个扩展的<b>安全底线</b>：FastAPI 全挂 = 系统退化为已交付的 Java 单体 AI 形态，零功能损失。
 *
 * <p><b>契约</b>（与 {@code ai-service/app/schemas.py} 严格对应）：
 * <pre>
 *   POST {prefix}/search        {query, top_k, filters} → {hits:[{chunk_id,score,payload}], took_ms, dim}
 *   GET  {prefix}/health                                → {status, embedding, qdrant, ocr, llm}
 *   POST {prefix}/index/sync   {chunks:[...]}            → {synced, skipped, failed, took_ms}
 *   GET  {prefix}/index/status                           → {collection, chunk_synced, status, ...}
 *   POST {prefix}/index/rebuild {model_id?, dim?}        → {status, collection, model_id, dim}
 * </pre>
 */
@Slf4j
@Component
public class FastApiClient {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient searchClient;
    private final RestClient healthClient;
    private final RestClient indexClient;
    private final CircuitBreaker breaker;

    /** 健康检查结果缓存（避免每次问答都打一次 health） */
    private volatile HealthSnapshot healthCache;

    /**
     * 最近一次 {@code /index/sync} 失败的原因
     *
     * <p>用于排障：状态位不更新的根因往往藏在被吞掉的异常里，
     * 没有这个字段就只能看到「状态位还是 0」，看不出到底哪一步失败。
     */
    private volatile String lastSyncError;
    private final AtomicLong healthCheckCount = new AtomicLong();

    public FastApiClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AiProperties.FastApi cfg = properties.getFastapi();
        this.searchClient = buildClient(cfg.normalizedBaseUrl(), cfg.getSearchTimeoutMillis());
        this.healthClient = buildClient(cfg.normalizedBaseUrl(), cfg.getHealthTimeoutMillis());
        this.indexClient = buildClient(cfg.normalizedBaseUrl(), cfg.getIndexTimeoutMillis());
        this.breaker = new CircuitBreaker("fastapi",
                cfg.getCircuitBreakerThreshold(), cfg.getCircuitBreakerOpenMillis());
        log.info("[AI] FastAPI 客户端已注册（{}，向量通道开关 {}，检索超时 {}ms）",
                cfg.normalizedBaseUrl(), cfg.isEnabled(), cfg.getSearchTimeoutMillis());
    }

    // ==================================================================
    // 可用性
    // ==================================================================

    /**
     * 向量通道当前是否可用
     *
     * <p>三个条件全满足：配置开启、未熔断、健康检查通过。
     * 任何一个不满足都<b>静默返回 false</b>，不做任何网络调用（避免每次问答白等一次超时）。
     */
    public boolean available() {
        if (!properties.getVector().isEnabled()) {
            return false;
        }
        if (!properties.getFastapi().isEnabled()) {
            return false;
        }
        if (!breaker.canUse()) {
            return false;
        }
        return health().ready();
    }

    /** 熔断器（capability 展示降级状态用） */
    public CircuitBreaker breaker() {
        return breaker;
    }

    // ==================================================================
    // 向量检索
    // ==================================================================

    /**
     * 向量检索
     *
     * @param query       用户问题原文
     * @param topK        召回条数
     * @param subjectId   科目过滤，null 不过滤
     * @param knowledgeId 知识点过滤，null 不过滤
     * @return 命中列表；不可用 / 无命中 / 出错时返回<b>空 list，不抛异常</b>
     */
    public List<VectorHit> search(String query, int topK, Long subjectId, Long knowledgeId) {
        if (!properties.getVector().isEnabled() || !properties.getFastapi().isEnabled()) {
            return List.of();
        }
        if (!breaker.canUse()) {
            log.debug("[AI] FastAPI 处于熔断状态，跳过向量检索");
            return List.of();
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        if (subjectId != null) {
            filters.put("subject_id", subjectId);
        }
        if (knowledgeId != null) {
            filters.put("knowledge_id", knowledgeId);
        }
        // ★ 强制只检索有效块，与 ngram 通道的 status='ACTIVE' 口径保持一致
        filters.put("status", "ACTIVE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("top_k", Math.max(1, topK));
        body.put("filters", filters);

        long start = System.currentTimeMillis();
        try {
            String raw = searchClient.post()
                    .uri(properties.getFastapi().path("/search"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            List<VectorHit> hits = parseHits(raw);
            log.info("[AI] 向量检索命中 {} 块，耗时 {} ms", hits.size(),
                    System.currentTimeMillis() - start);
            return hits;
        } catch (RestClientException e) {
            breaker.onFailure(e.getMessage());
            log.warn("[AI] 向量检索失败（静默降级到 ngram 通道）: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            breaker.onFailure(e.getMessage());
            log.warn("[AI] 向量检索响应解析失败（静默降级到 ngram 通道）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 {@code {hits:[{chunk_id,score,payload}], took_ms, dim}} */
    private List<VectorHit> parseHits(String raw) throws Exception {
        List<VectorHit> hits = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return hits;
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode hitsNode = root.path("hits");
        if (!hitsNode.isArray()) {
            return hits;
        }
        for (JsonNode node : hitsNode) {
            Long chunkId = node.hasNonNull("chunk_id") ? node.get("chunk_id").asLong() : null;
            if (chunkId == null) {
                continue;
            }
            double score = node.path("score").asDouble(0.0);
            double rerankScore = node.path("payload").path("rerank_score").asDouble(score);
            Map<String, Object> payload = new LinkedHashMap<>();
            JsonNode payloadNode = node.path("payload");
            payloadNode.fields().forEachRemaining(e -> payload.put(e.getKey(),
                    e.getValue().isNumber() ? e.getValue().asDouble()
                            : e.getValue().asText(null)));
            hits.add(new VectorHit(chunkId, score, rerankScore, payload));
        }
        return hits;
    }

    // ==================================================================
    // 健康检查
    // ==================================================================

    /**
     * 健康检查（带缓存）
     *
     * @return 健康快照；不可用时 {@code ready=false}，<b>不抛异常</b>
     */
    public HealthSnapshot health() {
        if (!properties.getFastapi().isEnabled()) {
            return HealthSnapshot.off("FastAPI 通道未开启（app.ai.fastapi.enabled=false）");
        }
        if (!breaker.canUse()) {
            return HealthSnapshot.off("FastAPI 熔断中，剩余 "
                    + breaker.remainingOpenMillis() + " ms");
        }
        HealthSnapshot cached = healthCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.checkedAtMillis() < properties.getFastapi().getHealthCacheMillis()) {
            return cached;
        }
        try {
            String raw = healthClient.get()
                    .uri(properties.getFastapi().path("/health"))
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            HealthSnapshot snapshot = parseHealth(raw);
            healthCache = snapshot;
            healthCheckCount.incrementAndGet();
            return snapshot;
        } catch (Exception e) {
            breaker.onFailure(e.getMessage());
            // ★ 失败结果【不缓存】——这是本项目实测踩出来的坑：
            //   若把失败快照也按 health-cache-millis 缓存，FastAPI 恢复后仍要等缓存过期
            //   才能重新探测；叠加熔断 60s 窗口后，**恢复耗时被放大到分钟级**，
            //   现场表现就是「服务明明好了，系统却还在降级」。
            //   失败不缓存 → 下一次调用立即重探；探测频率由熔断器兜住
            //   （连续失败 3 次即熔断，不会打爆对方）。
            healthCache = null;
            return HealthSnapshot.off("健康检查失败：" + e.getMessage());
        }
    }

    private HealthSnapshot parseHealth(String raw) throws Exception {
        if (!StringUtils.hasText(raw)) {
            return HealthSnapshot.off("健康检查返回空");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode embedding = root.path("embedding");
        boolean embeddingReady = embedding.path("ready").asBoolean(false);
        boolean qdrantReady = root.path("qdrant").path("ready").asBoolean(false);
        boolean ocrReady = root.path("ocr").path("ready").asBoolean(false);
        boolean llmReady = root.path("llm").path("ready").asBoolean(false);
        String status = root.path("status").asText("unknown");
        int dim = embedding.path("dim").asInt(0);
        String modelId = embedding.path("model_id").asText("");

        // ★ 只有「embedding + qdrant 都就绪」才算向量通道可用：
        // 少任一个，检索都必然失败，此时应尽早判定不可用并走 ngram。
        boolean ready = embeddingReady && qdrantReady;
        String reason = ready ? "" : "embedding=" + embeddingReady + " qdrant=" + qdrantReady
                + "（向量检索需二者同时就绪）";
        return new HealthSnapshot(true, ready, status, embeddingReady, qdrantReady,
                ocrReady, llmReady, dim, modelId, reason, System.currentTimeMillis());
    }

    // ==================================================================
    // 索引同步
    // ==================================================================

    /**
     * 大模型代理通道是否就绪
     *
     * <p>与检索通道<b>独立判定</b>：embedding / Qdrant 挂了不代表大模型代理也挂了。
     * 这样「只有 LLM 代理可用」时（例如向量库故障但云端模型正常）仍能正常生成答案。
     *
     * <p>复用 health 快照（带缓存），不额外发起网络请求。
     */
    public boolean isLlmReady() {
        return health().isLlmReady();
    }

    /**
     * 大模型代理：POST {@code /v1/llm}
     *
     * <p>供 {@link FastApiLlmClient} 调用。失败时抛
     * {@link AiUnavailableException}，由上层降级到下一个客户端
     * （直连 SDK → 抽取式），<b>接口恒 200</b>。
     *
     * <p>与检索 / 索引通道<b>共用同一个熔断器</b>：FastAPI 进程整体不可用时，
     * 三条通道一起短路，避免「检索降级了但生成还在傻等」的不一致。
     *
     * @param jsonBody 已序列化好的 {@code LlmRequest} JSON
     * @return 原始响应体（{@code LlmResponse} JSON）
     * @throws AiUnavailableException 服务不可用 / 超时 / 5xx / 响应损坏
     */
    public String postLlm(String jsonBody) {
        if (!properties.getFastapi().isEnabled()) {
            throw new AiUnavailableException("FastAPI 未启用");
        }
        if (!breaker.canUse()) {
            throw new AiUnavailableException("FastAPI 处于熔断状态（剩余 "
                    + breaker.remainingOpenMillis() + " ms）");
        }
        try {
            String resp = indexClient.post()
                    .uri(properties.getFastapi().path("/llm"))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);
            if (resp == null || resp.isBlank()) {
                breaker.onFailure("llm 空响应");
                throw new AiUnavailableException("FastAPI 大模型代理返回空响应");
            }
            breaker.onSuccess();
            return resp;
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            breaker.onFailure("llm: " + e.getMessage());
            throw new AiUnavailableException("FastAPI 大模型代理调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 当前不可用的<b>具体原因</b>（用于排障与降级文案）
     *
     * <p>★ 这个方法存在的理由：{@link #syncIndex()} 不可用时统一返回 {@code null}，
     * 而返回 null 有三种完全不同的原因——未启用 / 熔断中 / 网络异常。
     * 如果都报同一句「FastAPI 不可用」，排障时会误判成「状态位没回写」，
     * 而真实原因往往只是<b>被 health 探测失败连带触发了熔断</b>
     * （三条通道共用一个熔断器，见类注释）。
     *
     * @return 中文原因描述；可用时返回空串
     */
    public String unavailableReason() {
        if (!properties.getFastapi().isEnabled()) {
            return "FastAPI 通道未开启（app.ai.fastapi.enabled=false）";
        }
        if (!breaker.canUse()) {
            return "FastAPI 处于熔断状态（剩余 " + breaker.remainingOpenMillis()
                    + " ms；连续失败 " + breaker.consecutiveFailures() + " 次）";
        }
        HealthSnapshot snapshot = health();
        if (!snapshot.reachable()) {
            return "FastAPI 不可达：" + snapshot.reason();
        }
        return "FastAPI 可用但索引同步调用失败（详见日志 [AI] 向量索引同步失败）";
    }

    /**
     * 推送待向量化语料块到 FastAPI
     *
     * @param chunks 待推送块（正文只用于算向量，不写入 Qdrant payload）
     * @return 同步结果；不可用 / 出错时返回 {@code null}（调用方据此跳过状态更新）
     */
    public IndexSyncResult syncIndex(List<IndexChunkPayload> chunks) {
        if (!properties.getFastapi().isEnabled() || chunks == null || chunks.isEmpty()) {
            return null;
        }
        if (!breaker.canUse()) {
            return null;
        }
        try {
            String raw = indexClient.post()
                    .uri(properties.getFastapi().path("/index/sync"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chunks", chunks))
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            JsonNode root = objectMapper.readTree(raw);
            IndexSyncResult result = new IndexSyncResult(
                    root.path("synced").asInt(0),
                    root.path("skipped").asInt(0),
                    root.path("failed").asInt(0),
                    root.path("took_ms").asInt(0));
            log.info("[AI] 向量索引同步完成：synced={} skipped={} failed={}",
                    result.synced(), result.skipped(), result.failed());
            return result;
        } catch (RestClientResponseException e) {
            // ★ 4xx/5xx 必须把状态码与响应体带出来：契约不匹配（如字段命名不符）
            //   会被 RestClient 归成 BadRequest/SQL 错误，全部塞进异常 message 后
            //   只留一行 warn，导致「状态位永远不更新」却看不出原因。
            String body = e.getResponseBodyAsString();
            String detail = body != null && body.length() > 500 ? body.substring(0, 500) : body;
            breaker.onFailure("HTTP " + e.getStatusCode());
            log.warn("[AI] 向量索引同步被拒绝 HTTP {}：{}（不影响主业务，状态位留待下次对账重试）",
                    e.getStatusCode(), detail);
            lastSyncError = "HTTP " + e.getStatusCode() + " " + detail;
            return null;
        } catch (Exception e) {
            breaker.onFailure(e.getMessage());
            log.warn("[AI] 向量索引同步失败（不影响主业务，状态位留待下次对账重试）: {}",
                    e.getMessage());
            lastSyncError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    /** 最近一次同步失败的原因（供排障；无失败记录时为空串） */
    public String lastSyncError() {
        return lastSyncError == null ? "" : lastSyncError;
    }

    /** 触发 FastAPI 侧全量重建索引（换 embedding 模型时必须调用） */
    public boolean rebuildIndex(String modelId, Integer dim) {
        if (!properties.getFastapi().isEnabled() || !breaker.canUse()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (StringUtils.hasText(modelId)) {
                body.put("model_id", modelId);
            }
            if (dim != null && dim > 0) {
                body.put("dim", dim);
            }
            String raw = indexClient.post()
                    .uri(properties.getFastapi().path("/index/rebuild"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            JsonNode root = objectMapper.readTree(raw);
            boolean ok = "READY".equalsIgnoreCase(root.path("status").asText(""));
            log.info("[AI] 向量索引重建请求完成，status={} collection={}",
                    root.path("status").asText("?"), root.path("collection").asText("?"));
            return ok;
        } catch (Exception e) {
            breaker.onFailure(e.getMessage());
            log.warn("[AI] 向量索引重建失败: {}", e.getMessage());
            return false;
        }
    }

    /** 查询 FastAPI 侧索引状态（与 MySQL {@code ai_vector_index_meta} 对账） */
    public IndexStatus indexStatus() {
        if (!properties.getFastapi().isEnabled() || !breaker.canUse()) {
            return null;
        }
        try {
            String raw = healthClient.get()
                    .uri(properties.getFastapi().path("/index/status"))
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            JsonNode root = objectMapper.readTree(raw);
            return new IndexStatus(
                    root.path("collection").asText(""),
                    root.path("model_id").asText(""),
                    root.path("config_model_id").asText(""),
                    root.path("dim").asInt(0),
                    root.path("chunk_synced").asInt(0),
                    root.path("status").asText("UNKNOWN"),
                    root.path("message").asText(""));
        } catch (Exception e) {
            log.debug("[AI] 查询向量索引状态失败: {}", e.getMessage());
            return null;
        }
    }

    private static RestClient buildClient(String baseUrl, int timeoutMillis) {
        int timeout = Math.max(200, timeoutMillis);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    // ==================================================================
    // 传输对象
    // ==================================================================

    /**
     * 向量命中
     *
     * @param chunkId     ai_chunk 主键（<b>回表取正文的唯一依据</b>）
     * @param score       Qdrant 余弦相似度 [-1,1]
     * @param rerankScore FastAPI 侧轻量重排分（参考值，Java 侧五因子才是权威）
     * @param payload     Qdrant payload 元数据（不含正文）
     */
    public record VectorHit(Long chunkId, double score, double rerankScore,
                            Map<String, Object> payload) {

        public String title() {
            Object t = payload == null ? null : payload.get("title");
            return t == null ? "" : String.valueOf(t);
        }

        public String refType() {
            Object t = payload == null ? null : payload.get("ref_type");
            return t == null ? null : String.valueOf(t);
        }

        public Long refId() {
            Object v = payload == null ? null : payload.get("ref_id");
            if (v == null) {
                return null;
            }
            try {
                return v instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public Long knowledgeId() {
            Object v = payload == null ? null : payload.get("knowledge_id");
            if (v == null) {
                return null;
            }
            try {
                return v instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public Long subjectId() {
            Object v = payload == null ? null : payload.get("subject_id");
            if (v == null) {
                return null;
            }
            try {
                return v instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public String sourceType() {
            Object t = payload == null ? null : payload.get("source_type");
            return t == null ? null : String.valueOf(t);
        }

        public long hotCount() {
            Object v = payload == null ? null : payload.get("hot_count");
            if (v == null) {
                return 0L;
            }
            try {
                return v instanceof Number n ? n.longValue() : (long) Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    /**
     * 健康快照
     *
     * @param reachable     HTTP 层是否可达
     * @param ready         向量通道是否就绪（embedding + qdrant 同时就绪）
     * @param status        服务自报状态 ok / degraded
     * @param ocrReady      OCR 是否就绪（T11 用）
     * @param llmReady      大模型代理是否就绪
     * @param dim           embedding 维度
     * @param modelId       embedding 模型标识（换模型必须重建索引）
     * @param reason        不可用原因；可用时为空串
     * @param checkedAtMillis 检查时间戳（缓存用）
     */
    public record HealthSnapshot(boolean reachable, boolean ready, String status,
                                 boolean embeddingReady, boolean qdrantReady,
                                 boolean ocrReady, boolean llmReady,
                                 int dim, String modelId, String reason,
                                 long checkedAtMillis) {

        /**
         * 大模型代理通道是否就绪
         *
         * <p>与检索通道<b>独立判定</b>：检索（embedding + Qdrant）挂了不代表
         * 大模型代理也挂了，反之亦然。这样「只有 LLM 代理可用」时仍能正常生成。
         */
        public boolean isLlmReady() {
            return reachable && llmReady;
        }

        static HealthSnapshot off(String reason) {
            return new HealthSnapshot(false, false, "UNREACHABLE",
                    false, false, false, false, 0, "", reason, System.currentTimeMillis());
        }
    }

    /** 索引同步结果 */
    public record IndexSyncResult(int synced, int skipped, int failed, int tookMs) {
    }

    /** FastAPI 侧索引状态 */
    public record IndexStatus(String collection, String modelId, String configModelId,
                              int dim, int chunkSynced, String status, String message) {

        /**
         * ★ 模型是否不匹配
         *
         * <p>换过 embedding 模型而未重建索引时，collection 里新旧向量混存会导致检索彻底错乱。
         * 必须在代码层拦住并触发全量重建。
         */
        public boolean modelMismatch() {
            return StringUtils.hasText(modelId) && StringUtils.hasText(configModelId)
                    && !modelId.equals(configModelId);
        }
    }

    /**
     * 推送待向量化的语料块
     *
     * @param chunkId          ai_chunk 主键
     * @param content          切块文本（<b>只用于算向量，不写入 payload</b>，正文唯一来源是 MySQL）
     * @param sourceType       来源类型
     * @param sourceId         来源业务 ID
     * @param title            标题
     * @param subjectId        科目
     * @param knowledgeId      知识点
     * @param difficulty       难度
     * @param refType          跳转类型
     * @param refId            跳转业务 ID
     * @param pageNo           PDF 页码
     * @param status           语料状态
     * @param contentHash      内容哈希（Python 侧据此幂等去重）
     * @param sourceUpdatedAt  来源更新时间
     * @param hotCount         热度
     */
    /**
     * ★ 字段名必须是 snake_case：Python 侧 {@code IndexChunk} 的契约是
     *   {@code chunk_id / source_type / source_id / subject_id / knowledge_id /
     *     ref_type / ref_id / page_no / content_hash / source_updated_at}。
     *   record 组件默认按驼峰序列化，FastAPI 会返回
     *   {@code 400 chunks.0.chunk_id Field required}，导致向量索引永远推送失败、
     *   检索通道永久降级。整条记录统一用 snake_case 策略序列化。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IndexChunkPayload(Long chunkId, String content, String sourceType,
                                    Long sourceId, String title, Long subjectId,
                                    Long knowledgeId, Integer difficulty, String refType,
                                    Long refId, Integer pageNo, String status,
                                    String contentHash,
                                    // ★ 必须显式指定字符串格式：JavaTimeModule 默认把 LocalDateTime
                                    //   序列化成数组 [2026,10,2,...]，而 Python 侧契约是 ISO 字符串，
                                    //   会导致 FastAPI 返回 400（Input should be a valid string）。
                                    @JsonFormat(shape = JsonFormat.Shape.STRING,
                                               pattern = "yyyy-MM-dd'T'HH:mm:ss")
                                    LocalDateTime sourceUpdatedAt,
                                    Integer hotCount) {
    }
}
