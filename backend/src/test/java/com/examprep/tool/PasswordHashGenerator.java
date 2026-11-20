package com.examprep.tool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希生成工具（仅用于生成 data.sql 里的初始账号密码）
 *
 * <p>用法（口令只由环境变量注入，不落仓库）：
 * <pre>
 *   QA_ADMIN_PWD=... QA_AUDITOR_PWD=... QA_STUDENT_PWD=... \
 *     mvn test -Dtest=PasswordHashGenerator -Dsurefire.useFile=false
 * </pre>
 *
 * <p><b>E1-06 / F1：仓库内不保存任何明文弱口令。</b>
 * 旧实现把 {@code admin123 / audit123} 等字面量写死在源码里；现在改为只从环境变量读取，
 * 未注入时用 {@link Assumptions#assumeTrue(boolean, String)} 跳过（不是失败），
 * 因此既不会让 {@code mvn test} 因缺环境而红，也不会把示例口令留在仓库或 CI 日志中。
 *
 * <p>BCrypt 每次加盐结果都不同，但同一明文的不同哈希都能校验通过，
 * 因此 data.sql 里写死一个即可。
 */
class PasswordHashGenerator {

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? null : value;
    }

    @Test
    void generateHashes() {
        String admin = requireEnv("QA_ADMIN_PWD");
        String auditor = requireEnv("QA_AUDITOR_PWD");
        String student = requireEnv("QA_STUDENT_PWD");
        Assumptions.assumeTrue(admin != null && auditor != null && student != null,
                "口令仅由 QA_ADMIN_PWD / QA_AUDITOR_PWD / QA_STUDENT_PWD 注入，未注入时跳过本工具");

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String[] plains = {admin, auditor, student};

        System.out.println("===================== 开始生成 =====================");
        for (int i = 0; i < plains.length; i++) {
            String hash = encoder.encode(plains[i]);
            boolean ok = encoder.matches(plains[i], hash);
            // 不把明文写进日志：只输出账号序号、哈希与自校验结果
            System.out.printf("%n账号[%d] 哈希: %s%n自校验: %s%n", i + 1, hash, ok ? "通过" : "失败");
        }
        System.out.println("===================================================");
    }
}
