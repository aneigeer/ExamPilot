package com.examprep.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FastAPI AI 微服务的大模型代理客户端（T09）
 *
 * <p>调用 Python 侧 {@code POST /v1/llm}（见 {@code ai-service/app/schemas.py} 的
 * {@code LlmRequest}/{@code LlmResponse}），把检索上下文交给独立进程里的模型生成。
 *
 * <p><b>为什么需要它</b>：企业场景常要求「所有模型调用走统一网关」便于
 * 限流 / 审计 / 成本归集。直连 DashScope、Zhipu 会绕过网关。
 * 这也是架构演进到独立 AI 推理服务时 {@code LlmClient} 抽象的兑现。
 *
 * <p><b>降级契约（与直连 SDK 客户端完全一致）</b>：
 * <ul>
 *   <li>无配置 / 熔断 / 超时 / 5xx → 抛 {@link AiUnavailableException}，上层降级下一个客户端</li>
 *   <li><b>永远不返回 null</b>；最终兜底仍是 {@link ExtractiveAnswerClient}</li>
 * </ul>
 *
 * <p><b>★ 溯源口径</b>：Python 侧返回的 {@code sources[].index} 是 1-based 的上下文下标，
 * 本类统一用 {@link LlmClient#resolveSources} 从<b>正文引用编号</b>反推溯源，
 * 保证与直连客户端行为一致——L3 幻觉校验（{@link AnswerPostProcessor}）才有意义。
 */
@Slf4j
@Component
@Order(15)
public class FastApiLlmClient implements LlmClient {

    private final AiProperties properties;
    private final FastApiClient fastApiClient;
    private final ObjectMapper objectMapper;

    public FastApiLlmClient(AiProperties properties,
                            FastApiClient fastApiClient,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.fastApiClient = fastApiClient;
        this.objectMapper = objectMapper;
        log.info("[AI] FastAPI 大模型代理客户端已注册（{}，开关 {}）",
                properties.getFastapi().normalizedBaseUrl(),
                properties.getFastapi().isEnabled());
    }

    @Override
    public String name() {
        return "fastapi";
    }

    /**
     * 可用条件：大模型代理开关开启 且 FastAPI 处于就绪状态
     *
     * <p>注意与 {@code FastApiClient.available()}（检索通道）的区别：
     * 检索不可用不代表生成不可用，反之亦然，因此各自独立判定。
     */
    @Override
    public boolean available() {
        return properties.getFastapi().isEnabled()
                && fastApiClient.isLlmReady();
    }

    @Override
    public LlmAnswer ask(String question, List<ChunkContext> contexts) {
        if (!properties.getFastapi().isEnabled()) {
            throw new AiUnavailableException("FastAPI 大模型代理未启用");
        }
        if (contexts == null || contexts.isEmpty()) {
            throw new AiUnavailableException("检索上下文为空，拒绝调用大模型");
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            // Python 侧 LlmRequest.contexts 是字符串列表（非结构体）
            body.put("contexts", contexts.stream().map(ChunkContext::content).toList());
            body.put("max_tokens", properties.getPrompt().getMaxTokens());
            body.put("temperature", properties.getPrompt().getTemperature());

            String raw = objectMapper.writeValueAsString(body);
            String resp = fastApiClient.postLlm(raw);
            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("answer").asText("");
            if (!StringUtils.hasText(content)) {
                throw new AiUnavailableException("FastAPI 返回答案内容为空");
            }
            String generator = root.path("generator").asText("fastapi");
            long cost = root.path("cost_ms").asLong(System.currentTimeMillis() - start);
            boolean degraded = root.path("degraded").asBoolean(false);

            // ★ 溯源：从正文引用编号反推（不信任模型自述），与直连客户端口径一致
            List<SourceRef> sources = resolveSources(content, contexts);
            LlmAnswer answer = new LlmAnswer(content, sources, name(),
                    System.currentTimeMillis() - start, degraded);
            log.info("[AI] FastAPI 代理生成成功（generator={}，耗时 {} ms，引用 {}）",
                    generator, cost, sources.size());
            return answer;
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("FastAPI 代理调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 复用接口的溯源解析（从正文 [n] 标注反推 SourceRef 列表）
     *
     * <p>放在这里显式转发一次，便于阅读时一眼看出「Python 返回的 sources 字段被忽略、
     * 统一以正文为准」这一关键口径。
     */
    private List<SourceRef> resolveSources(String content, List<ChunkContext> contexts) {
        List<SourceRef> sources = new ArrayList<>(LlmClient.resolveSources(content, contexts));
        return sources;
    }
}
