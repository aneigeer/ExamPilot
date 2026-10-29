package com.examprep.service.ai;

import com.examprep.common.BizException;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.InterviewAnswerRequest;
import com.examprep.dto.ai.InterviewStartRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 面试代理服务（Java ↔ Python）
 *
 * <p><b>职责边界</b>：本类只做「透传 + 错误翻译」，<b>不落库、不改写响应结构</b>。
 * 出题、评分、报告全部由 Python AI 服务基于已入库的面试 Q&amp;A 语料完成；
 * Java 侧负责鉴权、会话归属校验、以及把 Python 的失败翻译成用户能看懂的话。
 *
 * <p><b>为什么响应体用 {@link JsonNode} 透传而不是建一整套 DTO</b>：
 * <ol>
 *   <li>契约已由三方共同固定为 snake_case，透传可保证字段<b>逐字一致</b>，
 *       不会因为 Java 侧新建 DTO 少写一个字段（如 {@code hit_points}）而在链路上
 *       被静默丢弃——这类丢字段的问题不报错，只表现为「App 上就是不显示」；</li>
 *   <li>{@code report}、{@code follow_up}、{@code source} 都是可空分支，
 *       透传天然兼容，新增字段（如 Python 侧加 {@code time_spent}）无需改 Java。</li>
 * </ol>
 *
 * <p><b>降级策略</b>：面试<b>无法降级</b>（没有本地兜底出题/评分能力），
 * 因此 Python 不可用时如实返回 503 + 友好文案，绝不返回假的题目或假的分数。
 * 这与问答链路的「五层降级」是刻意不同的：宁可告诉用户「稍后再试」，
 * 也不能让面试官给出编造的评分。
 *
 * <p><b>超时</b>：单次作答要跑「检索 + 大模型评分」，本地 7B 模型需 15–25 秒，
 * 故走独立超时（{@code app.ai.fastapi.interview-timeout-millis}，默认 60s），
 * <b>不复用检索的 1200ms 超时</b>（那会每个请求都超时）。
 */
@Slf4j
@Service
public class AiInterviewService {

    /** 不可用统一文案：不暴露内部细节（Python 端口、熔断状态等只写日志） */
    private static final String UNAVAILABLE_MESSAGE = "面试服务暂不可用，请稍后再试";

    /** 会话过期文案 */
    private static final String SESSION_EXPIRED_MESSAGE = "面试会话已过期，请重新开始";

    /** 会话归属表容量上限（防内存无限增长；超出后随机淘汰最早的条目） */
    private static final int MAX_TRACKED_SESSIONS = 5000;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient interviewClient;

    /**
     * 会话 → 用户 归属映射
     *
     * <p><b>为什么需要</b>：{@code session_id} 一旦泄露，别人就能用自己的账号
     * 接着答（读取他人面试进度、把分数刷到别人名下）。Java 侧记住「谁开的会话」，
     * 他人作答直接 403。
     *
     * <p><b>为什么不落库</b>：面试会话本就是短生命周期（一场 3-5 分钟），
     * Python 侧重启后会话即失效，Java 重启后这份表丢失只会「少一层校验」，
     * 不会让正常用户无法继续（见 {@link #answer} 的宽松判定）。
     */
    private final Map<String, Long> sessionOwners = new ConcurrentHashMap<>();

    public AiInterviewService(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeout = Math.max(5_000, properties.getFastapi().getInterviewTimeoutMillis());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(5_000, timeout)));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        this.interviewClient = RestClient.builder()
                .baseUrl(properties.getFastapi().normalizedBaseUrl())
                .requestFactory(factory)
                .build();
        log.info("[Interview] AI 面试代理已注册（{}，超时 {}ms）",
                properties.getFastapi().normalizedBaseUrl(), timeout);
    }

    // ==================================================================
    // 开始面试
    // ==================================================================

    /**
     * 开始一场面试
     *
     * <p>返回的 {@code data} 为 Python 原始响应透传：
     * {@code {session_id, topic, question_no, total, question, source:{title, source_file, page_no}}}。
     *
     * @param request  开始请求（topic 可空 = 随机主题）
     * @param userId   当前登录用户（会话归属登记用）
     * @return 透传的响应体
     * @throws BizException Python 不可用 / 超时 / 响应异常（503 + 友好文案）
     */
    public JsonNode start(InterviewStartRequest request, Long userId) {
        // 服务端补齐默认值：Android 端也可能不传 total，不能指望客户端一定带上
        int total = request.getTotal() == null ? 3 : request.getTotal();
        total = Math.max(1, Math.min(10, total));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", request.getTopic() == null ? "" : request.getTopic().trim());
        body.put("total", total);
        // ★ 必须显式放 subject_id（即使为 null）：Python 侧按字段名读取，
        //   缺字段与传 null 在 pydantic 默认值上行为不同，显式传更可预期
        body.put("subject_id", request.getSubjectId());

        JsonNode node = post("/interview/start", body, false);
        String sessionId = node.path("session_id").asText("");
        if (sessionId.isBlank()) {
            // 契约要求必带 session_id：拿不到说明 Python 侧异常或契约漂移，
            // 此时继续下游只会得到「会话不存在」，不如现在就报错并打日志
            log.warn("[Interview] /interview/start 响应缺少 session_id：{}", abbreviate(node));
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        rememberSession(sessionId, userId);
        log.info("[Interview] 会话已创建 session={} topic={} total={} userId={}",
                sessionId, node.path("topic").asText(""), total, userId);
        return node;
    }

    // ==================================================================
    // 作答 + 评分
    // ==================================================================

    /**
     * 提作答，返回评分 / 追问 / 下一题，或（最后一题后）整场报告
     *
     * <p>透传字段：{@code score / verdict / hit_points / miss_points / reference /
     * follow_up / next_question / finished / question_no / total}；
     * 结束时多一个 {@code report}（{@code total_score / level / per_question /
     * strengths / weaknesses / suggestions / degraded}）。
     *
     * @param request 作答请求
     * @param userId  当前登录用户（会话归属校验）
     * @return 透传的响应体
     * @throws BizException 会话过期（404）/ 越权（403）/ 服务不可用（503）
     */
    public JsonNode answer(InterviewAnswerRequest request, Long userId) {
        String sessionId = request.getSessionId() == null ? "" : request.getSessionId().trim();
        checkOwnership(sessionId, userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("answer", request.getAnswer());

        JsonNode node = post("/interview/answer", body, true);
        boolean finished = node.path("finished").asBoolean(false);
        if (finished) {
            // 会话结束后清理归属记录，避免这张表只增不减
            sessionOwners.remove(sessionId);
            log.info("[Interview] 面试结束 session={} total_score={} level={} degraded={}",
                    sessionId, node.path("report").path("total_score").asDouble(0),
                    node.path("report").path("level").asText(""),
                    node.path("report").path("degraded").asBoolean(false));
        } else {
            log.info("[Interview] 评分完成 session={} question_no={} score={}",
                    sessionId, node.path("question_no").asInt(0), node.path("score").asDouble(0));
        }
        return node;
    }

    // ==================================================================
    // 内部：HTTP 调用 + 错误翻译
    // ==================================================================

    /**
     * 调用 Python 面试接口
     *
     * @param endpoint   相对路径（不含 {@code /v1} 前缀）
     * @param body       已按 snake_case 组好的请求体
     * @param tolerate404 是否把 404 当作「会话过期」（仅 /answer 成立）
     * @return 解析后的响应 JSON
     */
    private JsonNode post(String endpoint, Map<String, Object> body, boolean tolerate404) {
        if (!properties.getFastapi().isEnabled()) {
            log.warn("[Interview] app.ai.fastapi.enabled=false，面试能力不可用");
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        String uri = properties.getFastapi().path(endpoint);
        try {
            String raw = interviewClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(raw)) {
                log.warn("[Interview] {} 返回空响应", uri);
                throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
            }
            return objectMapper.readTree(raw);
        } catch (BizException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw translateHttpError(uri, e, tolerate404);
        } catch (Exception e) {
            // 连接失败 / 读超时 / JSON 解析失败统一归为「服务不可用」：
            // 对用户来说结果一样（这题答不了），细节只对排障有意义
            log.warn("[Interview] {} 调用失败：{}: {}", uri, e.getClass().getSimpleName(),
                    e.getMessage());
            throw new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * 把 Python 的 HTTP 错误翻译成用户可理解的业务错误
     *
     * <p>Python 侧会话不存在时按 FastAPI 惯例返回 <b>404</b>，
     * 响应体可能是 {@code {"detail":"..."}} 或 {@code {"detail":{"code":"SESSION_NOT_FOUND"}}}。
     * 两种形态都判：只看状态码会误伤（例如路由写错也是 404），
     * 只看 body 又会因 detail 是纯字符串而漏判，故「404 或 body 含 SESSION_NOT_FOUND」
     * 任一成立即视为会话过期。
     */
    private BizException translateHttpError(String uri, RestClientResponseException e,
                                            boolean tolerate404) {
        int status = e.getStatusCode().value();
        String respBody = e.getResponseBodyAsString();
        String detail = respBody == null ? "" : respBody;
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        log.warn("[Interview] {} 被拒绝 HTTP {}：{}", uri, status, detail);

        boolean sessionGone = detail.contains("SESSION_NOT_FOUND")
                || (tolerate404 && status == 404);
        if (sessionGone) {
            return new BizException(ResultCode.NOT_FOUND, SESSION_EXPIRED_MESSAGE);
        }
        if (status == 422 || status == 400) {
            // 参数层面被拒（如 answer 为空、total 越界）：如实告诉用户哪不对，
            // 但文案不透传 Python 的英文校验串，避免界面上出现技术黑话
            return new BizException(ResultCode.PARAM_ERROR, "提交内容不合规，请检查后重试");
        }
        return new BizException(ResultCode.AI_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }

    // ==================================================================
    // 内部：会话归属
    // ==================================================================

    private void rememberSession(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return;
        }
        if (sessionOwners.size() >= MAX_TRACKED_SESSIONS) {
            sessionOwners.clear();
            log.info("[Interview] 会话归属表已满 {} 条，已清空重置", MAX_TRACKED_SESSIONS);
        }
        sessionOwners.put(sessionId, userId);
    }

    /**
     * 会话归属校验
     *
     * <p>只在「表里有记录且不是本人」时拒绝；表里没有记录（Java 侧重启、
     * 或用户刚换了设备且服务端已回收）时<b>放行</b>——
     * 此时 Python 侧仍会按自己的会话表判定存在性，
     * 我们宁可在极端情况下少拦一次，也不能让正常面试因重启而中断。
     */
    private void checkOwnership(String sessionId, Long userId) {
        if (!StringUtils.hasText(sessionId) || userId == null) {
            return;
        }
        Long owner = sessionOwners.get(sessionId);
        if (owner != null && !owner.equals(userId)) {
            log.warn("[Interview] 越权作答被拦截 session={} owner={} current={}",
                    sessionId, owner, userId);
            throw new BizException(ResultCode.FORBIDDEN, "无权操作该面试会话");
        }
    }

    /** 日志里截断超长响应体，避免刷屏 */
    private static String abbreviate(JsonNode node) {
        String text = node == null ? "null" : node.toString();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
