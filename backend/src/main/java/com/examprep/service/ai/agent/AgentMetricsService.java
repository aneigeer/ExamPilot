package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentMetricsVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行指标聚合（管理端观测面板）。
 *
 * <p>口径<b>逐条</b>对齐 {@code docs/Agent阶段三接口契约.md} §2（冻结版），
 * 与管理端/验收共用同一份定义，任何改动都要先改契约文件：
 * <ol>
 *   <li><b>窗口</b>：<code>to = SELECT NOW()</code>（一次取定）、<code>from = to - days 天</code>，
 *       所有子查询共用这组边界，避免多次 NOW() 漂移；</li>
 *   <li><b>总数</b>：{@code total} 含 RUNNING，{@code terminal = total - running}；</li>
 *   <li><b>四个率</b>：分母恒为 {@code terminal}（RUNNING 不计入），分母为 0 → {@code null}
 *       （不是 0，也不是 NaN/Infinity）；结果保留 4 位小数 HALF_UP；</li>
 *   <li><b>降级率</b>：分子分母同为 {@code status IN ('SUCCESS','REFUSED')} 子集，
 *       {@code degraded} 为 NULL 视为非降级；</li>
 *   <li><b>耗时</b>：只统计终态且 {@code total_ms > 0}（{@code total_ms=0} 表示没跑到生成，
 *       如 {@code executor_rejected}，剔除后用 {@code samples} 可核对）；</li>
 *   <li><b>P95</b>：MySQL 8 窗口函数最近秩法 {@code rn = CEIL(0.95*n)}，无样本 → {@code null}；</li>
 *   <li><b>generator</b>：NULL 归入 {@code unknown}，按 count 倒序最多 20 组，组内率的分母是该组 terminal；</li>
 *   <li><b>days 越界</b>：刻意<b>不</b>静默 clamp（口径必须显式），抛 {@link BizException} → HTTP 400。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMetricsService {

    /** 允许的最小统计天数 */
    public static final int MIN_DAYS = 1;

    /** 允许的最大统计天数 */
    public static final int MAX_DAYS = 90;

    /** 与 application.yml 的 {@code spring.jackson.time-zone} 保持一致，不做时区换算 */
    public static final String TIMEZONE = "Asia/Shanghai";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 状态键顺序固定，缺失补 0，不允许省略 */
    private static final String[] STATUS_KEYS = {
            AiAgentRun.STATUS_RUNNING,
            AiAgentRun.STATUS_SUCCESS,
            AiAgentRun.STATUS_REFUSED,
            AiAgentRun.STATUS_FAILED,
            AiAgentRun.STATUS_CANCELLED
    };

    private final AiAgentRunMapper runMapper;

    /**
     * 统计最近 {@code days} 天的运行指标。
     *
     * @param days 天数，必须在 {@value #MIN_DAYS}..{@value #MAX_DAYS} 之间
     * @return 指标（空窗口也返回完整结构，比率为 null）
     * @throws BizException days 越界（HTTP 400 / code 400）
     */
    public AgentMetricsVO metrics(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "days 必须在 " + MIN_DAYS + ".." + MAX_DAYS + " 之间（当前 " + days + "）");
        }

        // ① 窗口边界：DB 时间取一次，所有子查询共用
        LocalDateTime to = runMapper.selectNow();
        if (to == null) {
            to = LocalDateTime.now();
        }
        LocalDateTime from = to.minusDays(days);

        // ② 计数 / 降级 / 耗时 一次扫描拿全
        Map<String, Object> summary = runMapper.selectSummary(from, to);
        long total = asLong(get(summary, "total"));
        long running = asLong(get(summary, "running"));
        long success = asLong(get(summary, "successCnt"));
        long refused = asLong(get(summary, "refusedCnt"));
        long failed = asLong(get(summary, "failedCnt"));
        long cancelled = asLong(get(summary, "cancelledCnt"));
        long terminal = Math.max(0L, total - running);

        AgentMetricsVO vo = new AgentMetricsVO();
        vo.setDays(days);
        vo.setTimezone(TIMEZONE);
        vo.setFrom(from.format(TS));
        vo.setTo(to.format(TS));
        vo.setGeneratedAt(to.format(TS));
        vo.setTotal(total);
        vo.setRunning(running);
        vo.setTerminal(terminal);

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put(AiAgentRun.STATUS_RUNNING, running);
        statusCounts.put(AiAgentRun.STATUS_SUCCESS, success);
        statusCounts.put(AiAgentRun.STATUS_REFUSED, refused);
        statusCounts.put(AiAgentRun.STATUS_FAILED, failed);
        statusCounts.put(AiAgentRun.STATUS_CANCELLED, cancelled);
        // 兜底：状态键必须恒定 5 个（DB 若出现未知状态也不会挤掉这 5 个键）
        for (String key : STATUS_KEYS) {
            statusCounts.putIfAbsent(key, 0L);
        }
        vo.setStatusCounts(statusCounts);

        vo.setSuccessRate(rate(success, terminal));
        vo.setRefusedRate(rate(refused, terminal));
        vo.setFailureRate(rate(failed, terminal));
        vo.setCancelRate(rate(cancelled, terminal));

        long degradedCount = asLong(get(summary, "degradedCount"));
        long degradedDenominator = asLong(get(summary, "degradedDenominator"));
        vo.setDegradedCount(degradedCount);
        vo.setDegradedDenominator(degradedDenominator);
        vo.setDegradedRate(rate(degradedCount, degradedDenominator));

        AgentMetricsVO.Latency latency = new AgentMetricsVO.Latency();
        latency.setSamples(asLong(get(summary, "samples")));
        latency.setAvgMs(asNullableLong(get(summary, "avgMs")));
        latency.setMaxMs(asNullableLong(get(summary, "maxMs")));
        latency.setP95Ms(runMapper.selectP95(from, to));
        vo.setLatency(latency);

        vo.setTotalTokens(asLong(get(summary, "totalTokens")));
        vo.setAvgTokensPerRun(asNullableDouble(get(summary, "avgTokens")));
        vo.setEstimatedCostCny(asBigDecimal(get(summary, "totalCost")));
        vo.setTokenByModel(runMapper.selectTokenByModel(from, to));

        vo.setGenerators(generators(from, to));
        vo.setErrorTop5(errorTop5(from, to));

        log.debug("[Metrics] days={} window=[{}, {}] total={} terminal={} successRate={} p95={}",
                days, vo.getFrom(), vo.getTo(), total, terminal, vo.getSuccessRate(), latency.getP95Ms());
        return vo;
    }

    // ==================================================================
    // 分组与错误
    // ==================================================================

    /** 按 generator 分组：计数 + 组内成功率/平均耗时 + 组内 P95 */
    private List<AgentMetricsVO.GeneratorStat> generators(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = runMapper.selectGeneratorStats(from, to);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 组内 P95 另起一次分区窗口查询，key = generator（已 COALESCE 成 unknown）
        Map<String, Long> p95ByGenerator = new LinkedHashMap<>();
        List<Map<String, Object>> p95Rows = runMapper.selectGeneratorP95(from, to);
        if (p95Rows != null) {
            for (Map<String, Object> row : p95Rows) {
                p95ByGenerator.put(normalizeGenerator(get(row, "generator")),
                        asNullableLong(get(row, "p95Ms")));
            }
        }

        List<AgentMetricsVO.GeneratorStat> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String generator = normalizeGenerator(get(row, "generator"));
            long terminalCnt = asLong(get(row, "terminalCnt"));
            AgentMetricsVO.GeneratorStat stat = new AgentMetricsVO.GeneratorStat();
            stat.setGenerator(generator);
            stat.setCount(asLong(get(row, "cnt")));
            stat.setSuccessRate(rate(asLong(get(row, "successCnt")), terminalCnt));
            stat.setAvgMs(asNullableLong(get(row, "avgMs")));
            stat.setP95Ms(p95ByGenerator.get(generator));
            list.add(stat);
        }
        return list;
    }

    /** 错误 Top5：域 = 终态且 error 非空，按次数倒序 */
    private List<AgentMetricsVO.ErrorStat> errorTop5(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = runMapper.selectTopErrors(from, to);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AgentMetricsVO.ErrorStat> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            AgentMetricsVO.ErrorStat stat = new AgentMetricsVO.ErrorStat();
            Object error = get(row, "error");
            stat.setError(error == null ? "" : error.toString());
            stat.setCount(asLong(get(row, "cnt")));
            list.add(stat);
        }
        return list;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /**
     * 取 Map 中的值，兼容驱动返回的大小写差异（MySQL 用 {@code AS xxx} 时通常原样返回）。
     */
    private static Object get(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object value = row.get(key);
        if (value != null || row.containsKey(key)) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** generator 为 NULL / 空白统一归入 unknown */
    private static String normalizeGenerator(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = value.toString().trim();
        return text.isEmpty() ? "unknown" : text;
    }

    /**
     * 比率 = 分子 / 分母，保留 4 位小数 HALF_UP。
     *
     * @return 分母为 0 时返回 {@code null}（契约要求：不是 0、不是 NaN）
     */
    private static Double rate(long numerator, long denominator) {
        if (denominator <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 计数类：NULL → 0（空窗口时 SUM 返回 NULL） */
    private static long asLong(Object value) {
        Long converted = asNullableLong(value);
        return converted == null ? 0L : converted;
    }

    /** 计数类：NULL → null（耗时指标需区分「无样本」与「0ms」） */
    private static Long asNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asNullableDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static java.math.BigDecimal asBigDecimal(Object value) {
        if (value instanceof java.math.BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return java.math.BigDecimal.valueOf(n.doubleValue());
        }
        return java.math.BigDecimal.ZERO;
    }
}
