package com.examprep.service.ai.agent;

import com.examprep.entity.AiAgentMemory;
import com.examprep.entity.AiAgentSession;
import com.examprep.mapper.AiAgentMemoryMapper;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiAgentSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆注入：条数上限、顺序、标题格式。
 *
 * <p><b>为什么值得测</b>：注入是「学生改了设定但模型不听话」的唯一定责点。
 * 这里错一点，排查时会误判成模型不听话：
 * <ul>
 *   <li>注入条数没限 → 记忆会把 prompt 预算吃光，工具结果被截断；</li>
 *   <li>顺序反了（长期记忆压住短期摘要）→ 上一轮的临时状态被旧设定覆盖；</li>
 *   <li>没有「【学生长期记忆】」标题 → 模型把学生偏好当成上一轮说过的话，复述出来；</li>
 *   <li>游客（userId=null）也去查库 → 每次对话多一次无意义查询。</li>
 * </ul>
 */
@DisplayName("长期记忆注入：条数 / 顺序 / 格式 / 游客短路")
class MemoryInjectionTest {

    private static final Long ME = 7L;

    private AiAgentMemoryMapper memoryMapper;
    private AgentMemoryService memoryService;

    private AiAgentSessionMapper sessionMapper;
    private AgentMemoryService memoryServiceInSession;
    private AgentSessionService sessionService;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AiAgentMemoryMapper.class);
        memoryService = new AgentMemoryService(memoryMapper);

        sessionMapper = mock(AiAgentSessionMapper.class);
        memoryServiceInSession = mock(AgentMemoryService.class);
        sessionService = new AgentSessionService(sessionMapper, mock(AiAgentRunMapper.class),
                memoryServiceInSession);
    }

    private AiAgentMemory memory(String key, String value) {
        AiAgentMemory m = new AiAgentMemory();
        m.setUserId(ME);
        m.setMemKey(key);
        m.setMemValue(value);
        m.setStatus(AiAgentMemory.STATUS_ACTIVE);
        m.setIsDeleted(0);
        return m;
    }

    // ------------------------------------------------------------------ 记忆块本身

    @Test
    @DisplayName("无记忆：返回空串（调用方据此决定不拼这一行）")
    void emptyWhenNoMemory() {
        when(memoryMapper.selectActive(ME, AgentMemoryService.INJECT_LIMIT)).thenReturn(List.of());

        assertEquals("", memoryService.longTermBlock(ME));
    }

    @Test
    @DisplayName("游客（userId=null）：短路返回空串，不查库")
    void guestSkipsQuery() {
        assertEquals("", memoryService.longTermBlock(null));
        org.mockito.Mockito.verifyNoInteractions(memoryMapper);
    }

    @Test
    @DisplayName("注入格式：【学生长期记忆】+ 每行「键: 值」，顺序与查询一致（按更新时间倒序）")
    void blockFormat() {
        when(memoryMapper.selectActive(ME, AgentMemoryService.INJECT_LIMIT)).thenReturn(List.of(
                memory("薄弱模块", "资料分析"),
                memory("目标考试", "2027 国考")));

        String block = memoryService.longTermBlock(ME);

        String[] lines = block.split("\n");
        assertEquals("【学生长期记忆】", lines[0], "必须有独立标题行，模型才能区分设定与对话");
        assertEquals("薄弱模块: 资料分析", lines[1]);
        assertEquals("目标考试: 2027 国考", lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    @DisplayName("最多注入 5 条：查询就按 5 条取（不靠 Python 侧截断）")
    void blockQueriesAtMostFive() {
        when(memoryMapper.selectActive(anyLong(), eq(5))).thenReturn(List.of(memory("k", "v")));

        memoryService.longTermBlock(ME);

        verify(memoryMapper).selectActive(ME, 5);
        assertEquals(5, AgentMemoryService.INJECT_LIMIT);
    }

    @Test
    @DisplayName("键或值为空的行：跳过，不留「键: 」这种半行")
    void skipsBlankEntries() {
        AiAgentMemory blankValue = memory("空值", "   ");
        when(memoryMapper.selectActive(ME, AgentMemoryService.INJECT_LIMIT))
                .thenReturn(List.of(blankValue));

        assertEquals("", memoryService.longTermBlock(ME), "全是空行时连标题都不该出现");
    }

    // ------------------------------------------------------------------ memorySummary 拼装

    @Test
    @DisplayName("长期记忆在前、滚动摘要在后（学生显式设定不能被系统流水挤到后面）")
    void summaryPutsLongTermFirst() {
        AiAgentSession session = new AiAgentSession();
        session.setSessionId("s-abc");
        session.setUserId(ME);
        session.setSummary("用户：上次问了死锁\n助手：死锁需四个条件");
        when(sessionMapper.selectBySessionId("s-abc")).thenReturn(session);
        when(memoryServiceInSession.longTermBlock(ME)).thenReturn("【学生长期记忆】\n目标考试: 2027 国考");

        String merged = sessionService.summary("s-abc", ME);

        int longTermAt = merged.indexOf("【学生长期记忆】");
        int shortTermAt = merged.indexOf("死锁需四个条件");
        assertTrue(longTermAt == 0, "长期记忆必须在最前：" + merged);
        assertTrue(shortTermAt > longTermAt, "滚动摘要必须在长期记忆之后：" + merged);
        assertTrue(merged.contains("目标考试: 2027 国考"));
    }

    @Test
    @DisplayName("总长上限 600：超限时保留长期记忆完整、截摘要头部（保留最近几轮）")
    void summaryTruncatesTo600KeepingLongTerm() {
        AiAgentSession session = new AiAgentSession();
        session.setSessionId("s-long");
        session.setUserId(ME);
        // 摘要 800 字，头部是"很早以前"，尾部是"刚刚"
        session.setSummary("很早以前的内容".repeat(100) + "刚刚说的最后一句");
        when(sessionMapper.selectBySessionId("s-long")).thenReturn(session);

        String longTerm = "【学生长期记忆】\n目标考试: 2027 国考";
        when(memoryServiceInSession.longTermBlock(ME)).thenReturn(longTerm);

        String merged = sessionService.summary("s-long", ME);

        assertTrue(merged.length() <= AgentSessionService.MEMORY_SUMMARY_MAX_CHARS,
                "总长必须 ≤600，实际 " + merged.length());
        assertTrue(merged.startsWith(longTerm), "长期记忆必须完整保留在开头");
        assertTrue(merged.endsWith("刚刚说的最后一句"), "摘要应保留最近的尾部：" + merged);
    }

    @Test
    @DisplayName("长期记忆自己就超预算时：按 600 截断，不抛异常")
    void summaryHandlesOversizedLongTerm() {
        when(sessionMapper.selectBySessionId(any())).thenReturn(null);
        when(memoryServiceInSession.longTermBlock(ME)).thenReturn("字".repeat(700));

        String merged = sessionService.summary("s-x", ME);

        assertEquals(AgentSessionService.MEMORY_SUMMARY_MAX_CHARS, merged.length());
    }

    @Test
    @DisplayName("只有长期记忆：不产生前导换行，原样返回")
    void summaryWithOnlyLongTerm() {
        when(sessionMapper.selectBySessionId(any())).thenReturn(null);
        when(memoryServiceInSession.longTermBlock(ME)).thenReturn("【学生长期记忆】\n目标考试: 国考");

        assertEquals("【学生长期记忆】\n目标考试: 国考", sessionService.summary("s-none", ME));
    }

    @Test
    @DisplayName("只有短期摘要：与旧行为完全一致（长期记忆为空不影响）")
    void summaryWithOnlyShortTerm() {
        AiAgentSession session = new AiAgentSession();
        session.setSessionId("s-abc");
        session.setUserId(ME);
        session.setSummary("用户：你好\n助手：你好");
        when(sessionMapper.selectBySessionId("s-abc")).thenReturn(session);
        when(memoryServiceInSession.longTermBlock(ME)).thenReturn("");

        assertEquals("用户：你好\n助手：你好", sessionService.summary("s-abc", ME));
    }

    @Test
    @DisplayName("都没有：空串（Python 侧按空背景处理）")
    void summaryEmpty() {
        when(sessionMapper.selectBySessionId(any())).thenReturn(null);
        when(memoryServiceInSession.longTermBlock(any())).thenReturn("");

        assertEquals("", sessionService.summary("s-none", ME));
        assertEquals("", sessionService.summary(null, ME), "sessionId 为空也要安全");
    }

    @Test
    @DisplayName("即时性：每次调用都重新查记忆（删/改后下一轮生效，无缓存）")
    void summaryQueriesMemoryEveryTime() {
        when(sessionMapper.selectBySessionId(any())).thenReturn(null);
        when(memoryServiceInSession.longTermBlock(ME))
                .thenReturn("【学生长期记忆】\nA: 1")
                .thenReturn("");

        assertTrue(sessionService.summary("s-x", ME).contains("A: 1"));
        assertFalse(sessionService.summary("s-x", ME).contains("A: 1"),
                "第二次已无记忆 → 必须立即不出现（说明没有缓存残留）");
    }
}
