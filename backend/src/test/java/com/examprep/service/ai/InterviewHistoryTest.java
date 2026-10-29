package com.examprep.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.dto.ai.InterviewDetailVO;
import com.examprep.dto.ai.InterviewHistoryVO;
import com.examprep.entity.AiInterviewQa;
import com.examprep.entity.AiInterviewSession;
import com.examprep.mapper.AiInterviewQaMapper;
import com.examprep.mapper.AiInterviewSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 面试历史查询单测（纯 Mockito）
 *
 * <p>重点守两件事：
 * <ol>
 *   <li><b>越权</b>：学生只能用 {@code detail(id)} 查<u>自己</u>的记录，
 *       查他人必须 403 —— 面试报告含能力评估与薄弱点，泄露给同学是隐私事故；</li>
 *   <li><b>列表口径</b>：{@code answeredCount}（实评题数，含追问轮）与
 *       {@code totalQuestions}（计划题数）必须分开，否则提前收尾的场次会被误读成「少答了」。</li>
 * </ol>
 */
@DisplayName("面试历史：越权拦截 / 列表口径 / 详情解析")
class InterviewHistoryTest {

    private AiInterviewSessionMapper sessionMapper;
    private AiInterviewQaMapper qaMapper;
    private AiInterviewService service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        sessionMapper = mock(AiInterviewSessionMapper.class);
        qaMapper = mock(AiInterviewQaMapper.class);
        service = new AiInterviewService(properties, objectMapper, sessionMapper, qaMapper);
    }

    private static AiInterviewSession session(long id, long userId) {
        AiInterviewSession s = new AiInterviewSession();
        s.setId(id);
        s.setUserId(userId);
        s.setPythonSessionId("py-" + id);
        s.setTopic("RAG");
        s.setTotalQuestions(5);
        s.setFinished(1);
        s.setTotalScore(67.0);
        s.setLevel("中等");
        s.setStartedAt(LocalDateTime.now().minusMinutes(10));
        s.setFinishedAt(LocalDateTime.now());
        return s;
    }

    private static AiInterviewQa qa(long sessionId, int no, double score, boolean followUp) {
        AiInterviewQa q = new AiInterviewQa();
        q.setId(no * 10L + (followUp ? 1 : 0));
        q.setSessionId(sessionId);
        q.setQuestionNo(no);
        q.setQuestion("问题" + no);
        q.setAnswer("作答" + no);
        q.setScore(score);
        q.setVerdict("还行");
        q.setHitPoints("[\"命中A\"]");
        q.setMissPoints("[\"遗漏B\"]");
        q.setReferenceText("参考答案");
        q.setIsFollowUp(followUp ? 1 : 0);
        q.setCreatedAt(LocalDateTime.now());
        return q;
    }

    @Test
    @DisplayName("查他人记录 → 403（报告含能力评估，不能给同学看）")
    void otherUsersRecordIsForbidden() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 9L));

        BizException e = assertThrows(BizException.class,
                () -> service.detail(1L, 8L, false));
        assertEquals(403, e.getCode());
        assertEquals("无权查看该面试记录", e.getMessage());
    }

    @Test
    @DisplayName("管理员可看他人记录；本人可看自己的")
    void ownerAndAdminCanRead() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 9L));
        when(qaMapper.selectList(any())).thenReturn(List.of(qa(1L, 1, 70.0, false)));

        InterviewDetailVO admin = service.detail(1L, 8L, true);
        assertEquals(1, admin.getQaList().size());
        assertEquals("命中A", admin.getQaList().get(0).getHitPoints().get(0),
                "命中要点应把 JSON 解析成列表，而不是把 [\"命中A\"] 原样丢给前端");

        InterviewDetailVO owner = service.detail(1L, 9L, false);
        assertEquals("RAG", owner.getTopic());
    }

    @Test
    @DisplayName("记录不存在 → 404")
    void missingRecordIsNotFound() {
        when(sessionMapper.selectById(99L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.detail(99L, 9L, false));
        assertEquals(404, e.getCode());
    }

    @Test
    @DisplayName("历史列表：实评题数与计划题数分列（含追问轮的计数）")
    void historySeparatesPlannedAndAnswered() {
        AiInterviewSession early = session(1L, 9L);
        early.setTotalQuestions(5);
        AiInterviewSession done = session(2L, 9L);
        done.setTotalQuestions(3);

        Page<AiInterviewSession> page = new Page<>(1, 10);
        page.setRecords(List.of(early, done));
        page.setTotal(2);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> earlyCount = new LinkedHashMap<>();
        earlyCount.put("sessionId", 1L);
        earlyCount.put("cnt", 3);
        Map<String, Object> doneCount = new LinkedHashMap<>();
        doneCount.put("sessionId", 2L);
        doneCount.put("cnt", 2);
        when(qaMapper.countBySessions(anyList())).thenReturn(List.of(earlyCount, doneCount));

        PageResult<InterviewHistoryVO> result = service.history(1, 10, 9L);

        assertEquals(2, result.getList().size());
        assertEquals(5, result.getList().get(0).getTotalQuestions(), "计划题数");
        assertEquals(3, result.getList().get(0).getAnsweredCount(), "实评题数（提前收尾）");
        assertEquals(2, result.getList().get(1).getAnsweredCount());
        assertEquals(67.0, result.getList().get(0).getTotalScore(), 0.001);
    }

    @Test
    @DisplayName("作答数统计失败不影响列表（degenerate 为 0 而不是整页 500）")
    void countFailureDegradesGracefully() {
        Page<AiInterviewSession> page = new Page<>(1, 10);
        page.setRecords(List.of(session(1L, 9L)));
        page.setTotal(1);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);
        when(qaMapper.countBySessions(anyList())).thenThrow(new RuntimeException("db down"));

        PageResult<InterviewHistoryVO> result = service.history(1, 10, 9L);

        assertTrue(result.getList().size() == 1, "列表仍应返回");
        assertEquals(0, result.getList().get(0).getAnsweredCount(), "统计失败退化为 0");
    }
}
