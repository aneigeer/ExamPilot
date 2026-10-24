package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Ollama 本地模型客户端测试
 *
 * <p><b>核心命题（比"能不能调通"重要得多）</b>：
 * <b>Ollama 没启动时，问答必须在 1 秒内降级到抽取式，不能卡在 90 秒超时上。</b>
 * 本地模型超时配的是 90 秒（7B 生成慢），如果失败路径也走这个超时，
 * 用户会看到界面卡死近两分钟——这比"没有 AI"糟糕得多。
 *
 * <p>保证这一点靠两件事，本测试逐条验证：
 * <ol>
 *   <li>{@code available()} <b>不发起任何网络请求</b>（否则降级链每轮都要等探测）；</li>
 *   <li>失败极快：Ollama 未启动时 TCP 连接被<b>立即拒绝</b>（不是超时）；
 *       连续 3 次失败后 {@link CircuitBreaker} 熔断 60 秒，后续请求直接跳过。</li>
 * </ol>
 */
@DisplayName("Ollama 本地模型：可用性判定、响应解析、熔断降级")
class OllamaLlmClientTest {

    private static final String BASE = "http://127.0.0.1:11434/v1";

    // ==================================================================
    // 脚手架
    // ==================================================================

    private record Fixture(OllamaLlmClient client, MockRestServiceServer server,
                           AiProperties properties) {
    }

    /**
     * 构造一个 RestClient 指向 MockRestServiceServer 的 Ollama 客户端
     *
     * <p>{@code restClient} 是抽象类的 {@code private final} 字段，
     * 用 reflection 替换成指向 mock 的实例，从而无需真实网络即可模拟各种响应。
     */
    private Fixture fixture() {
        AiProperties props = new AiProperties();
        props.getLlm().setProvider("ollama");
        props.getLlm().setOllamaBaseUrl(BASE);
        props.getLlm().setOllamaModel("qwen2.5-7b");
        props.getLlm().setOllamaTimeoutMillis(90_000);
        // ★ 刻意不设 api-key：验证"本地模型不需要 Key"
        props.getLlm().setApiKey("");

        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        RestClient mockRestClient = builder.build();

        OllamaLlmClient client = new OllamaLlmClient(props, new PromptBuilder(props), new ObjectMapper());
        inject(client, mockRestClient);
        return new Fixture(client, holder[0], props);
    }

    /** 把抽象类的 private final restClient 换成 mock 实例 */
    private static void inject(Object target, RestClient replacement) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("restClient");
                f.setAccessible(true);
                f.set(target, replacement);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("注入 restClient 失败", e);
            }
        }
        throw new IllegalStateException("未找到 restClient 字段");
    }

    private static ChunkContext ctx(long id) {
        return new ChunkContext(id, "QUESTION", id, "值传递与地址传递",
                "值传递是复制实参的值，地址传递是传地址。", 5L, 8L, 3,
                "QUESTION", id, null, LocalDateTime.now().minusDays(1),
                0L, 1.0, 1.0);
    }

    /** 标准的 OpenAI 兼容响应体 */
    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant",
             "content":"值传递复制实参的值，地址传递传地址 [1]。"}}]}
            """;

    // ==================================================================
    // ① 可用性：不需要 Key，且不发起网络请求
    // ==================================================================

    @Test
    @DisplayName("① 本地模型无需 API Key，available() 立刻返回 true（零网络请求）")
    void 无需ApiKey且不阻塞() {
        Fixture f = fixture();
        // 没有设置任何 server 期望：若 available() 发起请求，MockRestServiceServer 会失败
        long start = System.nanoTime();
        boolean available = f.client().available();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(available, "Ollama 配好端点后应可用，即使 api-key 为空");
        assertTrue(elapsedMs < 100,
                "available() 必须不发起网络请求（实测 " + elapsedMs + "ms，应 < 100ms）");
        assertEquals("ollama", f.client().name());
        assertEquals("qwen2.5-7b", f.client().model());
    }

    @Test
    @DisplayName("①-b 连续 3 次失败后 available() 返回 false（熔断生效，不再打网络）")
    void 连续失败后熔断() {
        Fixture f = fixture();
        // MockRestServiceServer 不允许在请求发生后再追加期望，
        // 因此先一次性声明 3 次失败期望（每次一次，按序消费）
        for (int i = 0; i < 3; i++) {
            f.server().expect(once(), requestTo(BASE + "/chat/completions"))
                    .andRespond(withServerError());
        }
        for (int i = 0; i < 3; i++) {
            assertThrows(AiUnavailableException.class,
                    () -> f.client().ask("值传递是什么", List.of(ctx(1))),
                    "服务端 500 时应抛出 AiUnavailableException");
        }
        f.server().verify();
        f.server().reset();

        // 第 4 次：熔断已开，available() 必须 false 且【不再发起任何请求】
        assertFalse(f.client().available(), "连续失败 3 次后应熔断");
        assertTrue(f.client().breaker().consecutiveFailures() >= 3,
                "应累计熔断计数，实际 " + f.client().breaker().consecutiveFailures());
        assertThrows(AiUnavailableException.class,
                () -> f.client().ask("再问一次", List.of(ctx(1))),
                "熔断期间调用应立即抛异常，不走网络");
        // 没有设置期望却抛的是熔断异常而非 mock 错误 → 证明真的没发请求
        f.server().verify();
    }

    // ==================================================================
    // ② 正常响应解析
    // ==================================================================

    @Test
    @DisplayName("② 正常响应：解析正文 + 溯源，generator=ollama")
    void 正常响应解析() {
        Fixture f = fixture();
        f.server().expect(once(), requestTo(BASE + "/chat/completions"))
                .andExpect(method(POST))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        LlmAnswer answer = f.client().ask("值传递和地址传递区别", List.of(ctx(5)));

        assertNotNull(answer, "不应返回 null");
        assertEquals("ollama", answer.generator(), "生成器标识应为 ollama");
        assertTrue(answer.content().contains("地址传递"), "正文应包含模型输出");
        // 【1】对应第 1 个上下文，应被解析为 1 条溯源
        assertEquals(1, answer.sources().size(), "正文引用 [1] 应解析出 1 条溯源");
        assertEquals(5L, answer.sources().get(0).chunkId(), "溯源应指向第 1 个上下文");
        assertEquals(0, f.client().breaker().consecutiveFailures(), "成功后熔断计数应清零");
        f.server().verify();
    }

    @Test
    @DisplayName("②-b 空响应 / 缺 choices → 抛 AiUnavailableException（降级而非空答案）")
    void 异常响应降级() {
        Fixture f = fixture();
        f.server().expect(once(), requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertThrows(AiUnavailableException.class,
                () -> f.client().ask("问题", List.of(ctx(1))),
                "缺少 choices 应降级，而不是返回空答案");
    }

    // ==================================================================
    // ③ 超时独立配置
    // ==================================================================

    @Test
    @DisplayName("③ Ollama 超时独立于云端：90s 不影响云端客户端的 8s")
    void 超时独立配置() {
        AiProperties props = new AiProperties();
        // 云端默认 8s
        assertEquals(8000, props.getLlm().getTimeoutMillis(), "云端超时默认应为 8s");
        // 本地默认 90s
        assertEquals(90_000, props.getLlm().getOllamaTimeoutMillis(), "Ollama 超时默认应为 90s");

        // 两者互不影响：改 ollama 超时不应改变云端超时
        props.getLlm().setOllamaTimeoutMillis(120_000);
        assertEquals(8000, props.getLlm().getTimeoutMillis(),
                "改 Ollama 超时不得影响云端超时（否则云端失败时要白等 2 分钟）");
    }

    @Test
    @DisplayName("③-b 上下文为空 → 直接拒绝调用（省一次 90s 的无效请求）")
    void 空上下文不调用() {
        Fixture f = fixture();
        // 不设期望：任何请求都会导致 mock 报错
        assertThrows(AiUnavailableException.class,
                () -> f.client().ask("问题", List.of()));
        f.server().verify();
    }
}
