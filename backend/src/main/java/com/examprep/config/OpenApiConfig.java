package com.examprep.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 配置
 * 访问地址：http://localhost:8080/api/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI examPrepOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("大学生多考试备考知识库与题库系统 · API")
                        .version("v1.0.0")
                        .description("""
                                ## 访问级别说明（每个接口都已标注）
                                - **PUBLIC** —— 匿名可访问，游客可用
                                - **AUTH** —— 需要登录（学生 / 审核员 / 超管）
                                - **ROLE:AUDITOR** —— 需要审核员及以上
                                - **ROLE:ADMIN** —— 仅超管

                                ## 使用步骤
                                1. 调用 `POST /auth/login` 获取 `token`
                                2. 点击右上角 **Authorize**，把 token 粘进去（不需要加 Bearer 前缀）
                                3. 之后所有需要登录的接口都会自动带上令牌

                                ## 统一响应
                                ```json
                                { "code": 200, "message": "成功", "data": {}, "traceId": "..." }
                                ```
                                """)
                        .contact(new Contact().name("软件工程课程设计")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("填入登录接口返回的 token")));
    }
}
