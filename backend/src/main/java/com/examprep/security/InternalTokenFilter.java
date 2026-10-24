package com.examprep.security;

import com.examprep.service.ai.AiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口令牌校验（只挂载在 {@code /internal/**} 的独立安全链上）。
 *
 * <p>安全默认：令牌未配置时一律 403，不允许“空令牌等于放行”。
 * 比较使用 {@link MessageDigest#isEqual}，避免逐字节短路带来的时序侧信道。
 *
 * <p>注意：本类**不注册为 Spring Bean**，只在 SecurityConfig 中手工构造并加入安全链，
 * 否则 Spring Boot 会把它自动注册到全局 Servlet 链，导致所有请求都被拦截。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final AiProperties properties;

    public InternalTokenFilter(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.getInternalToken();
        String actual = request.getHeader(HEADER);
        if (!StringUtils.hasText(expected) || actual == null
                || !MessageDigest.isEqual(
                        expected.trim().getBytes(StandardCharsets.UTF_8),
                        actual.trim().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":403,\"message\":\"内部接口未授权\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
