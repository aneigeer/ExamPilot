package com.examprep.tool;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希生成工具（仅用于生成 data.sql 里的初始账号密码）
 *
 * 用法：mvn test -Dtest=PasswordHashGenerator -Dsurefire.useFile=false
 *
 * BCrypt 每次加盐结果都不同，但同一明文的不同哈希都能校验通过，
 * 因此 data.sql 里写死一个即可。
 */
class PasswordHashGenerator {

    @Test
    void generateHashes() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String[] plains = {"admin123", "audit123", "123456"};

        System.out.println("===================== 开始生成 =====================");
        for (String plain : plains) {
            String hash = encoder.encode(plain);
            boolean ok = encoder.matches(plain, hash);
            System.out.printf("%n明文: %s%n哈希: %s%n自校验: %s%n", plain, hash, ok ? "通过" : "失败");
        }
        System.out.println("===================================================");
    }
}
