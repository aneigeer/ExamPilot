package com.examprep.config;

import com.examprep.common.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证入口防爆破限流（审计 B1：错误码 429「请求过频」）。
 *
 * 策略：同一 IP 固定窗口（60 秒）
 * - POST /auth/login    最多 5 次
 * - POST /auth/register 最多 3 次（独立桶，互不影响）
 * 超出返回 429。仅保护认证入口，不影响其余业务接口。
 *
 * <p><b>分桶用的 IP 怎么来（阻断项 B 修复）</b>：不再无条件采信 {@code X-Forwarded-For}
 * （客户端自带该头即可换桶，绕过限流），而是交给 {@link ClientIpResolver} 判定：
 * 只有 TCP 对端本身在 {@code app.rate-limit.trusted-proxies} 内（即我们自己的 nginx），
 * 才采信它写的 XFF；直连 8080 的请求一律按真实 {@code remoteAddr} 计数，伪造头无效。
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

    /**
     * 可信代理列表（IP 或 IPv4 CIDR）。只有这些对端写的 X-Forwarded-For 才被采信。
     *
     * <p>来源：{@code app.rate-limit.trusted-proxies}（逗号分隔）。默认仅本机回环，
     * 因为本机 nginx 与后端同机；Docker 编排下后端看到的对端是 nginx 容器 IP，
     * 需显式配置为容器网段（如 {@code 172.18.0.0/16}）。
     */
    private final Set<String> trustedProxies;

    /**
     * @param trustedProxiesCsv 可信代理配置（逗号分隔，允许空项与多余空白）
     */
    public RateLimitFilter(
            @Value("${app.rate-limit.trusted-proxies:127.0.0.1,::1}") String trustedProxiesCsv) {
        this.trustedProxies = parseTrustedProxies(trustedProxiesCsv);
    }

    /**
     * 解析可信代理配置：逗号分隔、去空白、丢空项、去重、不可变。
     *
     * @param csv 原始配置值，可为 null
     * @return 不可变集合；空配置返回空集合（语义 = 不信任任何代理，XFF 全部忽略）
     */
    private static Set<String> parseTrustedProxies(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String entry = part.trim();
            if (!entry.isEmpty()) {
                parsed.add(entry);
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

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

    /**
     * 取本次请求用于分桶的客户端 IP。
     *
     * <p><b>安全约束</b>：{@code X-Forwarded-For} 只在 TCP 对端属于可信代理时才被采信，
     * 否则完全忽略（见 {@link ClientIpResolver#resolve}）。这是阻断项 B 的修复点，
     * 请勿改回「直接取 XFF 第一段」的写法。
     *
     * @param request 当前请求
     * @return 分桶用的 IP 串（非 null）
     */
    private String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                trustedProxies);
    }
}

