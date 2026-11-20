package com.examprep.controller;

import com.examprep.service.CodeRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code app.code-run.enabled} 开关的装配语义（<b>双向</b>验证，不启动 Web 容器）。
 *
 * <p><b>为什么单独一个测试类</b>：{@code @SpringBootTest} 的配置项在类级别固定，
 * 一个类只能验证一个方向；而验证「显式开启后仍可用」若走真实 HTTP，就必须在宿主机执行
 * 用户代码——与本修复的安全目标直接冲突。这里用 {@link ApplicationContextRunner}
 * 只回答一个问题：<b>给定这个配置值，{@link CodeRunController} 到底有没有被注册成 Bean</b>。
 * 没注册 → {@code POST /api/run} 必然 404；注册了 → 映射随 {@code @PostMapping} 一起回来。
 * 依赖用一个不触达任何外部资源的实例顶替（构造器只赋值），
 * <b>零数据库、零子进程、零网络、零真实代码执行</b>。
 *
 * <p>四个用例对应四种真实场景：
 * <ol>
 *   <li>配置项完全缺失（换配置中心 / profile 覆盖漏了）→ 不注册（默认拒绝，fail-safe）；</li>
 *   <li>{@code enabled=false}（发布默认值）→ 不注册；</li>
 *   <li>{@code enabled=true}（本机调试手工开启）→ 注册，且可正常实例化；</li>
 *   <li>写法不规范的取值（{@code yes} / {@code 1} / 空串）→ 仍不注册，不做宽松解析。</li>
 * </ol>
 */
@DisplayName("代码运行开关：缺省/关闭不注册，只有显式 true 才注册")
class CodeRunGateTest {

    /**
     * 只装配 Controller 本身 + 一个永远不会被调用的服务实例：
     * 不加载 DataSource / Redis / Security / Tomcat。
     *
     * <p>服务实例用 {@code new CodeRunService(null, null)} 构造 —— 两个 mapper 只被真正执行时才会用到，
     * 这里不会调用任何方法，因此不产生数据库访问。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CodeRunService.class, () -> new CodeRunService(null, null))
            .withUserConfiguration(CodeRunController.class);

    /**
     * 断言上下文正常启动，且 Controller 的注册状态符合预期。
     *
     * @param ctx                已启动的上下文
     * @param expectedRegistered true 表示期望已注册（开关为 true）
     */
    private void assertGate(ApplicationContext ctx, boolean expectedRegistered) {
        int count = ctx.getBeanNamesForType(CodeRunController.class).length;
        if (expectedRegistered) {
            assertEquals(1, count, "app.code-run.enabled=true 时应注册唯一的 CodeRunController");
            assertNotNull(ctx.getBean(CodeRunController.class), "注册后必须能实例化");
        } else {
            assertEquals(0, count, "关闭状态下 CodeRunController 不应注册（否则 POST /api/run 仍可达）");
            assertFalse(ctx.containsBean("codeRunController"),
                    "连 Bean 名都不该存在，避免被其它组件按名注入后绕过开关");
        }
    }

    /** 启动一次上下文并断言开关语义；启动失败（而非「没注册」）必须显式报出来，避免误判为通过。 */
    private void runAndAssert(ApplicationContextRunner target, boolean expectedRegistered) {
        target.run(ctx -> {
            assertNull(ctx.getStartupFailure(), "上下文应能正常启动，实际失败：" + ctx.getStartupFailure());
            assertGate(ctx, expectedRegistered);
        });
    }

    @Test
    @DisplayName("① 配置项缺失 → 不注册（fail-safe：漏配等于关闭）")
    void missingPropertyKeepsControllerUnregistered() {
        runAndAssert(runner, false);
    }

    @Test
    @DisplayName("② app.code-run.enabled=false（发布默认值）→ 不注册")
    void disabledKeepsControllerUnregistered() {
        runAndAssert(runner.withPropertyValues("app.code-run.enabled=false"), false);
    }

    @Test
    @DisplayName("③ app.code-run.enabled=true（本机调试显式开启）→ 注册，接口恢复可用")
    void enabledRegistersController() {
        runAndAssert(runner.withPropertyValues("app.code-run.enabled=true"), true);
    }

    @Test
    @DisplayName("④ 只有字面量 true 才算开启：yes / 1 / on / 空串 一律视为关闭")
    void onlyLiteralTrueEnables() {
        for (String value : new String[] {"yes", "1", "on", ""}) {
            runAndAssert(runner.withPropertyValues("app.code-run.enabled=" + value), false);
        }
    }
}
