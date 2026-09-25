package com.examprep.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录接口防爆破限流（审计 B1：错误码 429「请求过频」）。
 *
 * 策略：同一 IP 对 POST /auth/login 固定窗口（60 秒）最多 5 次，超出返回 429。
 * 仅保护登录入口，不影响其余业务接口（性能测试不受影响）。
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT = 5;
    private static final long WINDOW_MS = 60_000L;

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/auth/login")) {
            String ip = request.getRemoteAddr();
            long now = System.currentTimeMillis();
            Deque<Long> deque = buckets.computeIfAbsent(ip, k -> new ArrayDeque<>());
            boolean blocked = false;
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                    deque.pollFirst();
                }
                if (deque.size() >= LIMIT) {
                    blocked = true;
                } else {
                    deque.addLast(now);
                }
            }
            if (blocked) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
