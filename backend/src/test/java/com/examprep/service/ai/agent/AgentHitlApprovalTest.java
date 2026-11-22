package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentStreamRequest;
import com.examprep.entity.AiAgentCheckpoint;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentCheckpointMapper;
import com.examprep.mapper.AiAgentRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Agent HITL 审批挂起与恢复测试")
class AgentHitlApprovalTest {

    private AiAgentCheckpointMapper checkpointMapper;
    private AiAgentRunMapper runMapper;
    private AgentStreamService streamService;
    private AgentCheckpointService service;

    @BeforeEach
    void setUp() {
        checkpointMapper = mock(AiAgentCheckpointMapper.class);
        runMapper = mock(AiAgentRunMapper.class);
        streamService = mock(AgentStreamService.class);
        service = new AgentCheckpointService(checkpointMapper, runMapper, streamService, new ObjectMapper());
    }

    private AiAgentRun waitingRun(Long id, Long userId) {
        AiAgentRun r = new AiAgentRun();
        r.setId(id);
        r.setUserId(userId);
        r.setSessionId("s-hitl-1");
        r.setQuestion("帮我清理错题");
        r.setStatus(AiAgentRun.STATUS_WAITING_APPROVAL);
        return r;
    }

    private AiAgentCheckpoint waitingCheckpoint(Long cpId, Long runId, String tool, String token) {
        AiAgentCheckpoint cp = new AiAgentCheckpoint();
        cp.setId(cpId);
        cp.setRunId(runId);
        cp.setStatus("WAITING_APPROVAL");
        cp.setPayloadJson("{\"event\":\"approval_required\",\"tool\":\"" + tool
                + "\",\"approvalToken\":\"" + token + "\"}");
        return cp;
    }

    @Test
    void approvesAndResumesWithApprovedTools() {
        when(runMapper.selectById(10L)).thenReturn(waitingRun(10L, 3L));
        when(checkpointMapper.findLatestWaitingApproval(10L))
                .thenReturn(waitingCheckpoint(99L, 10L, "danger_write", "tok-123456"));
        when(checkpointMapper.markApprovalResolved(99L, "APPROVED")).thenReturn(1);
        when(streamService.start(any(AgentStreamRequest.class), eq(3L), eq("trace001")))
                .thenReturn(new AgentStreamService.StreamStart(new SseEmitter(), 11L, "trace001", "s-hitl-1"));

        AgentCheckpointService.ApprovalOutcome out =
                service.approve(10L, 3L, false, "tok-123456", true, "trace001");

        assertTrue(out.approved());
        assertEquals("RESUMED", out.status());
        assertEquals(11L, out.newRunId());
        assertEquals("danger_write", out.tool());

        ArgumentCaptor<AgentStreamRequest> cap = ArgumentCaptor.forClass(AgentStreamRequest.class);
        verify(streamService).start(cap.capture(), eq(3L), eq("trace001"));
        assertEquals(List.of("danger_write"), cap.getValue().getApprovedTools());
    }

    @Test
    void rejectsAndCancelsRunWithoutStartingStream() {
        when(runMapper.selectById(10L)).thenReturn(waitingRun(10L, 3L));
        when(checkpointMapper.findLatestWaitingApproval(10L))
                .thenReturn(waitingCheckpoint(99L, 10L, "danger_write", "tok-123456"));
        when(checkpointMapper.markApprovalResolved(99L, "REJECTED")).thenReturn(1);

        AgentCheckpointService.ApprovalOutcome out =
                service.approve(10L, 3L, false, "tok-123456", false, "trace002");

        assertFalse(out.approved());
        assertEquals(AiAgentRun.STATUS_CANCELLED, out.status());
        assertNull(out.newRunId());
        verify(streamService, never()).start(any(), any(), any());
    }

    @Test
    void blocksCrossUserWrongTokenAndDuplicateApproval() {
        when(runMapper.selectById(10L)).thenReturn(waitingRun(10L, 3L));
        when(checkpointMapper.findLatestWaitingApproval(10L))
                .thenReturn(waitingCheckpoint(99L, 10L, "danger_write", "tok-123456"));

        BizException forbidden = assertThrows(BizException.class,
                () -> service.approve(10L, 999L, false, "tok-123456", true, "t1"));
        assertEquals(ResultCode.FORBIDDEN.getCode(), forbidden.getCode());

        BizException badToken = assertThrows(BizException.class,
                () -> service.approve(10L, 3L, false, "wrong-token", true, "t2"));
        assertEquals(ResultCode.CONFLICT.getCode(), badToken.getCode());

        when(checkpointMapper.markApprovalResolved(99L, "APPROVED")).thenReturn(0);
        BizException dup = assertThrows(BizException.class,
                () -> service.approve(10L, 3L, false, "tok-123456", true, "t3"));
        assertEquals(ResultCode.CONFLICT.getCode(), dup.getCode());
    }
}
