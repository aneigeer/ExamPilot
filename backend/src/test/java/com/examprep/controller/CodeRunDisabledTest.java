package com.examprep.controller;

import com.examprep.service.CodeRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 代码运行接口「默认关闭」的 Web 层验证（真 Filter 链 + 真条件装配 + 真路由）。
 *
 * <p><b>为什么必须测这个</b>：{@code CodeRunService} 用 {@code ProcessBuilder} 在宿主机裸跑
 * 用户代码，无 CPU/内存/网络/文件系统隔离、子进程继承全部环境变量
 * （第 9 周审查：{@code 项目规划/第9周/03_代码运行沙箱_2026-11-08.md}）。
 * 「需要登录」不构成边界——{@code /auth/register} 匿名开放，任何人都能自助拿令牌。
 * 因此判定标准不是「谁不能调」，而是<b>默认配置下这个接口在容器里根本不存在</b>。
 *
 * <p>本测试断言三件事：
 * <ol>
 *   <li>已登录用户 {@code POST /run} → <b>404</b>（不是 403、不是 500；
 *       404 才不会向攻击者确认「这里有代码执行入口」）；</li>
 *   <li>未登录 {@code POST /run} → 401（Security 链仍排在路由前面，行为与改前一致）；</li>
 *   <li>容器中没有 {@link CodeRunController} 这个 Bean，但 {@link CodeRunService} 仍在
 *       —— 证明是「条件装配关掉了入口」，而不是「把实现删了」。</li>
 * </ol>
 *
 * <p><b>为什么不测「开启后能跑通」</b>：那需要真的在宿主机执行用户代码，与本次修复的安全目标
 * 直接冲突。开启方向只验证「Bean 是否被注册」，见 {@link CodeRunGateTest}。
 *
 * <p>路径不带 {@code /api}：MockMvc 不套用 {@code server.servlet.context-path}，
 * 与既有 {@code AiOpsEndpointsTest} 的写法一致。
 */
@DisplayName("代码运行：默认关闭时接口不存在（404 / 无 Bean / 不执行任何代码）")
@SpringBootTest(properties = "app.code-run.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CodeRunDisabledTest {

    /** 与真实调用等价的请求体；若 Controller 被误注册，这条请求会真的进到编译执行层。 */
    private static final String BODY = """
            {"questionId":1,"language":"C","code":"int main(void){return 0;}"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("① 已登录 POST /run → 404（接口不存在，且响应体不是 200 成功体）")
    @WithMockUser(username = "student", roles = "STUDENT")
    void disabledReturnsNotFound() throws Exception {
        MvcResult result = mockMvc.perform(post("/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andReturn();

        // 404 有两种合法载体：GlobalExceptionHandler 的 {code:404}（NoHandlerFoundException 分支），
        // 或 Spring Boot 默认错误体 {status:404}。二者都接受，但绝不允许是 {code:200,...} 的成功包。
        String text = result.getResponse().getContentAsString();
        if (!text.isBlank()) {
            JsonNode body = json.readTree(text);
            int semantic = body.has("code") ? body.path("code").asInt(-1) : body.path("status").asInt(-1);
            assertEquals(404, semantic, "404 响应体必须表达 404 语义，实际：" + text);
        }
    }

    @Test
    @DisplayName("② 未登录 POST /run → 401（Security 链行为未变，不会先命中路由）")
    void unauthenticatedIsRejectedBySecurity() throws Exception {
        mockMvc.perform(post("/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("③ 容器中无 CodeRunController Bean，但 CodeRunService 仍在（只关入口，不删实现）")
    void controllerBeanAbsentServiceBeanKept() {
        // 条件装配发生在容器启动期：@ConditionalOnProperty 不匹配 → 该 Bean 定义根本不注册，
        // 因此既没有映射，也不会出现在 /v3/api-docs 中。
        assertEquals(0, applicationContext.getBeanNamesForType(CodeRunController.class).length,
                "app.code-run.enabled=false 时 CodeRunController 不应注册");

        String[] services = applicationContext.getBeanNamesForType(CodeRunService.class);
        assertTrue(services.length > 0, "CodeRunService 必须保留，供将来接独立判题沙箱");
        assertNotNull(applicationContext.getBean(CodeRunService.class), "实现类应可正常注入");
    }
}
