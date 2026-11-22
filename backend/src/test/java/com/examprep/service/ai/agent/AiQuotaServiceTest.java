package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiQuotaOverride;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiQuotaOverrideMapper;
import com.examprep.service.ai.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 每日 Token 与请求配额闸门单测")
class AiQuotaServiceTest {

    @Mock
    private AiAgentRunMapper runMapper;

    @Mock
    private AiQuotaOverrideMapper overrideMapper;

    private AiProperties properties;
    private AiQuotaService quotaService;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getQuota().setEnabled(true);
        properties.getQuota().setStudentDailyTokens(10_000);
        properties.getQuota().setStudentDailyRequests(10);
        properties.getQuota().setAdminDailyTokens(100_000);
        properties.getQuota().setAdminDailyRequests(100);
        quotaService = new AiQuotaService(properties, runMapper, overrideMapper);
    }

    @Test
    @DisplayName("未超额时正常放行并计算剩余额度")
    void allowWhenUnderQuota() {
        when(runMapper.selectUserDailyUsage(eq(101L), any(), any()))
                .thenReturn(Map.of("requestCount", 3L, "tokenSum", 2500L));

        AiQuotaService.QuotaSnapshot snap = assertDoesNotThrow(
                () -> quotaService.checkAndRecord(101L, "STUDENT", true));
        assertEquals(10_000, snap.dailyTokenLimit());
        assertEquals(10, snap.dailyRequestLimit());
        assertEquals(2500L, snap.usedTokens());
        assertEquals(3L, snap.usedRequests());
        assertEquals(7500L, snap.remainingTokens());
        assertEquals(7L, snap.remainingRequests());
    }

    @Test
    @DisplayName("当日请求数达到上限时返回 429 TOO_MANY_REQUESTS")
    void rejectWhenRequestLimitReached() {
        when(runMapper.selectUserDailyUsage(eq(102L), any(), any()))
                .thenReturn(Map.of("requestCount", 10L, "tokenSum", 1000L));

        BizException ex = assertThrows(BizException.class,
                () -> quotaService.checkAndRecord(102L, "STUDENT", false));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("请求次数已达上限"));
    }

    @Test
    @DisplayName("当日累计 Token 达到上限时返回 429 TOO_MANY_REQUESTS")
    void rejectWhenTokenLimitReached() {
        when(runMapper.selectUserDailyUsage(eq(103L), any(), any()))
                .thenReturn(Map.of("requestCount", 2L, "tokenSum", 10_000L));

        BizException ex = assertThrows(BizException.class,
                () -> quotaService.checkAndRecord(103L, "STUDENT", false));
        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("Token 额度已用尽"));
    }

    @Test
    @DisplayName("单用户配额覆盖优先于角色默认配额")
    void singleUserOverrideTakesPrecedence() {
        AiQuotaOverride ov = new AiQuotaOverride();
        ov.setUserId(104L);
        ov.setDailyTokenLimit(50_000);
        ov.setDailyRequestLimit(50);
        when(overrideMapper.selectByUserId(104L)).thenReturn(ov);
        when(runMapper.selectUserDailyUsage(eq(104L), any(), any()))
                .thenReturn(Map.of("requestCount", 20L, "tokenSum", 25_000L));

        AiQuotaService.QuotaSnapshot snap = assertDoesNotThrow(
                () -> quotaService.checkAndRecord(104L, "STUDENT", false));
        assertTrue(snap.overridden());
        assertEquals(50_000, snap.dailyTokenLimit());
        assertEquals(50, snap.dailyRequestLimit());
    }

    @Test
    @DisplayName("管理员角色自动适用管理员高配额")
    void adminRoleUsesHigherLimits() {
        when(runMapper.selectUserDailyUsage(eq(1L), any(), any()))
                .thenReturn(Map.of("requestCount", 25L, "tokenSum", 40_000L));

        AiQuotaService.QuotaSnapshot snap = assertDoesNotThrow(
                () -> quotaService.checkAndRecord(1L, "ADMIN", false));
        assertEquals(100_000, snap.dailyTokenLimit());
        assertEquals(100, snap.dailyRequestLimit());
    }
}
