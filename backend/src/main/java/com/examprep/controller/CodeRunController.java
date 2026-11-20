package com.examprep.controller;

import com.examprep.common.Result;
import com.examprep.dto.CodeRunRequest;
import com.examprep.dto.CodeRunResultVO;
import com.examprep.security.SecurityUtils;
import com.examprep.service.CodeRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编程题在线运行（P1）—— <b>默认关闭的特性开关（P0 安全门禁）</b>。
 *
 * <h2>为什么默认关闭</h2>
 * 本接口背后的 {@link CodeRunService} 用 {@code ProcessBuilder} 在<b>后端宿主的操作系统上裸跑</b>
 * {@code gcc} 与 {@code java.exe}，不具备任何沙箱语义（第 9 周静态审查结论见
 * {@code 项目规划/第9周/03_代码运行沙箱_2026-11-08.md}）：
 * <ul>
 *   <li><b>无 CPU / 内存 / PID 配额</b>：一个请求 = 1 子进程 + 2 排空线程 + 1 Tomcat 线程，无并发上限；</li>
 *   <li><b>无网络隔离</b>：用户代码可直连内网 MySQL（3306）、AI 服务（8000）、Ollama（11434）；</li>
 *   <li><b>无文件系统隔离</b>：工作目录只是 CWD，绝对路径读写不受限（可读 {@code application.yml} 中的库口令）；</li>
 *   <li><b>环境变量全继承</b>（F1 已做最小收口）：{@code CodeRunService#sandboxProcess} 现已
 *       {@code environment().clear()} 后只回填 PATH/临时目录白名单，
 *       子进程不再拿得到 {@code JWT_SECRET}/{@code DB_PASSWORD}；此<b>不等于隔离</b>；</li>
 *   <li><b>权威校验不足以构成边界</b>：{@code /auth/register} 匿名开放，任何人可自助注册拿 STUDENT 令牌，
 *       因此「需登录」不等于「可控」。</li>
 * </ul>
 * 结论：一旦管理员录入任一 {@code PROGRAMMING} 题型，本接口即等价于
 * <b>任意注册用户可在后端宿主执行任意 Java 代码</b>（P0 设计缺陷级高危）。
 * 当前库内无编程题、App 与管理端也均无调用方，关闭成本为零、业务影响为零 —— 故默认关闭。
 *
 * <p><b>★ F1 收尾（E1-05）：隔离未实现，禁止开启。</b>
 * 本轮仅对子进程做了环境变量白名单化（{@code CodeRunService#sandboxProcess}），
 * CPU / 内存 / PID / 网络 / 文件系统隔离仍为零；{@code app.code-run.enabled} 必须保持 {@code false}，
 * nginx 亦已对 {@code /api/run} 加 {@code deny all;} 双重拦截。接独立判题沙箱前严禁置 true。
 *
 * <h2>如何临时开启</h2>
 * <pre>
 *   启动参数：--app.code-run.enabled=true
 *   环境变量：APP_CODE_RUN_ENABLED=true
 *   配置项   ：app.code-run.enabled=true（application-local.yml，勿提交 Git）
 * </pre>
 * 关闭状态下本类<b>整体不注册为 Bean</b>（{@link ConditionalOnProperty} 在容器启动期生效），
 * 因此 {@code POST /api/run} 表现为 <b>404（接口不存在）</b>——而不是 403 或 500，
 * 也不会出现在 {@code /v3/api-docs} 里。这样既不给攻击者留探测信号，
 * 也不必改动 {@code SecurityConfig} 的鉴权规则（鉴权链保持原样）。
 *
 * <h2>开启前必须满足的隔离条件（第 9 周报告 6.5 节）</h2>
 * <ol>
 *   <li>容器化 / nsjail 执行：{@code --network=none}、只读 rootfs、tmpfs 工作目录、非 root 用户、
 *       drop capabilities、seccomp 白名单；</li>
 *   <li>CPU / 内存 / PID / 时间四类配额（cgroup 或 rlimit），且编译与运行<b>分离并各自超时</b>；</li>
 *   <li>输出硬上限（含编译输出）、语言白名单、代码长度上限、恶意 API 静态检查；</li>
 *   <li>异步队列 + 全局并发上限 + 每用户配额，并统一日志审计；</li>
 *   <li>环境变量白名单化（{@code environment().clear()} 后按需注入），杜绝密钥泄漏。</li>
 * </ol>
 * 上述任一条未完成验收前，<b>不得</b>在公网部署中把 {@code app.code-run.enabled} 置为 {@code true}。
 *
 * <p>注：{@link CodeRunService}、{@link CodeRunRequest}、{@link CodeRunResultVO} 均<b>刻意保留</b>——
 * 将来接入独立判题沙箱时只需替换执行层，接口契约无需重写。
 */
@Tag(name = "21. 编程题运行", description = "在线编译运行编程题代码（默认关闭，需 app.code-run.enabled=true）")
@RestController
@RequestMapping("/run")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.code-run.enabled", havingValue = "true")
public class CodeRunController {

    private final CodeRunService codeRunService;

    @Operation(summary = "在线运行代码",
            description = """
                    **访问级别：AUTH**，且受特性开关 `app.code-run.enabled` 约束（**默认 false，接口不存在**）。
                    支持 **C**（gcc）与 **Java**（JDK 11+ 单文件运行，公共类名需与类定义一致）。

                    - 题目的测试输入与期望输出存于答案 JSON：`{"input": "...", "expectedOutput": "..."}`
                    - 返回实际输出、错误信息、是否与期望输出一致
                    - 超过 10 秒强制终止
                    - ⚠️ 该功能直接在本机执行用户代码，无 CPU/内存/网络/文件系统隔离，
                      仅适合本机单人调试；公网部署必须先换独立判题沙箱并完成隔离验收
                    """)
    @PostMapping
    public Result<CodeRunResultVO> run(@Valid @RequestBody CodeRunRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(codeRunService.run(userId, request.getQuestionId(),
                request.getLanguage(), request.getCode()));
    }
}
