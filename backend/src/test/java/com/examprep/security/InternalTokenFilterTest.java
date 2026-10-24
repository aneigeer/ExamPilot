package com.examprep.security;

import com.examprep.service.ai.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部接口令牌过滤器：空令牌默认拒绝、错误令牌 403、正确令牌放行。
 */
@DisplayName("内部接口令牌：默认拒绝，正确才放行")
class InternalTokenFilterTest {

    private InternalTokenFilter filter(String token) {
        AiProperties properties = new AiProperties();
        properties.setInternalToken(token);
        return new InternalTokenFilter(properties);
    }

    @Test
    @DisplayName("未配置令牌：一律 403")
    void emptyTokenRejectsAll() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/llm");
        request.addHeader(InternalTokenFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("").doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("内部接口未授权"));
    }

    @Test
    @DisplayName("错误令牌：403")
    void wrongTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/llm");
        request.addHeader(InternalTokenFilter.HEADER, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("expected-token").doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("正确令牌：进入后续链")
    void correctTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/llm");
        request.addHeader(InternalTokenFilter.HEADER, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("expected-token").doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }
}
