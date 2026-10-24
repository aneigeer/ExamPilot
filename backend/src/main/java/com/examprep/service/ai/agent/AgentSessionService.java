package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.entity.AiAgentRun;
import com.examprep.entity.AiAgentSession;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.mapper.AiAgentSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 会话与分层记忆（短期/跨轮次）
 *
 * <p><b>记忆边界</b>：
 * <ul>
 *   <li>本轮工作记忆：Python Harness 的 observations（进程内）；</li>
 *   <li>短期记忆：本表 {@code summary} + 最近 N 轮 run（跨请求）；</li>
 *   <li>长期画像：薄弱点 / 错题 / 计划，由只读工具按需读取；</li>
 *   <li>语义知识：RAG + Wiki 检索。</li>
 * </ul>
 *
 * <p><b>安全</b>：sessionId 必须属于当前 userId；跨用户复用直接 403。
 * 摘要只做确定性滚动转录，不调用 LLM，所以不会引入额外幻觉或费用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int SUMMARY_MAX_CHARS = 1800;
    private static final int HISTORY_ANSWER_MAX_CHARS = 300;

    private final AiAgentSessionMapper sessionMapper;
    private final AiAgentRunMapper runMapper;

    /**
     * 解析或创建会话
     *
     * @param sessionId 客户端传入；空则新建
     * @param userId    当前用户；游客为 null
     * @return 可安全使用的 sessionId
     */
    public String resolve(String sessionId, Long userId, String question) {
        String sid = StringUtils.hasText(sessionId)
                ? sessionId.trim()
                : "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (!SESSION_ID.matcher(sid).matches()) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "sessionId 只能包含字母、数字、下划线和短横线（1-32 位）");
        }
        AiAgentSession session = sessionMapper.selectBySessionId(sid);
        if (session != null) {
            assertOwner(session, userId);
            return sid;
        }
        session = new AiAgentSession();
        session.setSessionId(sid);
        session.setUserId(userId);
        session.setSummary("");
        session.setTurnCount(0);
        session.setLastQuestion(trim(question, 500));
        session.setCreatedAt(LocalDateTime.now());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            // 并发首轮：另一请求已创建，复查归属即可
            AiAgentSession existing = sessionMapper.selectBySessionId(sid);
            if (existing == null) {
                throw new BizException(ResultCode.SERVER_ERROR, "会话创建失败");
            }
            assertOwner(existing, userId);
        }
        log.info("[Agent] 会话解析完成 session={} user={} turn={}", sid, userId, session.getTurnCount());
        return sid;
    }

    /** 最近 N 轮（时间正序），供 Python Context Manager 使用 */
    public List<Map<String, Object>> history(String sessionId, Long userId, int limit) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        AiAgentSession session = sessionMapper.selectBySessionId(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(10, limit));
        List<AiAgentRun> rows = runMapper.selectRecentTurns(sessionId, userId, safeLimit);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            AiAgentRun run = rows.get(i);
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("question", run.getQuestion());
            turn.put("answer", trim(run.getAnswer(), HISTORY_ANSWER_MAX_CHARS));
            turns.add(turn);
        }
        return turns;
    }

    public String summary(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return "";
        }
        AiAgentSession session = sessionMapper.selectBySessionId(sessionId);
        return session == null || session.getSummary() == null ? "" : session.getSummary();
    }

    /** 一轮成功（含拒答）结束后追加滚动摘要 */
    public void recordTurn(String sessionId, String question, String answer) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(answer)) {
            return;
        }
        AiAgentSession session = sessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            return;
        }
        String existing = session.getSummary() == null ? "" : session.getSummary().trim();
        String line = "用户：" + trim(question, 500) + "\n助手：" + trim(answer, 600);
        String merged = existing.isEmpty() ? line : existing + "\n" + line;
        if (merged.length() > SUMMARY_MAX_CHARS) {
            merged = merged.substring(merged.length() - SUMMARY_MAX_CHARS);
            int newline = merged.indexOf('\n');
            if (newline >= 0 && newline < 60) {
                merged = merged.substring(newline + 1);
            }
        }
        try {
            sessionMapper.recordTurn(sessionId, merged, trim(question, 500));
        } catch (Exception e) {
            // 记忆更新失败不能影响本轮回答
            log.warn("[Agent] 会话摘要更新失败 session={}：{}", sessionId, e.getMessage());
        }
    }

    private static void assertOwner(AiAgentSession session, Long userId) {
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "该会话不属于当前用户");
        }
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
