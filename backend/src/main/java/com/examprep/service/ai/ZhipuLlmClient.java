package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 智谱 GLM 大模型客户端
 *
 * <p>端点 {@code https://open.bigmodel.cn/api/paas/v4}，模型默认 {@code glm-4-flash}。
 * 协议与 DashScope 一致（OpenAI 兼容），故复用 {@link AbstractOpenAiCompatLlmClient}。
 *
 * <p><b>可用条件</b>：{@code app.ai.llm.provider=zhipu} 且 Key 非空且未熔断。
 */
@Slf4j
@Component
@Order(20)
public class ZhipuLlmClient extends AbstractOpenAiCompatLlmClient {

    private static final String BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String PROVIDER = "zhipu";

    public ZhipuLlmClient(AiProperties properties,
                          PromptBuilder promptBuilder,
                          ObjectMapper objectMapper) {
        super(properties, promptBuilder, objectMapper,
                BASE_URL, properties.getLlm().getZhipuModel(), PROVIDER, PROVIDER);
        log.info("[AI] 智谱 GLM 客户端已注册（端点 {}，模型 {}，Key {}）",
                BASE_URL, properties.getLlm().getZhipuModel(),
                properties.getLlm().hasKey() ? "已配置" : "未配置，将走抽取式兜底");
    }

    @Override
    public boolean available() {
        return PROVIDER.equalsIgnoreCase(properties.getLlm().getProvider())
                && super.available();
    }
}
