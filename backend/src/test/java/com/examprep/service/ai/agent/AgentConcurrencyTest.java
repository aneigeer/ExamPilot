package com.examprep.service.ai.agent;

import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentRunMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 并发闸门的数据库兜底：同一用户同时只能有一条 RUNNING 会话。
 *
 * <p>用 @Transactional 回滚，不污染真实数据。
 */
@DisplayName("Agent 并发闸门：数据库唯一约束兜底")
@SpringBootTest
@ActiveProfiles("local")
@org.springframework.transaction.annotation.Transactional
class AgentConcurrencyTest {

    @Autowired
    private AiAgentRunMapper mapper;

    private AiAgentRun running(long userId, String question) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(userId);
        run.setTraceId("test");
        run.setQuestion(question);
        run.setStatus(AiAgentRun.STATUS_RUNNING);
        run.setStepCount(0);
        run.setToolCalls(0);
        run.setTotalMs(0);
        run.setDegraded(false);
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    @Test
    @DisplayName("同一用户第二条 RUNNING 被唯一约束拒绝")
    void duplicateRunningRejected() {
        long userId = 990_001L;
        assertDoesNotThrow(() -> mapper.insert(running(userId, "first")));
        assertThrows(DuplicateKeyException.class,
                () -> mapper.insert(running(userId, "second")));
    }

    @Test
    @DisplayName("前一条转终态后，可以再次发起")
    void canStartAgainAfterFinish() {
        long userId = 990_002L;
        AiAgentRun first = running(userId, "first");
        mapper.insert(first);

        AiAgentRun update = new AiAgentRun();
        update.setId(first.getId());
        update.setStatus(AiAgentRun.STATUS_SUCCESS);
        update.setFinishedAt(LocalDateTime.now());
        mapper.updateById(update);

        assertDoesNotThrow(() -> mapper.insert(running(userId, "second")));
    }
}
