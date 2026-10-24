package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 本地大模型客户端（Ollama + OpenAI 兼容层）
 *
 * <p><b>为什么接 Ollama</b>：云端 API Key 会过期、会限流、按量计费，而答辩现场
 * 一旦 Key 失效整个「大模型生成」能力就没了。本地模型是**零网络依赖、零费用、
 * 不会过期**的选项，与本项目「降级优先」的稳定性设计一脉相承。
 *
 * <p><b>端点</b>：{@code http://127.0.0.1:11434/v1}（Ollama 的 OpenAI 兼容层）。
 * <b>不需要 API Key</b> —— 但 OpenAI 兼容协议要求 {@code Authorization} 头存在，
 * 因此发送占位串 {@code Bearer ollama}（Ollama 不校验该头）。
 *
 * <p><b>★ 超时是独立配置的</b>：{@code app.ai.llm.ollama-timeout-millis}（默认 90s）。
 * 本地 7B 生成 300 token 需 15–25 秒，若沿用云端的 8 秒会把每个回答都掐断。
 * <b>反过来也不能把全局超时改成 90 秒</b>——那会让云端失败时白等 90 秒才降级。
 *
 * <p><b>★ 为什么 {@code available()} 不探测网络</b>：
 * 见本类 {@link #available()} 的说明——Ollama 没启动时靠 TCP 连接被拒<b>立即</b>失败，
 * 不应该为了「提前知道」而每条请求都等一次连接超时。
 *
 * <p><b>优先级</b>：{@code @Order(5)}，排在所有云端客户端之前
 * （本地模型不会因 Key 过期而失效，应当优先）。
 */
@Slf4j
@Component
@Order(5)
public class OllamaLlmClient extends AbstractOpenAiCompatLlmClient {

    public OllamaLlmClient(AiProperties properties,
                           PromptBuilder promptBuilder,
                           ObjectMapper objectMapper) {
        super(properties, promptBuilder, objectMapper,
                properties.getLlm().getOllamaBaseUrl(),
                properties.getLlm().getOllamaModel(),
                "ollama",
                "ollama",
                properties.getLlm().getOllamaTimeoutMillis());
        log.info("[AI] Ollama 客户端已注册（{}，模型 {}，超时 {}ms）",
                properties.getLlm().getOllamaBaseUrl(),
                properties.getLlm().getOllamaModel(),
                properties.getLlm().getOllamaTimeoutMillis());
    }

    /**
     * 本地模型不需要 API Key
     *
     * <p>覆盖基类的「Key 非空才算可用」，否则 Ollama 永远不可用。
     */
    @Override
    protected boolean hasCredentials() {
        return StringUtils.hasText(properties.getLlm().getOllamaBaseUrl());
    }

    /**
     * Ollama 不校验 Authorization，但 OpenAI 兼容协议要求该头存在
     *
     * <p>用固定占位串而非空串：部分 HTTP 客户端/Ollama 版本对空 {@code Bearer }
     * 的处理不一致，占位串最稳且不泄露任何真实凭据。
     */
    @Override
    protected String authorizationHeader() {
        return "Bearer ollama";
    }

    /**
     * ★ Ollama 私有字段：{@code keep_alive}
     *
     * <p><b>不加这个字段，答辩现场会翻车</b>：Ollama 默认 5 分钟不活跃就卸载模型，
     * 卸载后下一次提问又要等约 55 秒重新加载（实测冷启动 59.9s，其中加载 55.3s）。
     * 演示时讲解几分钟再提问，用户就会盯着转圈近一分钟。
     *
     * <p>这里设为 30 分钟，覆盖一整场演示。**已实测 {@code /v1/chat/completions}
     * 接受该字段**（HTTP 200，不影响响应解析）。
     *
     * <p>注意：这个字段只有 Ollama 认识，因此由本类单独提供而非写进基类 ——
     * 云端 OpenAI 兼容端点对未知字段的处理不一致，有的直接返回 400。
     */
    @Override
    protected Map<String, Object> extraBodyParams() {
        return Map.of("keep_alive", properties.getLlm().getOllamaKeepAlive());
    }

    /**
     * 可用性判定：<b>不发起任何网络请求</b>
     *
     * <p><b>为什么不做健康探测（这是刻意的设计选择）：</b>
     * <ul>
     *   <li>Ollama 没启动时，TCP 连接会被<b>立即拒绝</b>（不是超时），
     *       失败极快，`ask()` 抛异常后上层立刻降级到抽取式——<b>不需要提前探测</b>。</li>
     *   <li>若在 {@code available()} 里探测，Ollama 未启动时每条问答都要多等
     *       一次连接超时；而 {@code available()} 在降级链里会被<b>逐个调用</b>，
     *       探测成本会被放大。</li>
     *   <li>真正的保护由 {@link CircuitBreaker} 提供：连续 3 次失败熔断 60 秒，
     *       熔断期间 {@code canUse()=false}，直接跳过本客户端。</li>
     * </ul>
     *
     * <p>实测：Ollama 未运行时，问答在 <b>1 秒内</b>降级到抽取式（连接拒绝是毫秒级）。
     */
    @Override
    public boolean available() {
        return super.available();
    }

    // ==================================================================
    // 可达性探测（仅供 /ai/capability 诊断用，不参与问答选路）
    // ==================================================================

    /** 探测结果缓存，避免 capability 被轮询时反复打网络 */
    private volatile Boolean reachableCache;
    private volatile long reachableCheckedAt;

    /** 探测缓存有效期（毫秒） */
    private static final long PROBE_CACHE_MILLIS = 10_000L;

    /**
     * Ollama 当前是否<b>真的</b>可达（带 10 秒缓存）
     *
     * <p><b>这个方法为什么不参与问答选路</b>：
     * 问答路径上探测会让每个请求多付一次网络往返，而「Ollama 没启动」时
     * TCP 连接本来就会被立即拒绝，失败已足够快（见 {@link #available()} 说明）。
     *
     * <p><b>那为什么还需要它</b>：{@code GET /ai/capability} 是<b>诊断接口</b>，
     * 低频调用、且用户就是想知道「现在到底会用什么模型」。
     * 若只按配置判断，Ollama 没运行时 capability 仍会报 {@code generator=ollama}，
     * <b>这是在说谎</b>——现场排障会被误导。诊断接口多付一次短探测是值得的。
     *
     * @return true 表示 {@code GET /v1/models} 可正常响应
     */
    public boolean reachable() {
        Boolean cached = reachableCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - reachableCheckedAt < PROBE_CACHE_MILLIS) {
            return cached;
        }
        boolean ok = false;
        try {
            // 用独立的短超时客户端：诊断探测绝不能用生成用的 90 秒超时
            ok = Boolean.TRUE.equals(RestClient.builder()
                    .baseUrl(properties.getLlm().getOllamaBaseUrl())
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                        {
                            setConnectTimeout(1_500);
                            setReadTimeout(1_500);
                        }
                    })
                    .build()
                    .get()
                    .uri("/models")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful());
        } catch (Exception e) {
            // 未启动 / 超时 / 404 —— 一律视为不可达（探测失败不应该影响任何业务）
            log.debug("[AI] Ollama 探测失败: {}", e.getMessage());
            ok = false;
        }
        reachableCache = ok;
        reachableCheckedAt = now;
        return ok;
    }

    /** 供测试重置探测缓存 */
    void resetProbeCache() {
        this.reachableCache = null;
        this.reachableCheckedAt = 0L;
    }
}
