package com.examprep.common;

/**
 * 日志脱敏工具（防止学号、手机号、密钥等 PII 明文落盘）
 */
public final class LogMaskUtils {

    private LogMaskUtils() {
    }

    /**
     * 学号/账号脱敏：保留前 2 位与后 2 位，中间用星号替代；长度 ≤ 5 仅保留首尾。
     */
    public static String maskAccount(String raw) {
        if (raw == null || raw.isBlank()) {
            return "***";
        }
        String s = raw.trim();
        int len = s.length();
        if (len <= 2) {
            return "*".repeat(len);
        }
        if (len <= 5) {
            return s.charAt(0) + "*".repeat(len - 2) + s.charAt(len - 1);
        }
        return s.substring(0, 2) + "*".repeat(len - 4) + s.substring(len - 2);
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位。
     */
    public static String maskPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return "***";
        }
        String s = raw.trim();
        if (s.length() < 7) {
            return maskAccount(s);
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }
}
