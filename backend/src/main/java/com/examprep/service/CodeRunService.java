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

    private CodeRunResultVO execute(Path dir, List<String> command, String stdin, String expected) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        Process process = pb.start();

        if (stdin != null && !stdin.isEmpty()) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CodeRunResultVO("", "运行超时（超过 " + TIMEOUT_SECONDS + " 秒，已强制终止）", expected, false, false, null);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean matched = expected != null && normalize(output).equals(normalize(expected));
        long cost = 0;
        return new CodeRunResultVO(output, error, expected, matched, false, cost);
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
