package com.examprep.controller;

import com.examprep.common.BizException;
import com.examprep.common.Result;
import com.examprep.common.ResultCode;
import com.examprep.dto.ai.AiStudyPlanRequest;
import com.examprep.dto.ai.KnowledgeWeakVO;
import com.examprep.dto.ai.StudyPlanVO;
import com.examprep.entity.AiEvalRun;
import com.examprep.mapper.AiEvalRunMapper;
import com.examprep.mapper.WrongBookMapper;
import com.examprep.service.QuestionService;
import com.examprep.service.StudyPlanService;
import com.examprep.service.ai.AiGradingService;
import com.examprep.service.ai.AiProperties;
import com.examprep.service.ai.AiQaService;
import com.examprep.service.ai.AiStudyPlanService;
import com.examprep.service.ai.AnswerPostProcessor;
import com.examprep.service.ai.ChunkContext;
import com.examprep.service.ai.ExtractiveAnswerClient;
import com.examprep.service.ai.HybridRetriever;
import com.examprep.service.ai.KnowledgeProfileService;
import com.examprep.service.ai.LlmAnswer;
import com.examprep.service.ai.LlmClient;
import com.examprep.service.ai.PromptBuilder;
import com.examprep.service.ai.SourceRef;
import com.examprep.service.ai.agent.AsyncJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部接口（仅 Java ↔ Python 使用，走独立安全链 + {@code X-Internal-Token}）
 *
 * <p>职责：给 Python Agent Harness 提供业务数据（检索/画像/错题/计划/题目）、
 * LLM 代理、最终答案的 L3 校验，以及异步任务状态回调。
 *
 * <p>这些接口不在 Swagger 对外文档中，也不允许普通 JWT 访问。
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class AgentInternalController {

    private final HybridRetriever hybridRetriever;
    private final KnowledgeProfileService profileService;
    private final WrongBookMapper wrongBookMapper;
    private final StudyPlanService studyPlanService;
    private final QuestionService questionService;
    private final AiQaService aiQaService;
    private final AnswerPostProcessor answerPostProcessor;
    private final AsyncJobService asyncJobService;
    private final AiGradingService aiGradingService;
    private final AiStudyPlanService aiStudyPlanService;
    private final AiEvalRunMapper evalRunMapper;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final List<LlmClient> llmClients;

    // ==================================================================
    // 工具数据
    // ==================================================================

    @PostMapping("/agent/tools/search")
    public Result<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String question = str(body.get("question"));
        if (!StringUtils.hasText(question)) {
            throw new BizException(ResultCode.PARAM_ERROR, "question 不能为空");
        }
        Long userId = longOrNull(body.get("userId"));
        Long subjectId = longOrNull(body.get("subjectId"));
        Long examCategoryId = longOrNull(body.get("examCategoryId"));
        int topK = clamp(intOr(body.get("topK"), 5), 1, 20);
        boolean applyGate = !Boolean.FALSE.equals(body.get("applyGate"));

        Set<Long> weak = userId == null ? Set.of() : profileService.weakKnowledgeIds(userId, 5);
        HybridRetriever.Outcome outcome = hybridRetriever.retrieve(
                question, subjectId, examCategoryId, weak, topK, applyGate);

        double threshold = properties.getRetrieval().getRejectThreshold();
        boolean refused = applyGate
                && (outcome.contexts().isEmpty() || outcome.bestScore() < threshold);
        List<ChunkContext> contexts = refused ? List.of() : outcome.contexts();

        List<Map<String, Object>> items = new ArrayList<>();
        for (ChunkContext ctx : contexts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", ctx.chunkId());
            item.put("title", ctx.title());
            item.put("content", ctx.content());
            item.put("score", ctx.finalScore());
            item.put("refType", ctx.refType());
            item.put("refId", ctx.refId());
            item.put("sourceType", ctx.sourceType());
            item.put("sourceId", ctx.sourceId());
            item.put("knowledgeId", ctx.knowledgeId());
            item.put("subjectId", ctx.subjectId());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contexts", items);
        data.put("mode", outcome.mode());
        data.put("recallCount", outcome.recallCount());
        data.put("bestScore", outcome.bestScore());
        data.put("refused", refused);
        return Result.success(data);
    }

    @GetMapping("/agent/tools/weak-knowledge")
    public Result<List<Map<String, Object>>> weakKnowledge(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "5") int limit) {
        int safe = clamp(limit, 1, 20);
        List<KnowledgeWeakVO> rows = profileService.weakKnowledge(userId, safe, null);
        List<Map<String, Object>> data = new ArrayList<>();
        for (KnowledgeWeakVO row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeId", row.getKnowledgeId());
            item.put("name", row.getKnowledgeName());
            item.put("mastery", row.getMastery());
            item.put("weakness", row.getWeakness());
            item.put("attempts", row.getAttempts());
            data.add(item);
        }
        return Result.success(data);
    }

    @GetMapping("/agent/tools/wrong-book")
    public Result<List<Map<String, Object>>> wrongBook(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "5") int limit) {
        int safe = clamp(limit, 1, 20);
        return Result.success(wrongBookMapper.selectUnmasteredTop(userId, safe));
    }

    @GetMapping("/agent/tools/study-plan")
    public Result<StudyPlanVO> studyPlan(@RequestParam Long userId) {
        return Result.success(studyPlanService.today(userId));
    }

    /**
     * 题目详情（Agent 只读工具）。
     *
     * <p>★ A11 产品决策：<b>默认不下发答案</b>。{@code includeAnswer} 缺省为 {@code false} 时，
     * 选项 {@code isAnswer}、{@code analysis}、{@code answerContent}、{@code gradingPoints}
     * 在响应体中整键消失（全局 {@code non_null} 序列化），而不是置 null / 空串。
     * Python 侧只有判分、讲解等确需答案的场景才显式传 {@code includeAnswer=true}。
     *
     * <p>口径来源：{@code docs/阶段二验收结果-2026-10-31.md} A11 +
     * {@code docs/Agent阶段三接口契约.md} §3。
     *
     * @param id            题目 ID
     * @param includeAnswer 是否携带正确答案与解析，默认 false
     * @return 题目详情；题目不存在/未开放时 data 为 null（工具调用不应中断整轮对话）
     */
    @GetMapping("/agent/tools/question/{id}")
    public Result<Object> question(
            @PathVariable Long id,
            @RequestParam(value = "includeAnswer", defaultValue = "false") boolean includeAnswer) {
        try {
            return Result.success(questionService.getQuestionDetail(id, includeAnswer));
        } catch (BizException e) {
            return Result.success(null);
        }
    }

    // ==================================================================
    // LLM 代理与最终答案
    // ==================================================================

    /**
     * 非检索类生成代理（规划器 / Agent 最终回答）
     *
     * <p>Key 只在 Java 侧；全部客户端不可用时返回 503，Python 自动切规则规划器。
     */
    @PostMapping("/agent/llm")
    public Result<String> llm(@RequestBody Map<String, Object> body) {
        String systemPrompt = str(body.get("systemPrompt"));
        String userPrompt = str(body.get("userPrompt"));
        if (!StringUtils.hasText(systemPrompt) || !StringUtils.hasText(userPrompt)) {
            throw new BizException(ResultCode.PARAM_ERROR, "systemPrompt / userPrompt 不能为空");
        }
        for (LlmClient client : llmClients) {
            if (client instanceof ExtractiveAnswerClient || !client.available()) {
                continue;
            }
            try {
                String content = client.complete(systemPrompt, userPrompt);
                if (StringUtils.hasText(content)) {
                    return Result.success(content);
                }
            } catch (Exception e) {
                log.warn("[Agent] LLM 客户端 {} 不可用：{}", client.name(), e.getMessage());
            }
        }
        throw new BizException(ResultCode.AI_LLM_UNAVAILABLE,
                "大模型不可用（未配置 Key / 已熔断）");
    }

    /**
     * 最终答案定稿：L3 校验 draft，或基于 contexts 生成，或走完整 RAG 兜底
     */
    @PostMapping("/agent/answer")
    public Result<Map<String, Object>> answer(@RequestBody Map<String, Object> body) {
        String question = str(body.get("question"));
        if (!StringUtils.hasText(question)) {
            throw new BizException(ResultCode.PARAM_ERROR, "question 不能为空");
        }
        Long subjectId = longOrNull(body.get("subjectId"));
        String draft = str(body.get("draftAnswer"));
        List<ChunkContext> contexts = parseContexts(body.get("contexts"));

        LlmAnswer answer;
        if (!contexts.isEmpty()) {
            // 2026-11-13：原逻辑直接拿规划器草稿做 L3 校验，草稿往往只有一两句，
            // 学生拿到的就是「几十字结论」。改为**先基于资料重写一次**（带引用与讲解结构），
            // 生成失败或被判拒答时才退回草稿，兼顾质量与可用性。
            LlmAnswer generated = null;
            try {
                generated = aiQaService.answerWithContexts(question, contexts);
            } catch (Exception e) {
                log.warn("[Agent] 带资料生成失败，回退规划器草稿：{}", e.getMessage());
            }
            boolean usable = generated != null
                    && StringUtils.hasText(generated.content())
                    && !PromptBuilder.REFUSAL_TEXT.equals(generated.content().trim());
            if (usable) {
                answer = generated;
            } else if (StringUtils.hasText(draft)) {
                LlmAnswer draftAnswer = new LlmAnswer(draft,
                        LlmClient.resolveSources(draft, contexts), "agent-llm", 0L, false);
                answer = answerPostProcessor.verify(question, draftAnswer, contexts);
            } else {
                answer = generated != null ? generated
                        : aiQaService.answerWithContexts(question, contexts);
            }
        } else {
            var request = new com.examprep.dto.ai.AskRequest();
            request.setQuestion(question);
            request.setSubjectId(subjectId);
            var vo = aiQaService.ask(request, null);
            answer = new LlmAnswer(vo.getAnswer(), List.of(), vo.getGenerator(),
                    vo.getCostMillis() == null ? 0L : vo.getCostMillis(),
                    Boolean.TRUE.equals(vo.getDegraded()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", vo.getAnswer());
            data.put("generator", vo.getGenerator());
            data.put("degraded", Boolean.TRUE.equals(vo.getDegraded()));
            data.put("refused", Boolean.TRUE.equals(vo.getRefused()));
            data.put("sources", vo.getSources());
            return Result.success(data);
        }

        // ★ 输出不变量（F-10）：拒答一律归一为统一文案并清空 sources，
        //   否则「正文=未找到相关资料[1][2][3]、refused=false」会自相矛盾。
        //   判据复用 AnswerPostProcessor.isRefusal（正文含拒答文案 / sources 空 / generator=none）。
        boolean refused = answerPostProcessor.isRefusal(answer);
        if (refused && !PromptBuilder.REFUSAL_TEXT.equals(answer.content())) {
            log.warn("[Agent] 拒答但正文非空（generator={}, sources={}），按不变量归一为拒答文案，"
                            + "原正文 {} 字已丢弃",
                    answer.generator(),
                    answer.sources() == null ? 0 : answer.sources().size(),
                    answer.content() == null ? 0 : answer.content().length());
        }
        String answerText = refused ? PromptBuilder.REFUSAL_TEXT : answer.content();
        // 清掉模型机械照抄提示词留下的空话（实测 7B 会输出「资料未覆盖：无」）
        if (!refused && answerText != null) {
            answerText = answerText
                    .replaceAll("(?m)^\\s*资料未覆盖\\s*[：:]\\s*(无|没有|暂无|N/?A|无未覆盖内容)\\s*$", "")
                    .replaceAll("(?m)^\\s*资料未覆盖\\s*[：:]\\s*$", "")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        }
        List<SourceRef> sources = refused ? List.of() : answer.sources();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answerText);
        data.put("generator", answer.generator());
        data.put("degraded", answer.degraded()
                || !PromptBuilder.isLlmGenerator(answer.generator()));
        data.put("refused", refused);
        data.put("sources", sourceMaps(sources));
        return Result.success(data);
    }

    // ==================================================================
    // 异步任务回调
    // ==================================================================

    @PostMapping("/jobs/{id}/progress")
    public Result<Map<String, Object>> jobProgress(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        boolean updated = asyncJobService.progress(id, intOr(body.get("percent"), 0));
        return Result.success(Map.of("updated", updated));
    }

    @PostMapping("/jobs/{id}/complete")
    public Result<Map<String, Object>> jobComplete(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body) {
        String result;
        try {
            result = objectMapper.writeValueAsString(body.get("result"));
        } catch (Exception e) {
            result = "{}";
        }
        boolean updated = asyncJobService.complete(id, result);
        return Result.success(Map.of("updated", updated, "ignored", !updated));
    }

    @PostMapping("/jobs/{id}/fail")
    public Result<Map<String, Object>> jobFail(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        boolean updated = asyncJobService.fail(id, str(body.get("error")));
        return Result.success(Map.of("updated", updated, "ignored", !updated));
    }

    // ==================================================================
    // 测评中台：评测用内部接口（仅 Python 评测脚本 / worker 调用）
    // ==================================================================

    /**
     * 主观题判分预览：按题干 + 参考答案直接判分，不依赖题库 ID。
     *
     * <p>供 {@code ai-service/eval/grading_eval.py} 评测「判分等级一致率」使用，
     * 与线上 {@code POST /questions/{id}/grade} 共用同一套 Prompt 与解析。
     */
    @PostMapping("/ai/eval/grading")
    public Result<AiGradingService.GradeResult> evalGrading(@RequestBody Map<String, Object> body) {
        String stem = str(body.get("stem"));
        String reference = str(body.get("reference"));
        String userAnswer = str(body.get("userAnswer"));
        return Result.success(aiGradingService.grade(stem, reference, userAnswer));
    }

    /**
     * 学习计划预览：按用户真实错题生成计划，并回传真实错题 ID 白名单。
     *
     * <p>供 {@code study_plan_eval.py} 校验「JSON 合规率」与「题号零幻觉率」。
     */
    @PostMapping("/ai/eval/study-plan")
    public Result<Map<String, Object>> evalStudyPlan(@RequestBody Map<String, Object> body) {
        Long userId = longOrNull(body.get("userId"));
        if (userId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "userId 必填");
        }
        AiStudyPlanRequest request = new AiStudyPlanRequest();
        Integer days = intOrNull(body.get("days"));
        if (days != null) {
            request.setDays(days);
        }
        Integer dailyTarget = intOrNull(body.get("dailyTarget"));
        if (dailyTarget != null) {
            request.setDailyTarget(dailyTarget);
        }
        request.setSubjectId(longOrNull(body.get("subjectId")));
        request.setGoalNote(str(body.get("goalNote")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", aiStudyPlanService.generate(userId, request));
        data.put("realWrongIds", wrongBookMapper.selectWrongQuestionIds(userId));
        return Result.success(data);
    }

    /**
     * 评测结果写回 ai_eval_run（CLI 直跑场景）。
     *
     * <p>worker 触发的评测走 {@code /jobs/{id}/complete} 自动落库；
     * 本接口只服务「脚本独立运行 + --write-back」。
     */
    @PostMapping("/ai/eval-runs")
    public Result<Map<String, Object>> saveEvalRun(@RequestBody Map<String, Object> body) {
        String layer = str(body.get("layer"));
        if (!AiEvalRun.LAYERS.contains(layer)) {
            throw new BizException(ResultCode.PARAM_ERROR, "未知评测层：" + layer);
        }
        AiEvalRun run = new AiEvalRun();
        run.setLayer(layer);
        String status = str(body.get("status"));
        run.setStatus(AiEvalRun.STATUS_FAILED.equals(status)
                ? AiEvalRun.STATUS_FAILED : AiEvalRun.STATUS_SUCCESS);
        run.setTotal(intOr(body.get("total"), 0));
        run.setPassed(intOr(body.get("passed"), 0));
        run.setMetricsJson(writeJsonOrNull(body.get("metrics")));
        run.setDetailJson(writeJsonOrNull(body.get("detail")));
        run.setReportPath(str(body.get("reportPath")));
        run.setError(str(body.get("error")));
        run.setCreatedAt(java.time.LocalDateTime.now());
        run.setFinishedAt(java.time.LocalDateTime.now());
        evalRunMapper.insert(run);
        return Result.success(Map.of("id", run.getId(), "layer", layer));
    }

    private String writeJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 解析辅助
    // ==================================================================

    @SuppressWarnings("unchecked")
    private List<ChunkContext> parseContexts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<ChunkContext> contexts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            contexts.add(new ChunkContext(
                    longOrNull(m.get("chunkId")),
                    str(m.get("sourceType")),
                    longOrNull(m.get("sourceId")),
                    str(m.get("title")),
                    str(m.get("content")),
                    longOrNull(m.get("subjectId")),
                    longOrNull(m.get("knowledgeId")),
                    intOrNull(m.get("difficulty")),
                    str(m.get("refType")),
                    longOrNull(m.get("refId")),
                    intOrNull(m.get("pageNo")),
                    null,
                    0L,
                    doubleOr(m.get("score"), 0.0),
                    doubleOr(m.get("score"), 0.0)));
        }
        return contexts;
    }

    private List<Map<String, Object>> sourceMaps(List<SourceRef> sources) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (sources == null) {
            return list;
        }
        for (SourceRef source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", source.chunkId());
            item.put("title", source.title());
            item.put("snippet", source.snippet());
            item.put("refType", source.refType());
            item.put("refId", source.refId());
            item.put("refPath", source.refPath());
            list.add(item);
        }
        return list;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = intOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static double doubleOr(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
