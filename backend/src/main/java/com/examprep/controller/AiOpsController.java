package com.examprep.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.config.ObservabilityProperties;
import com.examprep.dto.ai.AgentMemoryVO;
import com.examprep.dto.ai.AgentMetricsVO;
import com.examprep.dto.ai.AgentRunVO;
import com.examprep.dto.ai.AsyncJobRequest;
import com.examprep.dto.ai.AsyncTaskVO;
import com.examprep.dto.ai.EvalCompareVO;
import com.examprep.common.TraceContext;
import com.examprep.entity.AiAgentCheckpoint;
import com.examprep.entity.AiEvalRun;
import com.examprep.entity.AsyncTask;
import com.examprep.mapper.AiEvalRunMapper;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ai.agent.AgentCheckpointService;
import com.examprep.service.ai.agent.AgentMemoryService;
import com.examprep.service.ai.agent.AgentMetricsService;
import com.examprep.service.ai.agent.AgentRunRetentionService;
import com.examprep.service.ai.agent.AgentRunService;
import com.examprep.service.ai.agent.AgentStreamService;
import com.examprep.service.ai.agent.AsyncJobService;
import com.examprep.service.ai.agent.EvalCompareService;
import com.examprep.service.ai.agent.EvalGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 运维接口（管理端）：异步任务队列 / 离线评估 / Agent 轨迹
 *
 * <p>**访问级别：AUDITOR / ADMIN**（{@code /admin/**} 由 SecurityConfig 统一约束）。
 */
@Tag(name = "14. AI 运维", description = "异步任务、离线评估、Agent 轨迹（管理端）")
@RestController
@RequiredArgsConstructor
public class AiOpsController {

    private final AsyncJobService asyncJobService;
    private final AiEvalRunMapper evalRunMapper;
    private final AgentRunService agentRunService;
    private final AgentMetricsService agentMetricsService;
    private final EvalCompareService evalCompareService;
    private final EvalGateService evalGateService;
    private final AgentRunRetentionService retentionService;
    private final ObservabilityProperties observabilityProperties;
    private final AgentMemoryService memoryService;
    private final AgentCheckpointService checkpointService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.examprep.service.ai.agent.AiQuotaService quotaService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.examprep.service.OperationLogService operationLogService;

    public void setQuotaService(com.examprep.service.ai.agent.AiQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    public void setOperationLogService(com.examprep.service.OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    private void audit(String action, String targetType, Long targetId, String detail) {
        if (operationLogService != null) {
            operationLogService.record("AI_GOVERNANCE", action, targetType, targetId,
                    detail + ",traceId=" + TraceContext.of(null));
        }
    }

    // ==================================================================
    // 评估对比（只读）
    // ==================================================================

    @Operation(summary = "两次离线评估对比（只读）",
            description = """
                    **访问级别：AUDITOR / ADMIN**。只读，不改任何记录。

                    返回两侧快照（`id/layer/status/createdAt/total/passed/passRate/metrics`）与 `delta`。

                    `delta` 键规则：`passRate` 为裸键；指标项统一加前缀 `metrics.`（如 `metrics.Recall@5`）。
                    值规则：两侧都能解析成数字 → 差值（target − base，4 位小数，百分号转 0..1）；
                    否则原样返回目标侧字符串（如 `"-"`、`"no_final×2"`），不做任何猜测性加工。

                    跨 layer（RETRIEVAL vs AGENT）指标集不相交 → HTTP 200 且 `comparable=false`、
                    `delta=null`，并附 `message` 说明。记录不存在 → 404。
                    """)
    @GetMapping("/admin/ai/eval/compare")
    public Result<EvalCompareVO> compareEval(
            @Parameter(description = "基线评估 ID") @RequestParam Long baseId,
            @Parameter(description = "目标评估 ID") @RequestParam Long targetId) {
        return Result.success(evalCompareService.compare(baseId, targetId));
    }

    // ==================================================================
    // 保留期清理（写操作，仅 ADMIN）
    // ==================================================================

    @Operation(summary = "手动清理过期 Agent 运行轨迹（仅 ADMIN）",
            description = """
                    **访问级别：ADMIN**（方法级 `@PreAuthorize`，AUDITOR 也会被拒 403）。

                    **会真实删除数据**：物理删除 `ai_agent_run` 中 `created_at < 当前时间 - days 天` 的行，
                    分批执行（`app.observability.retention-batch`，默认 500 行/批），
                    循环到删不动为止，避免大事务长时间持锁。

                    只返回统计 `{deleted, days, cutoff}`，**不返回被删内容**（打印内容等于把数据搬进日志）。
                    `days` 允许 1..3650；越界 → 400，不静默 clamp。不传则用配置的保留天数。
                    """)
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/admin/ai/retention/purge")
    public Result<AgentRunRetentionService.PurgeResult> purgeRetention(
            @Parameter(description = "保留天数 1..3650；不传则用 app.observability.retention-days")
            @RequestParam(required = false) Integer days) {
        int effectiveDays = days == null
                ? observabilityProperties.getRetentionDays()
                : days;
        return Result.success(retentionService.purge(effectiveDays));
    }

    @Operation(summary = "预读：按保留天数还需清理多少行（仅 ADMIN，只读）",
            description = "**访问级别：ADMIN**。只读计数，供管理端在确认弹窗里展示「将删除 N 条」。")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/ai/retention/preview")
    public Result<Map<String, Object>> previewRetention(
            @Parameter(description = "保留天数 1..3650；不传则用配置值")
            @RequestParam(required = false) Integer days) {
        int effectiveDays = days == null
                ? observabilityProperties.getRetentionDays()
                : days;
        return Result.success(Map.of(
                "days", effectiveDays,
                "pending", retentionService.preview(effectiveDays)));
    }

    // ==================================================================
    // 检查点与中断恢复
    // ==================================================================

    @Operation(summary = "某次运行的步骤检查点（只读）",
            description = """
                    **访问级别：AUDITOR / ADMIN**。只读。

                    每步一条（`thought` / `tool_call` / `tool_result`），按 `step_no` 升序。
                    `payloadJson` **只含事件名、工具名、参数键名**，不含用户问题原文与工具返回原文
                    （数据最小化：恢复只需要知道"走到第几步、本来要干什么"）。
                    `status`：`RECORDED` 正常记录 / `RESUMED` 已被用于一次恢复。
                    """)
    @GetMapping("/admin/ai/agent/runs/{id}/checkpoints")
    public Result<List<AiAgentCheckpoint>> checkpoints(@PathVariable Long id) {
        return Result.success(checkpointService.list(id));
    }

    @Operation(summary = "恢复一次中断的 Agent 运行（重开一轮）",
            description = """
                    **访问级别：AUDITOR / ADMIN**。**会创建新的 run 并开始消耗模型调用**。

                    **能做什么**：对 `FAILED` / `CANCELLED` 且留有检查点的运行，用原 `question` + 原 `sessionId`
                    重新投递一轮 SSE，返回<b>新的 run</b>。
                    **不做什么**：不是"从第 N 步续跑"，不恢复工具中间结果，不做跨版本兼容——
                    语义是**可解释的重试**（新 run 有完整轨迹，旧 run 原样保留）。

                    `X-Agent-Run-Id` 响应头带新 runId（SSE 响应体被事件流占用，塞不进 JSON）。

                    错误：运行不存在 → 404；`SUCCESS`/`RUNNING` → 409；
                    无检查点 → 409；**同一 run 已恢复过 → 409**（幂等，靠 `status` 条件更新，
                    并发也不会两次都成功）。启动失败会回滚恢复标记，机会不会被空耗。
                    """)
    @PostMapping("/admin/ai/agent/runs/{id}/resume")
    public SseEmitter resumeAgentRun(@PathVariable Long id, HttpServletResponse response) {
        AgentStreamService.StreamStart started =
                checkpointService.resume(id, TraceContext.current());
        response.setHeader("X-Agent-Run-Id", String.valueOf(started.runId()));
        return started.emitter();
    }

    // ==================================================================
    // 长期记忆（管理端只读）
    // ==================================================================

    @Operation(summary = "某学生的 Agent 长期记忆（只读）",
            description = """
                    **访问级别：AUDITOR / ADMIN**。只读，不能代学生增删改。

                    长期记忆由学生自己维护（`/api/me/agent-memory`），这里是运维/客服视角的
                    **可见性**入口：回答"模型为什么这么说"时，需要能看到学生自己设定了什么。
                    只返回有效（`deleted=0`）记录，按更新时间倒序，最多 100 条。
                    """)
    @GetMapping("/admin/ai/memory")
    public Result<List<AgentMemoryVO>> agentMemory(
            @Parameter(description = "学生 userId") @RequestParam Long userId) {
        return Result.success(memoryService.listForAdmin(userId));
    }

    // ==================================================================
    // 运行指标（只读）
    // ==================================================================

    @Operation(summary = "Agent 运行指标（只读）",
            description = """
                    **访问级别：AUDITOR / ADMIN**（`/admin/**` 由 SecurityConfig 统一约束）。

                    只读聚合，不落表、不改数据。窗口 = `created_at >= from AND created_at <= to`，
                    `to` 取自一次 `SELECT NOW()`，`from = to - days 天`。

                    口径（写死，详见 `docs/Agent阶段三接口契约.md` §2）：
                    - `total` 含 RUNNING，`terminal = total - running`；
                    - 四个率分母恒为 `terminal`，分母为 0 → `null`；
                    - `degradedRate` 分子分母同为 `status IN (SUCCESS, REFUSED)` 子集；
                    - `latency` 只统计终态且 `total_ms > 0`，P95 = 最近秩法 `rn = CEIL(0.95n)`。

                    `days` 允许 1..90，越界返回 400（**不静默 clamp**，避免报告失真）。
                    """)
    @GetMapping("/admin/ai/metrics")
    public Result<AgentMetricsVO> metrics(
            @Parameter(description = "统计天数 1..90，默认 7")
            @RequestParam(defaultValue = "7") int days) {
        return Result.success(agentMetricsService.metrics(days));
    }

    // ==================================================================
    // 任务队列
    // ==================================================================

    @Operation(summary = "提交异步任务")
    @PostMapping("/admin/ai/jobs")
    public Result<AsyncTask> submit(@Valid @RequestBody AsyncJobRequest request) {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(asyncJobService.submit(
                request.getType(), request.getPayload(), userId));
    }

    @Operation(summary = "任务列表")
    @GetMapping("/admin/ai/jobs")
    public Result<PageResult<AsyncTaskVO>> jobs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(asyncJobService.list(page, size));
    }

    @Operation(summary = "任务详情（含结果 JSON）")
    @GetMapping("/admin/ai/jobs/{id}")
    public Result<AsyncTask> job(@PathVariable Long id) {
        return Result.success(asyncJobService.get(id));
    }

    // ==================================================================
    // 离线评估
    // ==================================================================

    @Operation(summary = "提交检索侧评估（Golden Set → Recall@K / MRR）")
    @PostMapping("/admin/ai/eval/retrieval")
    public Result<AsyncTask> runRetrievalEval() {
        Long userId = SecurityUtils.requireUserId();
        AsyncTask task = asyncJobService.submit("EVAL_RETRIEVAL", Map.of(), userId);
        audit("EVAL_TRIGGER", "ASYNC_TASK", task.getId(), "type=EVAL_RETRIEVAL");
        return Result.success(task);
    }

    @Operation(summary = "提交 Agent 侧评估（合成任务集 → 规则判定）")
    @PostMapping("/admin/ai/eval/agent")
    public Result<AsyncTask> runAgentEval() {
        Long userId = SecurityUtils.requireUserId();
        AsyncTask task = asyncJobService.submit("EVAL_AGENT", Map.of(), userId);
        audit("EVAL_TRIGGER", "ASYNC_TASK", task.getId(), "type=EVAL_AGENT");
        return Result.success(task);
    }

    @Operation(summary = "提交拒答评测（域外拒答率 / 域内误拒率）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=REFUSAL，硬门槛：域外拒答率 >= 90%、域内误拒率 <= 10%。")
    @PostMapping("/admin/ai/eval/refusal")
    public Result<AsyncTask> runRefusalEval() {
        Long userId = SecurityUtils.requireUserId();
        AsyncTask task = asyncJobService.submit("EVAL_REFUSAL", Map.of(), userId);
        audit("EVAL_TRIGGER", "ASYNC_TASK", task.getId(), "type=EVAL_REFUSAL");
        return Result.success(task);
    }

    @Operation(summary = "提交判分评测（等级一致率 / 得分率 MAE）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=GRADING，基于人工标注参考分档样题。")
    @PostMapping("/admin/ai/eval/grading")
    public Result<AsyncTask> runGradingEval() {
        Long userId = SecurityUtils.requireUserId();
        AsyncTask task = asyncJobService.submit("EVAL_GRADING", Map.of(), userId);
        audit("EVAL_TRIGGER", "ASYNC_TASK", task.getId(), "type=EVAL_GRADING");
        return Result.success(task);
    }

    @Operation(summary = "提交学习计划评测（JSON 合规率 / 题号零幻觉 / 降级率）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=STUDY_PLAN，错题 ID 零幻觉率为硬门槛（必须 100%）。")
    @PostMapping("/admin/ai/eval/study-plan")
    public Result<AsyncTask> runStudyPlanEval() {
        Long userId = SecurityUtils.requireUserId();
        AsyncTask task = asyncJobService.submit("EVAL_STUDY_PLAN", Map.of(), userId);
        audit("EVAL_TRIGGER", "ASYNC_TASK", task.getId(), "type=EVAL_STUDY_PLAN");
        return Result.success(task);
    }

    @Operation(summary = "发布门禁汇总",
            description = """
                    **访问级别：AUDITOR / ADMIN**。对 5 个评测层各取最新一次 SUCCESS 运行，
                    按硬阈值给出红绿灯。缺失运行记录按「未通过」处理。
                    """)
    @GetMapping("/admin/ai/eval/gate-summary")
    public Result<Map<String, Object>> evalGateSummary() {
        return Result.success(evalGateService.gateSummary());
    }

    @Operation(summary = "评估运行列表")
    @GetMapping("/admin/ai/eval/runs")
    public Result<PageResult<AiEvalRun>> evalRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        IPage<AiEvalRun> result = evalRunMapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<AiEvalRun>lambdaQuery().orderByDesc(AiEvalRun::getId));
        return Result.success(PageResult.of(result, run -> run));
    }

    @Operation(summary = "评估运行详情（逐条结果 + 报告路径）")
    @GetMapping("/admin/ai/eval/runs/{id}")
    public Result<AiEvalRun> evalRun(@PathVariable Long id) {
        AiEvalRun run = evalRunMapper.selectById(id);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "评估记录不存在");
        }
        return Result.success(run);
    }

    // ==================================================================
    // Agent 轨迹（管理端可看全部）
    // ==================================================================

    @Operation(summary = "Agent 会话列表（全部用户）")
    @GetMapping("/admin/ai/agent/runs")
    public Result<PageResult<AgentRunVO>> agentRuns(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return Result.success(agentRunService.list(page, size, null));
    }

    @Operation(summary = "轨迹加入回归评测集",
            description = """
                    **访问级别：ROLE:ADMIN**。把一条线上轨迹转成标准评测用例：
                    只保留问题文本与来源 ID，自动脱敏手机号/邮箱/身份证，不含 userId/学号。
                    `append=true` 时追加到 `runtime/eval_candidates.jsonl`。
                    """)
    @PostMapping("/admin/ai/runs/{id}/export-eval-case")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> exportEvalCase(
            @PathVariable Long id,
            @Parameter(description = "IN / OUT") @RequestParam(defaultValue = "OUT") String domain,
            @Parameter(description = "是否期望拒答") @RequestParam(required = false) Boolean expectRefusal,
            @Parameter(description = "refusal / agent") @RequestParam(defaultValue = "refusal") String targetDataset,
            @Parameter(description = "是否追加写入候选文件") @RequestParam(defaultValue = "false") boolean append) {
        Map<String, Object> res = agentRunService.exportEvalCase(id, domain, expectRefusal, targetDataset, append);
        audit("EVAL_CASE_EXPORT", "AI_AGENT_RUN", id,
                "domain=" + domain + ",dataset=" + targetDataset + ",append=" + append);
        return Result.success("用例已生成", res);
    }

    @Operation(summary = "Agent 会话轨迹回放")
    @GetMapping("/admin/ai/agent/runs/{id}")
    public Result<AgentRunVO> agentRun(@PathVariable Long id) {
        return Result.success(agentRunService.get(id));
    }

    // ==================================================================
    // 每日配额治理、合规审计与 Prometheus 指标导出
    // ==================================================================

    @Operation(summary = "查询指定用户今日 AI 配额与用量（ADMIN）")
    @GetMapping("/admin/ai/quota/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> getUserQuota(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "STUDENT") String role) {
        if (quotaService == null) {
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, "配额服务未初始化");
        }
        return Result.success(quotaService.snapshot(userId, role).toMap());
    }

    @Operation(summary = "调整指定用户每日 AI 配额覆盖（ADMIN）")
    @org.springframework.web.bind.annotation.PutMapping("/admin/ai/quota/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> updateUserQuota(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        if (quotaService == null) {
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, "配额服务未初始化");
        }
        Long operatorId = SecurityUtils.requireUserId();
        Integer tokenLimit = body.get("dailyTokenLimit") instanceof Number n ? n.intValue() : null;
        Integer reqLimit = body.get("dailyRequestLimit") instanceof Number n ? n.intValue() : null;
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        var snap = quotaService.updateOverride(userId, tokenLimit, reqLimit, remark, operatorId);
        audit("QUOTA_UPDATE", "USER", userId,
                "dailyTokenLimit=" + snap.dailyTokenLimit() + ",dailyRequestLimit=" + snap.dailyRequestLimit());
        return Result.success("配额已更新", snap.toMap());
    }

    @Operation(summary = "查询 AI 治理审计日志（ADMIN）")
    @GetMapping("/admin/ai/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<com.examprep.entity.OperationLog>> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(defaultValue = "AI_GOVERNANCE") String module,
            @RequestParam(required = false) Long userId) {
        if (operationLogService == null) {
            return Result.success(PageResult.of(page, size, 0L, List.of()));
        }
        return Result.success(operationLogService.page(page, size, module, userId));
    }

    @Operation(summary = "Prometheus 文本格式指标导出（ADMIN）")
    @GetMapping(value = "/admin/ai/metrics/prometheus", produces = "text/plain; version=0.0.4; charset=utf-8")
    @PreAuthorize("hasRole('ADMIN')")
    public String prometheusMetrics(@RequestParam(defaultValue = "7") int days) {
        AgentMetricsVO m = agentMetricsService.metrics(days);
        Map<String, Long> sc = m.getStatusCounts() != null ? m.getStatusCounts() : Map.of();
        long p95Ms = (m.getLatency() != null && m.getLatency().getP95Ms() != null)
                ? m.getLatency().getP95Ms() : 0L;
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("# HELP examprep_ai_runs_total Total AI agent runs in window\n");
        sb.append("# TYPE examprep_ai_runs_total gauge\n");
        sb.append("examprep_ai_runs_total{window_days=\"").append(m.getDays()).append("\"} ")
          .append(m.getTotal()).append("\n");
        sb.append("# HELP examprep_ai_runs_by_status AI agent runs grouped by status\n");
        sb.append("# TYPE examprep_ai_runs_by_status gauge\n");
        sb.append("examprep_ai_runs_by_status{status=\"RUNNING\"} ").append(sc.getOrDefault("RUNNING", m.getRunning())).append("\n");
        sb.append("examprep_ai_runs_by_status{status=\"SUCCESS\"} ").append(sc.getOrDefault("SUCCESS", 0L)).append("\n");
        sb.append("examprep_ai_runs_by_status{status=\"REFUSED\"} ").append(sc.getOrDefault("REFUSED", 0L)).append("\n");
        sb.append("examprep_ai_runs_by_status{status=\"FAILED\"} ").append(sc.getOrDefault("FAILED", 0L)).append("\n");
        sb.append("examprep_ai_runs_by_status{status=\"CANCELLED\"} ").append(sc.getOrDefault("CANCELLED", 0L)).append("\n");
        sb.append("# HELP examprep_ai_degraded_rate Degraded fallback ratio in window\n");
        sb.append("# TYPE examprep_ai_degraded_rate gauge\n");
        sb.append("examprep_ai_degraded_rate ")
          .append(m.getDegradedRate() == null ? 0.0 : m.getDegradedRate()).append("\n");
        sb.append("# HELP examprep_ai_latency_p95_ms P95 latency in milliseconds\n");
        sb.append("# TYPE examprep_ai_latency_p95_ms gauge\n");
        sb.append("examprep_ai_latency_p95_ms ").append(p95Ms).append("\n");
        sb.append("# HELP examprep_ai_tokens_total Total LLM tokens in window\n");
        sb.append("# TYPE examprep_ai_tokens_total gauge\n");
        sb.append("examprep_ai_tokens_total ").append(m.getTotalTokens()).append("\n");
        sb.append("# HELP examprep_ai_estimated_cost_cny Estimated LLM cost in CNY\n");
        sb.append("# TYPE examprep_ai_estimated_cost_cny gauge\n");
        sb.append("examprep_ai_estimated_cost_cny ")
          .append(m.getEstimatedCostCny() == null ? java.math.BigDecimal.ZERO : m.getEstimatedCostCny()).append("\n");
        sb.append("# HELP examprep_jvm_heap_used_bytes JVM heap memory used in bytes\n");
        sb.append("# TYPE examprep_jvm_heap_used_bytes gauge\n");
        sb.append("examprep_jvm_heap_used_bytes ").append(heapUsed).append("\n");
        sb.append("# HELP examprep_jvm_heap_max_bytes JVM max heap memory in bytes\n");
        sb.append("# TYPE examprep_jvm_heap_max_bytes gauge\n");
        sb.append("examprep_jvm_heap_max_bytes ").append(heapMax).append("\n");
        return sb.toString();
    }
}
