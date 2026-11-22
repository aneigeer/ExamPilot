package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiAgentCheckpoint;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentCheckpointMapper;
import com.examprep.mapper.AiAgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中断恢复：状态门槛 / 幂等 / 失败回滚标记。
 *
 * <p><b>为什么值得测</b>：恢复接口会<b>真的发起一轮新的模型调用</b>，
 * 而且"每个 run 只能恢复一次"是产品承诺。三类错误都很贵：
 * <ol>
 *   <li>状态门槛写漏 → 把 SUCCESS 的运行再跑一遍，用户看到重复回答，还多花钱；</li>
 *   <li>幂等靠"先查后改" → 并发下两次都成功，"只能一次"失效（这里靠条件更新，返回 0 即冲突）；</li>
 *   <li>启动失败不回滚标记 → 运维点一次撞上并发满（429），这个 run 就永远不能恢复了。</li>
 * </ol>
 */
@DisplayName("检查点恢复：状态门槛 / 幂等 / 失败回滚")
class CheckpointResumeTest {

    private static final Long RUN_ID = 101L;

    private AiAgentCheckpointMapper checkpointMapper;
    private AiAgentRunMapper runMapper;
    private AgentStreamService streamService;
    private AgentCheckpointService service;

    @BeforeEach
    void setUp() {
        checkpointMapper = mock(AiAgentCheckpointMapper.class);
        runMapper = mock(AiAgentRunMapper.class);
        streamService = mock(AgentStreamService.class);
        service = new AgentCheckpointService(checkpointMapper, runMapper, streamService,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private AiAgentRun run(String status) {
        AiAgentRun run = new AiAgentRun();
        run.setId(RUN_ID);
        run.setUserId(7L);
        run.setSessionId("s-abc");
        run.setQuestion("什么是死锁");
        run.setStatus(status);
        run.setTraceId("0123456789abcdef");
        return run;
    }

    // ------------------------------------------------------------------ 状态门槛

    @Test
    @DisplayName("运行不存在：404")
    void notFoundIs404() {
        when(runMapper.selectById(RUN_ID)).thenReturn(null);

        assertEquals(ResultCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid")).getCode());
        verify(streamService, never()).start(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("SUCCESS 运行：409（跑成功了不需要恢复，恢复=重复回答 + 重复花钱）")
    void successIs409() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_SUCCESS));

        BizException e = assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid"));

        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("SUCCESS"), e.getMessage());
        verify(streamService, never()).start(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("RUNNING 运行：409（还在跑，不能让两条流同时跑同一会话）")
    void runningIs409() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_RUNNING));

        assertEquals(ResultCode.CONFLICT.getCode(),
                assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid")).getCode());
    }

    @Test
    @DisplayName("FAILED 但没有检查点：409（没有可恢复的痕迹）")
    void noCheckpointIs409() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_FAILED));
        when(checkpointMapper.countByRunId(RUN_ID)).thenReturn(0L);

        BizException e = assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid"));

        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("检查点"), e.getMessage());
        verify(streamService, never()).start(any(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    @DisplayName("★ 已恢复过（条件更新影响 0 行）：409，且不会启动新会话")
    void secondResumeIs409() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_CANCELLED));
        when(checkpointMapper.countByRunId(RUN_ID)).thenReturn(3L);
        when(checkpointMapper.markResumed(RUN_ID)).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid"));

        assertEquals(ResultCode.CONFLICT.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("已经恢复过"), e.getMessage());
        verify(streamService, never()).start(any(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("CANCELLED + 有检查点：用原 question/sessionId 重开一轮，返回新 runId")
    void resumeStartsNewRun() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_CANCELLED));
        when(checkpointMapper.countByRunId(RUN_ID)).thenReturn(2L);
        when(checkpointMapper.markResumed(RUN_ID)).thenReturn(2);
        SseEmitter emitter = new SseEmitter();
        AgentStreamService.StreamStart expected =
                new AgentStreamService.StreamStart(emitter, 202L, "tid", "s-abc");
        when(streamService.start(any(), eq(7L), eq("tid"))).thenReturn(expected);

        AgentStreamService.StreamStart actual = service.resume(RUN_ID, "tid");

        assertSame(expected, actual);
        assertEquals(202L, actual.runId(), "必须把新 runId 交给调用方写进响应头");

        ArgumentCaptor<com.examprep.dto.ai.AgentStreamRequest> captor =
                ArgumentCaptor.forClass(com.examprep.dto.ai.AgentStreamRequest.class);
        verify(streamService).start(captor.capture(), eq(7L), eq("tid"));
        assertEquals("什么是死锁", captor.getValue().getQuestion(), "必须复用原问题");
        assertEquals("s-abc", captor.getValue().getSessionId(), "必须复用原会话，否则丢失上下文");
    }

    @Test
    @DisplayName("启动失败（如并发已满 429）：回滚 RESUMED 标记，恢复机会不被空耗")
    void startFailureRevertsMarker() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_FAILED));
        when(checkpointMapper.countByRunId(RUN_ID)).thenReturn(1L);
        when(checkpointMapper.markResumed(RUN_ID)).thenReturn(1);
        when(streamService.start(any(), anyLong(), anyString()))
                .thenThrow(new BizException(ResultCode.TOO_MANY_REQUESTS, "并发已满"));

        BizException e = assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid"));

        assertEquals(ResultCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        verify(checkpointMapper).revertResumed(RUN_ID);
    }

    @Test
    @DisplayName("回滚本身失败：只记日志，原始异常仍向上抛（不掩盖真因）")
    void revertFailureDoesNotMaskOriginal() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_FAILED));
        when(checkpointMapper.countByRunId(RUN_ID)).thenReturn(1L);
        when(checkpointMapper.markResumed(RUN_ID)).thenReturn(1);
        when(checkpointMapper.revertResumed(RUN_ID)).thenThrow(new IllegalStateException("db down"));
        when(streamService.start(any(), anyLong(), anyString()))
                .thenThrow(new BizException(ResultCode.AI_SERVICE_UNAVAILABLE));

        assertEquals(ResultCode.AI_SERVICE_UNAVAILABLE.getCode(),
                assertThrows(BizException.class, () -> service.resume(RUN_ID, "tid")).getCode());
    }

    // ------------------------------------------------------------------ 只读列表

    @Test
    @DisplayName("检查点列表：run 不存在 → 404；存在 → 原样返回")
    void listCheckpoints() {
        when(runMapper.selectById(404L)).thenReturn(null);
        assertEquals(ResultCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.list(404L)).getCode());

        when(runMapper.selectById(RUN_ID)).thenReturn(run(AiAgentRun.STATUS_FAILED));
        AiAgentCheckpoint step1 = new AiAgentCheckpoint();
        step1.setStepNo(1);
        step1.setStatus(AiAgentCheckpoint.STATUS_RECORDED);
        when(checkpointMapper.selectByRunId(RUN_ID)).thenReturn(List.of(step1));

        assertEquals(1, service.list(RUN_ID).size());
        assertEquals(AiAgentCheckpoint.STATUS_RECORDED, service.list(RUN_ID).get(0).getStatus());
    }

    @Test
    @DisplayName("缺 runId：400（不查库）")
    void missingRunIdIs400() {
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.resume(null, "tid")).getCode());
        assertEquals(ResultCode.PARAM_ERROR.getCode(),
                assertThrows(BizException.class, () -> service.list(null)).getCode());
        verify(checkpointMapper, never()).selectByRunId(any());
    }
}
