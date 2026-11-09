package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.config.ObservabilityProperties;
import com.examprep.mapper.AiAgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 保留期清理：分批删、越界拒、失败不外抛。
 *
 * <p><b>为什么值得测</b>：这是一段<b>会真删数据</b>的代码，三类错误都是不可逆的：
 * <ol>
 *   <li>把 days 静默 clamp —— 运维传 0 本意是「立刻清空」还是「别删」，猜错就删多了；</li>
 *   <li>不分批 —— 几十万行一条 DELETE 会长时间持锁，正在收尾的 SSE 落终态会被卡死；</li>
 *   <li>循环条件写错 —— 少判一次就漏删，多判一次就空转；返回的 deleted 与真实不符，
 *       运维无法判断「到底清干净没有」。</li>
 * </ol>
 */
@DisplayName("Agent 轨迹保留期清理：分批 / 越界 / 无数据 / 失败不外抛")
class AgentRunRetentionServiceTest {

    private AiAgentRunMapper runMapper;
    private ObservabilityProperties observability;
    private AgentRunRetentionService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        observability = new ObservabilityProperties();
        observability.setRetentionDays(90);
        observability.setRetentionBatch(500);
        service = new AgentRunRetentionService(runMapper, observability);
    }

    @Test
    @DisplayName("分批：500/500/0 → 共删 1000，循环到删不动为止")
    void deletesInBatchesUntilEmpty() {
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(500, 500, 0);

        AgentRunRetentionService.PurgeResult result = service.purge(90);

        assertEquals(1000L, result.deleted());
        assertEquals(90, result.days());
        verify(runMapper, times(3)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("最后一批不满批：120 行 → 只查 1 次就停（省一次空查询）")
    void stopsWhenBatchNotFull() {
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(120);

        AgentRunRetentionService.PurgeResult result = service.purge(30);

        assertEquals(120L, result.deleted());
        verify(runMapper, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("无数据：返回 0，不抛异常、不空转")
    void handlesEmptyTable() {
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

        AgentRunRetentionService.PurgeResult result = service.purge(365);

        assertEquals(0L, result.deleted());
        assertEquals(365, result.days());
        verify(runMapper, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("cutoff 与 batch 正确传入：cutoff ≈ now - days，batch 取配置值")
    void passesCutoffAndConfiguredBatch() {
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);
        LocalDateTime before = LocalDateTime.now().minusDays(7).minusMinutes(1);

        service.purge(7);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> batch = ArgumentCaptor.forClass(Integer.class);
        verify(runMapper).deleteOlderThan(cutoff.capture(), batch.capture());
        assertEquals(500, batch.getValue(), "batch 必须取配置 retention-batch");
        assertTrue(cutoff.getValue().isAfter(before) || cutoff.getValue().isEqual(before),
                "cutoff 必须接近 now-7d：" + cutoff.getValue());
        assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().plusMinutes(1)));
    }

    @Test
    @DisplayName("days 越界（0 / -1 / 3651）：抛 400 且不查库，不静默 clamp")
    void rejectsOutOfRangeDays() {
        for (int days : new int[] {0, -1, 3651, 99999}) {
            BizException e = assertThrows(BizException.class, () -> service.purge(days));
            assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode(), "必须是 400");
            assertTrue(e.getMessage().contains("days 必须在 1..3650 之间"), e.getMessage());
        }
        verifyNoInteractions(runMapper);
    }

    @Test
    @DisplayName("边界 days=1 与 days=3650：合法")
    void acceptsBoundaryDays() {
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

        assertEquals(1, service.purge(1).days());
        assertEquals(3650, service.purge(3650).days());
    }

    @Test
    @DisplayName("定时任务：删库异常不外抛（定时任务抛异常没人接，但必须留痕）")
    void scheduledPurgeSwallowsException() {
        when(runMapper.deleteOlderThan(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.scheduledPurge());
    }

    @Test
    @DisplayName("定时任务：按配置的保留天数执行")
    void scheduledPurgeUsesConfiguredDays() {
        observability.setRetentionDays(30);
        when(runMapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

        service.scheduledPurge();

        verify(runMapper, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("预读计数：只读，用于确认弹窗")
    void previewCountsWithoutDeleting() {
        when(runMapper.countOlderThan(any())).thenReturn(42L);

        assertEquals(42L, service.preview(90));
        verify(runMapper, never()).deleteOlderThan(any(), anyInt());
    }
}
