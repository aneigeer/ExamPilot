package com.examprep.controller;

import com.examprep.common.PageResult;
import com.examprep.common.Result;
import com.examprep.common.TraceContext;
import com.examprep.dto.ai.AgentRunVO;
import com.examprep.dto.ai.AgentStreamRequest;
import com.examprep.security.SecurityUtils;
import com.examprep.service.ai.agent.AgentRunService;
import com.examprep.service.ai.agent.AgentSessionService;
import com.examprep.service.ai.agent.AgentStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 会话接口
 *
 * <p>鉴权分层：
 * <ul>
 *   <li>{@code POST /ai/agent/stream} —— 需登录（并发闸门按用户）</li>
 *   <li>{@code GET /ai/agent/runs} —— 需登录；普通用户只看自己的，审核员/超管可看全部</li>
 * </ul>
 */
@Tag(name = "13. AI Agent", description = "Agent 流式会话与轨迹回放")
@RestController
@RequiredArgsConstructor
public class AiAgentController {

    private final AgentStreamService streamService;
    private final AgentRunService runService;
    private final AgentSessionService sessionService;

    @Operation(summary = "Agent 流式会话（SSE）",
            description = """
                    **访问级别：AUTH**。

                    事件顺序：`meta → thought → tool_call → tool_result … → final → done`。
                    Python 不可用时自动回退 Java 问答，仍会收到 `final`（`degraded=true`）。

                    并发限制：每用户同时 1 个会话，全局默认 8 个；超限返回 429。

                    链路追踪：入站 `X-Trace-Id` 合法则全程复用（响应头、`meta.traceId`、
                    `ai_agent_run.trace_id`、Java/Python 日志 4 处同值）；断连/超时不重新生成。
                    """)
    @PostMapping("/ai/agent/stream")
    public SseEmitter stream(
            @Valid @RequestBody AgentStreamRequest request,
            @Parameter(description = "上游链路追踪 ID，16 位小写 hex；缺失或非法时由后端生成")
            @RequestHeader(value = TraceContext.HEADER, required = false) String traceId) {
        Long userId = SecurityUtils.requireUserId();
        // 会话解析在这里完成：校验归属（跨用户复用 403），并把最终 sessionId 写回请求
        request.setSessionId(sessionService.resolve(
                request.getSessionId(), userId, request.getQuestion()));
        // 合法入站头 > 同线程 MDC（Filter 已写入）> 新生成；不在此处再生成第二个
        String resolved = TraceContext.of(traceId);
        return streamService.stream(request, userId, resolved);
    }

    @Operation(summary = "Agent 会话历史",
            description = "**访问级别：AUTH**。普通用户仅返回自己的会话；审核员/超管可传 `all=true` 查看全部。")
    @GetMapping("/ai/agent/runs")
    public Result<PageResult<AgentRunVO>> runs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @Parameter(description = "审核员/超管可传 true 查看全部会话")
            @RequestParam(defaultValue = "false") boolean all) {
        Long userId = SecurityUtils.requireUserId();
        Long filterUser = (all && SecurityUtils.isAuditorOrAdmin()) ? null : userId;
        return Result.success(runService.list(page, size, filterUser));
    }

    @Operation(summary = "Agent 会话轨迹回放",
            description = "**访问级别：AUTH**。`steps` 为完整事件数组，一次性返回（不分页）。")
    @GetMapping("/ai/agent/runs/{id}")
    public Result<AgentRunVO> run(@PathVariable Long id) {
        AgentRunVO vo = runService.get(id);
        Long current = SecurityUtils.requireUserId();
        if (!SecurityUtils.isAuditorOrAdmin() && !current.equals(vo.getUserId())) {
            throw new com.examprep.common.BizException(
                    com.examprep.common.ResultCode.FORBIDDEN, "无权查看该会话");
        }
        return Result.success(vo);
    }
}
