package com.examprep.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可观测性与数据最小化配置（{@code app.observability.*}）。
 *
 * <p><b>为什么独立于 {@code AiProperties}</b>：本组配置管的是「日志与留存」这类
 * <b>合规边界</b>，不是 AI 能力参数。放在 {@code app.observability} 下，
 * ① 与 {@code @Scheduled(cron="${app.observability.retention-cron}")} 的键名严格对应；
 * ② 未来非 AI 模块（如练习记录留存）也能复用同一组开关，不必再挂到 {@code app.ai} 下。
 *
 * <p><b>默认值偏保守</b>：{@link #logQuestion} 默认 {@code false}——
 * 用户问题可能含个人信息或隐私内容，默认不进日志。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilityProperties {

    /**
     * 是否把<b>用户问题原文</b>写进日志（默认 false）
     *
     * <p>false 时日志只输出 {@code qLen=.., qHash=<sha256 前 8 位>}：
     * 既能按哈希把同一问题的多条日志串起来排查，又不会在日志系统里留下原文。
     * 排查特定问题时可临时置 true（或改本机 profile），<b>生产不建议长期开启</b>。
     */
    private boolean logQuestion = false;

    /**
     * Agent 运行轨迹（{@code ai_agent_run}）保留天数（默认 90）
     *
     * <p>轨迹里含用户问题与模型回答，属于最敏感的一类数据。超过保留期由
     * {@code AgentRunRetentionService} 分批物理删除（不软删：数据最小化要求真的删掉）。
     */
    private int retentionDays = 90;

    /**
     * 单批删除行数（默认 500）
     *
     * <p>分批是为了避免一条大 DELETE 长时间持锁、把在线写入拖死；
     * 循环删到 0 为止，日志只记总条数。
     */
    private int retentionBatch = 500;

    /**
     * 定时清理的 cron（默认每天 04:10，业务低峰）
     *
     * <p>保留字段是为了让运维可见/可审计：{@code @Scheduled} 的占位符默认值与此处一致。
     */
    private String retentionCron = "0 10 4 * * ?";
}
