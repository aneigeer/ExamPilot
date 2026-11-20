package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用 OpenAI 兼容云端 API 客户端
 *
 * <p>支持任意实现 {@code /chat/completions} 协议的在线或自托管服务：
 * DeepSeek（{@code https://api.deepseek.com/v1}）、OpenAI、硅基流动、月之暗面、vLLM 等。
 *
 * <p><b>可用条件</b>：{@code app.ai.llm.provider} 为 {@code openai} 或 {@code auto}，
 * 且 {@code openai-base-url} 与 {@code api-key} 均非空且未熔断。
 * 在 {@code auto} 模式下排在 {@link OllamaLlmClient}（{@code @Order(12)}）之前：
 * 配了 API Key 走云端 API，未配 Key 自动回退本地 Ollama。
 */
@Slf4j
@Component
@Order(8)
public class OpenAiCompatLlmClient extends AbstractOpenAiCompatLlmClient {

    private static final String PROVIDER = "openai";

    public OpenAiCompatLlmClient(AiProperties properties,
                                 PromptBuilder promptBuilder,
                                 ObjectMapper objectMapper) {
        super(properties, promptBuilder, objectMapper,
                normalizeBaseUrl(properties.getLlm().getOpenaiBaseUrl()),
                properties.getLlm().getOpenaiModel(),
                PROVIDER,
                PROVIDER);
        log.info("[AI] OpenAI 兼容 API 客户端已注册（端点 {}，模型 {}，Key {}）",
                normalizeBaseUrl(properties.getLlm().getOpenaiBaseUrl()),
                properties.getLlm().getOpenaiModel(),
                properties.getLlm().hasKey() ? "已配置" : "未配置");
    }

    @Override
    public boolean available() {
        String p = properties.getLlm().getProvider();
        boolean matched = PROVIDER.equalsIgnoreCase(p == null ? "" : p.trim())
                || properties.getLlm().isAuto();
        return matched
                && StringUtils.hasText(properties.getLlm().getOpenaiBaseUrl())
                && super.available();
    }

    private static String normalizeBaseUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
