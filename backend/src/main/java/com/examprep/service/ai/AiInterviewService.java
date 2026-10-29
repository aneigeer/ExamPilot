package com.examprep.service.ai;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.common.BizException;
import com.examprep.common.PageResult;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.InterviewAnswerRequest;
import com.examprep.dto.ai.InterviewDetailVO;
import com.examprep.dto.ai.InterviewHistoryVO;
import com.examprep.dto.ai.InterviewStartRequest;
import com.examprep.entity.AiInterviewQa;
import com.examprep.entity.AiInterviewSession;
import com.examprep.mapper.AiInterviewQaMapper;
import com.examprep.mapper.AiInterviewSessionMapper;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AiInterviewSessionMapper sessionMapper;
    private final AiInterviewQaMapper qaMapper;
    private final RestClient interviewClient;

    /**
     * 落库失败计数（单调递增）
     *
     * <p><b>为什么要暴露</b>：落库失败被设计成「不影响面试继续」，于是它的失败是**静默**的。
     * 静默失败最危险的地方是没人会发现——上线几天后才发现历史记录缺了一半。
     * 计数 + warn 日志让它在监控/排障时可见（{@code /api/admin/ai/ops} 一类接口可直接读）。
     */
    private final AtomicLong persistenceFailures = new AtomicLong();

    /** python session_id → 本地 ai_interview_session.id（落库关闭或失败时可能没有） */
    private final Map<String, Long> localSessionIds = new ConcurrentHashMap<>();

    /**
     * 当前待作答的题面（python session_id → 题面文本）
     *
     * <p><b>为什么必须自己记</b>：作答接口的响应里只有 {@code next_question}，
     * <b>没有</b>「你刚答的那道题」。要落库「题面 + 作答 + 得分」三件套，
     * 就必须在上一轮响应时把题面缓存下来（start 的第一题、以及每次的 next_question）。
     */
    private final Map<String, String> pendingQuestions = new ConcurrentHashMap<>();

    /**
     * 当前待答题号（python session_id → 题号）
     *
     * <p><b>为什么不能直接用作答响应里的 question_no</b>（实测踩坑）：
     * 作答响应里的 {@code question_no} 表示「<b>接下来</b>要答第几题」，
     * 而落库要记的是「<b>刚答的</b>是第几题」——直接取会整体 +1，
     * 库里出现第 1 题记成 q_no=2、第 3 题与第 2 题都记成 3 的错位。
     * 因此与题面一样，题号也必须在上一轮响应时缓存下来。
     */
    private final Map<String, Integer> currentQuestionNos = new ConcurrentHashMap<>();

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

    public AiInterviewService(AiProperties properties, ObjectMapper objectMapper,
                              AiInterviewSessionMapper sessionMapper,
                              AiInterviewQaMapper qaMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sessionMapper = sessionMapper;
        this.qaMapper = qaMapper;
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
        // 题面缓存：作答接口不返回「刚答的那道题」，必须在这里记下来才能落库
        pendingQuestions.put(sessionId, node.path("question").asText(""));
        currentQuestionNos.put(sessionId, node.path("question_no").asInt(1));
        persistSession(sessionId, userId, node.path("topic").asText(""), total);
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
        boolean followUp = node.path("is_follow_up").asBoolean(false);
        // 先落库（用缓存的题面），再更新题面缓存：顺序反了会把「下一题」记成「刚答的题」
        Long localId = localSessionIds.get(sessionId);
        // 刚答的那道题的题号 = 上一轮缓存的题号（响应里的 question_no 是"下一题"）
        int answeredNo = currentQuestionNos.getOrDefault(sessionId,
                Math.max(1, node.path("question_no").asInt(1)));
        persistQa(sessionId, localId, answeredNo, node, followUp, request.getAnswer());
        if (!finished) {
            // 追问轮 question_no 不前进，这里按原值回写也不会错
            currentQuestionNos.put(sessionId, node.path("question_no").asInt(answeredNo));
        }
        String next = node.path("next_question").asText("");
        if (StringUtils.hasText(next)) {
            pendingQuestions.put(sessionId, next);
        }
        if (finished) {
            persistFinish(sessionId, localId, node.path("report"));
            // 会话结束后清理内存态，避免这三张表只增不减
            sessionOwners.remove(sessionId);
            localSessionIds.remove(sessionId);
            pendingQuestions.remove(sessionId);
            currentQuestionNos.remove(sessionId);
            log.info("[Interview] 面试结束 session={} total_score={} level={} degraded={}",
                    sessionId, node.path("report").path("total_score").asDouble(0),
                    node.path("report").path("level").asText(""),
                    node.path("report").path("degraded").asBoolean(false));
        } else {
            log.info("[Interview] 评分完成 session={} question_no={} score={} followUp={}",
                    sessionId, node.path("question_no").asInt(0),
                    node.path("score").asDouble(0), followUp);
        }
        return node;
    }

    // ==================================================================
    // 落库（失败绝不影响面试主流程）
    // ==================================================================

    /**
     * 写入会话行（幂等：同一 python session 重复回调不会插两行）
     *
     * <p><b>失败策略</b>：只记 warn + 计数，<b>绝不抛出</b>。
     * 面试是实时交互，数据库抖动不应该让用户「答到一半被封」；
     * 代价是这条记录会缺，所以必须让失败可见（见 {@link #persistenceFailures()}）。
     */
    private void persistSession(String pythonSessionId, Long userId, String topic, int total) {
        if (userId == null) {
            return;
        }
        try {
            AiInterviewSession existing = sessionMapper.selectOne(
                    Wrappers.<AiInterviewSession>lambdaQuery()
                            .eq(AiInterviewSession::getPythonSessionId, pythonSessionId)
                            .last("LIMIT 1"));
            if (existing != null) {
                // 重放/重试：沿用原行，不重复插入
                localSessionIds.put(pythonSessionId, existing.getId());
                return;
            }
            AiInterviewSession session = new AiInterviewSession();
            session.setUserId(userId);
            session.setPythonSessionId(pythonSessionId);
            session.setTopic(topic);
            session.setTotalQuestions(total);
            session.setFinished(0);
            session.setStartedAt(LocalDateTime.now());
            session.setIsDeleted(0);
            sessionMapper.insert(session);
            // 自增主键由 MySQL 回填；拿不到 id 时不要缓存（ConcurrentHashMap 不接受 null 值）
            if (session.getId() != null) {
                localSessionIds.put(pythonSessionId, session.getId());
            }
        } catch (Exception e) {
            recordPersistenceFailure("session", pythonSessionId, e);
        }
    }

    /** 写入一条作答记录；会话已结束（重复提交）时跳过，避免脏数据 */
    private void persistQa(String pythonSessionId, Long localId, int answeredNo, JsonNode node,
                           boolean followUp, String answerText) {
        if (localId == null) {
            return; // 落库关闭或会话行写入失败：静默跳过，不影响作答
        }
        try {
            AiInterviewSession session = sessionMapper.selectById(localId);
            if (session != null && Integer.valueOf(1).equals(session.getFinished())) {
                log.debug("[Interview] 会话已结束，跳过重复提交的作答落库 session={}", pythonSessionId);
                return;
            }
            AiInterviewQa qa = new AiInterviewQa();
            qa.setSessionId(localId);
            qa.setQuestionNo(answeredNo);
            qa.setQuestion(truncate(pendingQuestions.get(pythonSessionId), 4000));
            qa.setAnswer(answerText);
            qa.setScore(node.hasNonNull("score") ? node.get("score").asDouble() : null);
            qa.setVerdict(truncate(node.path("verdict").asText(""), 255));
            qa.setHitPoints(node.path("hit_points").toString());
            qa.setMissPoints(node.path("miss_points").toString());
            qa.setReferenceText(node.path("reference").asText(""));
            qa.setIsFollowUp(followUp ? 1 : 0);
            qa.setCreatedAt(LocalDateTime.now());
            qaMapper.insert(qa);
        } catch (Exception e) {
            recordPersistenceFailure("qa", pythonSessionId, e);
        }
    }

    /** 回填总分 / 等级 / 结束时间 */
    private void persistFinish(String pythonSessionId, Long localId, JsonNode report) {
        if (localId == null) {
            return;
        }
        try {
            AiInterviewSession update = new AiInterviewSession();
            update.setId(localId);
            update.setFinished(1);
            update.setTotalScore(report.path("total_score").asDouble(0));
            update.setLevel(truncate(report.path("level").asText(""), 32));
            update.setFinishedAt(LocalDateTime.now());
            sessionMapper.updateById(update);
        } catch (Exception e) {
            recordPersistenceFailure("finish", pythonSessionId, e);
        }
    }

    private void recordPersistenceFailure(String stage, String sessionId, Exception e) {
        long count = persistenceFailures.incrementAndGet();
        log.warn("[Interview] 落库失败（{}，session={}，累计 {} 次，面试继续）：{}",
                stage, sessionId, count, e.getMessage());
    }

    /** 落库失败累计次数（监控/排障用；0 表示一直正常） */
    public long persistenceFailures() {
        return persistenceFailures.get();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
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
    // 历史查询（学生看自己，管理员/审核员可看全部）
    // ==================================================================

    /**
     * 面试历史分页
     *
     * @param page         页码（从 1 开始）
     * @param size         每页条数（1-50）
     * @param filterUserId 只看该用户；null 表示不限（仅管理员/审核员会传 null）
     */
    public PageResult<InterviewHistoryVO> history(long page, long size, Long filterUserId) {
        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(50, size));
        IPage<AiInterviewSession> pageResult = sessionMapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<AiInterviewSession>lambdaQuery()
                        .eq(filterUserId != null, AiInterviewSession::getUserId, filterUserId)
                        .orderByDesc(AiInterviewSession::getStartedAt));
        List<AiInterviewSession> records = pageResult.getRecords();
        Map<Long, Integer> answered = answeredCounts(records);
        List<InterviewHistoryVO> list = new ArrayList<>(records.size());
        for (AiInterviewSession s : records) {
            InterviewHistoryVO vo = new InterviewHistoryVO();
            vo.setId(s.getId());
            vo.setTopic(s.getTopic());
            vo.setTotalQuestions(s.getTotalQuestions());
            vo.setAnsweredCount(answered.getOrDefault(s.getId(), 0));
            vo.setTotalScore(s.getTotalScore());
            vo.setLevel(s.getLevel());
            vo.setFinished(s.getFinished());
            vo.setStartedAt(s.getStartedAt());
            vo.setFinishedAt(s.getFinishedAt());
            list.add(vo);
        }
        return PageResult.of(pageResult.getCurrent(), pageResult.getSize(),
                pageResult.getTotal(), list);
    }

    /**
     * 面试详情（复盘用）
     *
     * @param id          ai_interview_session.id
     * @param userId      当前登录用户
     * @param canViewAll  是否管理员/审核员（可看他人记录）
     * @throws BizException 记录不存在（404）或越权（403）
     */
    public InterviewDetailVO detail(long id, Long userId, boolean canViewAll) {
        AiInterviewSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new BizException(ResultCode.NOT_FOUND, "面试记录不存在");
        }
        if (!canViewAll && (userId == null || !userId.equals(session.getUserId()))) {
            // 越权看他人记录：不泄露「这条记录存在」以外的信息
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该面试记录");
        }
        InterviewDetailVO vo = new InterviewDetailVO();
        vo.setId(session.getId());
        vo.setTopic(session.getTopic());
        vo.setTotalQuestions(session.getTotalQuestions());
        vo.setTotalScore(session.getTotalScore());
        vo.setLevel(session.getLevel());
        vo.setFinished(session.getFinished());
        vo.setStartedAt(session.getStartedAt());
        vo.setFinishedAt(session.getFinishedAt());

        List<AiInterviewQa> qaRows = qaMapper.selectList(
                Wrappers.<AiInterviewQa>lambdaQuery()
                        .eq(AiInterviewQa::getSessionId, id)
                        // 题号升序 + 时间升序：同一题的追问轮排在主作答之后，客户端顺序渲染即对话
                        .orderByAsc(AiInterviewQa::getQuestionNo)
                        .orderByAsc(AiInterviewQa::getId));
        List<InterviewDetailVO.Qa> qaList = new ArrayList<>(qaRows.size());
        for (AiInterviewQa row : qaRows) {
            InterviewDetailVO.Qa qa = new InterviewDetailVO.Qa();
            qa.setId(row.getId());
            qa.setQuestionNo(row.getQuestionNo());
            qa.setQuestion(row.getQuestion());
            qa.setAnswer(row.getAnswer());
            qa.setScore(row.getScore());
            qa.setVerdict(row.getVerdict());
            qa.setHitPoints(readStringList(row.getHitPoints()));
            qa.setMissPoints(readStringList(row.getMissPoints()));
            qa.setReference(row.getReferenceText());
            qa.setIsFollowUp(row.getIsFollowUp());
            qa.setCreatedAt(row.getCreatedAt());
            qaList.add(qa);
        }
        vo.setQaList(qaList);
        vo.setAnsweredCount(qaList.size());
        return vo;
    }

    /** 批量取各会话作答条数（一次 GROUP BY，避免列表 N+1） */
    private Map<Long, Integer> answeredCounts(List<AiInterviewSession> sessions) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (sessions.isEmpty()) {
            return result;
        }
        List<Long> ids = sessions.stream().map(AiInterviewSession::getId).toList();
        try {
            for (Map<String, Object> row : qaMapper.countBySessions(ids)) {
                Object sid = row.get("sessionId");
                Object cnt = row.get("cnt");
                if (sid instanceof Number n1 && cnt instanceof Number n2) {
                    result.put(n1.longValue(), n2.intValue());
                }
            }
        } catch (Exception e) {
            // 统计失败不影响列表主体：answeredCount 退化为 0，页面仍可用
            log.warn("[Interview] 作答数统计失败（列表仍返回）：{}", e.getMessage());
        }
        return result;
    }

    /** JSON 数组字符串 → List；脏数据/空值一律退化为空 list，不让详情页 500 */
    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> list.add(item.asText("")));
            }
            return list;
        } catch (Exception e) {
            log.debug("[Interview] 要点 JSON 解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
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
