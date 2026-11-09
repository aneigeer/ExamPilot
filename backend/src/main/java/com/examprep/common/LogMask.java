package com.examprep.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 日志脱敏工具（数据最小化）。
 *
 * <p><b>为什么需要</b>：用户问题原文进日志等于把 UGC 内容复制到日志系统——
 * 日志的访问面比数据库宽得多（运维、日志平台、备份、导出的排障包），
 * 一旦写进去就难以按人撤回。而排查问题并不需要原文，只需要
 * 「同一个问题能不能串起来」+「长度对不对」。
 *
 * <p>输出格式固定：{@code qLen=12, qHash=a1b2c3d4}
 * <ul>
 *   <li>{@code qLen}：字符数（可用于识别超长/空问题）；</li>
 *   <li>{@code qHash}：SHA-256 前 8 位 hex（同问题同值，可 grep 聚合，且不可逆）。</li>
 * </ul>
 *
 * <p>开关 {@code app.observability.log-question=true} 时才输出原文（默认 false）。
 */
public final class LogMask {

    /** 哈希取前几位（8 位 hex ≈ 32 bit，够聚合又不至于被穷举反查短问题） */
    private static final int HASH_PREFIX = 8;

    private LogMask() {
        // 工具类
    }

    /**
     * 生成「用户问题」的日志安全文本。
     *
     * @param logRaw   是否允许输出原文（来自 {@code app.observability.log-question}）
     * @param question 问题原文，可为 {@code null}
     * @return 允许时返回原文；否则返回 {@code qLen=.., qHash=..}
     */
    public static String question(boolean logRaw, String question) {
        if (logRaw) {
            return question == null ? "-" : question;
        }
        if (question == null) {
            return "qLen=0, qHash=-";
        }
        return "qLen=" + question.length() + ", qHash=" + hash8(question);
    }

    /**
     * 任意文本的短哈希（SHA-256 前 8 位 hex）。
     *
     * @param text 待哈希文本
     * @return 8 位小写 hex；{@code null} 入参返回 {@code "-"}
     */
    public static String hash8(String text) {
        if (text == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_PREFIX);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0x0F, 16))
                        .append(Character.forDigit(b & 0x0F, 16));
                if (sb.length() >= HASH_PREFIX) {
                    break;
                }
            }
            return sb.substring(0, HASH_PREFIX);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不可达；降级为长度标记而不是让业务抛错
            return "-";
        }
    }
}
