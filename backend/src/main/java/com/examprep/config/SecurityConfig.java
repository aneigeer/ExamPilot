package com.examprep.config;

import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.security.InternalTokenFilter;
import com.examprep.security.JwtAuthenticationFilter;
import com.examprep.service.ai.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 6 配置
 * 使用 SecurityFilterChain，禁止 WebSecurityConfigurerAdapter（已在 Spring Security 6 中移除）
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    /**
     * 允许跨域的来源（逗号分隔）。
     *
     * <p>默认仅本机：管理端与后端同源部署，App 端为原生 OkHttp 无跨域需求。
     * 部署到正式域名后，用 {@code app.cors.allowed-origins} 或环境变量
     * {@code APP_CORS_ALLOWED_ORIGINS} 注入实际域名——否则从其它源发起的浏览器请求会被拦。
     */
    @Value("${app.cors.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    /** 匿名可读白名单（PUBLIC）：只读的 GET 接口 */
    private static final String[] PUBLIC_GET = {
            // 考试知识体系
            "/exams/**",
            "/subjects/**",
            "/knowledge/**",
            // 题库
            "/questions/**",
            // 资料库
            "/resources/**",
            "/files/preview/**",
            // 课程库（校内课程维度）
            "/colleges/**",
            "/schools/**",
            "/majors/**",
            "/teachers/**",
            "/semesters/**",
            "/courses/**",
            // AI 导学：能力探测需匿名可调（App 未登录时也要能判断是否显示 AI 入口）
            "/ai/capability",
            // Wiki 主题页浏览与导航图（公开知识内容，匿名可读）
            "/wiki/**",
            // 其他
            "/announcements/**",
            "/search/**",
            "/home"
    };

    /**
     * 虽在 PUBLIC_GET 前缀内、但实际需要登录的接口
     * （必须声明在 PUBLIC_GET 之前才会生效）
     */
    private static final String[] AUTH_REQUIRED_GET = {
            "/resources/mine",
            "/favorites/**",
            "/me/**",
            // AI 导学：学情画像与个性化推荐是个人数据，必须登录
            "/ai/knowledge-profile",
            "/ai/recommendations"
    };

    /** 完全放行（无需登录，含 POST） */
    private static final String[] PUBLIC_ALL = {
            "/auth/login",
            "/auth/register",
            "/auth/guest",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-resources/**",
            "/webjars/**",
            "/error",
            "/favicon.ico",
            // 健康探活：供负载均衡/容器编排匿名探测；details 已在配置中关闭
            "/actuator/health",
            "/actuator/health/**"
    };

    /*
     * 关于 CSP（内容安全策略）—— 暂不启用，原因如下：
     *
     * 管理端是「零构建」单页应用，用的是 Vue **全局版**（含运行时模板编译器），
     * 编译器在运行时通过 new Function() 生成渲染函数，因此 CSP 的 script-src
     * 必须显式允许 'unsafe-eval'，否则整页不会初始化（页面只剩 {{ }} 模板占位符）。
     * 已实测：仅用 script-src 'self' 时管理端不渲染；加入 'unsafe-eval' 后组件可渲染，
     * 但当前环境缺少可信的浏览器复验手段（自动化代理多次结论不一致），
     * 按「未测试不下发」的原则，先不启用 CSP。
     *
     * 需要启用时（建议先在本机真浏览器验证登录页与各管理页正常，再上线）：
     *   String CSP = "default-src 'self'; script-src 'self' 'unsafe-eval'; "
     *       + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
     *       + "connect-src 'self'; object-src 'none'; base-uri 'self'; "
     *       + "frame-ancestors 'none'; form-action 'self'";
     *   并在此处 headers 块内加：headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP));
     *
     * 彻底方案（可去掉 'unsafe-eval'）：给管理端引入 Vue 构建步骤，把模板预编译，
     * 代价是放弃「零构建」的部署便利，属较大改动，暂不做。
     */

    /**
     * 内部接口安全链（Java ↔ Python 专用）
     *
     * <p>用 {@code securityMatcher} 完全隔离 JWT/session 体系，不依赖主链的规则书写顺序。
     * 令牌未配置时 {@link InternalTokenFilter} 一律返回 403。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .addFilterBefore(new InternalTokenFilter(aiProperties),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 安全响应头：防点击劫持 / MIME 嗅探 / 信息泄露（等保与上架审核常用项）
                // 注意：各子配置方法返回的是子配置对象而非 HeadersConfigurer，
                //      因此用语句块逐个设置，不能链式调用。
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.deny());
                    headers.contentTypeOptions(Customizer.withDefaults());
                    headers.referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.permissionsPolicy(permissions -> permissions.policy(
                            "geolocation=(), microphone=(), camera=()"));
                    // CSP 暂不启用，原因见类内注释（零构建 Vue 需要 'unsafe-eval'，暂缺可信复验）
                    // TLS 由 nginx 终止并下发 HSTS；后端自身走明文，不重复发 HSTS
                    headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE / 异步响应：原请求已完成鉴权，ASYNC/ERROR 二次分派不能再被拦截，
                        // 否则流结束后会抛 AuthorizationDeniedException（响应已提交，无法回滚）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 预检请求
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 认证与文档
                        .requestMatchers(PUBLIC_ALL).permitAll()
                        // 下载：凭短时效签名访问（签名在业务层用 HMAC 校验），因此不需登录态
                        .requestMatchers(HttpMethod.GET, "/files/download/**").permitAll()
                        // 需要登录的 GET：必须声明在 PUBLIC_GET 之前，否则会被前缀规则误放行
                        .requestMatchers(HttpMethod.GET, AUTH_REQUIRED_GET).authenticated()
                        // 匿名可读接口
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        // AI 问答：需登录（不带 token → 401）。
                        // 业务层用 SecurityUtils.currentUserIdOrNull() 取用户，天然兼容游客调用场景
                        //（游客可提问，只是不建画像加权、不做薄弱知识点 boost）。
                        .requestMatchers(HttpMethod.POST, "/ai/ask").authenticated()
                        // 管理端静态页面（Vue 单页应用）匿名可访问；页面内的管理 API 仍有角色校验
                        .requestMatchers(HttpMethod.GET, "/", "/admin/", "/admin/index.html",
                                "/admin/vendor/**").permitAll()
                        // 管理端：超管专属
                        .requestMatchers("/admin/logs/audit").hasAnyRole("AUDITOR", "ADMIN")
                        .requestMatchers("/admin/users/**", "/admin/logs/**", "/admin/roles/**")
                        .hasRole("ADMIN")
                        // 管理端：审核员及以上
                        .requestMatchers("/admin/**").hasAnyRole("AUDITOR", "ADMIN")
                        // 其余一律需登录
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, ResultCode rc) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(rc)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 收紧同源白名单：管理端与后端同源部署（/api/admin/index.html），App 端为原生 OkHttp 无跨域需求。
        // 来源通过 app.cors.allowed-origins 配置，便于部署到正式域名后按需放开。
        cfg.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
