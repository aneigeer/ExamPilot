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
        return Result.success(asyncJobService.submit("EVAL_RETRIEVAL", Map.of(), userId));
    }

    @Operation(summary = "提交 Agent 侧评估（合成任务集 → 规则判定）")
    @PostMapping("/admin/ai/eval/agent")
    public Result<AsyncTask> runAgentEval() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(asyncJobService.submit("EVAL_AGENT", Map.of(), userId));
    }

    @Operation(summary = "提交拒答评测（域外拒答率 / 域内误拒率）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=REFUSAL，硬门槛：域外拒答率 >= 90%、域内误拒率 <= 10%。")
    @PostMapping("/admin/ai/eval/refusal")
    public Result<AsyncTask> runRefusalEval() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(asyncJobService.submit("EVAL_REFUSAL", Map.of(), userId));
    }

    @Operation(summary = "提交判分评测（等级一致率 / 得分率 MAE）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=GRADING，基于人工标注参考分档样题。")
    @PostMapping("/admin/ai/eval/grading")
    public Result<AsyncTask> runGradingEval() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(asyncJobService.submit("EVAL_GRADING", Map.of(), userId));
    }

    @Operation(summary = "提交学习计划评测（JSON 合规率 / 题号零幻觉 / 降级率）",
            description = "**访问级别：AUDITOR / ADMIN**。layer=STUDY_PLAN，错题 ID 零幻觉率为硬门槛（必须 100%）。")
    @PostMapping("/admin/ai/eval/study-plan")
    public Result<AsyncTask> runStudyPlanEval() {
        Long userId = SecurityUtils.requireUserId();
        return Result.success(asyncJobService.submit("EVAL_STUDY_PLAN", Map.of(), userId));
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

    @Operation(summary = "Agent 会话轨迹回放")
    @GetMapping("/admin/ai/agent/runs/{id}")
    public Result<AgentRunVO> agentRun(@PathVariable Long id) {
        return Result.success(agentRunService.get(id));
    }
}
