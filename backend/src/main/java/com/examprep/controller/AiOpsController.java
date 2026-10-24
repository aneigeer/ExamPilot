package com.examprep.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AgentRunVO;
import com.examprep.dto.ai.AsyncJobRequest;
import com.examprep.dto.ai.AsyncTaskVO;
import com.examprep.entity.AiEvalRun;
import com.examprep.entity.AsyncTask;
import com.examprep.mapper.AiEvalRunMapper;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ai.agent.AgentRunService;
import com.examprep.service.ai.agent.AsyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
