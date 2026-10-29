package com.examprep.service.ai;

import com.examprep.common.BizException;
import com.examprep.dto.ai.InterviewAnswerRequest;
import com.examprep.dto.ai.InterviewStartRequest;
import com.examprep.entity.AiInterviewSession;
import com.examprep.mapper.AiInterviewQaMapper;
import com.examprep.mapper.AiInterviewSessionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 面试代理服务测试
 *
 * <p><b>为什么用 JDK 自带 HttpServer 而不是 Mockito 打桩</b>：
 * 本服务的关键行为全在 HTTP 边界上——请求体字段名（{@code subject_id}）、
 * 状态码翻译（404 → 会话过期）、响应透传（{@code report} / {@code follow_up} 不丢字段）。
 * 打桩 RestClient 只能验证「我调了哪个方法」，验不到真正会出错的地方。
 * 起一个本地假 Python 服务，才能覆盖真实序列化/反序列化路径，且零新增依赖。
 */
@DisplayName("AI 面试代理：契约透传 / 错误翻译 / 会话归属")
class AiInterviewServiceTest {

    private HttpServer server;
    private AiProperties properties;
    private ObjectMapper objectMapper;
    private AiInterviewSessionMapper sessionMapper;
    private AiInterviewQaMapper qaMapper;
    private AiInterviewService service;

    /** 收到的请求路径（按调用顺序） */
    private final List<String> paths = new ArrayList<>();

    /** 收到的请求体（按调用顺序） */
    private final List<String> bodies = new ArrayList<>();

    /** 下一次 /interview/answer 的应答：状态码 + body */
    private int answerStatus = 200;
    private String answerBody = "";

    @BeforeEach
    void setUp() throws Exception {
        paths.clear();
        bodies.clear();
        objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/interview/start", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(readBody(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"session_id":"s-1","topic":"RAG","question_no":1,"total":3,
                     "question":"什么是 RAG？","source":{"title":"RAG 面试题",
                     "source_file":"interview_rag.docx","page_no":2}}
                    """);
        });
        server.createContext("/v1/interview/answer", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(readBody(exchange.getRequestBody()));
            respond(exchange, answerStatus, answerBody);
        });
        server.start();

        properties = new AiProperties();
        properties.getFastapi().setEnabled(true);
        properties.getFastapi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getFastapi().setPathPrefix("/v1");
        sessionMapper = org.mockito.Mockito.mock(AiInterviewSessionMapper.class);
        qaMapper = org.mockito.Mockito.mock(AiInterviewQaMapper.class);
        // 默认：没有历史会话、插入成功（返回 1）；需要验证"落库失败"的用例单独打桩
        org.mockito.Mockito.when(sessionMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        // 模拟自增主键回填：MyBatis-Plus 真实实现会在 insert 后把 id 写回实体，
        // Mockito 不会，所以这里显式打桩，否则后续作答落库拿不到本地 sessionId
        org.mockito.Mockito.when(sessionMapper.insert(org.mockito.ArgumentMatchers.any(AiInterviewSession.class)))
                .thenAnswer(inv -> {
                    AiInterviewSession s = inv.getArgument(0);
                    s.setId(100L);
                    return 1;
                });
        service = new AiInterviewService(properties, objectMapper, sessionMapper, qaMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    private static String readBody(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static InterviewStartRequest startRequest(String topic, Integer total, Long subjectId) {
        InterviewStartRequest req = new InterviewStartRequest();
        req.setTopic(topic);
        req.setTotal(total);
        req.setSubjectId(subjectId);
        return req;
    }

    private static InterviewAnswerRequest answerRequest(String sessionId, String answer) {
        InterviewAnswerRequest req = new InterviewAnswerRequest();
        req.setSessionId(sessionId);
        req.setAnswer(answer);
        return req;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("开始面试：请求体字段为 snake_case（subject_id），响应逐字段透传")
    void startPassesContractFieldsThrough() {
        JsonNode node = service.start(startRequest("RAG", 5, 42L), 7L);

        assertEquals("/v1/interview/start", paths.get(0));
        String sent = bodies.get(0);
        // ★ 契约要求 subject_id：写成 subjectId 的话 Python 侧收不到，会跨科目抽题
        assertTrue(sent.contains("\"subject_id\":42"), "请求体应含 subject_id，实际：" + sent);
        assertTrue(sent.contains("\"total\":5"), "请求体应含 total=5，实际：" + sent);
        assertTrue(sent.contains("\"topic\":\"RAG\""), "请求体应含 topic，实际：" + sent);

        assertEquals("s-1", node.path("session_id").asText());
        assertEquals("什么是 RAG？", node.path("question").asText());
        assertEquals(3, node.path("total").asInt());
        assertEquals("interview_rag.docx", node.path("source").path("source_file").asText());
        assertEquals(2, node.path("source").path("page_no").asInt());
    }

    @Test
    @DisplayName("开始面试：total 缺省与越界都被钳到 1-10")
    void startClampsTotal() {
        service.start(startRequest("", null, null), 1L);
        assertTrue(bodies.get(0).contains("\"total\":3"), "缺省题数应为 3：" + bodies.get(0));

        service.start(startRequest("", 99, null), 1L);
        assertTrue(bodies.get(1).contains("\"total\":10"), "越界题数应钳到 10：" + bodies.get(1));
    }

    @Test
    @DisplayName("作答未结束：评分/命中/遗漏/追问/下一题全部透传，report 为 null 不报错")
    void answerPassesThroughScoring() {
        answerStatus = 200;
        answerBody = """
                {"session_id":"s-1","question_no":1,"total":3,"score":82.5,"verdict":"答得不错",
                 "hit_points":["提到检索","提到生成"],"miss_points":["没提重排"],
                 "reference":"RAG = 检索增强生成","follow_up":"那重排怎么做？",
                 "next_question":"向量库怎么选？","finished":false}
                """;
        JsonNode node = service.answer(answerRequest("s-1", "检索加生成"), 7L);

        assertEquals("/v1/interview/answer", paths.get(0));
        assertTrue(bodies.get(0).contains("\"session_id\":\"s-1\""), "请求体应含 session_id");
        assertEquals(82.5, node.path("score").asDouble(), 0.001);
        assertEquals(2, node.path("hit_points").size());
        assertEquals(1, node.path("miss_points").size());
        assertEquals("那重排怎么做？", node.path("follow_up").asText());
        assertEquals("向量库怎么选？", node.path("next_question").asText());
        assertTrue(node.path("finished").isBoolean());
        assertEquals(false, node.path("finished").asBoolean());
    }

    @Test
    @DisplayName("作答结束：report 完整透传（逐题/优势/不足/建议/degraded）")
    void answerPassesThroughReport() {
        answerStatus = 200;
        answerBody = """
                {"session_id":"s-1","finished":true,
                 "report":{"total_score":76.0,"level":"良好",
                   "per_question":[{"question":"什么是 RAG？","score":82,"comment":"概念准确"}],
                   "strengths":["概念清晰"],"weaknesses":["缺少工程细节"],
                   "suggestions":["补充重排与评测"],"degraded":true}}
                """;
        JsonNode node = service.answer(answerRequest("s-1", "最后一题"), 7L);

        assertTrue(node.path("finished").asBoolean());
        JsonNode report = node.path("report");
        assertEquals(76.0, report.path("total_score").asDouble(), 0.001);
        assertEquals("良好", report.path("level").asText());
        assertEquals(1, report.path("per_question").size());
        assertEquals("概念准确", report.path("per_question").get(0).path("comment").asText());
        assertEquals(1, report.path("strengths").size());
        assertEquals(1, report.path("weaknesses").size());
        assertEquals(1, report.path("suggestions").size());
        assertTrue(report.path("degraded").asBoolean());
    }

    @Test
    @DisplayName("会话不存在（404）→ 404 + 「面试会话已过期，请重新开始」")
    void sessionNotFoundMapsTo404() {
        answerStatus = 404;
        answerBody = "{\"detail\":{\"code\":\"SESSION_NOT_FOUND\",\"message\":\"session expired\"}}";

        BizException e = assertThrows(BizException.class,
                () -> service.answer(answerRequest("s-404", "任意回答"), 7L));
        assertEquals(404, e.getCode());
        assertEquals("面试会话已过期，请重新开始", e.getMessage());
    }

    @Test
    @DisplayName("Python 5xx → 503 + 「面试服务暂不可用，请稍后再试」")
    void serverErrorMapsTo503() {
        answerStatus = 500;
        answerBody = "{\"detail\":\"boom\"}";

        BizException e = assertThrows(BizException.class,
                () -> service.answer(answerRequest("s-1", "回答"), 7L));
        assertEquals(503, e.getCode());
        assertEquals("面试服务暂不可用，请稍后再试", e.getMessage());
    }

    @Test
    @DisplayName("未开启 FastAPI → 立即 503，不发起任何网络请求")
    void disabledFastApiReturns503() {
        properties.getFastapi().setEnabled(false);
        BizException e = assertThrows(BizException.class,
                () -> service.start(startRequest("RAG", 3, null), 1L));
        assertEquals(503, e.getCode());
        assertEquals("面试服务暂不可用，请稍后再试", e.getMessage());
        assertTrue(paths.isEmpty(), "未启用时不应发起请求");
    }

    @Test
    @DisplayName("他人 session_id 作答 → 403（防止借用别人的面试进度）")
    void otherUsersSessionIsForbidden() {
        // 用户 7 开局，登记归属
        service.start(startRequest("RAG", 3, null), 7L);

        BizException e = assertThrows(BizException.class,
                () -> service.answer(answerRequest("s-1", "我来接手"), 8L));
        assertEquals(403, e.getCode());
        assertEquals("无权操作该面试会话", e.getMessage());
        // 只有开局的 start 打到了 Python，越权请求在本地就被拦下
        assertEquals(1, paths.size());
    }


    // ------------------------------------------------------------------
    // 落库：失败不得打断面试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("落库失败：面试照常返回题目，失败计数 +1（静默失败必须可见）")
    void persistenceFailureDoesNotBreakInterview() {
        org.mockito.Mockito.when(sessionMapper.insert(org.mockito.ArgumentMatchers.any(AiInterviewSession.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        JsonNode node = service.start(startRequest("RAG", 3, null), 7L);

        assertEquals("s-1", node.path("session_id").asText(), "落库失败也必须把题目返回给用户");
        assertEquals(1, service.persistenceFailures(), "失败计数应 +1，否则静默丢数据无人发现");
    }

    @Test
    @DisplayName("作答落库：题面来自上一轮缓存，追问标记与得分如实写入")
    void qaIsPersistedWithCachedQuestion() {
        answerStatus = 200;
        answerBody = """
                {"session_id":"s-1","question_no":1,"total":3,"score":82.5,"verdict":"不错",
                 "hit_points":["要点A"],"miss_points":["要点B"],"reference":"参考答案",
                 "follow_up":"追问一句","next_question":"下一题","finished":false}
                """;
        service.start(startRequest("RAG", 3, null), 7L);
        service.answer(answerRequest("s-1", "我的作答"), 7L);

        org.mockito.ArgumentCaptor<com.examprep.entity.AiInterviewQa> captor =
                org.mockito.ArgumentCaptor.forClass(com.examprep.entity.AiInterviewQa.class);
        org.mockito.Mockito.verify(qaMapper).insert(captor.capture());
        com.examprep.entity.AiInterviewQa saved = captor.getValue();
        assertEquals("什么是 RAG？", saved.getQuestion(), "题面应取自 start 返回的缓存（作答响应里没有题面）");
        assertEquals("我的作答", saved.getAnswer());
        assertEquals(82.5, saved.getScore(), 0.001);
        assertTrue(saved.getMissPoints().contains("要点B"), "遗漏要点应落库为 JSON：" + saved.getMissPoints());
        assertEquals(0, saved.getIsFollowUp(), "普通作答 is_follow_up=0");
    }

    @Test
    @DisplayName("响应缺 session_id → 503（契约漂移不往下游传）")
    void missingSessionIdIsRejected() throws Exception {
        // 用一个新的假服务覆盖 start 的响应
        server.stop(0);
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/v1/interview/start", exchange -> respond(exchange,
                200, "{\"topic\":\"RAG\",\"question\":\"无会话号\"}"));
        broken.start();
        properties.getFastapi().setBaseUrl("http://127.0.0.1:" + broken.getAddress().getPort());
        AiInterviewService brokenService = new AiInterviewService(properties, objectMapper,
                sessionMapper, qaMapper);
        try {
            BizException e = assertThrows(BizException.class,
                    () -> brokenService.start(startRequest("RAG", 3, null), 1L));
            assertEquals(503, e.getCode());
            assertNotNull(e.getMessage());
        } finally {
            broken.stop(0);
        }
    }
}
