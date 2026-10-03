package com.examprep.service;

import com.examprep.common.BizException;
import com.examprep.common.QuestionType;
import com.examprep.common.ResultCode;
import com.examprep.dto.CodeRunResultVO;
import com.examprep.entity.Question;
import com.examprep.entity.QuestionAnswer;
import com.examprep.mapper.QuestionAnswerMapper;
import com.examprep.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编程题在线运行（P1）
 *
 * 受控子进程执行：临时目录 → 编译 → 带 stdin 运行 → 捕获输出 → 超时强杀 → 清理。
 * 支持语言：C（gcc）/ Java（JDK 11+ 单文件运行）。
 * 安全边界：本服务执行用户提交的代码，仅适合本机单人使用环境，公网部署必须接独立判题沙箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeRunService {

    private static final long TIMEOUT_SECONDS = 10;
    private static final Pattern JAVA_CLASS = Pattern.compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private final QuestionMapper questionMapper;
    private final QuestionAnswerMapper questionAnswerMapper;

    public CodeRunResultVO run(Long userId, Long questionId, String language, String code) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(ResultCode.QUESTION_NOT_FOUND);
        }
        if (!"PROGRAMMING".equals(question.getType())) {
            throw new BizException("该题不是编程题，无法在线运行");
        }

        String lang = language == null ? "" : language.toUpperCase();
        if (!"C".equals(lang) && !"JAVA".equals(lang)) {
            throw new BizException("语言只支持 C 或 JAVA");
        }

        // 测试输入 / 期望输出存在题目答案 JSON：{"input": "...", "expectedOutput": "..."}
        String stdin = "";
        String expected = null;
        QuestionAnswer answer = questionAnswerMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuestionAnswer>lambdaQuery()
                        .eq(QuestionAnswer::getQuestionId, questionId));
        if (answer != null && answer.getAnswerContent() instanceof java.util.Map<?, ?> m) {
            Object in = m.get("input");
            Object exp = m.get("expectedOutput");
            if (in != null) {
                stdin = String.valueOf(in);
            }
            if (exp != null) {
                expected = String.valueOf(exp);
            }
        }

        Path dir = null;
        long begin = System.currentTimeMillis();
        try {
            dir = Files.createTempDirectory("coderun-");
            return switch (lang) {
                case "C" -> runC(dir, code, stdin, expected);
                case "JAVA" -> runJava(dir, code, stdin, expected);
                default -> throw new BizException("语言只支持 C 或 JAVA");
            };
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("代码运行失败 questionId={}", questionId, e);
            throw new BizException("运行失败：" + e.getMessage());
        } finally {
            // P2-8③：临时目录在所有退出路径（正常 / 异常 / 超时强杀）都会在此统一清理
            cleanup(dir);
            log.info("用户 {} 运行编程题 {}（{}），耗时 {}ms", userId, questionId, lang, System.currentTimeMillis() - begin);
        }
    }

    private CodeRunResultVO runC(Path dir, String code, String stdin, String expected) throws Exception {
        Path src = dir.resolve("main.c");
        Files.writeString(src, code, StandardCharsets.UTF_8);

        // 编译（本机未装 gcc 时给出明确提示，而不是 500）
        Process compile;
        try {
            compile = new ProcessBuilder("gcc", "main.c", "-o", "main.exe")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.info("本机未安装 gcc：{}", e.getMessage());
            return new CodeRunResultVO("", "本机未安装 gcc，无法运行 C 代码（可安装 MinGW 后重试，或改用 Java）",
                    expected, false, true, null);
        }
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean compiled = compile.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && compile.exitValue() == 0;
        if (!compiled) {
            return new CodeRunResultVO("", compileOut.isBlank() ? "编译失败" : compileOut, expected, false, false, null);
        }

        Path exe = dir.resolve("main.exe");
        if (!Files.exists(exe)) {
            return new CodeRunResultVO("", "本机未安装 gcc（或编译器不可用），无法运行 C 代码", expected, false, true, null);
        }
        return execute(dir, List.of(exe.toAbsolutePath().toString()), stdin, expected);
    }

    private CodeRunResultVO runJava(Path dir, String code, String stdin, String expected) throws Exception {
        // 从代码提取公共类名作为文件名（JDK 11+ 单文件运行要求文件名与公共类一致）
        String className = "Main";
        Matcher matcher = JAVA_CLASS.matcher(code);
        if (matcher.find()) {
            className = matcher.group(1);
        }
        Path src = dir.resolve(className + ".java");
        Files.writeString(src, code, StandardCharsets.UTF_8);

        // 用服务自身的 JDK 运行，避免依赖 PATH 里是否有 java
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(javaBin))) {
            javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }
        return execute(dir, List.of(javaBin, className + ".java"), stdin, expected);
    }

    /** 输出上限（约 1MB），防止失控程序把内存吃满 */
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    private CodeRunResultVO execute(Path dir, List<String> command, String stdin, String expected) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        Process process = pb.start();

        // P2-8①：起独立线程排空 stdout/stderr。若在 waitFor 之后才读输出，
        // 大输出会把 OS 管道缓冲区写满，子进程阻塞在 write 上永不退出，被误判为「超时」
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        Thread outDrainer = new Thread(() -> drain(process.getInputStream(), outBuf), "coderun-stdout");
        Thread errDrainer = new Thread(() -> drain(process.getErrorStream(), errBuf), "coderun-stderr");
        outDrainer.setDaemon(true);
        errDrainer.setDaemon(true);
        outDrainer.start();
        errDrainer.start();

        // 写 stdin：进程提前退出时管道已关，写入抛 IOException 属正常路径，吞掉即可
        if (stdin != null && !stdin.isEmpty()) {
            try {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            } catch (IOException ignored) {
                // 进程未读 stdin 就退出
            }
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 同上
        }

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            destroyTree(process);
            // 等排空线程把残余输出读完（被杀的进程管道会关闭，read 返回 -1）
            outDrainer.join(2000);
            errDrainer.join(2000);
            return new CodeRunResultVO("", "运行超时（超过 " + TIMEOUT_SECONDS + " 秒，已强制终止）", expected, false, false, null);
        }

        // 进程已退出，管道关闭，排空线程必然很快结束；join 只兜底防悬挂
        outDrainer.join(5000);
        errDrainer.join(5000);

        String output = outBuf.toString();
        String error = errBuf.toString();
        boolean matched = expected != null && normalize(output).equals(normalize(expected));
        long cost = 0;
        return new CodeRunResultVO(output, error, expected, matched, false, cost);
    }

    /** 后台排空一个输入流到缓冲区（P2-8①），超出上限后停止读取（该进程随后会被超时强杀） */
    private void drain(java.io.InputStream is, StringBuilder buf) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] cbuf = new char[4096];
            int n;
            while ((n = reader.read(cbuf)) != -1) {
                buf.append(cbuf, 0, n);
                if (buf.length() >= MAX_OUTPUT_CHARS) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // 进程被杀 / 管道中断
        }
    }

    /**
     * P2-8②：强杀整棵进程树。
     * 局限说明：JDK 的 destroyForcibly 只杀直接子进程；Windows 上若用户程序又派生了孙进程
     * （如 C 程序 spawn 子进程），destroyForcibly 杀不掉，这里用 `taskkill /T /F` 按树强杀兜底。
     * 本服务当前只运行单文件 C / Java 程序，通常无孙进程，此兜底防御用户代码自行 fork 的场景。
     */
    private void destroyTree(Process process) {
        process.destroyForcibly();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .start().waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("taskkill 兜底失败：{}", e.getMessage());
            }
        }
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").trim();
    }

    private void cleanup(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
