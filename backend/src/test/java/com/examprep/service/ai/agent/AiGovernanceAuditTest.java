package com.examprep.service.ai.agent;

import com.examprep.controller.AiAgentController;
import com.examprep.controller.AiOpsController;
import com.examprep.controller.WikiAdminController;
import com.examprep.dto.ai.AgentApprovalRequest;
import com.examprep.dto.ai.AgentMetricsVO;
import com.examprep.entity.AsyncTask;
import com.examprep.service.OperationLogService;
import com.examprep.service.wiki.WikiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 治理审计日志与 Prometheus 指标导出单测")
class AiGovernanceAuditTest {

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private AgentCheckpointService checkpointService;

    @Mock
    private AgentStreamService streamService;

    @Mock
    private AgentRunService runService;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private WikiService wikiService;

    @Mock
    private AsyncJobService asyncJobService;

    @Mock
    private AgentMetricsService agentMetricsService;

    @Mock
    private AiQuotaService quotaService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockAdminContext() {
        var loginUser = new com.examprep.security.LoginUser(
                1L, "admin", "管理员", "", "ADMIN", true);
        var auth = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("HITL 审批通过/拒绝均写入 OperationLog 且含 traceId")
    void hitlApproveRecordsAuditLog() {
        mockAdminContext();
        AiAgentController controller = new AiAgentController(
                streamService, runService, sessionService, checkpointService);
        controller.setOperationLogService(operationLogService);

        when(checkpointService.approve(eq(88L), eq(1L), anyBoolean(), eq("tok123"), eq(true), anyString()))
                .thenReturn(new AgentCheckpointService.ApprovalOutcome(
                        88L, true, "RESUMED", 99L, "submit_answer", "1122334455667788"));

        AgentApprovalRequest req = new AgentApprovalRequest();
        req.setApprovalToken("tok123");
        req.setApproved(true);
        controller.approve(88L, req, "1122334455667788");

        ArgumentCaptor<String> detailCap = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).record(
                eq("AI_GOVERNANCE"), eq("HITL_APPROVE"), eq("AI_AGENT_RUN"), eq(88L), detailCap.capture());
        assertTrue(detailCap.getValue().contains("tool=submit_answer"));
        assertTrue(detailCap.getValue().contains("traceId=1122334455667788"));
        assertFalse(detailCap.getValue().contains("tok123"), "审计日志不得记录一次性审批 Token");
    }

    @Test
    @DisplayName("Wiki 退役与重新蒸馏操作写入 OperationLog")
    void wikiRetireRecordsAuditLog() {
        mockAdminContext();
        WikiAdminController controller = new WikiAdminController(wikiService);
        controller.setOperationLogService(operationLogService);

        when(wikiService.retire(eq(12L), anyString())).thenReturn(Map.of("id", 12L, "trustTier", "RETIRED"));
        controller.retire(12L, "考纲已更新");

        verify(operationLogService).record(
                eq("AI_GOVERNANCE"), eq("WIKI_RETIRE"), eq("WIKI_PAGE"), eq(12L), anyString());
    }

    @Test
    @DisplayName("离线评测触发、回归用例导出与配额修改均写入 OperationLog")
    void opsActionsRecordAuditLog() {
        mockAdminContext();
        AiOpsController controller = new AiOpsController(
                asyncJobService, null, runService, agentMetricsService,
                null, null, null, null, null, checkpointService);
        controller.setOperationLogService(operationLogService);
        controller.setQuotaService(quotaService);

        AsyncTask task = new AsyncTask();
        task.setId(55L);
        task.setType("EVAL_REFUSAL");
        when(asyncJobService.submit(eq("EVAL_REFUSAL"), any(), eq(1L))).thenReturn(task);
        controller.runRefusalEval();
        verify(operationLogService).record(
                eq("AI_GOVERNANCE"), eq("EVAL_TRIGGER"), eq("ASYNC_TASK"), eq(55L), anyString());

        when(runService.exportEvalCase(eq(77L), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(Map.of("question", "脱敏问题"));
        controller.exportEvalCase(77L, "OUT", true, "refusal", false);
        verify(operationLogService).record(
                eq("AI_GOVERNANCE"), eq("EVAL_CASE_EXPORT"), eq("AI_AGENT_RUN"), eq(77L), anyString());

        when(quotaService.updateOverride(eq(3L), eq(50000), eq(50), any(), eq(1L)))
                .thenReturn(new AiQuotaService.QuotaSnapshot(
                        3L, "STUDENT", true, 50000, 50, 0L, 0L, 50000L, 50L, true, "2026-11-23T00:00"));
        controller.updateUserQuota(3L, Map.of("dailyTokenLimit", 50000, "dailyRequestLimit", 50));
        verify(operationLogService).record(
                eq("AI_GOVERNANCE"), eq("QUOTA_UPDATE"), eq("USER"), eq(3L), anyString());
    }

    @Test
    @DisplayName("Prometheus 指标端点输出符合 exposition format 规范")
    void prometheusEndpointFormatsMetricsCorrectly() {
        AgentMetricsVO vo = new AgentMetricsVO();
        vo.setDays(7);
        vo.setTotal(42L);
        vo.setRunning(1L);
        vo.setStatusCounts(Map.of(
                "RUNNING", 1L,
                "SUCCESS", 38L,
                "REFUSED", 2L,
                "FAILED", 1L,
                "CANCELLED", 0L
        ));
        vo.setDegradedRate(0.025);
        AgentMetricsVO.Latency lat = new AgentMetricsVO.Latency();
        lat.setP95Ms(1250L);
        vo.setLatency(lat);
        vo.setTotalTokens(128000L);
        vo.setEstimatedCostCny(java.math.BigDecimal.valueOf(0.512));
        when(agentMetricsService.metrics(7)).thenReturn(vo);

        AiOpsController controller = new AiOpsController(
                asyncJobService, null, runService, agentMetricsService,
                null, null, null, null, null, checkpointService);
        String text = controller.prometheusMetrics(7);

        assertTrue(text.contains("# TYPE examprep_ai_runs_total gauge"));
        assertTrue(text.contains("examprep_ai_runs_total{window_days=\"7\"} 42"));
        assertTrue(text.contains("examprep_ai_runs_by_status{status=\"SUCCESS\"} 38"));
        assertTrue(text.contains("examprep_ai_latency_p95_ms 1250"));
        assertTrue(text.contains("examprep_ai_tokens_total 128000"));
        assertTrue(text.contains("examprep_jvm_heap_used_bytes "));
    }
}
