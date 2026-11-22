package com.examprep.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件密钥卫生护栏：防止把真实云端 API Key 或数据库明文密码硬编码进配置文件。
 */
@DisplayName("配置文件密钥卫生静态防回归单测")
class SecretHygieneTest {

    private static final Pattern REAL_API_KEY_PATTERN =
            Pattern.compile("(sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,}|[0-9a-f]{32}\\.[A-Za-z0-9]{12,})");

    @Test
    @DisplayName("backend/src/main/resources/*.yml 不得包含明文数据库密码或真实云端 API Key")
    void backendResourcesContainNoHardcodedSecrets() throws IOException {
        Path resDir = Path.of("src", "main", "resources");
        assertTrue(Files.isDirectory(resDir), "找不到 src/main/resources");
        try (var stream = Files.list(resDir)) {
            List<Path> ymls = stream.filter(p -> p.toString().endsWith(".yml")).toList();
            assertFalse(ymls.isEmpty());
            for (Path yml : ymls) {
                String content = Files.readString(yml, StandardCharsets.UTF_8);
                assertFalse(REAL_API_KEY_PATTERN.matcher(content).find(),
                        yml.getFileName() + " 中疑似存在硬编码云端 API Key");
                for (String rawLine : content.split("\n")) {
                    String line = rawLine.trim();
                    if (line.startsWith("#") || !line.startsWith("password:")) {
                        continue;
                    }
                    String val = line.substring("password:".length()).trim();
                    assertTrue(val.isEmpty() || val.startsWith("${") || val.contains("CHANGE_ME") || val.contains("<"),
                            yml.getFileName() + " 的 password 字段未使用环境变量/外部密钥占位符: " + val);
                }
            }
        }
    }

    @Test
    @DisplayName(".env.example 与 .env.prod.example 不得包含真实密钥")
    void envExamplesContainNoRealSecrets() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (String rel : List.of("ai-service/.env.example", ".env.prod.example")) {
            Path file = root.resolve(rel);
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(REAL_API_KEY_PATTERN.matcher(content).find(),
                        rel + " 中疑似存在真实 API Key");
            }
        }
    }
}
