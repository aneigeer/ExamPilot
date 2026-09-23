package com.examprep.config;

import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            "/majors/**",
            "/teachers/**",
            "/semesters/**",
            "/courses/**",
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
            "/me/**"
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
            "/favicon.ico"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
                        // 管理端：超管专属
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
        cfg.setAllowedOriginPatterns(List.of("*"));
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
