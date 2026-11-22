package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行指标（管理端 {@code GET /admin/ai/metrics}）。
 *
 * <p>字段与分母口径<b>严格</b>按 {@code docs/Agent阶段三接口契约.md} §2 冻结，
 * 管理端按本结构取数，任何改动都要同步契约文件：
 * <ul>
 *   <li>{@code total} 含 RUNNING；{@code terminal = total - running}；</li>
 *   <li>四个率的分母都是 {@code terminal}，分母为 0 返回 {@code null}（不是 0、不是 NaN）；</li>
 *   <li>{@code degraded} 的分子分母同为 {@code status IN ('SUCCESS','REFUSED')} 子集；</li>
 *   <li>{@code latency} 只统计 {@code status <> 'RUNNING' AND total_ms > 0} 的行
 *       （{@code total_ms=0} 是「没跑到生成」的轮次，剔除后可凭 {@code samples} 核对）；</li>
 *   <li>P95 用 MySQL 8 窗口函数 + 最近秩法：{@code rn = CEIL(0.95 * n)}。</li>
 * </ul>
 */
@Data
@Schema(description = "Agent 运行指标")
public class AgentMetricsVO {

    @Schema(description = "统计窗口天数", example = "7")
    private int days;

    @Schema(description = "时区（与 DB 同时区，不做换算）", example = "Asia/Shanghai")
    private String timezone;

    @Schema(description = "窗口起点 yyyy-MM-dd HH:mm:ss", example = "2026-10-24 21:00:00")
    private String from;

    @Schema(description = "窗口终点 yyyy-MM-dd HH:mm:ss（取自一次 SELECT NOW()）",
            example = "2026-10-31 21:00:00")
    private String to;

    @Schema(description = "指标生成时间 yyyy-MM-dd HH:mm:ss", example = "2026-10-31 21:00:00")
    private String generatedAt;

    @Schema(description = "窗口内全部运行行数（含 RUNNING）", example = "128")
    private long total;

    @Schema(description = "RUNNING 行数", example = "8")
    private long running;

    @Schema(description = "终态行数 = total - running", example = "120")
    private long terminal;

    @Schema(description = "状态分布，恒定 5 键（RUNNING/SUCCESS/REFUSED/FAILED/CANCELLED）")
    private Map<String, Long> statusCounts;

    @Schema(description = "成功率 = SUCCESS / terminal；分母为 0 时为 null", example = "0.8000")
    private Double successRate;

    @Schema(description = "拒答率 = REFUSED / terminal；分母为 0 时为 null", example = "0.0500")
    private Double refusedRate;

    @Schema(description = "失败率 = FAILED / terminal；分母为 0 时为 null", example = "0.1333")
    private Double failureRate;

    @Schema(description = "取消率 = CANCELLED / terminal；分母为 0 时为 null", example = "0.0167")
    private Double cancelRate;

    @Schema(description = "降级轮次数（degraded=1 且 status IN (SUCCESS, REFUSED)）", example = "12")
    private long degradedCount;

    @Schema(description = "降级率分母（status IN (SUCCESS, REFUSED) 的行数）", example = "102")
    private long degradedDenominator;

    @Schema(description = "降级率；分母为 0 时为 null", example = "0.1176")
    private Double degradedRate;

    @Schema(description = "窗口内总 Token", example = "128000")
    private long totalTokens;

    @Schema(description = "单次平均 Token（无样本为 null）", example = "1024.5")
    private Double avgTokensPerRun;

    @Schema(description = "估算总成本（元）", example = "0.128000")
    private java.math.BigDecimal estimatedCostCny;

    @Schema(description = "按模型分布（model / runs / tokens / cost）")
    private List<Map<String, Object>> tokenByModel;

    @Schema(description = "耗时统计")
    private Latency latency;

    @Schema(description = "按 generator 分组（NULL 归入 unknown，按 count desc 取前 20 组）")
    private List<GeneratorStat> generators;

    @Schema(description = "错误 Top5")
    private List<ErrorStat> errorTop5;

    /** 耗时统计（只含终态且 total_ms &gt; 0 的行） */
    @Data
    @Schema(description = "耗时统计")
    public static class Latency {

        @Schema(description = "参与统计的样本数（可核对）", example = "118")
        private long samples;

        @Schema(description = "平均耗时 ms（四舍五入整数）；无样本为 null", example = "2415")
        private Long avgMs;

        @Schema(description = "P95 耗时 ms（最近秩法）；无样本为 null", example = "6120")
        private Long p95Ms;

        @Schema(description = "最大耗时 ms；无样本为 null", example = "15320")
        private Long maxMs;
    }

    /** 按生成器分组 */
    @Data
    @Schema(description = "生成器分组统计")
    public static class GeneratorStat {

        @Schema(description = "生成器名称，NULL 归入 unknown", example = "llm")
        private String generator;

        @Schema(description = "该组运行行数（含 RUNNING）", example = "80")
        private long count;

        @Schema(description = "该组成功率 = SUCCESS / 该组 terminal；分母为 0 时为 null")
        private Double successRate;

        @Schema(description = "该组平均耗时 ms；无样本为 null")
        private Long avgMs;

        @Schema(description = "该组 P95 耗时 ms；无样本为 null")
        private Long p95Ms;
    }

    /** 错误 Top5 */
    @Data
    @Schema(description = "错误统计")
    public static class ErrorStat {

        @Schema(description = "错误原文（不做归一化，动态内容会分行）", example = "timeout")
        private String error;

        @Schema(description = "出现次数", example = "7")
        private long count;
    }
}
