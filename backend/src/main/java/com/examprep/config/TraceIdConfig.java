package com.examprep.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 链路追踪过滤器注册。
 *
 * <p>为什么不用 {@code @Component} 直接注册：那样无法指定
 * <ul>
 *   <li>{@code DispatcherType} —— 必须排除 {@code ASYNC}/{@code ERROR}，
 *       否则 SSE 收尾与错误转发会二次进入 Filter，重复 {@code setHeader}；</li>
 *   <li>URL 模式 —— 显式 {@code /*}，确保 {@code /internal/**} 独立安全链也被覆盖
 *       （Python 回调 Java 内部接口时日志能串上同一 traceId）。</li>
 * </ul>
 *
 * <p>顺序 {@link Ordered#HIGHEST_PRECEDENCE}：必须早于 Spring Security 的 -100，
 * 否则 401/403 响应没有 {@code X-Trace-Id}。
 */
@Configuration
public class TraceIdConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setName("traceIdFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
