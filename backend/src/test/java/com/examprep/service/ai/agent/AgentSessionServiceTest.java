package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiAgentRun;
import com.examprep.entity.AiAgentSession;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiAgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话与记忆：归属隔离、历史顺序、摘要滚动。
 */
@DisplayName("Agent 会话与分层记忆")
class AgentSessionServiceTest {

    private AiAgentSessionMapper sessionMapper;
    private AiAgentRunMapper runMapper;
    private AgentSessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AiAgentSessionMapper.class);
        runMapper = mock(AiAgentRunMapper.class);
        service = new AgentSessionService(sessionMapper, runMapper);
    }

    private AiAgentSession session(String sessionId, Long userId, String summary) {
        AiAgentSession row = new AiAgentSession();
        row.setSessionId(sessionId);
        row.setUserId(userId);
        row.setSummary(summary);
        row.setTurnCount(1);
        return row;
    }

    @Test
    @DisplayName("新建会话：客户端未传 sessionId 时生成合法 ID 并落库")
    void createsSessionWhenAbsent() {
        when(sessionMapper.selectBySessionId(anyString())).thenReturn(null);

        String sid = service.resolve(null, 3L, "第一问");

        assertTrue(sid.startsWith("s-"));
        assertTrue(sid.length() <= 32);
        verify(sessionMapper).insert(any(AiAgentSession.class));
    }

    @Test
    @DisplayName("跨用户复用 sessionId 直接 403，不读取他人记忆")
    void rejectsCrossUserSession() {
        when(sessionMapper.selectBySessionId("s-other")).thenReturn(session("s-other", 9L, "x"));

        BizException e = assertThrows(BizException.class,
                () -> service.resolve("s-other", 3L, "偷看别人的会话"));

        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("非法 sessionId 字符返回参数错误")
    void rejectsIllegalSessionId() {
        BizException e = assertThrows(BizException.class,
                () -> service.resolve("bad id!", 3L, "q"));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("history 按时间正序返回，且会话归属不符时返回空")
    void historyIsChronologicalAndOwnerScoped() {
        when(sessionMapper.selectBySessionId("s-1")).thenReturn(session("s-1", 3L, null));
        AiAgentRun third = run(3L, "q3", "a3");
        AiAgentRun second = run(2L, "q2", "a2");
        AiAgentRun first = run(1L, "q1", "a1");
        // Mapper 约定：倒序取最近 N 轮
        when(runMapper.selectRecentTurns(eq("s-1"), eq(3L), anyInt()))
                .thenReturn(List.of(third, second, first));

        List<Map<String, Object>> turns = service.history("s-1", 3L, 6);

        assertEquals(List.of("q1", "q2", "q3"),
                turns.stream().map(t -> t.get("question")).toList());

        assertEquals(List.of(), service.history("s-1", 4L, 6));
    }

    @Test
    @DisplayName("recordTurn 滚动摘要超长时保留尾部，不整段丢弃")
    void recordTurnTruncatesToTail() {
        when(sessionMapper.selectBySessionId("s-1"))
                .thenReturn(session("s-1", 3L, "旧".repeat(1800)));

        service.recordTurn("s-1", "新问题", "新答案");

        verify(sessionMapper).recordTurn(eq("s-1"),
                org.mockito.ArgumentMatchers.argThat(summary ->
                        summary != null && summary.length() <= 1800 && summary.endsWith("新答案")),
                eq("新问题"));
    }

    @Test
    @DisplayName("会话不存在时不写记忆，避免污染")
    void recordTurnIgnoresMissingSession() {
        when(sessionMapper.selectBySessionId("s-gone")).thenReturn(null);

        service.recordTurn("s-gone", "q", "a");

        verify(sessionMapper, never()).recordTurn(anyString(), anyString(), anyString());
    }

    private AiAgentRun run(Long id, String question, String answer) {
        AiAgentRun row = new AiAgentRun();
        row.setId(id);
        row.setQuestion(question);
        row.setAnswer(answer);
        return row;
    }
}
