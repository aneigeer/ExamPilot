package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentMetricsVO;
import com.examprep.mapper.AiAgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 运行指标口径。
 *
 * <p>为什么值得测：指标接口本身不会报错，<b>算错也看不出来</b>——
 * 分母把 RUNNING 算进去、空窗口返回 0% 而不是 null、generator 为 NULL 时丢组，
 * 都会让管理端给出错误结论。本测试锁定契约 §2 的分母与空数据语义：
 * <ul>
 *   <li>空窗口 → 结构完整、四个率与 P95 为 {@code null}、{@code statusCounts} 恒定 5 键；</li>
 *   <li>{@code terminal = total - running}，RUNNING 不进任何率的分母；</li>
 *   <li>{@code days} 越界 → {@link BizException}（HTTP 400），且<b>不查库</b>；</li>
 *   <li>P95 用最近秩法：{@code total_ms=[10,20,30,40]} → 40。</li>
 * </ul>
 */
@DisplayName("Agent 运行指标：分母口径与空数据")
class AgentMetricsServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 21, 0, 0);

    private AiAgentRunMapper runMapper;
    private AgentMetricsService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        service = new AgentMetricsService(runMapper);
    }

    /** 空窗口：DB 聚合返回 total=0，其余 SUM/AVG/MAX 全 NULL */
    private void stubEmptyWindow() {
        when(runMapper.selectNow()).thenReturn(NOW);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", 0L);
        summary.put("running", null);
        summary.put("successCnt", null);
        summary.put("refusedCnt", null);
        summary.put("failedCnt", null);
        summary.put("cancelledCnt", null);
        summary.put("degradedCount", null);
        summary.put("degradedDenominator", null);
        summary.put("samples", null);
        summary.put("avgMs", null);
        summary.put("maxMs", null);
        when(runMapper.selectSummary(any(), any())).thenReturn(summary);
        when(runMapper.selectP95(any(), any())).thenReturn(null);
        when(runMapper.selectGeneratorStats(any(), any())).thenReturn(List.of());
        when(runMapper.selectGeneratorP95(any(), any())).thenReturn(List.of());
        when(runMapper.selectTopErrors(any(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("空窗口：不返回 0%，比率为 null、P95 为 null、statusCounts 五键齐全")
    void emptyWindowKeepsNullRates() {
        stubEmptyWindow();

        AgentMetricsVO vo = service.metrics(7);

        assertEquals(7, vo.getDays());
        assertEquals("Asia/Shanghai", vo.getTimezone());
        assertEquals("2026-10-24 21:00:00", vo.getFrom(), "from = to - days 天");
        assertEquals("2026-10-31 21:00:00", vo.getTo());
        assertEquals(0L, vo.getTotal());
        assertEquals(0L, vo.getRunning());
        assertEquals(0L, vo.getTerminal());

        Map<String, Long> statusCounts = vo.getStatusCounts();
        assertEquals(5, statusCounts.size(), "状态键必须恒定 5 个，缺的补 0");
        for (String key : List.of("RUNNING", "SUCCESS", "REFUSED", "FAILED", "CANCELLED")) {
            assertTrue(statusCounts.containsKey(key), "缺少状态键：" + key);
            assertEquals(0L, statusCounts.get(key).longValue(), "无数据必须补 0：" + key);
        }

        assertNull(vo.getSuccessRate(), "分母为 0 必须返回 null，不是 0");
        assertNull(vo.getRefusedRate());
        assertNull(vo.getFailureRate());
        assertNull(vo.getCancelRate());
        assertNull(vo.getDegradedRate());
        assertEquals(0L, vo.getDegradedCount());
        assertEquals(0L, vo.getDegradedDenominator());

        assertNotNull(vo.getLatency());
        assertEquals(0L, vo.getLatency().getSamples());
        assertNull(vo.getLatency().getAvgMs());
        assertNull(vo.getLatency().getP95Ms());
        assertNull(vo.getLatency().getMaxMs());

        assertTrue(vo.getGenerators().isEmpty());
        assertTrue(vo.getErrorTop5().isEmpty());
    }

    @Test
    @DisplayName("RUNNING 不计入分母：total=5、running=1 → terminal=4，successRate=3/4")
    void runningExcludedFromDenominator() {
        when(runMapper.selectNow()).thenReturn(NOW);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", 5L);
        summary.put("running", 1L);
        summary.put("successCnt", 3L);
        summary.put("refusedCnt", 0L);
        summary.put("failedCnt", 1L);
        summary.put("cancelledCnt", 0L);
        summary.put("degradedCount", 2L);
        summary.put("degradedDenominator", 3L);
        summary.put("samples", 4L);
        summary.put("avgMs", 25L);
        summary.put("maxMs", 40L);
        when(runMapper.selectSummary(any(), any())).thenReturn(summary);
        when(runMapper.selectP95(any(), any())).thenReturn(40L);
        when(runMapper.selectGeneratorStats(any(), any())).thenReturn(List.of());
        when(runMapper.selectGeneratorP95(any(), any())).thenReturn(List.of());
        when(runMapper.selectTopErrors(any(), any())).thenReturn(List.of());

        AgentMetricsVO vo = service.metrics(7);

        assertEquals(5L, vo.getTotal());
        assertEquals(1L, vo.getRunning());
        assertEquals(4L, vo.getTerminal(), "terminal = total - running");
        assertEquals(0.75d, vo.getSuccessRate().doubleValue(), 1e-9, "3 / 4，分母不含 RUNNING");
        assertEquals(0.0d, vo.getRefusedRate().doubleValue(), 1e-9);
        assertEquals(0.25d, vo.getFailureRate().doubleValue(), 1e-9);
        assertEquals(0.0d, vo.getCancelRate().doubleValue(), 1e-9);
        // 降级口径：分子分母同为 SUCCESS+REFUSED 子集（此处分母 3 ≠ terminal 4）
        assertEquals(2L, vo.getDegradedCount());
        assertEquals(3L, vo.getDegradedDenominator());
        assertEquals(0.6667d, vo.getDegradedRate().doubleValue(), 1e-9, "2 / 3，保留 4 位小数");
        // P95 最近秩法：total_ms=[10,20,30,40] → CEIL(0.95*4)=4 → 40
        assertEquals(40L, vo.getLatency().getP95Ms().longValue());
        assertEquals(4L, vo.getLatency().getSamples());
        assertEquals(25L, vo.getLatency().getAvgMs().longValue());
        assertEquals(40L, vo.getLatency().getMaxMs().longValue());
        assertEquals(3L, vo.getStatusCounts().get("SUCCESS").longValue());
        assertEquals(1L, vo.getStatusCounts().get("RUNNING").longValue());
    }

    @Test
    @DisplayName("generator 为 NULL：归入 unknown，组内率的分母是该组 terminal")
    void nullGeneratorFallsBackToUnknown() {
        when(runMapper.selectNow()).thenReturn(NOW);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", 3L);
        summary.put("running", 0L);
        summary.put("successCnt", 2L);
        summary.put("refusedCnt", 0L);
        summary.put("failedCnt", 1L);
        summary.put("cancelledCnt", 0L);
        when(runMapper.selectSummary(any(), any())).thenReturn(summary);
        when(runMapper.selectP95(any(), any())).thenReturn(null);

        Map<String, Object> group = new HashMap<>();
        group.put("generator", null);
        group.put("cnt", 3L);
        group.put("terminalCnt", 3L);
        group.put("successCnt", 2L);
        group.put("avgMs", 120L);
        when(runMapper.selectGeneratorStats(any(), any())).thenReturn(List.of(group));

        Map<String, Object> p95Row = new HashMap<>();
        p95Row.put("generator", null);
        p95Row.put("p95Ms", 250L);
        when(runMapper.selectGeneratorP95(any(), any())).thenReturn(List.of(p95Row));
        when(runMapper.selectTopErrors(any(), any())).thenReturn(List.of());

        AgentMetricsVO vo = service.metrics(7);

        assertEquals(1, vo.getGenerators().size());
        AgentMetricsVO.GeneratorStat stat = vo.getGenerators().get(0);
        assertEquals("unknown", stat.getGenerator(), "NULL 必须归入 unknown，不能丢组");
        assertEquals(3L, stat.getCount());
        assertEquals(0.6667d, stat.getSuccessRate().doubleValue(), 1e-9);
        assertEquals(120L, stat.getAvgMs().longValue());
        assertEquals(250L, stat.getP95Ms().longValue(), "组内 P95 按 generator 分区取");
    }

    @Test
    @DisplayName("错误 Top5：原文与次数原样下发")
    void mapsTopErrors() {
        when(runMapper.selectNow()).thenReturn(NOW);
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", 2L);
        summary.put("running", 0L);
        summary.put("failedCnt", 2L);
        when(runMapper.selectSummary(any(), any())).thenReturn(summary);
        when(runMapper.selectP95(any(), any())).thenReturn(null);
        when(runMapper.selectGeneratorStats(any(), any())).thenReturn(List.of());
        when(runMapper.selectGeneratorP95(any(), any())).thenReturn(List.of());

        Map<String, Object> row = new HashMap<>();
        row.put("error", "timeout");
        row.put("cnt", 2L);
        when(runMapper.selectTopErrors(any(), any())).thenReturn(List.of(row));

        AgentMetricsVO vo = service.metrics(7);

        assertEquals(1, vo.getErrorTop5().size());
        assertEquals("timeout", vo.getErrorTop5().get(0).getError());
        assertEquals(2L, vo.getErrorTop5().get(0).getCount());
        assertEquals(1.0d, vo.getFailureRate().doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("days 越界（0 / -1 / 91 / 1000）：抛 400 且不查库，不静默 clamp")
    void rejectsOutOfRangeDays() {
        for (int days : new int[] {0, -1, 91, 1000}) {
            BizException e = assertThrows(BizException.class, () -> service.metrics(days));
            assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode(), "必须是 400");
            assertTrue(e.getMessage().contains("days 必须在 1..90 之间"), "文案要含范围：" + e.getMessage());
            assertTrue(e.getMessage().contains(String.valueOf(days)), "文案要含当前值");
        }
        verifyNoInteractions(runMapper);
    }

    @Test
    @DisplayName("边界 days=1 与 days=90：合法，窗口起止差等于天数")
    void acceptsBoundaryDays() {
        stubEmptyWindow();

        assertEquals(1, service.metrics(1).getDays());
        AgentMetricsVO max = service.metrics(90);
        assertEquals(90, max.getDays());
        assertEquals("2026-08-02 21:00:00", max.getFrom(), "to - 90 天");
    }
}
