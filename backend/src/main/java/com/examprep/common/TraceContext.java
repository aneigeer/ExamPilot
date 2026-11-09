package com.examprep.common;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 链路追踪上下文（Java 侧唯一来源，勿新增第二个常量类）。
 *
 * <p>口径见 {@code docs/Agent阶段三接口契约.md} §1：
 * <ul>
 *   <li>头名 {@link #HEADER} 必须与 Python 侧 {@code main.TRACE_ID_HEADER} 字符串完全相等；</li>
 *   <li>值格式固定 16 位小写 hex（{@code ^[0-9a-f]{16}$}），超长/含中文/空白/大小写混杂一律视为非法；</li>
 *   <li>优先级：<b>合法入站头 &gt; 同线程已有 MDC &gt; 新生成</b>；非法入站值丢弃并重新生成，不报错（防日志注入）；</li>
 *   <li>入站值不进入 URL / query，只走请求头。</li>
 * </ul>
 *
 * <p>线程模型注意：MDC 是 {@link ThreadLocal}，线程池复用会串号。凡是跨线程传递
 * （如 SSE 转发任务）必须<b>显式把 traceId 作为参数传进去</b>，并在任务入口
 * {@code MDC.put}、{@code finally} 中 {@link #clear()}。
 */
public final class TraceContext {

    /** 出入站请求头名（与 Python 侧必须逐字一致） */
    public static final String HEADER = "X-Trace-Id";

    /** 日志 MDC key（与 application.yml 的 {@code %X{traceId:-}} 对应） */
    public static final String MDC_KEY = "traceId";

    /** traceId 长度（16 位 hex） */
    public static final int LENGTH = 16;

    /** 合法 traceId：恰好 16 位小写十六进制 */
    private static final Pattern LEGAL = Pattern.compile("^[0-9a-f]{16}$");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceContext() {
        // 常量与工具方法，不允许实例化
    }

    /**
     * 生成 16 位小写 hex traceId。
     *
     * @return 形如 {@code 0123456789abcdef} 的字符串
     */
    public static String generate() {
        byte[] bytes = new byte[LENGTH / 2];
        RANDOM.nextBytes(bytes);
        char[] out = new char[LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    /**
     * 取当前线程的 traceId：MDC 有合法值就复用，否则生成并<b>回填 MDC</b>，
     * 保证同一线程内 {@code Result.traceId} 与日志占位符一致。
     *
     * @return 一定非空的 16 位 hex 字符串
     */
    public static String current() {
        String fromMdc = legalize(MDC.get(MDC_KEY));
        if (fromMdc != null) {
            return fromMdc;
        }
        String generated = generate();
        MDC.put(MDC_KEY, generated);
        return generated;
    }

    /**
     * 按「合法入站头 &gt; MDC &gt; 新生成」解析 traceId。
     *
     * @param candidate 入站请求头原始值，可为 {@code null}
     * @return 一定非空的 16 位 hex 字符串
     */
    public static String of(String candidate) {
        String legal = legalize(candidate);
        return legal != null ? legal : current();
    }

    /**
     * 校验并规范化入站 traceId。
     *
     * @param candidate 原始值，可为 {@code null}
     * @return 合法则返回 trim 后的值；非法返回 {@code null}（调用方需重新生成）
     */
    public static String legalize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        return LEGAL.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * 清理 MDC，必须在 {@code finally} 中调用（线程池复用防串号）。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
        MDC.clear();
    }
}
