package com.examprep.service.ai.agent;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.common.TraceContext;
import com.examprep.dto.ai.AgentStreamRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.dto.ai.SourceRefVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.security.InternalTokenFilter;
import com.examprep.service.ai.AiProperties;
import com.examprep.service.ai.AiQaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Java 侧 SSE 汇聚与转发。
 *
 * <p>核心职责：
 * <ol>
 *   <li>逐行读取 Python SSE，并用**事件边界状态机**重组（空行才 dispatch）；</li>
 *   <li>转发给前端 SseEmitter，同时收集完整事件数组用于轨迹回放；</li>
 *   <li>并发闸门（全局信号量 + DB 唯一约束）；</li>
 *   <li>客户端断开 / 超时 / 上游异常时取消上游，并把终态写回数据库。</li>
 * </ol>
 *
 * <p>终态判定只在转发任务 {@code finally} 中完成：
 * {@code finalSent → SUCCESS/REFUSED}、{@code timeoutFlag → FAILED(timeout)}、
 * {@code errorFlag → FAILED(error)}、三者皆无 → {@code CANCELLED(client_disconnect)}。
 * {@code onCompletion} 不参与终态判断（正常 complete 也会触发它）。
 */
@Slf4j
@Service
public class AgentStreamService {

    private static final String EVENT_COMMENT_PREFIX = ":";

    private final AgentRunService runService;
    private final AgentSessionService sessionService;
    private final AiQaService aiQaService;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Semaphore semaphore;
    private final HttpClient httpClient;

    /** 步骤检查点（中断恢复用）；落库失败只 WARN，绝不影响会话 */
    private final com.examprep.mapper.AiAgentCheckpointMapper checkpointMapper;

    /** 哪些事件需要落检查点（final/done/meta 不需要：恢复只关心"走到第几步、干了什么"） */
    private static final java.util.Set<String> CHECKPOINT_EVENTS =
            java.util.Set.of("thought", "tool_call", "tool_result");

    public AgentStreamService(
            AgentRunService runService,
            AgentSessionService sessionService,
            AiQaService aiQaService,
            AiProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("agentStreamExecutor") ThreadPoolTaskExecutor executor,
            @Qualifier("agentHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
            com.examprep.mapper.AiAgentCheckpointMapper checkpointMapper) {
        this.runService = runService;
        this.sessionService = sessionService;
        this.aiQaService = aiQaService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.checkpointMapper = checkpointMapper;
        this.semaphore = new Semaphore(
                Math.max(1, properties.getAgent().getMaxConcurrent()), true);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 建立 SSE 会话（返回前完成并发闸门与 RUNNING 记录创建）
     */
    /**
     * 一次会话的启动结果。
     *
     * @param emitter   已注册好回调的 SSE 连接
     * @param runId     本轮 {@code ai_agent_run} 主键（恢复接口要把它回传给调用方）
     * @param traceId   本轮链路 ID
     * @param sessionId 会话 ID
     */
    public record StreamStart(SseEmitter emitter, Long runId, String traceId, String sessionId) {
    }

    /**
     * 建立 SSE 会话，并把新 runId 一并返回（给「检查点恢复」用）。
     *
     * <p><b>为什么要有这个重载</b>：中断恢复必须能告诉调用方"新 run 是哪一条"，
     * 而 SSE 的响应体被事件流占用，塞不进 JSON。所以新 runId 在<b>返回之前</b>
     * （{@code runService.start} 已经落库）就取出来，由 controller 写进响应头。
     *
     * @throws BizException 能力未启用（503）或并发超限（429）
     */
    public StreamStart start(AgentStreamRequest request, Long userId, String traceId) {
        AiProperties.Agent cfg = properties.getAgent();
        if (!cfg.isEnabled()) {
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE,
                    "Agent 能力未启用（app.ai.agent.enabled=false）");
        }
        // ① 全局信号量：失败直接 429，不碰 DB
        if (!semaphore.tryAcquire()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "AI 会话并发已满，请稍后再试");
        }
        AiAgentRun run;
        try {
            // ② 插入 RUNNING（started_at=NOW）；用户唯一约束冲突 → 429
            run = runService.start(userId, traceId, request.getSessionId(), request.getQuestion());
        } catch (RuntimeException e) {
            semaphore.release();
            throw e;
        }

        SseEmitter emitter = new SseEmitter(cfg.getStreamTimeoutMillis());
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        };

        try {
            executor.execute(() -> relay(emitter, run, request, releaseOnce));
        } catch (RejectedExecutionException e) {
            // ③ 队列满：先落终态，再释放，最后 429
            runService.finish(run.getId(), AiAgentRun.STATUS_FAILED, null, null,
                    0, 0, 0, true, "[]", "executor_rejected");
            releaseOnce.run();
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "AI 会话排队已满，请稍后再试");
        }
        return new StreamStart(emitter, run.getId(), traceId, run.getSessionId());
    }

    /**
     * 建立 SSE 会话（不需要 runId 的常规入口）。
     *
     * @see #start(AgentStreamRequest, Long, String)
     */
    public SseEmitter stream(AgentStreamRequest request, Long userId, String traceId) {
        return start(request, userId, traceId).emitter();
    }

    // ==================================================================
    // 转发任务
    // ==================================================================

    /** 转发任务（包级可见，便于测试直接用捕获型 SseEmitter 驱动） */
    void relay(SseEmitter emitter, AiAgentRun run, AgentStreamRequest request,
               Runnable releaseOnce) {
        RelayState state = new RelayState();
        state.runId = run.getId();
        state.sessionId = run.getSessionId();
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CompletableFuture<HttpResponse<Stream<String>>>> futureRef =
                new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();

        emitter.onTimeout(() -> {
            state.timeoutFlag.set(true);
            cancelUpstream(futureRef);
        });
        emitter.onError(e -> {
            state.errorFlag.set(true);
            cancelUpstream(futureRef);
        });
        // onCompletion 只触发取消，不做终态判断（正常 complete() 同样会触发）
        emitter.onCompletion(() -> cancelUpstream(futureRef));

        heartbeatRef.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (state.finalSent.get() || state.clientGone.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                state.clientGone.set(true);
                cancelUpstream(futureRef);
            }
        }, 15, 15, TimeUnit.SECONDS));

        long started = System.currentTimeMillis();
        // ★ 转发任务跑在 agentStreamExecutor 上，MDC 不会从请求线程继承：
        //   任务入口显式写入，finally 中清理（线程池复用防串号）。
        //   放在 try 内首行，保证「写入 ⇔ 清理」严格配对（心跳任务不写 MDC）。
        MDC.put(TraceContext.MDC_KEY, run.getTraceId());
        try {
            callPython(emitter, run, request, state, events, futureRef);
        } catch (Exception e) {
            if (!state.finalSent.get() && !state.clientGone.get()) {
                log.warn("[Agent] Python 事件流不可用，回退 Java 问答：{}", e.getMessage());
                fallbackToJava(emitter, run, request, state, events);
            } else {
                state.errorFlag.set(true);
                state.error = "upstream: " + e.getMessage();
            }
        } finally {
            ScheduledFuture<?> heartbeat = heartbeatRef.get();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            cancelUpstream(futureRef);

            long totalMs = System.currentTimeMillis() - started;
            String status;
            String error = state.error;
            if (state.finalSent.get()) {
                status = state.refused ? AiAgentRun.STATUS_REFUSED : AiAgentRun.STATUS_SUCCESS;
            } else if (state.timeoutFlag.get()) {
                status = AiAgentRun.STATUS_FAILED;
                error = "timeout";
            } else if (state.errorFlag.get()) {
                status = AiAgentRun.STATUS_FAILED;
                error = error == null ? "upstream_error" : error;
            } else {
                status = AiAgentRun.STATUS_CANCELLED;
                error = "client_disconnect";
            }

            String stepsJson = "[]";
            try {
                stepsJson = objectMapper.writeValueAsString(events);
            } catch (Exception e) {
                log.warn("[Agent] 轨迹序列化失败：{}", e.getMessage());
            }

            // ④ 顺序写死：先落终态（生成列自动变 NULL），再释放信号量
            try {
                runService.finish(run.getId(), status, state.answer, state.generator,
                        state.stepCount, state.toolCalls, totalMs, state.degraded,
                        stepsJson, error);
                // 只有真正产生了回答的轮次才进入会话记忆；取消/超时不写入，避免污染下一轮
                if (state.finalSent.get() && (AiAgentRun.STATUS_SUCCESS.equals(status)
                        || AiAgentRun.STATUS_REFUSED.equals(status))) {
                    sessionService.recordTurn(run.getSessionId(), run.getQuestion(), state.answer);
                }
            } catch (Exception e) {
                log.error("[Agent] 终态落库失败 run={}", run.getId(), e);
            }
            releaseOnce.run();

            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }

            // ★ 与任务入口的 MDC.put 配对：线程池复用防串号
            TraceContext.clear();
        }
    }

    private void callPython(SseEmitter emitter, AiAgentRun run, AgentStreamRequest request,
                            RelayState state, List<Map<String, Object>> events,
                            AtomicReference<CompletableFuture<HttpResponse<Stream<String>>>> futureRef)
            throws Exception {
        AiProperties.FastApi fastapi = properties.getFastapi();
        String url = fastapi.normalizedBaseUrl() + fastapi.path("/agent/stream");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.getQuestion());
        body.put("userId", run.getUserId());
        body.put("subjectId", request.getSubjectId());
        body.put("examCategoryId", request.getExamCategoryId());
        body.put("traceId", run.getTraceId());
        body.put("sessionId", run.getSessionId());
        // 分层记忆：滚动摘要 + 最近 6 轮问答 + 学生长期记忆，由 Python Context Manager 做预算与压缩
        body.put("memorySummary", sessionService.summary(run.getSessionId(), run.getUserId()));
        body.put("history", sessionService.history(run.getSessionId(), run.getUserId(), 6));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        // ★ 链路追踪：头与 body.traceId 必须同值（Python 侧以头为准，body 字段仅保留兼容）。
        //   一次会话/一轮 run 只用一个 traceId，断连与超时都不重新生成。
        String traceHeader = run.getTraceId();
        builder.header(TraceContext.HEADER,
                traceHeader != null && !traceHeader.isBlank() ? traceHeader : TraceContext.generate());
        // ★ Python 侧 /v1/agent/* 现在校验内部令牌（缺失/错误 → 401）。
        // 未配置时不发该头，保持「本地零配置也能跑」；配置后 Java↔Python 才对得上。
        String internalToken = properties.getInternalToken();
        if (internalToken != null && !internalToken.isBlank()) {
            builder.header(InternalTokenFilter.HEADER, internalToken);
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();

        CompletableFuture<HttpResponse<Stream<String>>> future =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines());
        futureRef.set(future);
        HttpResponse<Stream<String>> response = future.join();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("AI 服务返回 HTTP " + response.statusCode());
        }

        String eventName = null;
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (state.clientGone.get()) {
                    break;
                }
                if (line.isEmpty()) {
                    // ★ 空行才是事件边界
                    eventName = dispatch(emitter, eventName, data, state, events);
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(EVENT_COMMENT_PREFIX)) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            // 流结束：补发最后一个未以空行结尾的事件
            if (data.length() > 0) {
                dispatch(emitter, eventName, data, state, events);
            }
        }
    }

    /** 派发完整事件；返回重置后的事件名 */
    private String dispatch(SseEmitter emitter, String eventName, StringBuilder data,
                            RelayState state, List<Map<String, Object>> events) {
        if (eventName == null && data.length() == 0) {
            return null;
        }
        String name = eventName == null ? "message" : eventName;
        String payload = data.toString();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("event", name);
        item.put("data", parseJson(payload));
        events.add(item);
        recordCheckpoint(state, name, item.get("data"));

        boolean isFinal = "final".equals(name);
        JsonNode finalNode = null;
        if (isFinal) {
            try {
                finalNode = objectMapper.readTree(payload);
            } catch (Exception e) {
                log.warn("[Agent] final 事件解析失败：{}", e.getMessage());
                state.errorFlag.set(true);
                state.error = "final_parse_error";
            }
        }

        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(payload, MediaType.TEXT_PLAIN));
            // ★ 只有成功送达客户端，才把 final 视为已发送（断开时不得误判 SUCCESS）
            if (isFinal && finalNode != null) {
                state.answer = finalNode.path("answer").asText(null);
                state.generator = finalNode.path("generator").asText(null);
                state.degraded = finalNode.path("degraded").asBoolean(false);
                state.refused = finalNode.path("refused").asBoolean(false);
                state.stepCount = finalNode.path("step_count").asInt(0);
                state.toolCalls = finalNode.path("tool_calls").asInt(0);
                state.finalSent.set(true);
            }
        } catch (IOException | IllegalStateException e) {
            state.clientGone.set(true);
        }
        return null;
    }

    /** Python 不可用时的降级：直接走 Java 问答，发 meta + final */
    private void fallbackToJava(SseEmitter emitter, AiAgentRun run, AgentStreamRequest request,
                                RelayState state, List<Map<String, Object>> events) {
        try {
            var ask = new com.examprep.dto.ai.AskRequest();
            ask.setQuestion(request.getQuestion());
            ask.setSubjectId(request.getSubjectId());
            ask.setExamCategoryId(request.getExamCategoryId());
            AskVO vo = aiQaService.ask(ask, run.getUserId());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("traceId", run.getTraceId());
            meta.put("sessionId", run.getSessionId());
            meta.put("degraded", true);
            meta.put("reason", "Python Agent 不可用，已回退 Java 问答");
            send(emitter, "meta", meta, state, events);

            Map<String, Object> finalData = new LinkedHashMap<>();
            finalData.put("answer", vo.getAnswer());
            finalData.put("generator", vo.getGenerator());
            finalData.put("degraded", true);
            finalData.put("refused", Boolean.TRUE.equals(vo.getRefused()));
            finalData.put("step_count", 0);
            finalData.put("tool_calls", 0);
            finalData.put("sources", sourceMaps(vo.getSources()));
            boolean sent = send(emitter, "final", finalData, state, events);

            state.answer = vo.getAnswer();
            state.generator = vo.getGenerator();
            state.degraded = true;
            state.refused = Boolean.TRUE.equals(vo.getRefused());
            if (sent) {
                state.finalSent.set(true);
            }
        } catch (Exception e) {
            state.errorFlag.set(true);
            state.error = "fallback_failed: " + e.getMessage();
            Map<String, Object> error = Map.of("message", "AI 服务暂不可用，请稍后重试");
            send(emitter, "error", error, state, events);
        }
    }

    private boolean send(SseEmitter emitter, String name, Map<String, Object> data,
                         RelayState state, List<Map<String, Object>> events) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return false;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("event", name);
        item.put("data", data);
        events.add(item);
        recordCheckpoint(state, name, data);
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(payload, MediaType.TEXT_PLAIN));
            return true;
        } catch (Exception e) {
            state.clientGone.set(true);
            return false;
        }
    }

    private List<Map<String, Object>> sourceMaps(List<SourceRefVO> sources) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (sources == null) {
            return list;
        }
        for (SourceRefVO s : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", s.getChunkId());
            item.put("title", s.getTitle());
            item.put("snippet", s.getSnippet());
            item.put("refType", s.getRefType());
            item.put("refId", s.getRefId());
            item.put("refPath", s.getRefPath());
            list.add(item);
        }
        return list;
    }

    private Object parseJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return payload;
        }
    }

    private void cancelUpstream(
            AtomicReference<CompletableFuture<HttpResponse<Stream<String>>>> futureRef) {
        CompletableFuture<HttpResponse<Stream<String>>> future = futureRef.get();
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    /** 转发过程中累积的状态 */
    /**
     * 落一条步骤检查点（thought / tool_call / tool_result）。
     *
     * <p><b>payload 不含用户原文</b>：只记事件名、工具名、参数<b>键名</b>。
     * 把问题原文或工具返回原文写进 checkpoint，等于在库里复制一份对话内容，
     * 还会绕过 {@code ai_agent_run} 的保留期清理（checkpoint 有自己的生命周期）。
     *
     * <p>失败只 WARN：检查点是"锦上添花"的能力，不能因为它把正常会话搞失败。
     */
    private void recordCheckpoint(RelayState state, String eventName, Object data) {
        if (state.runId == null || !CHECKPOINT_EVENTS.contains(eventName)) {
            return;
        }
        int step = state.checkpointStep.incrementAndGet();
        try {
            checkpointMapper.insertCheckpoint(state.runId, state.sessionId, step,
                    checkpointSummary(eventName, data));
        } catch (Exception e) {
            log.warn("[Agent] 检查点落库失败 run={} step={} event={}：{}",
                    state.runId, step, eventName, e.getMessage());
        }
    }

    /**
     * 步骤摘要 JSON：事件名 + 工具名 + 参数键名（无任何取值）。
     *
     * <p>入参可能是 {@link JsonNode}（Python 流解析出来的）或 {@code Map}（本地降级路径构造的），
     * 统一转成 JsonNode 再取，避免两条链路的行为不一致——这种"只在一半路径生效"的差异
     * 极难发现（降级时检查点就突然没内容了）。
     */
    private String checkpointSummary(String eventName, Object data) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("event", eventName);
        try {
            JsonNode node = data instanceof JsonNode jsonNode
                    ? jsonNode
                    : objectMapper.valueToTree(data);
            if (node != null && node.isObject()) {
                String tool = node.path("tool").asText(null);
                if (tool != null && !tool.isBlank()) {
                    summary.put("tool", tool);
                }
                // 工具参数键名：Python 侧字段是 arguments（历史代码也有 args，两个都认）
                JsonNode args = node.has("arguments") ? node.get("arguments") : node.get("args");
                if (args != null && args.isObject()) {
                    List<String> keys = new ArrayList<>();
                    args.fieldNames().forEachRemaining(keys::add);
                    summary.put("argKeys", keys);
                }
            }
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return "{\"event\":\"" + eventName + "\"}";
        }
    }

    private static final class RelayState {
        /** 检查点写入需要知道属于哪个 run（不改 dispatch 签名，避免大范围改动） */
        volatile Long runId;
        volatile String sessionId;
        final java.util.concurrent.atomic.AtomicInteger checkpointStep =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final AtomicBoolean finalSent = new AtomicBoolean(false);
        final AtomicBoolean timeoutFlag = new AtomicBoolean(false);
        final AtomicBoolean errorFlag = new AtomicBoolean(false);
        final AtomicBoolean clientGone = new AtomicBoolean(false);
        volatile String answer;
        volatile String generator;
        volatile String error;
        volatile boolean degraded;
        volatile boolean refused;
        volatile int stepCount;
        volatile int toolCalls;
    }
}
