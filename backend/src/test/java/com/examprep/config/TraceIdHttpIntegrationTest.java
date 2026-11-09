package com.examprep.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * traceId 端到端（真容器真 Filter 链）。
 *
 * <p>为什么单测不够：{@link TraceIdFilterTest} 只能证明 Filter 自身逻辑对，
 * <b>证明不了它在 Servlet 容器里排在 Spring Security 前面</b>。而契约 §4② 的押点正是
 * 「未带 token 的 401 响应也要有 {@code X-Trace-Id}」——这只有在
 * {@code FilterRegistrationBean.setOrder(HIGHEST_PRECEDENCE)} 早于
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER(-100)} 时才成立。
 * 顺序弄错的后果是：所有鉴权失败/异常响应全部丢链路，排查时正是最需要 traceId 的时刻。
 *
 * <p>本测试直接起 Tomcat（RANDOM_PORT）打真实 HTTP，覆盖三条：
 * <ol>
 *   <li>未登录 401 带 {@code X-Trace-Id}（顺序断言）；</li>
 *   <li>合法入站头在 401 分支也被复用；非法入站头被替换（防日志注入）；</li>
 *   <li>{@code Result.traceId} 与响应头<b>完全相等</b>（body ↔ header 同源）。</li>
 * </ol>
 */
@DisplayName("traceId 端到端：401 分支带头 + body 与 header 相等")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class TraceIdHttpIntegrationTest {

    private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");

    private static final String LEGAL = "fedcba9876543210";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    /** 打真实 HTTP；context-path 固定 /api（application.yml:4） */
    private HttpResponse<String> get(String path, String inboundTraceId) throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (inboundTraceId != null) {
            builder.header("X-Trace-Id", inboundTraceId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String header(HttpResponse<String> response) {
        return response.headers().firstValue("X-Trace-Id").orElse(null);
    }

    @Test
    @DisplayName("未登录 401：Filter 早于 Spring Security，响应头仍带 16 位 hex")
    void unauthenticatedCarriesTraceId() throws Exception {
        HttpResponse<String> response = get("/ai/agent/runs", null);

        assertEquals(401, response.statusCode(), "未带 token 应被 Security 拒绝");
        String traceId = header(response);
        assertNotNull(traceId, "401 响应必须带 X-Trace-Id（Filter 顺序错了就会丢）");
        assertTrue(HEX16.matcher(traceId).matches(), "格式必须是 16 位小写 hex：" + traceId);
    }

    @Test
    @DisplayName("合法入站头：401 分支也复用（链路不因鉴权失败而断）")
    void unauthenticatedReusesInboundTraceId() throws Exception {
        HttpResponse<String> response = get("/ai/agent/runs", LEGAL);

        assertEquals(401, response.statusCode());
        assertEquals(LEGAL, header(response), "入站合法值必须原样复用");
    }

    @Test
    @DisplayName("非法入站头：替换为 16 位 hex，绝不回显原值（防日志注入）")
    void unauthenticatedReplacesIllegalTraceId() throws Exception {
        // 注意：CR/LF 根本无法作为 HTTP 头发送（容器与 HttpClient 先拒绝），
        // 这里用「合法头值但不符合 traceId 值域」的样本：大写 hex、超长、含冒号。
        for (String illegal : new String[] {"0123456789ABCDEF", "a".repeat(65), "trace:0123456789ab"}) {
            HttpResponse<String> response = get("/ai/agent/runs", illegal);

            assertEquals(401, response.statusCode());
            String traceId = header(response);
            assertNotNull(traceId);
            assertTrue(HEX16.matcher(traceId).matches(), "非法入站值必须被替换：" + traceId);
            assertNotEquals(illegal, traceId, "不得回显非法入站值：" + illegal);
        }
    }

    @Test
    @DisplayName("公开接口 200：Result.traceId 与响应头完全相等（契约 §4② 押点）")
    void bodyTraceIdEqualsResponseHeader() throws Exception {
        HttpResponse<String> response = get("/ai/capability", LEGAL);

        assertEquals(200, response.statusCode(), "capability 是 PUBLIC 接口");
        String traceId = header(response);
        assertNotNull(traceId);
        assertTrue(HEX16.matcher(traceId).matches(), "响应头格式：" + traceId);
        assertEquals(LEGAL, traceId, "入站合法值应贯穿到响应头");

        JsonNode body = json.readTree(response.body());
        assertNotNull(body, "响应体应为 JSON");
        assertEquals(traceId, body.path("traceId").asText(),
                "Result.traceId 必须与响应头完全相等，否则五处一致性验证必挂");
    }
}
