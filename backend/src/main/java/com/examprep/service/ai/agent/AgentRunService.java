package com.examprep.service.ai.agent;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.LogMaskUtils;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentRunVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentRunMapper;
import com.examprep.service.ai.AiProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    private final AiProperties properties;

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

    /** 写终态（旧签名，兼容不关心 Token 的调用方） */
    public void finish(Long id, String status, String answer, String generator,
                       int stepCount, int toolCalls, long totalMs, boolean degraded,
                       String stepsJson, String error) {
        finish(id, status, answer, generator, stepCount, toolCalls, totalMs, degraded,
                stepsJson, error, null, 0, 0, 0);
    }

    /**
     * 单独补写 Token / 成本。
     *
     * <p>与 {@link #finish} 分离是为了保持既有调用与测试契约不变：
     * 终态写入仍是原来那 10 个参数，用量作为增强信息后置更新。
     */
    public void updateUsage(Long id, String modelName, int promptTokens,
                            int completionTokens, int totalTokens) {
        if (id == null || (totalTokens <= 0 && modelName == null)) {
            return;
        }
        AiAgentRun update = new AiAgentRun();
        update.setId(id);
        update.setModelName(modelName);
        update.setPromptTokens(Math.max(0, promptTokens));
        update.setCompletionTokens(Math.max(0, completionTokens));
        update.setTotalTokens(Math.max(0, totalTokens));
        update.setEstimatedCostCny(estimateCost(promptTokens, completionTokens));
        mapper.updateById(update);
    }

    /**
     * 写终态 + Token / 成本（必须在释放并发信号量之前调用）。
     *
     * <p>成本按 {@code app.ai.llm.input-price-per-k / output-price-per-k} 估算；
     * 本地模型单价为 0，云端可配置。缺 usage 时 Python 侧已用字符数估算并标记，
     * 这里仍如实落库，不做二次猜测。
     */
    public void finish(Long id, String status, String answer, String generator,
                       int stepCount, int toolCalls, long totalMs, boolean degraded,
                       String stepsJson, String error, String modelName,
                       int promptTokens, int completionTokens, int totalTokens) {
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
        update.setModelName(modelName);
        update.setPromptTokens(Math.max(0, promptTokens));
        update.setCompletionTokens(Math.max(0, completionTokens));
        update.setTotalTokens(Math.max(0, totalTokens));
        update.setEstimatedCostCny(estimateCost(promptTokens, completionTokens));
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

    // ==================================================================
    // 评测飞轮：失败轨迹 → 回归用例
    // ==================================================================

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");

    /**
     * 将一条线上 Agent 轨迹导出为回归评测用例。
     *
     * <p><b>脱敏边界</b>：导出内容只保留问题文本与来源 ID，绝不包含 userId、学号、
     * 手机号、邮箱、身份证号；问题/答案正文再做一次正则脱敏。落盘目录固定为
     * {@code <project>/runtime/eval_candidates.jsonl}，不接受调用方传路径，避免穿越。
     *
     * @param runId          ai_agent_run.id
     * @param domain         IN / OUT（用于 refusal 数据集）
     * @param expectRefusal  期望拒答；缺省按 domain 推断
     * @param targetDataset  refusal / agent
     * @param append         是否追加写入候选文件
     */
    public Map<String, Object> exportEvalCase(Long runId, String domain, Boolean expectRefusal,
                                              String targetDataset, boolean append) {
        AiAgentRun run = mapper.selectById(runId);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "轨迹不存在：" + runId);
        }
        String dataset = targetDataset == null ? "refusal" : targetDataset.trim().toLowerCase();
        if (!"refusal".equals(dataset) && !"agent".equals(dataset)) {
            throw new BizException(ResultCode.PARAM_ERROR, "targetDataset 只支持 refusal / agent");
        }
        String dom = domain == null ? "" : domain.trim().toUpperCase();
        if (!dom.isEmpty() && !"IN".equals(dom) && !"OUT".equals(dom)) {
            throw new BizException(ResultCode.PARAM_ERROR, "domain 只支持 IN / OUT");
        }
        boolean refusal = expectRefusal != null ? expectRefusal : "OUT".equals(dom);

        String question = scrub(run.getQuestion());
        String answer = scrub(run.getAnswer());
        if (question.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "该轨迹没有可导出的提问文本");
        }

        Map<String, Object> evalCase = new LinkedHashMap<>();
        evalCase.put("id", "RUN-" + run.getId());
        if ("refusal".equals(dataset)) {
            evalCase.put("question", question);
            evalCase.put("expect_refusal", refusal);
            evalCase.put("domain", dom.isEmpty() ? (refusal ? "OUT" : "IN") : dom);
        } else {
            evalCase.put("goal", question);
            evalCase.put("expected_tools", List.of());
            evalCase.put("required_answer_tokens", List.of());
            evalCase.put("forbidden_tools", List.of());
            evalCase.put("max_steps", Math.max(3, Math.min(8,
                    run.getStepCount() == null ? 6 : run.getStepCount() + 2)));
        }
        evalCase.put("source", "agent_run:" + run.getId());

        String jsonl;
        try {
            jsonl = objectMapper.writeValueAsString(evalCase);
        } catch (Exception e) {
            throw new BizException("用例序列化失败：" + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("targetDataset", dataset);
        result.put("jsonl", jsonl);
        result.put("preview", evalCase);
        result.put("masked", true);
        result.put("answerPreview", answer == null ? "" : abbreviate(answer, 200));
        result.put("append", append);

        if (append) {
            Path file = candidateFile();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, jsonl + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                result.put("writtenPath", file.toString());
                log.info("[EvalFlywheel] 轨迹 {} 已追加到 {}", runId, file.getFileName());
            } catch (Exception e) {
                throw new BizException("写入候选集失败：" + e.getMessage());
            }
        }
        return result;
    }

    /** 固定目录：<project>/runtime/eval_candidates.jsonl，拒绝任何调用方路径 */
    private static Path candidateFile() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (dir.getFileName() != null && "backend".equals(dir.getFileName().toString())) {
            dir = dir.getParent();
        }
        Path runtime = dir.resolve("runtime").normalize();
        Path file = runtime.resolve("eval_candidates.jsonl").normalize();
        if (!file.startsWith(runtime)) {
            throw new BizException("候选集路径非法");
        }
        return file;
    }

    private static String scrub(String text) {
        if (text == null) {
            return "";
        }
        String masked = LogMaskUtils.maskPhone(text);
        masked = EMAIL.matcher(masked).replaceAll("[email]");
        masked = ID_CARD.matcher(masked).replaceAll("[id]");
        return masked.trim();
    }

    private static String abbreviate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 成本 = prompt/1000*输入单价 + completion/1000*输出单价（元，6 位小数） */
    private BigDecimal estimateCost(int promptTokens, int completionTokens) {
        double p = Math.max(0, promptTokens) / 1000.0 * properties.getLlm().getInputPricePerK();
        double c = Math.max(0, completionTokens) / 1000.0 * properties.getLlm().getOutputPricePerK();
        return BigDecimal.valueOf(p + c).setScale(6, RoundingMode.HALF_UP);
    }
}
