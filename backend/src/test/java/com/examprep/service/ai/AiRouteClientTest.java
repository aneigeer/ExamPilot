package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由客户端单测（JDK HttpServer 假服务，零新增依赖）
 *
 * <p><b>为什么这些用例值得写</b>：路由是「优化项」，它的失败绝不能传导成用户拿不到答案。
 * 所以这里重点不是「200 时能解析」，而是<b>各种坏情况下是否老老实实降级</b>：
 * <ul>
 *   <li>关闭 / 问题过短 / 缓存命中 → <b>不该发起 HTTP</b>（用计数器验证，而不是看日志）</li>
 *   <li>5xx、超时、连不上、响应是脏 JSON、skill 越界 → 一律回默认路由 + degraded=true</li>
 *   <li>连续 3 次失败 → 熔断期内不再发请求（第 4 次必须 0 次 HTTP）</li>
 *   <li>缓存的语义是「同一问题不重复打路由」，用第 2 次 0 次 HTTP 验证</li>
 * </ul>
 */
@DisplayName("AiRouteClient：解析 / 降级 / 缓存 / 熔断")
class AiRouteClientTest {

    private HttpServer server;
    private AiProperties properties;
    private AiRouteClient client;

    /** 服务端收到的请求数（用于断言「本不该发请求」的场景） */
    private final AtomicInteger requests = new AtomicInteger();
    /** 服务端收到的请求体（校验 snake_case 与令牌头） */
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastToken = new AtomicReference<>("");

    /** 下一次响应的状态码与 body */
    private int nextStatus = 200;
    private String nextBody = "";

    @BeforeEach
    void setUp() throws Exception {
        requests.set(0);
        lastBody.set("");
        lastToken.set("");
        nextStatus = 200;
        nextBody = "{\"skill\":\"COURSE_QA\",\"source_types\":[\"QUESTION\",\"KNOWLEDGE\"],"
                + "\"confidence\":0.83,\"margin\":0.21,\"degraded\":false,\"reason\":\"ROUTER_OK\"}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/route", exchange -> {
            requests.incrementAndGet();
            lastBody.set(readBody(exchange.getRequestBody()));
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            respond(exchange, nextStatus, nextBody);
        });
        server.start();

        properties = new AiProperties();
        properties.getFastapi().setEnabled(true);
        properties.getFastapi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getFastapi().setPathPrefix("/v1");
        properties.getRoute().setEnabled(true);
        properties.getRoute().setTimeoutMillis(300);
        properties.getRoute().setConnectTimeoutMillis(500);
        properties.setInternalToken("secret-token");
        client = new AiRouteClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String readBody(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常路由：snake_case 请求体 + 内部令牌头 + 逐字段解析")
    void routesSuccessfully() {
        AiRouteClient.RouteResult result = client.route("值传递和地址传递有什么区别", null);

        assertEquals(1, requests.get());
        assertTrue(lastBody.get().contains("\"question\""), "请求体应含 question：" + lastBody.get());
        assertEquals("secret-token", lastToken.get(), "应带内部令牌头（Java↔Python 共用）");

        assertEquals("COURSE_QA", result.skill());
        assertEquals(List.of("QUESTION", "KNOWLEDGE"), result.sourceTypes());
        assertEquals(0.83, result.confidence(), 1e-9);
        assertEquals(0.21, result.margin(), 1e-9);
        assertFalse(result.degraded(), "200 且 ROUTER_OK 不应标记降级");
        assertEquals("ROUTER_OK", result.reason());
        assertTrue(result.hasSourceFilter());
    }

    @Test
    @DisplayName("Python 侧自降级（LOW_CONFIDENCE + 空 source_types）原样透传，不重复降级")
    void passesThroughPythonSideDegradation() {
        nextBody = "{\"skill\":\"COURSE_QA\",\"source_types\":[],\"confidence\":0.41,"
                + "\"margin\":0.02,\"degraded\":true,\"reason\":\"LOW_CONFIDENCE\"}";

        AiRouteClient.RouteResult result = client.route("这个问题有点模糊", null);

        assertTrue(result.degraded());
        assertEquals("LOW_CONFIDENCE", result.reason());
        assertTrue(result.sourceTypes().isEmpty(), "空数组语义是「不过滤」");
        assertFalse(result.hasSourceFilter(), "空 list 不应触发 SQL 过滤");
    }

    @Test
    @DisplayName("未知 skill → 契约漂移，降级为默认 COURSE_QA（不把脏值透传给检索层）")
    void unknownSkillFallsBack() {
        nextBody = "{\"skill\":\"TOTALLY_NEW_SKILL\",\"source_types\":[\"QUESTION\"],"
                + "\"confidence\":0.9,\"margin\":0.5,\"degraded\":false,\"reason\":\"ROUTER_OK\"}";

        AiRouteClient.RouteResult result = client.route("随便一个问题", null);

        assertEquals(AiRouteClient.DEFAULT_SKILL, result.skill());
        assertTrue(result.degraded());
        assertEquals("UNKNOWN_SKILL", result.reason());
        assertTrue(result.sourceTypes().isEmpty(), "未知 skill 时不应带着半截过滤条件去检索");
    }

    @Test
    @DisplayName("source_types 里的未知类型被过滤（SQL 只认白名单）")
    void filtersUnknownSourceTypes() {
        nextBody = "{\"skill\":\"COURSEWARE_QA\",\"source_types\":[\"COURSEWARE\",\"WEIRD\",\"question\"],"
                + "\"confidence\":0.7,\"margin\":0.3,\"degraded\":false,\"reason\":\"ROUTER_OK\"}";

        AiRouteClient.RouteResult result = client.route("课件里怎么讲的", null);

        assertEquals(List.of("COURSEWARE", "QUESTION"), result.sourceTypes(),
                "应过滤未知类型并把小写归一成大写：" + result.sourceTypes());
    }

    @Test
    @DisplayName("服务 500 → 默认路由 + degraded，且不抛异常")
    void httpErrorFallsBack() {
        nextStatus = 500;
        nextBody = "{\"detail\":\"boom\"}";

        AiRouteClient.RouteResult result = client.route("服务挂了会怎样", null);

        assertEquals(AiRouteClient.DEFAULT_SKILL, result.skill());
        assertTrue(result.degraded());
        assertEquals("HTTP_ERROR", result.reason());
        assertTrue(result.sourceTypes().isEmpty());
        assertEquals(1, client.degradedCalls());
    }

    @Test
    @DisplayName("响应是脏 JSON → 降级为默认路由（不让解析异常冒到问答链路）")
    void malformedBodyFallsBack() {
        nextBody = "not-a-json";

        AiRouteClient.RouteResult result = client.route("脏响应怎么办", null);

        assertEquals(AiRouteClient.DEFAULT_SKILL, result.skill());
        assertTrue(result.degraded());
        assertEquals("CALL_FAILED", result.reason());
    }

    @Test
    @DisplayName("配置关闭 → 0 次 HTTP（这是「灰度/回退只改一个开关」的前提）")
    void disabledSendsNoRequest() {
        properties.getRoute().setEnabled(false);

        AiRouteClient.RouteResult result = client.route("值传递和地址传递有什么区别", null);

        assertEquals(0, requests.get(), "关闭时不该发起任何网络调用");
        assertEquals(AiRouteClient.DEFAULT_SKILL, result.skill());
        assertTrue(result.degraded());
        assertEquals("ROUTER_DISABLED", result.reason());
    }

    @Test
    @DisplayName("问题过短（<6 字）→ 0 次 HTTP，直接默认")
    void shortQuestionSkipsHttp() {
        AiRouteClient.RouteResult result = client.route("你好", null);

        assertEquals(0, requests.get(), "过短问题不值得一次往返");
        assertEquals("TOO_SHORT", result.reason());
    }

    @Test
    @DisplayName("缓存命中 → 同一问题第 2 次 0 次 HTTP")
    void cacheAvoidsSecondCall() {
        client.route("值传递和地址传递有什么区别", null);
        AiRouteClient.RouteResult second = client.route("值传递和地址传递有什么区别 ", null);

        assertEquals(1, requests.get(), "空白差异应被归一化后命中缓存");
        assertFalse(second.degraded());
    }

    @Test
    @DisplayName("连续 3 次失败 → 熔断，第 4 次 0 次 HTTP 且降级为默认")
    void breakerOpensAfterThreeFailures() {
        nextStatus = 500;
        nextBody = "{}";
        client.route("第一个问题会不会失败", null);
        client.route("第二个问题会不会失败", null);
        client.route("第三个问题会不会失败", null);
        int before = requests.get();

        AiRouteClient.RouteResult result = client.route("第四个问题应该被熔断", null);

        assertEquals(3, before, "前三次应各发一次请求");
        assertEquals(3, requests.get(), "熔断期内不该再发请求");
        assertTrue(result.degraded());
        assertEquals("CIRCUIT_OPEN", result.reason());
        assertTrue(client.breaker().isOpen(), "熔断器应处于打开状态");
    }

    @Test
    @DisplayName("连不上（服务已停）→ 默认路由，不抛异常")
    void connectionRefusedFallsBack() {
        server.stop(0);
        server = null;

        AiRouteClient.RouteResult result = client.route("服务没起来怎么办", null);

        assertEquals(AiRouteClient.DEFAULT_SKILL, result.skill());
        assertTrue(result.degraded());
        assertEquals("CALL_FAILED", result.reason());
    }

    @Test
    @DisplayName("RouteResult.fallback 语义：有过滤能力标记为 false，便于调用方分支")
    void fallbackHasNoFilter() {
        AiRouteClient.RouteResult result = client.route("你好", null);
        assertNotNull(result.sourceTypes());
        assertFalse(result.hasSourceFilter());
    }
}
