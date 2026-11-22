package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentStreamRequest;
import com.examprep.entity.AiAgentCheckpoint;
import com.examprep.entity.AiAgentRun;
import com.examprep.mapper.AiAgentCheckpointMapper;
import com.examprep.mapper.AiAgentRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 任务中断恢复（Checkpoint MVP）。
 *
 * <p><b>能力边界（写清楚，免得被当成"断点续跑"）</b>：
 * <ul>
 *   <li><b>做</b>：对于中断（FAILED / CANCELLED）且留有步骤检查点的 run，
 *       用<b>原 question + 原 sessionId</b> 重开一轮，并返回新的 runId；</li>
 *   <li><b>不做</b>：不从第 N 步继续执行（Python harness 无此能力），
 *       不恢复工具调用的中间结果，不做跨版本/跨模型兼容；</li>
 *   <li>因此"恢复"的语义是<b>可解释的重试</b>：新 run 有完整轨迹，旧 run 的记录保持不变。</li>
 * </ul>
 *
 * <p><b>幂等</b>：同一 run 只允许成功恢复一次。靠
 * {@link AiAgentCheckpointMapper#markResumed} 的<b>条件更新</b>（{@code status='RECORDED'}
 * → {@code 'RESUMED'}）实现：影响 0 行即意味着已被恢复过 → 409。
 * 不用"先查后改"，因为那在并发下会两条都成功。
 *
 * <p><b>为什么先标记再启动</b>：先把唯一一次机会占住，再启动新 run；
 * 若启动失败（如并发已满 429）则<b>回滚标记</b>，避免"失败一次就把机会用掉"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCheckpointService {

    /** 允许恢复的状态（SUCCESS 无需恢复；RUNNING 还在跑） */
    private static final List<String> RESUMMABLE = List.of(
            AiAgentRun.STATUS_FAILED, AiAgentRun.STATUS_CANCELLED);

    private final AiAgentCheckpointMapper checkpointMapper;
    private final AiAgentRunMapper runMapper;
    private final AgentStreamService streamService;
    private final ObjectMapper objectMapper;

    /** 高风险写操作人工审批结果（HITL） */
    public record ApprovalOutcome(
            Long sourceRunId,
            boolean approved,
            String status,
            Long newRunId,
            String tool,
            String traceId
    ) {
    }

    /**
     * 某次运行的步骤检查点（管理端只读）。
     *
     * @param runId 运行 ID
     * @return 检查点列表（按 step 升序）
     * @throws BizException run 不存在（404）
     */
    public List<AiAgentCheckpoint> list(Long runId) {
        if (runId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少 runId");
        }
        requireRun(runId);
        return checkpointMapper.selectByRunId(runId);
    }

    /**
     * 恢复一次中断的运行。
     *
     * @param runId   被恢复的运行 ID
     * @param traceId 新的链路 ID
     * @return 新会话的启动结果（含新 runId，由 controller 写进响应头）
     * @throws BizException run 不存在（404）；状态不允许 / 无检查点 / 已恢复过（409）
     */
    public AgentStreamService.StreamStart resume(Long runId, String traceId) {
        if (runId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少 runId");
        }
        AiAgentRun run = requireRun(runId);
        if (!RESUMMABLE.contains(run.getStatus())) {
            throw new BizException(ResultCode.CONFLICT,
                    "只有 FAILED / CANCELLED 的运行可以恢复，当前状态：" + run.getStatus());
        }
        if (checkpointMapper.countByRunId(runId) <= 0) {
            throw new BizException(ResultCode.CONFLICT, "该运行没有步骤检查点，无法恢复");
        }
        // 条件更新抢占"唯一一次恢复机会"
        if (checkpointMapper.markResumed(runId) <= 0) {
            throw new BizException(ResultCode.CONFLICT, "该运行已经恢复过，不能重复恢复");
        }

        AgentStreamRequest request = new AgentStreamRequest();
        request.setQuestion(run.getQuestion());
        request.setSessionId(run.getSessionId());
        try {
            AgentStreamService.StreamStart started =
                    streamService.start(request, run.getUserId(), traceId);
            log.info("[Agent] 恢复运行成功 srcRun={} newRun={} traceId={}",
                    runId, started.runId(), traceId);
            return started;
        } catch (RuntimeException e) {
            // 启动失败 → 把机会还回去，否则运维"点一次失败就再也恢复不了"
            try {
                checkpointMapper.revertResumed(runId);
            } catch (Exception revertError) {
                log.warn("[Agent] 恢复标记回滚失败 run={}：{}", runId, revertError.getMessage());
            }
            log.warn("[Agent] 恢复运行失败 run={}：{}", runId, e.getMessage());
            throw e;
        }
    }

    /**
     * 审批并恢复（或拒绝终止）一次挂起的高风险写操作（HITL）。
     *
     * <p>校验顺序：run 存在（404）→ 归属权限（403）→ 状态 WAITING_APPROVAL（409）
     * → 一次性 approvalToken 常量时间比对（409）→ 条件更新原子抢占（409 防重放）。
     */
    public ApprovalOutcome approve(Long runId, Long currentUserId, boolean isAdmin,
                                   String approvalToken, boolean approved, String traceId) {
        if (runId == null || approvalToken == null || approvalToken.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少 runId 或 approvalToken");
        }
        AiAgentRun run = requireRun(runId);
        if (!isAdmin && (currentUserId == null || !currentUserId.equals(run.getUserId()))) {
            throw new BizException(ResultCode.FORBIDDEN, "无权审批该会话");
        }
        if (!AiAgentRun.STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
            throw new BizException(ResultCode.CONFLICT,
                    "当前会话未处于待审批状态：" + run.getStatus());
        }
        AiAgentCheckpoint cp = checkpointMapper.findLatestWaitingApproval(runId);
        if (cp == null) {
            throw new BizException(ResultCode.CONFLICT, "未找到待审批检查点或已被处理");
        }
        String expectedToken = "";
        String tool = "";
        try {
            JsonNode node = objectMapper.readTree(cp.getPayloadJson() == null ? "{}" : cp.getPayloadJson());
            expectedToken = node.path("approvalToken").asText("");
            tool = node.path("tool").asText("");
        } catch (Exception e) {
            throw new BizException(ResultCode.CONFLICT, "审批检查点数据异常");
        }
        if (expectedToken.isBlank() || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                approvalToken.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ResultCode.CONFLICT, "审批令牌不匹配或已失效");
        }
        String targetCpStatus = approved ? "APPROVED" : "REJECTED";
        if (checkpointMapper.markApprovalResolved(cp.getId(), targetCpStatus) <= 0) {
            throw new BizException(ResultCode.CONFLICT, "该审批请求已被处理，请勿重复提交");
        }

        if (!approved) {
            AiAgentRun upd = new AiAgentRun();
            upd.setId(runId);
            upd.setStatus(AiAgentRun.STATUS_CANCELLED);
            upd.setError("approval_rejected");
            runMapper.updateById(upd);
            log.info("[Agent][HITL] 用户拒绝高风险工具 run={} tool={}", runId, tool);
            return new ApprovalOutcome(runId, false, AiAgentRun.STATUS_CANCELLED, null, tool, traceId);
        }

        AgentStreamRequest request = new AgentStreamRequest();
        request.setQuestion(run.getQuestion());
        request.setSessionId(run.getSessionId());
        request.setApprovedTools(tool.isBlank() ? List.of() : List.of(tool));
        try {
            AgentStreamService.StreamStart started =
                    streamService.start(request, run.getUserId(), traceId);
            AiAgentRun upd = new AiAgentRun();
            upd.setId(runId);
            upd.setStatus("RESUMED");
            upd.setError("resumed_as_run:" + started.runId());
            runMapper.updateById(upd);
            log.info("[Agent][HITL] 审批通过并恢复执行 srcRun={} newRun={} tool={} traceId={}",
                    runId, started.runId(), tool, traceId);
            return new ApprovalOutcome(runId, true, "RESUMED", started.runId(), tool, traceId);
        } catch (RuntimeException e) {
            try {
                checkpointMapper.revertApprovalStatus(cp.getId(), targetCpStatus);
            } catch (Exception revertErr) {
                log.warn("[Agent][HITL] 审批状态回滚失败 cp={}：{}", cp.getId(), revertErr.getMessage());
            }
            throw e;
        }
    }

    private AiAgentRun requireRun(Long runId) {
        AiAgentRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "运行记录不存在：" + runId);
        }
        return run;
    }
}
