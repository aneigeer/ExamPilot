package com.examprep.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 限流过滤器的<b>绕过回归测试</b>（阻断项 B：XFF 可伪造绕过登录限流）。
 *
 * <p><b>修之前会发生什么</b>：{@code clientIp()} 无条件取 {@code X-Forwarded-For} 第一段，
 * 于是「打满 5 次拿到 429 → 换一个假 XFF → 立刻 200」可以无限循环，登录爆破限流形同虚设
 * （第 9 周已实测）。本测试直接复现该攻击序列，并断言它现在必须失效。
 *
 * <p><b>为什么用过滤级而不是只测解析器</b>：解析器对只证明「算得对」，
 * 这里证明的是「过滤器的分桶 key 真的按它算」——中间若有人把
 * {@code clientIp()} 改回直接取 XFF，用例立刻变红。
 *
 * <p>直接调 {@code doFilterInternal}（同包可见），不起 Spring 上下文、不连库、不绑端口。
 */
@DisplayName("登录限流：伪造 X-Forwarded-For 无法换桶（阻断项 B 回归）")
class RateLimitFilterSpoofingTest {

    private static final String LOGIN = "/auth/login";
    private static final String REGISTER = "/auth/register";

    /** 记录被放行（进入后续链路）的次数；429 分支不会调用它 */
    private static final class CountingChain implements FilterChain {
        private int count;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            count++;
        }
    }

    private MockHttpServletResponse send(RateLimitFilter filter, CountingChain chain, String uri,
                                        String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    /** 默认可信代理：仅本机回环（与 application.yml 默认值一致） */
    private RateLimitFilter filter() {
        return new RateLimitFilter("127.0.0.1,::1");
    }

    @Test
    @DisplayName("① 直连 8080：每次换伪造 XFF，第 6 次仍必须 429（攻击序列失效）")
    void spoofedForwardedForCannotEscapeLimit() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 5; i++) {
            MockHttpServletResponse response = send(filter, chain, LOGIN, "203.0.113.7", "10.0.0." + i);
            assertEquals(200, response.getStatus(), "第 " + i + " 次在阈值内应放行");
        }
        MockHttpServletResponse blocked = send(filter, chain, LOGIN, "203.0.113.7", "10.0.0.99");
        assertEquals(429, blocked.getStatus(),
                "伪造 XFF 换桶必须失效：修复前这里会返回 200（限流被绕过）");
        assertEquals(5, chain.count, "被拦的请求不得进入后续链路");
    }

    @Test
    @DisplayName("② 经可信代理（nginx 回环）：不同真实客户端各自独立分桶，不互相误伤")
    void trustedProxyKeepsPerClientBuckets() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 8; i++) {
            MockHttpServletResponse response = send(filter, chain, LOGIN, "127.0.0.1", "198.51.100." + i);
            assertEquals(200, response.getStatus(), "不同真实客户端不应共用桶（第 " + i + " 个）");
        }
        assertEquals(8, chain.count);
    }

    @Test
    @DisplayName("③ 经可信代理：同一真实客户端连续 6 次 → 第 6 次 429（反代下限流依然有效）")
    void trustedProxyStillLimitsSingleClient() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 5; i++) {
            assertEquals(200, send(filter, chain, LOGIN, "127.0.0.1", "198.51.100.23").getStatus());
        }
        assertEquals(429, send(filter, chain, LOGIN, "127.0.0.1", "198.51.100.23").getStatus());
    }

    @Test
    @DisplayName("④ 可信代理 + 非法 XFF：回退真实地址，轮换垃圾值同样换不到桶")
    void illegalForwardedForFallsBackToRemoteAddr() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 5; i++) {
            assertEquals(200, send(filter, chain, LOGIN, "127.0.0.1", "unknown-" + i).getStatus());
        }
        assertEquals(429, send(filter, chain, LOGIN, "127.0.0.1", "also-unknown").getStatus(),
                "非法 XFF 必须回退 remoteAddr 计数，不能成为新桶");
    }

    @Test
    @DisplayName("⑤ 可信代理列表为空：XFF 一律不采信（默认拒绝）")
    void emptyTrustedProxiesIgnoresForwardedFor() throws Exception {
        RateLimitFilter filter = new RateLimitFilter("");
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 5; i++) {
            assertEquals(200, send(filter, chain, LOGIN, "127.0.0.1", "198.51.100." + i).getStatus());
        }
        assertEquals(429, send(filter, chain, LOGIN, "127.0.0.1", "198.51.100.99").getStatus());
    }

    @Test
    @DisplayName("⑥ 注册规则（3 次）：伪造 XFF 同样无效")
    void registerRuleAlsoImmuneToSpoofing() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 3; i++) {
            assertEquals(200, send(filter, chain, REGISTER, "203.0.113.8", "10.1.0." + i).getStatus());
        }
        assertEquals(429, send(filter, chain, REGISTER, "203.0.113.8", "10.1.0.99").getStatus());
    }

    @Test
    @DisplayName("⑦ 非认证接口不受影响：再多次也全放行（既有行为未变）")
    void otherEndpointsUnaffected() throws Exception {
        RateLimitFilter filter = filter();
        CountingChain chain = new CountingChain();

        for (int i = 1; i <= 20; i++) {
            assertEquals(200, send(filter, chain, "/questions/1", "203.0.113.9", null).getStatus());
        }
        assertEquals(20, chain.count, "限流只保护 /auth/login 与 /auth/register");
    }
}
