package com.examprep.config;

import com.examprep.common.TraceContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路追踪过滤器。
 *
 * <p>为什么值得测：traceId 串错不会报错，只会让「排查时对不上日志」，
 * 是典型的<b>静默失效</b>。三个必须守住的点：
 * <ol>
 *   <li>合法入站头要复用（否则上游网关的 traceId 断链，五处一致性验证必挂）；</li>
 *   <li>非法入站值要丢弃重生成（否则可以把换行/中文写进日志 = 日志注入）；</li>
 *   <li>请求结束必须清空 MDC（Tomcat 线程池复用，残留值会串到下一个用户）。</li>
 * </ol>
 */
@DisplayName("链路追踪：复用 / 重生成 / 清理")
class TraceIdFilterTest {

    private static final String LEGAL = "0123456789abcdef";

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("合法入站头：复用并回写响应头，链路内 MDC 可见")
    void reusesLegalInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        request.addHeader(TraceContext.HEADER, LEGAL);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        FilterChain chain = (req, resp) -> mdcInChain.set(MDC.get(TraceContext.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertEquals(LEGAL, response.getHeader(TraceContext.HEADER), "响应头必须回写入站值");
        assertEquals(LEGAL, mdcInChain.get(), "链路内日志要能拿到同一个 traceId");
    }

    @Test
    @DisplayName("缺失入站头：生成 16 位 hex 并回写响应头")
    void generatesWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceContext.HEADER);
        assertNotNull(traceId, "任何响应都必须带 X-Trace-Id");
        assertTrue(traceId.matches("^[0-9a-f]{16}$"), "格式必须是 16 位小写 hex：" + traceId);
    }

    @Test
    @DisplayName("前导/尾随空白：trim 后合法则复用")
    void trimsSurroundingWhitespace() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        request.addHeader(TraceContext.HEADER, "  " + LEGAL + " ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(LEGAL, response.getHeader(TraceContext.HEADER));
    }

    @Test
    @DisplayName("超长入站头：丢弃并重新生成")
    void replacesOverlongHeader() throws Exception {
        assertReplaced("a".repeat(65));
    }

    @Test
    @DisplayName("含中文入站头：丢弃并重新生成（防日志注入）")
    void replacesChineseHeader() throws Exception {
        assertReplaced("追踪-1234567890ab");
    }

    @Test
    @DisplayName("纯空白入站头：丢弃并重新生成")
    void replacesBlankHeader() throws Exception {
        assertReplaced("     ");
    }

    @Test
    @DisplayName("大写/长度不符的 hex：丢弃并重新生成（契约值域为小写 16 位）")
    void replacesIllegalCaseHex() throws Exception {
        assertReplaced("0123456789ABCDEF");
        assertReplaced("0123456789abc");
    }

    @Test
    @DisplayName("请求结束：MDC 必须清空（线程池复用防串号）")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        request.addHeader(TraceContext.HEADER, LEGAL);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(MDC.get(TraceContext.MDC_KEY), "MDC 残留会串到下一个请求的日志里");
    }

    @Test
    @DisplayName("响应体 traceId 与响应头相等：Result 取的就是同一个值（契约 §4②）")
    void resultTraceIdEqualsResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        request.addHeader(TraceContext.HEADER, LEGAL);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> resultTraceId = new AtomicReference<>();
        // Result 构造函数取 TraceContext.current()，这里等价验证
        filter.doFilter(request, response, (req, resp) -> resultTraceId.set(TraceContext.current()));

        assertEquals(response.getHeader(TraceContext.HEADER), resultTraceId.get(),
                "body.traceId 必须与响应头完全相等，否则五处一致性验证必挂");
        assertEquals(LEGAL, resultTraceId.get());
    }

    @Test
    @DisplayName("链内抛异常（含 401/403/500 路径）：头仍带、MDC 仍清")
    void keepsHeaderAndClearsMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/ai/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 模拟后续过滤器/控制器直接失败（Spring Security 的 401/403 就是这种形态）
        FilterChain failing = (req, resp) -> {
            throw new IllegalStateException("unauthorized");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, failing));

        String traceId = response.getHeader(TraceContext.HEADER);
        assertNotNull(traceId, "异常分支同样必须带 X-Trace-Id");
        assertTrue(traceId.matches("^[0-9a-f]{16}$"));
        assertNull(MDC.get(TraceContext.MDC_KEY), "异常分支也不能残留 MDC");
    }

    /** 非法入站值：响应头与入站值不同、且符合 16 位小写 hex */
    private void assertReplaced(String illegal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/agent/runs");
        request.addHeader(TraceContext.HEADER, illegal);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceContext.HEADER);
        assertNotNull(traceId);
        assertNotEquals(illegal.trim(), traceId, "非法入站值必须被替换");
        assertTrue(traceId.matches("^[0-9a-f]{16}$"), "替换值必须是 16 位小写 hex：" + traceId);
        assertFalse(traceId.isEmpty());
    }
}
