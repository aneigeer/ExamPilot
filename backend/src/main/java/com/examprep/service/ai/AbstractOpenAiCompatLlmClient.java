package com.examprep.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
                .requestFactory(requestFactory(properties.getLlm().getTimeoutMillis()))
                .build();
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
        return properties.getLlm().hasKey() && circuitBreaker.canUse();
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
        if (!properties.getLlm().hasKey()) {
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
                    .header("Authorization", "Bearer " + properties.getLlm().keyOrEmpty())
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
        return objectMapper.writeValueAsString(body);
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
