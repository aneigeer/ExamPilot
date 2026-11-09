package com.examprep.config;

import com.examprep.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪过滤器：入站解析 → MDC → 响应头 → 清理。
 *
 * <p>注册方式见 {@link TraceIdConfig}（{@code FilterRegistrationBean}，
 * <b>刻意不加 {@code @Component}</b>：否则 Boot 会把同一个 Filter 二次注册进全局链，
 * 且无法限定 {@code DispatcherType.REQUEST}）。
 *
 * <p>顺序必须早于 Spring Security（-100），否则 401/403 响应拿不到 {@code X-Trace-Id}。
 *
 * <p>响应头在 {@code chain.doFilter} <b>之前</b>设置，保证成功、异常、SSE、未授权等
 * 所有分支都携带该头。{@code finally} 中清理 MDC，避免 Tomcat 线程池复用时串号。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 合法入站头优先复用；缺失/非法则生成（不报错，防日志注入）
        String traceId = TraceContext.of(request.getHeader(TraceContext.HEADER));
        MDC.put(TraceContext.MDC_KEY, traceId);
        // ★ 先设头再进链：任何分支（含 Spring Security 的 401/403）都带得上
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
