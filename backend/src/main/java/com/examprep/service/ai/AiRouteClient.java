package com.examprep.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义路由客户端（A1 {@code POST /v1/route} 的 Java 侧）
 *
 * <p><b>本类的第一职责不是「调通」，而是「调不通也不能拖垮问答」</b>：
 * 路由只是「决定检索哪几类来源」的优化项，它挂掉时正确答案依然存在于库里。
 * 所以这里**所有路径都不抛异常**，失败一律返回 {@link RouteResult#fallback(String)}，
 * 由调用方按「不过滤来源」继续检索——与项目既有的五层降级口径一致。
 *
 * <p><b>四条快速返回（不发起 HTTP）</b>：
 * <ol>
 *   <li>配置关闭（{@code app.ai.route.enabled=false}，默认）→ 行为与未引入路由完全一致；</li>
 *   <li>问题过短（&lt; {@code min-question-length}）→ 路由信息量不足，不值得一次往返；</li>
 *   <li>命中本地缓存（LRU + TTL）→ 用户重试/多人问相似题时不重复打路由；</li>
 *   <li>熔断中 → 直接降级（复用 {@link CircuitBreaker}，不另造熔断器）。</li>
 * </ol>
 *
 * <p><b>超时为什么给 300ms</b>：本地 bge 路由 P95 约 20ms，300ms 已是 15 倍余量；
 * 路由位于「用户点了发送之后」的关键路径上，宁可降级也不能让体感变慢。
 *
 * <p><b>为什么单独建一个 300ms 的 RestClient 而不是复用 FastApiClient 的</b>：
 * 后者各通道超时是 1.2s / 60s 级别（检索与索引推送），路由用它会一秒起步；
 * 但<b>熔断器复用同一个 {@link CircuitBreaker} 类与同一组阈值参数</b>，不新写熔断逻辑。
 */
@Slf4j
@Component
public class AiRouteClient {

    /** 内部令牌头（与 {@code InternalTokenFilter.HEADER} 一致，Java↔Python 共用） */
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** 默认 skill：路由不可用时的兜底（课程问答，覆盖面最广） */
    public static final String DEFAULT_SKILL = "COURSE_QA";

    /** 合法 skill 枚举（Python 侧契约，越界值视为契约漂移 → 降级） */
    private static final List<String> KNOWN_SKILLS = List.of(
            "COURSE_QA", "COURSEWARE_QA", "EXERCISE_EXPLAIN", "RESOURCE_RECOMMEND",
            "SMALLTALK", "STUDY_PLAN", "WRONG_BOOK_REVIEW", "GRADING", "INTERVIEW");

    /** 合法来源类型（用于过滤脏值，避免把未知类型传进 SQL） */
    private static final List<String> KNOWN_SOURCE_TYPES = List.of(
            "QUESTION", "KNOWLEDGE", "RESOURCE", "COURSEWARE");

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient routeClient;
    private final CircuitBreaker breaker;
    private final RouteCache cache;
    private final AtomicLong httpCalls = new AtomicLong();
    private final AtomicLong degradedCalls = new AtomicLong();

    public AiRouteClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        AiProperties.Route cfg = properties.getRoute();
        AiProperties.FastApi fastapi = properties.getFastapi();
        // 连接与读超时分开设置：路由要的是「快速失败」，不是「等它想清楚」
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(50, cfg.getConnectTimeoutMillis())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(50, cfg.getTimeoutMillis())));
        this.routeClient = RestClient.builder()
                .baseUrl(fastapi.normalizedBaseUrl())
                .requestFactory(factory)
                .build();
        // 熔断阈值复用 fastapi 那组（连续 3 次失败 → 60s），不引入第二套参数
        this.breaker = new CircuitBreaker("route",
                fastapi.getCircuitBreakerThreshold(), fastapi.getCircuitBreakerOpenMillis());
        this.cache = new RouteCache(cfg.getCacheMaxEntries(), cfg.getCacheTtlMillis());
        log.info("[Route] 路由客户端已注册（{}，enabled={}，读超时 {}ms / 连接 {}ms）",
                fastapi.normalizedBaseUrl(), cfg.isEnabled(),
                cfg.getTimeoutMillis(), cfg.getConnectTimeoutMillis());
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /**
     * 路由一个问题（<b>永不抛异常</b>）
     *
     * @param question  用户问题原文
     * @param subjectId 科目过滤，可为 null（Java 侧目前不传，预留）
     * @return 路由结果；任何异常/关闭/过短/熔断/缓存过期都会返回「默认路由 + degraded=true」
     */
    public RouteResult route(String question, Long subjectId) {
        AiProperties.Route cfg = properties.getRoute();
        if (!cfg.isEnabled()) {
            return count(RouteResult.fallback("ROUTER_DISABLED"));
        }
        String normalized = normalize(question);
        if (normalized.length() < Math.max(1, cfg.getMinQuestionLength())) {
            return count(RouteResult.fallback("TOO_SHORT"));
        }
        RouteResult cached = cache.get(normalized);
        if (cached != null) {
            return cached;
        }
        if (!breaker.canUse()) {
            // 熔断期间不再发起连接：这一条决定了「路由服务挂了会不会把问答拖慢」
            return count(RouteResult.fallback("CIRCUIT_OPEN"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        if (subjectId != null) {
            body.put("subject_id", subjectId);
        }

        long start = System.currentTimeMillis();
        try {
            httpCalls.incrementAndGet();
            String raw = routeClient.post()
                    .uri(properties.getFastapi().path("/route"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String token = properties.getInternalToken();
                        if (StringUtils.hasText(token)) {
                            headers.set(INTERNAL_TOKEN_HEADER, token);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
            breaker.onSuccess();
            RouteResult parsed = parse(raw);
            cache.put(normalized, parsed);
            log.info("[Route] skill={} sourceTypes={} confidence={} degraded={} 耗时 {}ms",
                    parsed.skill(), parsed.sourceTypes(), parsed.confidence(),
                    parsed.degraded(), System.currentTimeMillis() - start);
            return parsed;
        } catch (RestClientResponseException e) {
            // 4xx/5xx：契约或服务问题，计数并降级
            breaker.onFailure("HTTP " + e.getStatusCode().value());
            log.warn("[Route] 路由服务返回 {}，降级为默认路由继续检索：{}",
                    e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            return count(RouteResult.fallback("HTTP_ERROR"));
        } catch (Exception e) {
            // 超时 / 连不上 / 解析失败：一律降级
            breaker.onFailure(e.getMessage());
            log.warn("[Route] 路由调用失败（降级为默认路由继续检索）: {}", e.getMessage());
            return count(RouteResult.fallback("CALL_FAILED"));
        }
    }

    /** 熔断状态（供 capability / 排障展示） */
    public CircuitBreaker breaker() {
        return breaker;
    }

    /** 累计真实 HTTP 调用次数（缓存命中与快速返回不计入；便于验证缓存是否生效） */
    public long httpCalls() {
        return httpCalls.get();
    }

    /** 累计降级次数（含关闭/过短/熔断/失败；便于判断是否该摘掉路由） */
    public long degradedCalls() {
        return degradedCalls.get();
    }

    // ==================================================================
    // 解析与校验
    // ==================================================================

    /**
     * 解析响应并做**契约校验**
     *
     * <p>三类漂移一律当降级处理，绝不把脏值透传给检索层：
     * <ul>
     *   <li>{@code skill} 不在枚举里 → 无法确定用哪套提示词，降级；</li>
     *   <li>{@code source_types} 含未知类型 → 过滤掉未知项（SQL 只认白名单内的值）；</li>
     *   <li>{@code source_types} 为空/缺失 → 语义是「不过滤」，返回空 list（不是 null）。</li>
     * </ul>
     */
    RouteResult parse(String raw) throws Exception {
        if (!StringUtils.hasText(raw)) {
            return RouteResult.fallback("EMPTY_RESPONSE");
        }
        RouteResponse response = objectMapper.readValue(raw, RouteResponse.class);
        String skill = response.skill == null ? "" : response.skill.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_SKILLS.contains(skill)) {
            log.warn("[Route] 未知 skill「{}」，按默认路由处理（契约漂移）", response.skill);
            return RouteResult.fallback("UNKNOWN_SKILL");
        }
        List<String> sourceTypes = filterSourceTypes(response.sourceTypes);
        return new RouteResult(
                skill,
                sourceTypes,
                response.confidence == null ? 0.0 : response.confidence,
                response.margin == null ? 0.0 : response.margin,
                Boolean.TRUE.equals(response.degraded),
                response.reason == null ? "ROUTER_OK" : response.reason);
    }

    /** 只保留白名单内的来源类型（去重保序）；null/空 → 空 list（= 不过滤） */
    private static List<String> filterSourceTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(KNOWN_SOURCE_TYPES::contains)
                .distinct()
                .toList();
    }

    /** 归一化：去空白 + 小写（缓存键；中文不受影响，英文/全角空白会被抹平） */
    static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private RouteResult count(RouteResult result) {
        degradedCalls.incrementAndGet();
        return result;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    // ==================================================================
    // 传输对象
    // ==================================================================

    /** 路由响应（字段与 A1 契约逐字对齐，snake_case 用 @JsonProperty 固定） */
    static class RouteResponse {
        @JsonProperty("skill")
        String skill;
        @JsonProperty("source_types")
        List<String> sourceTypes;
        @JsonProperty("confidence")
        Double confidence;
        @JsonProperty("margin")
        Double margin;
        @JsonProperty("degraded")
        Boolean degraded;
        @JsonProperty("reason")
        String reason;
    }

    /**
     * 路由结果
     *
     * @param skill        skill 名（默认 COURSE_QA）
     * @param sourceTypes  需过滤的来源类型；<b>空 list 表示不过滤</b>（与契约一致）
     * @param confidence   置信度
     * @param margin       与次优 skill 的差距（判断「路由是否犹豫」）
     * @param degraded     本次路由是否降级（Java 侧失败或 Python 侧 LOW_CONFIDENCE 都为 true）
     * @param reason       路由原因，便于排障
     */
    public record RouteResult(String skill, List<String> sourceTypes,
                              double confidence, double margin,
                              boolean degraded, String reason) {

        /** 降级结果：默认 skill + 空过滤（= 不过滤，等价今天行为） */
        static RouteResult fallback(String reason) {
            return new RouteResult(DEFAULT_SKILL, List.of(), 0.0, 0.0, true, reason);
        }

        /** 是否需要对检索加来源过滤（空 = 不过滤） */
        public boolean hasSourceFilter() {
            return sourceTypes != null && !sourceTypes.isEmpty();
        }

        /**
         * ★ 可直接用于检索过滤的集合；<b>路由降级时一律返回 null（不过滤）</b>
         *
         * <p><b>为什么降级时不能信它给的 sourceTypes</b>（E2E 实测踩到）：
         * 问「分块大小怎么选」时 Python 返回
         * {@code skill=COURSE_QA, source_types=[QUESTION,KNOWLEDGE], degraded=true, reason=SIM_LOW}——
         * 也就是「路由自己没把握，但仍给了一套默认过滤」。若照用，课件里那段**本来能答对**的
         * RAG 分块内容会被整段滤掉：向量通道 20→1 条，最终 {@code refused=true} 并引用了
         * 一条毫不相关的行测题。
         *
         * <p>结论：<b>降级 = 它的判断不可信</b>，此时应退化成「不过滤」（= 今天的行为），
         * 而不是退化成「按它猜的类型过滤」。
         */
        public java.util.Set<String> effectiveSourceTypes() {
            if (degraded || !hasSourceFilter()) {
                return null;
            }
            return new java.util.LinkedHashSet<>(sourceTypes);
        }
    }

    // ==================================================================
    // 缓存
    // ==================================================================

    /**
     * 路由结果缓存：LRU（访问序）+ TTL
     *
     * <p><b>为什么要缓存</b>：路由结果只取决于问题文本，而同一问题在一次演示里
     * 可能被反复问（用户点重试、多人问同一道题）。不缓存的话每次都要付一次往返，
     * 而路由恰恰是「宁可不要也不能变慢」的环节。
     *
     * <p><b>为什么不用 Caffeine</b>：只需要 200 条 LRU + TTL，几十行足够，
     * 不为此新增一个依赖（项目当前无缓存库）。
     */
    static class RouteCache {

        private record Entry(RouteResult result, long expiresAt) {
        }

        private final int maxEntries;
        private final long ttlMillis;
        private final Map<String, Entry> entries;

        RouteCache(int maxEntries, long ttlMillis) {
            this.maxEntries = Math.max(1, maxEntries);
            this.ttlMillis = Math.max(0, ttlMillis);
            // accessOrder=true → get 也会移动顺序，天然实现 LRU
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > RouteCache.this.maxEntries;
                }
            };
        }

        synchronized RouteResult get(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (System.currentTimeMillis() > entry.expiresAt()) {
                entries.remove(key);
                return null;
            }
            return entry.result();
        }

        synchronized void put(String key, RouteResult result) {
            if (ttlMillis == 0) {
                return;
            }
            entries.put(key, new Entry(result, System.currentTimeMillis() + ttlMillis));
        }

        synchronized int size() {
            return entries.size();
        }
    }
}
