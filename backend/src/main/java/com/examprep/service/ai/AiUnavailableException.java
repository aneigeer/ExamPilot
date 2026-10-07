package com.examprep.service.ai;

/**
 * AI 生成链路不可用异常（非超时即 5xx 时抛出）
 *
 * <p>由 {@code AiQaService} 捕获后自动降级到抽取式，用户无感。
 * 属于预期内的降级路径，故单独建类型以便上层精确捕获（不计入 5xx 错误统计）。
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
