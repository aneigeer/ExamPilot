package com.examprep.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具（jjwt 0.12.x API）
 */
@Slf4j
@Component
public class JwtUtil {

    private static final String CLAIM_UID = "uid";
    private static final String CLAIM_STUDENT_NO = "stu";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-minutes:10080}") long expireMinutes) {
        if (secret == null || secret.isBlank()) {
            // application.yml 的默认值已置空：密钥必须显式注入，防止 Git 里的默认密钥被用来伪造 token
            throw new IllegalStateException(
                    "app.jwt.secret 未配置：请设置环境变量 JWT_SECRET，"
                            + "或在 application-local.yml 中配置 app.jwt.secret（HS256 要求至少 32 字节）");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度不足，HS256 要求至少 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = expireMinutes * 60L * 1000L;
    }

    /** 签发令牌 */
    public String generate(Long userId, String studentNo, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMillis);
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_UID, userId);
        claims.put(CLAIM_STUDENT_NO, studentNo);
        claims.put(CLAIM_ROLE, role);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /** 解析令牌，无效返回 null（不抛异常，交给过滤器放行到匿名） */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        Number uid = claims.get(CLAIM_UID, Number.class);
        return uid == null ? null : uid.longValue();
    }

    public String getStudentNo(Claims claims) {
        return claims.get(CLAIM_STUDENT_NO, String.class);
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public long getExpireMillis() {
        return expireMillis;
    }
}
