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
 * 认证入口防爆破限流（审计 B1：错误码 429「请求过频」）。
 *
 * 策略：同一 IP 固定窗口（60 秒）
 * - POST /auth/login    最多 5 次
 * - POST /auth/register 最多 3 次（独立桶，互不影响）
 * 超出返回 429。仅保护认证入口，不影响其余业务接口。
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LOGIN_LIMIT = 5;
    private static final int REGISTER_LIMIT = 3;
    private static final long WINDOW_MS = 60_000L;
    /** 桶数量超过该阈值时触发一次过期清理，防止长期运行内存无限增长 */
    private static final int MAX_BUCKETS = 10_000;

    /** key = 规则名:IP，value = 该桶内的请求时间戳 */
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        int limit = 0;
        String rule = null;
        if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/auth/login")) {
            limit = LOGIN_LIMIT;
            rule = "login";
        } else if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/auth/register")) {
            limit = REGISTER_LIMIT;
            rule = "register";
        }

        if (limit > 0) {
            cleanExpiredBucketsIfNeeded();
            String ip = clientIp(request);
            long now = System.currentTimeMillis();
            Deque<Long> deque = buckets.computeIfAbsent(rule + ":" + ip, k -> new ArrayDeque<>());
            boolean blocked = false;
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                    deque.pollFirst();
                }
                if (deque.size() >= limit) {
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

    /** 桶总量超阈值时移除全部已过期的桶（简单全量清理即可，触发频率极低） */
    private void cleanExpiredBucketsIfNeeded() {
        if (buckets.size() <= MAX_BUCKETS) {
            return;
        }
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }

    /** nginx 反代场景优先取 X-Forwarded-For 第一个 IP（最原始客户端），没有再取直连地址 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
