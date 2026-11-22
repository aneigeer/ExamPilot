package com.examprep.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的大模型客户端基类
 *
 * <p><b>技术选型说明（设计方案 B.2）</b>：DashScope 百炼与智谱 GLM 都是 OpenAI 兼容协议，
 * {@code spring-boot-starter-web} 已含 {@code spring-web}，用 Spring 自带的
 * {@link RestClient} + {@code ObjectMapper} 手写请求体即可，<b>pom 一行不加</b>。
 *
 * <p><b>降级契约</b>：本类所有异常路径统一抛 {@link AiUnavailableException}，
 * 由 {@code AiQaService} 捕获后走抽取式；同时驱动 {@link CircuitBreaker} 计数。
 */
@Slf4j
public abstract class AbstractOpenAiCompatLlmClient implements LlmClient {

    protected final AiProperties properties;
    protected final PromptBuilder promptBuilder;
    protected final ObjectMapper objectMapper;
    protected final CircuitBreaker circuitBreaker;
    private final RestClient restClient;

    /**
     * @param baseUrl        OpenAI 兼容端点前缀（不含 /chat/completions）
     * @param model          模型名
     * @param generatorName  生成器标识（dashscope / zhipu）
     * @param breakerName    熔断器名（区分不同 provider 的独立计数）
     */
    protected AbstractOpenAiCompatLlmClient(AiProperties properties,
                                            PromptBuilder promptBuilder,
                                            ObjectMapper objectMapper,
                                            String baseUrl,
                                            String model,
                                            String generatorName,
                                            String breakerName) {
        this(properties, promptBuilder, objectMapper, baseUrl, model,
                generatorName, breakerName, properties.getLlm().getTimeoutMillis());
    }

    /**
     * 带自定义超时的构造器
     *
     * <p>★ 为什么需要它：本地模型（Ollama + 7B）生成 300 token 需要 15–25 秒，
     * 云端 API 的 8 秒超时会把每个回答都掐断。<b>超时必须能按 client 单独配置</b>，
     * 否则「换本地模型」这个动作在代码层就做不成。
     *
     * @param timeoutMillis 本客户端专用的读超时（毫秒）
     */
    protected AbstractOpenAiCompatLlmClient(AiProperties properties,
                                            PromptBuilder promptBuilder,
                                            ObjectMapper objectMapper,
                                            String baseUrl,
                                            String model,
                                            String generatorName,
                                            String breakerName,
                                            int timeoutMillis) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.generatorName = generatorName;
        this.model = model;
        this.baseUrl = baseUrl;
        this.circuitBreaker = new CircuitBreaker(breakerName,
                properties.getLlm().getCircuitBreakerThreshold(),
                properties.getLlm().getCircuitBreakerOpenMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeoutMillis))
                .build();
    }

    /**
     * 是否具备调用凭据
     *
     * <p>默认要求 API Key 非空。**本地模型（Ollama）不需要 Key**，
     * 子类覆盖本方法返回 {@code true} 即可接入，
     * 而无需在基类里塞满 {@code if (isLocal)} 之类的分支。
     */
    protected boolean hasCredentials() {
        return properties.getLlm().hasKey();
    }

    /**
     * Authorization 头的值
     *
     * <p>Ollama 不校验该头，但 OpenAI 兼容协议要求它存在，
     * 因此本地模型返回一个占位串（注释说明原因，避免后人困惑）。
     */
    protected String authorizationHeader() {
        return "Bearer " + properties.getLlm().keyOrEmpty();
    }

    private final String baseUrl;
    private final String model;
    private final String generatorName;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 可用性 = Key 非空 且 未熔断
     *
     * <p>熔断期间自动走抽取式，用户无感——这是四层降级中的第 3 层。
     */
    @Override
    public boolean available() {
        return hasCredentials() && circuitBreaker.canUse();
    }

    @Override
    public String name() {
        return generatorName;
    }

    /** 熔断器（/ai/capability 展示降级状态用） */
    public CircuitBreaker breaker() {
        return circuitBreaker;
    }

    /** 端点（Swagger / 日志展示用） */
    public String baseUrl() {
        return baseUrl;
    }

    /** 模型名 */
    public String model() {
        return model;
    }

    // ==================================================================
    // 调用
    // ==================================================================

    @Override
    public LlmAnswer ask(String question, List<ChunkContext> contexts) {
        if (!hasCredentials()) {
            throw new AiUnavailableException(generatorName + " 未配置 API Key");
        }
        if (!circuitBreaker.canUse()) {
            throw new AiUnavailableException(generatorName + " 处于熔断状态（剩余 "
                    + circuitBreaker.remainingOpenMillis() + " ms）");
        }
        if (contexts == null || contexts.isEmpty()) {
            throw new AiUnavailableException("检索上下文为空，拒绝调用大模型");
        }

        long start = System.currentTimeMillis();
        try {
            String body = requestBody(question, contexts);
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", authorizationHeader())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String content = extractContent(raw);
            circuitBreaker.onSuccess();
            long cost = System.currentTimeMillis() - start;
            LlmAnswer answer = new LlmAnswer(content,
                    resolveSources(content, contexts), generatorName, cost, false);
            log.info("[AI] {} 生成成功（模型 {}，耗时 {} ms），正文引用编号 {}",
                    generatorName, model, cost, extractCitationIndexes(content));
            return answer;
        } catch (AiUnavailableException e) {
            circuitBreaker.onFailure(e.getMessage());
            throw e;
        } catch (RestClientException e) {
            circuitBreaker.onFailure(e.getMessage());
            throw new AiUnavailableException(generatorName + " 调用失败：" + e.getMessage(), e);
        } catch (Exception e) {
            circuitBreaker.onFailure(e.getMessage());
            throw new AiUnavailableException(generatorName + " 响应解析失败：" + e.getMessage(), e);
        }
    }

    /** 组装 OpenAI 兼容请求体 */
    private String requestBody(String question, List<ChunkContext> contexts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", properties.getPrompt().getTemperature());
        body.put("max_tokens", properties.getPrompt().getMaxTokens());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.systemPrompt()));
        messages.add(Map.of("role", "user", "content", promptBuilder.userPrompt(question, contexts)));
        body.put("messages", messages);
        // 厂商私有字段（如 Ollama 的 keep_alive），默认无 —— 云端 API 不认识未知字段会报错
        body.putAll(extraBodyParams());
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 厂商私有请求字段（默认空）
     *
     * <p>★ 为什么单独开一个钩子而不是直接写进基类：
     * 云端 OpenAI 兼容端点对<b>未知字段</b>的处理不一致（有的直接 400），
     * 所以这些字段只能由「明确知道该端点支持它」的子类提供。
     */
    protected Map<String, Object> extraBodyParams() {
        return Map.of();
    }

    /**
     * 非检索类结构化生成（判分等）
     *
     * <p>与 {@link #ask} 的差别只有 System Prompt：这里用调用方给的提示词，
     * 不套用「基于资料作答 + 强制引用」的问答 Prompt。
     * 温度取 0——判分这类任务需要可复现，不要随机发挥。
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return completeWithUsage(systemPrompt, userPrompt).content();
    }

    /**
     * 普通生成 + 用量。
     *
     * <p>上游返回 {@code usage} 就用真实值；缺字段时按字符数保守估算并标记
     * {@code estimated=true}，绝不把估算伪装成真实计量。
     */
    @Override
    public LlmCompletion completeWithUsage(String systemPrompt, String userPrompt) {
        requireAvailable();
        try {
            LlmCompletion completion = postChat(systemPrompt, userPrompt, false);
            circuitBreaker.onSuccess();
            return completion;
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            circuitBreaker.onFailure(e.getMessage());
            throw new AiUnavailableException(generatorName + " 结构化生成失败：" + e.getMessage());
        }
    }

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        return completeJsonWithUsage(systemPrompt, userPrompt).content();
    }

    /**
     * 带 {@code response_format=json_object} 的结构化生成 + 用量。
     *
     * <p>部分兼容端点（尤其是本地推理服务）不认识 {@code response_format}，会返回 400/422。
     * 这类「能力不支持」不计入熔断失败：自动回退一次普通请求。
     */
    @Override
    public LlmCompletion completeJsonWithUsage(String systemPrompt, String userPrompt) {
        requireAvailable();
        try {
            LlmCompletion completion = postChat(systemPrompt, userPrompt, true);
            circuitBreaker.onSuccess();
            return completion;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 422) {
                log.info("[AI] {} 不支持 response_format（HTTP {}），回退普通请求",
                        generatorName, status);
                return completeWithUsage(systemPrompt, userPrompt);
            }
            circuitBreaker.onFailure(e.getMessage());
            throw new AiUnavailableException(generatorName + " JSON 生成失败：HTTP " + status);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            circuitBreaker.onFailure(e.getMessage());
            throw new AiUnavailableException(generatorName + " JSON 生成失败：" + e.getMessage());
        }
    }

    private void requireAvailable() {
        if (!hasCredentials()) {
            throw new AiUnavailableException(generatorName + " 未配置 API Key");
        }
        if (!circuitBreaker.canUse()) {
            throw new AiUnavailableException(generatorName + " 处于熔断状态（剩余 "
                    + circuitBreaker.remainingOpenMillis() + " ms）");
        }
    }

    private LlmCompletion postChat(String systemPrompt, String userPrompt, boolean jsonMode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", properties.getPrompt().getMaxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        String raw = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", authorizationHeader())
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        return parseCompletion(raw, systemPrompt + userPrompt);
    }

    private LlmCompletion parseCompletion(String raw, String prompt) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new AiUnavailableException("大模型返回空响应");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiUnavailableException("大模型响应缺少 choices（可能是限流或鉴权失败）："
                    + truncate(raw));
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content == null || content.isBlank()) {
            throw new AiUnavailableException("大模型返回内容为空");
        }
        String responseModel = root.path("model").asText(model);
        JsonNode usage = root.path("usage");
        if (usage.isObject() && usage.has("prompt_tokens")) {
            int p = usage.path("prompt_tokens").asInt(0);
            int c = usage.path("completion_tokens").asInt(0);
            int t = usage.path("total_tokens").asInt(p + c);
            return new LlmCompletion(content.trim(),
                    new LlmUsage(responseModel, p, c, t, false));
        }
        return new LlmCompletion(content.trim(),
                LlmUsage.estimate(responseModel, prompt, content));
    }

    /** 从 OpenAI 兼容响应中取 {@code choices[0].message.content} */
    private String extractContent(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new AiUnavailableException("大模型返回空响应");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiUnavailableException("大模型响应缺少 choices（可能是限流或鉴权失败）：" + truncate(raw));
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content == null || content.isBlank()) {
            throw new AiUnavailableException("大模型返回内容为空");
        }
        return content.trim();
    }

    /**
     * 按正文中的引用编号构造溯源列表
     *
     * <p>实现委托给接口静态方法，行为完全一致；
     * 保留此方法只为让本类内部调用点可读。
     */
    protected List<SourceRef> resolveSources(String content, List<ChunkContext> contexts) {
        return LlmClient.resolveSources(content, contexts);
    }

    /** 提取引用编号（委托给接口静态方法） */
    public static List<Integer> extractCitationIndexes(String content) {
        return LlmClient.extractCitationIndexes(content);
    }

    /** 取内容前 160 字作为 snippet（委托给接口静态方法） */
    protected static String snippet(String content) {
        return LlmClient.snippet(content);
    }

    /** 同包内复用 */
    public static String snippetPublic(String content) {
        return LlmClient.snippet(content);
    }

    private static String truncate(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "…";
    }

    /** 带连接 / 读取超时的 RestClient（默认 JDK 客户端，零新增依赖） */
    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMillis) {
        int timeout = Math.max(1000, timeoutMillis);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return factory;
    }
}
