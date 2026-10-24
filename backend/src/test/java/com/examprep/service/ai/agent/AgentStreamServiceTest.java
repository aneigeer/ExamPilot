package com.examprep.service.ai.agent;

import com.examprep.dto.ai.AgentStreamRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.entity.AiAgentRun;
import com.examprep.service.ai.AiProperties;
import com.examprep.service.ai.AiQaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 汇聚与转发：事件边界状态机 / 终态判定 / 降级回退。
 */
@DisplayName("Agent SSE 转发：空行分帧、终态唯一、断链可降级")
class AgentStreamServiceTest {

    /** 捕获发送内容；可在指定次数后模拟客户端断开。 */
    private static class CapturingEmitter extends SseEmitter {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final int failAt;

        CapturingEmitter(int failAt) {
            super(5000L);
            this.failAt = failAt;
        }

        @Override
        public void send(SseEventBuilder builder) {
            if (failAt > 0 && sent.size() >= failAt) {
                throw new IllegalStateException("client disconnected");
            }
            builder.build().forEach(part -> sent.add(String.valueOf(part.getData())));
        }
    }

    private AgentStreamService service(String baseUrl,
                                       AgentRunService runService,
                                       AiQaService aiQaService) {
        AiProperties properties = new AiProperties();
        properties.getAgent().setEnabled(true);
        properties.getAgent().setStreamTimeoutMillis(5000L);
        properties.getFastapi().setBaseUrl(baseUrl);
        properties.getFastapi().setPathPrefix("/v1");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AgentSessionService sessionService = mock(AgentSessionService.class);
        when(sessionService.summary("s-1")).thenReturn("");
        when(sessionService.history(eq("s-1"), any(), anyInt())).thenReturn(List.of());
        return new AgentStreamService(runService, sessionService, aiQaService, properties,
                new ObjectMapper(), executor, scheduler);
    }

    private AgentStreamRequest request() {
        AgentStreamRequest request = new AgentStreamRequest();
        request.setQuestion("C语言int占几个字节");
        return request;
    }

    private AiAgentRun run() {
        AiAgentRun run = new AiAgentRun();
        run.setId(99L);
        run.setUserId(3L);
        run.setSessionId("s-1");
        run.setTraceId("trace-1");
        run.setQuestion("C语言int占几个字节");
        return run;
    }

    @Test
    @DisplayName("跨行事件 + 心跳注释 + 多行 data 能正确分帧")
    void relaysEventsWithBoundaryStateMachine() throws Exception {
        String body = "event: meta\ndata: {\"traceId\":\"t\"}\n\n"
                + ": ping\n\n"
                + "event: thought\ndata: {\"step\":1,\"text\":\"先检索\"}\n\n"
                + "event: tool_call\ndata: {\"step\":1,\"tool\":\"search_knowledge\","
                + "\"arguments\":{\"query\":\"x\"}}\n\n"
                + "event: final\ndata: {\"answer\":\"A\",\"generator\":\"extractive\","
                + "\"degraded\":true,\"refused\":false,\"step_count\":2,\"tool_calls\":1}\n\n"
                + "event: done\ndata: {\"totalMs\":10}\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AgentRunService runService = mock(AgentRunService.class);
            AiQaService aiQaService = mock(AiQaService.class);
            AgentStreamService service = service(
                    "http://127.0.0.1:" + server.getAddress().getPort(), runService, aiQaService);
            CapturingEmitter emitter = new CapturingEmitter(0);

            service.relay(emitter, run(), request(), () -> {
            });

            assertTrue(emitter.sent.stream().anyMatch(s -> s.contains("\"先检索\"")));
            assertTrue(emitter.sent.stream().anyMatch(s -> s.contains("\"answer\":\"A\"")));
            verify(runService).finish(eq(99L), eq(AiAgentRun.STATUS_SUCCESS),
                    eq("A"), eq("extractive"), eq(2), eq(1), anyLong(), eq(true),
                    contains("\"final\""), isNull());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("客户端中途断开：终态 CANCELLED(client_disconnect)，不留 RUNNING")
    void clientDisconnectBecomesCancelled() throws Exception {
        String body = "event: thought\ndata: {\"step\":1,\"text\":\"先检索\"}\n\n"
                + "event: final\ndata: {\"answer\":\"A\",\"generator\":\"extractive\","
                + "\"degraded\":true,\"refused\":false,\"step_count\":2,\"tool_calls\":1}\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AgentRunService runService = mock(AgentRunService.class);
            AiQaService aiQaService = mock(AiQaService.class);
            AgentStreamService service = service(
                    "http://127.0.0.1:" + server.getAddress().getPort(), runService, aiQaService);
            // 第一次发送成功（meta），之后模拟断开，导致 final 未送达
            CapturingEmitter emitter = new CapturingEmitter(1);

            service.relay(emitter, run(), request(), () -> {
            });

            verify(runService).finish(eq(99L), eq(AiAgentRun.STATUS_CANCELLED),
                    any(), any(), anyInt(), anyInt(), anyLong(), eq(false),
                    anyString(), eq("client_disconnect"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Python 不可用：回退 Java 问答并仍发 final")
    void fallsBackToJavaWhenPythonDown() {
        AgentRunService runService = mock(AgentRunService.class);
        AiQaService aiQaService = mock(AiQaService.class);
        AskVO vo = new AskVO();
        vo.setAnswer("回退答案");
        vo.setGenerator("extractive");
        vo.setDegraded(true);
        vo.setRefused(false);
        when(aiQaService.ask(any(), any())).thenReturn(vo);

        AgentStreamService service = service("http://127.0.0.1:1", runService, aiQaService);
        CapturingEmitter emitter = new CapturingEmitter(0);

        service.relay(emitter, run(), request(), () -> {
        });

        assertTrue(emitter.sent.stream().anyMatch(s -> s.contains("回退答案")));
        verify(runService).finish(eq(99L), eq(AiAgentRun.STATUS_SUCCESS),
                eq("回退答案"), eq("extractive"), eq(0), eq(0), anyLong(), eq(true),
                anyString(), isNull());
    }
}
