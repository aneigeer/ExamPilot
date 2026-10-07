package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope）大模型客户端
 *
 * <p>OpenAI 兼容端点 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 模型默认 {@code qwen-plus}。
 *
 * <p><b>可用条件</b>：{@code app.ai.llm.provider=dashscope} 且 {@code app.ai.llm.api-key} 非空
 * 且未熔断。三者任一不满足，{@link #available()} 返回 false，链路自动走抽取式，
 * <b>接口仍返回 200</b>（降级对用户透明）。
 *
 * <p>本类无条件注册为 Bean（不依赖 Key），由 available() 决定是否参与生成，
 * 这样「无 Key 也能启动、能使用、能跑通全链路」。
 */
@Slf4j
@Component
@Order(10)
public class DashScopeLlmClient extends AbstractOpenAiCompatLlmClient {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String PROVIDER = "dashscope";

    public DashScopeLlmClient(AiProperties properties,
                              PromptBuilder promptBuilder,
                              ObjectMapper objectMapper) {
        super(properties, promptBuilder, objectMapper,
                BASE_URL, properties.getLlm().getDashscopeModel(), PROVIDER, PROVIDER);
        log.info("[AI] DashScope 客户端已注册（端点 {}，模型 {}，Key {}）",
                BASE_URL, properties.getLlm().getDashscopeModel(),
                properties.getLlm().hasKey() ? "已配置" : "未配置，将走抽取式兜底");
    }

    @Override
    public boolean available() {
        return PROVIDER.equalsIgnoreCase(properties.getLlm().getProvider())
                && super.available();
    }
}
