package com.examprep.service.ai.agent;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentRunVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentRunMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行轨迹的落库与查询。
 *
 * <p>并发约束由数据库生成列 {@code running_user_id} + UNIQUE 兜底：
 * 插入 RUNNING 记录冲突即 429，不依赖应用层「先查后插」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final TypeReference<List<Map<String, Object>>> STEPS_TYPE =
            new TypeReference<>() {
            };

    private final AiAgentRunMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建 RUNNING 会话
     *
     * <p>同时写 {@code started_at=NOW()}：若随后进程崩溃，恢复逻辑能识别并清理，
     * 不会让该用户的并发位永久占用。
     */
    public AiAgentRun start(Long userId, String traceId, String sessionId, String question) {
        AiAgentRun run = new AiAgentRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setTraceId(traceId);
        run.setQuestion(question);
        run.setStatus(AiAgentRun.STATUS_RUNNING);
        run.setStepCount(0);
        run.setToolCalls(0);
        run.setTotalMs(0);
        run.setDegraded(false);
        run.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(run);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "你有一个正在进行的 AI 会话，请等待完成后再试");
        }
        return run;
    }

    /** 写终态（必须在释放并发信号量之前调用） */
    public void finish(Long id, String status, String answer, String generator,
                       int stepCount, int toolCalls, long totalMs, boolean degraded,
                       String stepsJson, String error) {
        if (id == null) {
            return;
        }
        AiAgentRun update = new AiAgentRun();
        update.setId(id);
        update.setStatus(status);
        update.setAnswer(answer);
        update.setGenerator(generator);
        update.setStepCount(stepCount);
        update.setToolCalls(toolCalls);
        update.setTotalMs((int) Math.min(Integer.MAX_VALUE, totalMs));
        update.setDegraded(degraded);
        update.setStepsJson(stepsJson);
        update.setError(error);
        update.setFinishedAt(LocalDateTime.now());
        mapper.updateById(update);
        log.info("[Agent] 会话 {} 结束：status={} steps={} tools={} totalMs={}",
                id, status, stepCount, toolCalls, totalMs);
    }

    public AgentRunVO get(Long id) {
        AiAgentRun run = mapper.selectById(id);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会话不存在");
        }
        return toVO(run);
    }

    public PageResult<AgentRunVO> list(long page, long size, Long userId) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        IPage<AiAgentRun> result = mapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<AiAgentRun>lambdaQuery()
                        .eq(userId != null, AiAgentRun::getUserId, userId)
                        .orderByDesc(AiAgentRun::getId));
        return PageResult.of(result, this::toVO);
    }

    private AgentRunVO toVO(AiAgentRun run) {
        AgentRunVO vo = new AgentRunVO();
        vo.setId(run.getId());
        vo.setUserId(run.getUserId());
        vo.setSessionId(run.getSessionId());
        vo.setTraceId(run.getTraceId());
        vo.setQuestion(run.getQuestion());
        vo.setStatus(run.getStatus());
        vo.setAnswer(run.getAnswer());
        vo.setGenerator(run.getGenerator());
        vo.setStepCount(run.getStepCount());
        vo.setToolCalls(run.getToolCalls());
        vo.setTotalMs(run.getTotalMs());
        vo.setDegraded(run.getDegraded());
        vo.setError(run.getError());
        vo.setCreatedAt(run.getCreatedAt());
        vo.setFinishedAt(run.getFinishedAt());
        vo.setSteps(parseSteps(run.getStepsJson()));
        return vo;
    }

    private List<Map<String, Object>> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STEPS_TYPE);
        } catch (Exception e) {
            log.warn("[Agent] steps_json 解析失败：{}", e.getMessage());
            return List.of();
        }
    }
}
