package com.examprep.service.ai;

import com.examprep.dto.ai.AskRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.dto.ai.CapabilityVO;
import com.examprep.dto.ai.SourceRefVO;
import com.examprep.entity.AiChunk;
import com.examprep.entity.WikiPage;
import com.examprep.service.wiki.WikiQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 问答编排服务（设计方案 B.4 降级链）
 *
 * <p><b>完整链路</b>：
 * <pre>
 *   ① 参数校验（空问题 / 超长 → 400）
 *   ② 检索：RetrievalService 召回 + 五因子重排 + 拒答阈值
 *      └─ 无命中 / 最高分 &lt; 0.15 → <b>直接拒答，不进生成环节</b>（L1 防线）
 *   ③ 用户画像加权：登录用户查薄弱知识点（+0.15）；游客跳过，不建画像
 *   ④ 生成：LlmClient 链式选择 —— 大模型可用则调大模型，否则 ExtractiveAnswerClient
 *   ⑤ 后处理：AnswerPostProcessor 做 L3 幻觉校验，不通过则切抽取式
 *   ⑥ 组装 AskVO（sources 永不为 null）
 * </pre>
 *
 * <p><b>四层降级</b>：拒答 → 抽取式 → 熔断静默降级 → App 端隐藏 AI 入口。
 * 最差情况是「没有 AI 增强」，而不是「功能不可用」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQaService {

    private final HybridRetriever hybridRetriever;

    /** 语义路由（A1）：决定 skill 与来源过滤；未启用/失败时返回默认值，不影响回答 */
    private final AiRouteClient routeClient;
    private final KnowledgeProfileService profileService;
    private final ExtractiveAnswerClient extractiveAnswerClient;
    private final AnswerPostProcessor answerPostProcessor;
    private final PromptBuilder promptBuilder;
    private final ChunkSyncService chunkSyncService;
    private final AiProperties properties;

    /**
     * Wiki 热层（双引擎第一层）
     *
     * <p>热层命中直接返回预编译主题页；未命中才走下面的 RAG 冷层。
     * Wiki 关闭 / 无已发布页面 / 未命中时行为与原先完全一致。
     */
    private final WikiQueryService wikiQueryService;

    /** 向量通道：能力探测与降级状态展示用（检索本身已收口到 HybridRetriever） */
    private final VectorChannel vectorChannel;

    /**
     * 大模型客户端列表
     *
     * <p><b>注入类型必须是 {@link LlmClient} 接口而不是抽象类</b>：
     * 问答链路只依赖接口契约，新增任何实现（本地 SDK 客户端、
     * 独立的 FastAPI 微服务客户端等）都能被 Spring 扫描到并自动参与降级链，
     * 本类一行不用改。这是「AI 推理拆成微服务」的前置条件。
     *
     * <p><b>★ 顺序即优先级（必须用 {@code @Order} 显式声明）</b>：
     * {@code generate()} 取「第一个 available 的」，所以多个客户端同时可用时
     * <b>winner 取决于注入顺序</b>。缺省不写 {@code @Order} 时顺序由 classpath 扫描决定，
     * 行为会随打包环境漂移、极难排查。当前档位：
     * <pre>
     *    5 Ollama        本地模型（零网络依赖、不过期、不收费）—— 排在云端之前
     *   10 DashScope     云端直连，质量最高
     *   15 FastApiLlm    独立 AI 微服务代理（统一网关/限流/审计时用）
     *   20 Zhipu         云端直连
     *   30 Extractive    本地抽取（兜底，另被 isProviderCandidate 双重排除）
     * </pre>
     * <b>为什么 Ollama 排最前</b>：云端 Key 会过期/限流/欠费，而本地模型不会。
     * 现场答辩最怕「Key 突然失效」，本地模型是更稳的第一选择；
     * 需要云端质量时改 {@code app.ai.llm.provider} 一行即可切回。
     * <b>新增实现时必须显式给一个档位</b>，不要依赖默认顺序。
     */
    private final List<LlmClient> llmClients;

    /**
     * AI 问答主入口
     *
     * @param req    请求参数
     * @param userId 当前用户 ID；<b>可为 null（游客）</b>，此时不建画像加权
     */
    public AskVO ask(AskRequest req, Long userId) {
        long start = System.currentTimeMillis();
        String question = req.getQuestion() == null ? "" : req.getQuestion().trim();

        // ---------- 阶段0 学情画像（游客跳过，不建画像）----------
        Set<Long> weakKpIds = userId == null
                ? Set.of()
                : profileService.weakKnowledgeIds(userId, 5);
        if (userId == null) {
            log.info("[AI] 游客调用问答（不建画像加权）: {}", question);
        }

        // ---------- 双引擎第一层：Wiki 热层（预编译主题页，命中即返回）----------
        // ★ Wiki 是增强层：迁移未执行 / 全文索引损坏 / 页面脏数据等任何异常
        //   都必须降级到 RAG，绝不能让原本可用的问答 500。
        WikiQueryService.WikiHit wikiHit = null;
        try {
            wikiHit = wikiQueryService.query(question, req.getSubjectId());
        } catch (Exception e) {
            log.warn("[Wiki] 热层查询异常，降级到 RAG：{}", e.getMessage());
        }
        if (wikiHit != null) {
            WikiPage page = wikiHit.page();
            long cost = System.currentTimeMillis() - start;
            AskVO vo = new AskVO();
            vo.setQuestion(question);
            vo.setAnswer(StringUtils.hasText(page.getContent()) ? page.getContent() : page.getSummary());
            vo.setGenerator("wiki");
            vo.setEngine("WIKI");
            vo.setFallbackFromWiki(Boolean.FALSE);
            vo.setDegraded(Boolean.FALSE);
            vo.setRefused(Boolean.FALSE);
            vo.setRecallCount(1);
            vo.setRetrievalMode("WIKI_" + wikiHit.channel());
            vo.setRetrievalMillis(cost);
            vo.setCostMillis(cost);
            vo.setWikiPageId(page.getId());
            vo.setWikiPageSlug(page.getSlug());
            vo.setWikiTitle(page.getTitle());
            vo.setSources(toSourceVO(List.of(SourceRef.of(null, page.getTitle(), page.getSummary(),
                    "WIKI", page.getId(), "/wiki/" + page.getId()))));
            vo.setContexts(List.of(page.getTitle()));
            log.info("[Wiki] 热层命中「{}」（score={}，channel={}，{}ms）",
                    page.getTitle(), String.format("%.4f", wikiHit.score()),
                    wikiHit.channel(), cost);
            return vo;
        }

        // ---------- 阶段1.5 语义路由（A1/A2）----------
        // 位置很关键：**在 Wiki 热层命中之后、检索之前**。
        //   - 放在 Wiki 之前是浪费：Wiki 命中直接返回，路由结果根本用不上（还多一次 300ms 往返）；
        //   - 放在检索之后则没有意义：过滤条件必须在召回时就生效（LIMIT 配额问题）。
        // ★ 路由只是优化项：AiRouteClient 保证**永不抛异常**，失败/超时/关闭都返回
        //   「默认 COURSE_QA + 空过滤」= 今天的行为，所以这里不需要 try/catch，也不会影响回答。
        AiRouteClient.RouteResult route = routeClient.route(question, req.getSubjectId());
        // ★ 降级时不用它给的 sourceTypes：路由没把握时它的过滤会把正确答案滤掉
        //（实测「分块大小怎么选」被滤成 refused，见 RouteResult.effectiveSourceTypes 注释）
        Set<String> sourceTypes = routeSourceTypes(route);
        logRouteDecision(route, sourceTypes);

        // ---------- 阶段1+2+3 检索：向量召回 + ngram 召回 + 双通道融合 ----------
        // 统一收口到 HybridRetriever，Agent 工具与离线评估复用同一条链路
        HybridRetriever.Outcome outcome = retrieveWithRouteFallback(
                question, req, weakKpIds, route, sourceTypes);
        List<ChunkContext> contexts = outcome.contexts();
        String mode = outcome.mode();

        AskVO vo = new AskVO();
        vo.setQuestion(question);
        vo.setRecallCount(outcome.recallCount());
        vo.setRetrievalMode(mode);
        vo.setEngine("RAG");
        vo.setFallbackFromWiki(wikiQueryService.isEnabled());

        // ---------- 阶段4 拒答闸门（L1，反幻觉第一道）----------
        // 三道判据任一成立即拒：① 两路皆空或融合最高分低于阈值；
        // ② 语义层判定「整库都不像这个问题」（向量通道就绪时才生效）；
        // ③ ngram 层的内容覆盖度/能力边界（在 RetrievalService 内部已拒并返回空 contexts）。
        double best = outcome.bestScore();
        double threshold = properties.getRetrieval().getRejectThreshold();
        if (contexts.isEmpty() || best < threshold || outcome.refused()) {
            log.info("[AI] 拒答：最高分 {}（阈值 {}，语义拒答={} {}），召回 {} 块，模式 {}，问题「{}」",
                    String.format("%.4f", best), threshold, outcome.refused(),
                    outcome.refuseReason(), outcome.recallCount(), mode, question);
            vo.setAnswer(PromptBuilder.REFUSAL_TEXT);
            vo.setGenerator(PromptBuilder.GENERATOR_NONE);
            vo.setRefused(Boolean.TRUE);
            vo.setDegraded(Boolean.FALSE);
            vo.setSources(List.of());
            vo.setContexts(List.of());
            vo.setRetrievalMillis(System.currentTimeMillis() - start);
            vo.setCostMillis(System.currentTimeMillis() - start);
            return vo;
        }
        vo.setRetrievalMillis(System.currentTimeMillis() - start);

        // ---------- 阶段5 生成：大模型优先，不可用则抽取式 ----------
        LlmAnswer answer = generate(question, contexts);

        // ---------- 阶段6 L3 幻觉校验 ----------
        LlmAnswer verified = answerPostProcessor.verify(question, answer, contexts);
        if (!verified.content().equals(answer.content())) {
            log.info("[AI] L3 校验替换了大模型输出，generator: {} → {}",
                    answer.generator(), verified.generator());
        }

        // ---------- ⑥ 组装 ----------
        boolean degraded = verified.degraded()
                || !PromptBuilder.isLlmGenerator(verified.generator());
        boolean refused = answerPostProcessor.isRefusal(verified);
        // ★ 输出不变量（QA 阶段二验收提出的「refused=true 却带非空正文」）：
        //   拒答时正文一律归一为统一拒答文案，二选一里取**文案**而不是空串——
        //   因为前端/接口文档本来就以 "未找到相关资料" 这一句作为拒答口径，
        //   空串会让「拒答」和「后端 bug 返回空」难以区分。
        //   触发场景：抽取式兜底也没取到 sources（isRefusal 的第一个条件），
        //   此时正文是有的、但**不可溯源**，按拒答处理才与「反幻觉」的承诺一致。
        String answerText = refused ? PromptBuilder.REFUSAL_TEXT : verified.content();
        if (refused && !PromptBuilder.REFUSAL_TEXT.equals(verified.content())) {
            log.warn("[AI] 拒答但正文非空（generator={}, sources={}），按不变量归一为拒答文案，"
                            + "原正文 {} 字已丢弃：{}",
                    verified.generator(),
                    verified.sources() == null ? 0 : verified.sources().size(),
                    verified.content() == null ? 0 : verified.content().length(),
                    truncateForLog(verified.content()));
        }
        vo.setAnswer(answerText);
        vo.setGenerator(verified.generator());
        vo.setDegraded(degraded);
        vo.setRefused(refused);
        vo.setSources(toSourceVO(verified.sources()));
        vo.setContexts(contexts.stream().map(ChunkContext::title).toList());
        vo.setCostMillis(System.currentTimeMillis() - start);
        return vo;
    }

    /**
     * 基于调用方给定的上下文生成答案（Agent 工具收集到的资料）
     *
     * <p>与 {@link #ask} 共用同一套生成降级链与 L3 幻觉校验，
     * 但不再重复检索，避免「Agent 检索一次、Java 再检索一次」的语义漂移。
     */
    public LlmAnswer answerWithContexts(String question, List<ChunkContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return LlmAnswer.refuse(PromptBuilder.REFUSAL_TEXT);
        }
        LlmAnswer answer = generate(question, contexts);
        return answerPostProcessor.verify(question, answer, contexts);
    }

    /**
     * 生成：按优先级挑选客户端，异常统一降级到抽取式
     *
     * <p>列表已按 {@code @Order} 排好序（云端直连 > 微服务代理 > 本地抽取），
     * 返回第一个 available 的；任一客户端抛异常则继续尝试下一个，
     * 全部不可用才落抽取式。
     */
    private LlmAnswer generate(String question, List<ChunkContext> contexts) {
        for (LlmClient client : llmClients) {
            if (!isProviderCandidate(client) || !client.available()) {
                continue;
            }
            try {
                return client.ask(question, contexts);
            } catch (AiUnavailableException e) {
                // 熔断计数已在客户端内部完成，这里只需降级，用户无感
                log.warn("[AI] 生成器 {} 不可用，降级抽取式: {}", client.name(), e.getMessage());
            } catch (Exception e) {
                log.warn("[AI] 生成器 {} 异常，降级抽取式: {}", client.name(), e.getMessage());
            }
        }
        log.info("[AI] 无可用大模型客户端（Key 未配置或已熔断），使用抽取式兜底");
        return extractiveAnswerClient.ask(question, contexts);
    }

    /**
     * 是否可作为「主生成器」参与降级链
     *
     * <p><b>必须排除 {@link ExtractiveAnswerClient}</b>：它本身也是一个
     * {@code LlmClient} Bean（{@code available()} 恒为 true），
     * 若不排除，它会作为「主生成器」被选中，导致
     * 「有可用大模型却仍走抽取式」以及 capability 里
     * {@code generator=extractive} 与 {@code extractiveMode=false} 自相矛盾。
     *
     * <p>它是<b>兜底</b>而不是<b>候选</b>：只有所有主生成器都不可用时才由上层直接调用。
     */
    private boolean isProviderCandidate(LlmClient client) {
        return !(client instanceof ExtractiveAnswerClient);
    }

    /** 溯源列表转 VO；<b>永不为 null</b> */
    /**
     * 路由 → 实际用于检索的来源过滤集合
     *
     * <p><b>降级时返回 null（不过滤）</b>：路由自己说「没把握」时还会带一套默认 sourceTypes，
     * 照用会把正确答案滤掉（实测「分块大小怎么选」被滤成 refused）。
     * 口径写在这里而不是散在调用点，避免以后有人绕过。
     */
    Set<String> routeSourceTypes(AiRouteClient.RouteResult route) {
        return route == null ? null : route.effectiveSourceTypes();
    }

    /** 路由决策日志：非降级打命中，降级打「忽略其 sourceTypes」 */
    private void logRouteDecision(AiRouteClient.RouteResult route, Set<String> effective) {
        if (route == null) {
            return;
        }
        if (route.degraded()) {
            log.info("[AI] 路由降级（{}），skill={}，忽略其 sourceTypes={} → 实际过滤={}",
                    route.reason(), route.skill(), route.sourceTypes(), effective);
        } else {
            log.info("[AI] 路由命中 skill={} sourceTypes={} confidence={}",
                    route.skill(), route.sourceTypes(), String.format("%.2f", route.confidence()));
        }
    }

    /**
     * 检索 + 「空结果放宽一次」兜底（EMPTY_WIDEN）
     *
     * <p><b>为什么需要这次重试</b>：skill 隔离按类型过滤，一旦路由把问题判成
     * {@code COURSE_QA}（只查 QUESTION+KNOWLEDGE）而答案其实在课件里，过滤后会一条都搜不到，
     * 直接拒答——用户看到「没有相关资料」，但库里明明有。
     *
     * <p><b>边界</b>：仅当「本次确实加了过滤」且「过滤后为空」才重试一次，
     * 重试用 {@code sourceTypes=null}（= 今天的行为），<b>不递归</b>；放宽后仍空则照常拒答
     * （说明是真没有，不是被过滤掉）。
     */
    HybridRetriever.Outcome retrieveWithRouteFallback(String question, AskRequest req,
                                                      Set<Long> weakKpIds,
                                                      AiRouteClient.RouteResult route,
                                                      Set<String> sourceTypes) {
        HybridRetriever.Outcome outcome = hybridRetriever.retrieve(
                question, req.getSubjectId(), req.getExamCategoryId(), weakKpIds, sourceTypes);
        if (sourceTypes == null || !outcome.contexts().isEmpty()) {
            return outcome;
        }
        log.warn("[AI] routeFallback=EMPTY_WIDEN skill={} sourceTypes={} 过滤后命中 0 条 → 放宽过滤重试一次",
                route == null ? "-" : route.skill(), sourceTypes);
        HybridRetriever.Outcome widened = hybridRetriever.retrieve(
                question, req.getSubjectId(), req.getExamCategoryId(), weakKpIds, (Set<String>) null);
        log.info("[AI] routeFallback=EMPTY_WIDEN 结果：放宽后命中 {} 条（原 skill={}，原 sourceTypes={}）",
                widened.contexts().size(), route == null ? "-" : route.skill(), sourceTypes);
        return widened;
    }

    /** 日志里截断正文，避免把整段答案打进日志 */
    private static String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private List<SourceRefVO> toSourceVO(List<SourceRef> sources) {
        List<SourceRefVO> list = new ArrayList<>();
        if (sources == null) {
            return list;
        }
        for (SourceRef s : sources) {
            SourceRefVO vo = new SourceRefVO();
            vo.setChunkId(s.chunkId());
            vo.setTitle(s.title());
            vo.setSnippet(s.snippet());
            vo.setRefType(s.refType());
            vo.setRefId(s.refId());
            vo.setRefPath(s.refPath());
            vo.setOffset(s.offset());
            list.add(vo);
        }
        return list;
    }

    // ==================================================================
    // 能力探测
    // ==================================================================

    /**
     * AI 能力探测（App 启动时调，决定是否显示 AI 入口）
     *
     * <p>{@code available} 的语义是「AI 问答能不能用」，而不是「大模型能不能用」：
     * 没有 Key 时走抽取式，接口依然能答，所以 {@code available = true}、
     * {@code generator = extractive}、{@code extractiveMode = true}。
     * App 端据此显示「本地抽取模式」提示，而不是隐藏入口。
     */
    public CapabilityVO capability() {
        CapabilityVO vo = new CapabilityVO();
        AiProperties.Llm llm = properties.getLlm();
        LlmClient active = activeClient();
        // ★ capability 是诊断接口，必须报告「真的会用谁」，不能只按配置说话。
        //   Ollama 的 available() 刻意不探测网络（避免问答路径每轮多付一次往返），
        //   所以这里单独做一次带缓存的短探测：Ollama 没启动就要如实显示
        //   generator=extractive，否则现场排障会被「generator=ollama」误导。
        boolean ollamaDown = false;
        if (active instanceof OllamaLlmClient ollama && !ollama.reachable()) {
            active = null;
            ollamaDown = true;
        }
        // 本地模型不需要 Key，所以「已配置」的判定不能只看 api-key
        boolean llmConfigured = llm.hasKey() || llm.isOllama();
        boolean circuitOpen = false;
        long remaining = 0L;
        for (LlmClient client : llmClients) {
            // 熔断器是本地 SDK 客户端的能力；纯 HTTP 客户端（如独立微服务）没有熔断器
            if (client instanceof AbstractOpenAiCompatLlmClient sdk
                    && sdk.breaker().isOpen()) {
                circuitOpen = true;
                remaining = Math.max(remaining, sdk.breaker().remainingOpenMillis());
            }
        }

        long chunkCount = chunkSyncService.indexStats().getOrDefault("chunk", 0L) instanceof Number n
                ? n.longValue() : 0L;

        vo.setLlmConfigured(llmConfigured);
        vo.setProvider(llm.getProvider());
        vo.setCircuitOpen(circuitOpen);
        vo.setCircuitRemainingMillis(remaining);
        vo.setExtractiveMode(active == null);
        vo.setGenerator(active == null
                ? PromptBuilder.GENERATOR_EXTRACTIVE
                : active.name());
        vo.setDegraded(active == null);
        vo.setDegradedReason(degradedReason(llmConfigured, active, circuitOpen, ollamaDown));
        // 索引里有语料就能答（抽取式）；一条语料都没有才算不可用
        vo.setAvailable(chunkCount > 0);
        vo.setChunkCount(chunkCount);
        vo.setRejectThreshold(properties.getRetrieval().getRejectThreshold());
        vo.setIndexStats(chunkSyncService.indexStats());

        // ---------- T09 扩展字段：向量通道 / OCR / 存储 ----------
        enrichVectorCapability(vo);
        return vo;
    }

    /**
     * 补齐向量通道相关的能力字段
     *
     * <p>设计文档 C.3：<b>只扩展字段，不改接口</b>，
     * App 端已有的 {@code /ai/capability} 调用逻辑不变，只是多几个可展示项。
     *
     * <p>降级体验：FastAPI 挂时 {@code vectorIndexReady=false}，
     * App 显示「当前为基础检索模式」，<b>入口不隐藏</b>（ngram 仍能答）。
     */
    private void enrichVectorCapability(CapabilityVO vo) {
        FastApiClient.HealthSnapshot health = vectorChannel.health();
        vo.setVectorEnabled(vectorChannel.isEnabled());
        vo.setVectorIndexReady(vectorChannel.isReady());
        vo.setVectorChannelAvailable(health.ready());
        vo.setOcrEnabled(health.ocrReady());
        vo.setEmbeddingModel(health.modelId());
        vo.setEmbeddingDim(health.dim());
        vo.setAiServiceStatus(health.status());
        vo.setStorageProvider(storageProvider());

        // 熔断状态并入降级原因，App 可据此提示「语义检索临时不可用」
        if (vectorChannel.isEnabled() && !vectorChannel.isReady()) {
            String reason = StringUtils.hasText(health.reason())
                    ? health.reason() : "向量通道不可用，已降级为基础检索（ngram）";
            vo.setVectorDegradedReason(reason);
            if (vo.getDegradedReason() == null) {
                vo.setDegraded(Boolean.TRUE);
                vo.setDegradedReason(reason);
            }
        }
    }

    /**
     * 当前生效的存储提供方
     *
     * <p>T10 接入 MinIO 前恒为 {@code local}；
     * 接入后读 {@code app.storage.provider}，MinIO 不可用时自动回落 local。
     */
    private String storageProvider() {
        return properties.getStorage() == null || !properties.getStorage().isEnabled()
                ? "local" : properties.getStorage().getProvider();
    }

    private String degradedReason(boolean llmConfigured, LlmClient active,
                                  boolean circuitOpen, boolean ollamaDown) {
        if (active != null) {
            return null;
        }
        if (ollamaDown) {
            return "本地模型 Ollama 未响应（确认 `ollama serve` 已启动），当前为本地抽取模式";
        }
        if (!llmConfigured) {
            return "未配置大模型 API Key，当前为本地抽取模式（key 留空即自动降级，不影响使用）";
        }
        if (circuitOpen) {
            return "大模型连续失败已熔断，60 秒内自动使用本地抽取模式";
        }
        return "当前 provider 未匹配到可用客户端，使用本地抽取模式";
    }

    /** 当前生效的大模型客户端；无可用时返回 null（表示走抽取式） */
    private LlmClient activeClient() {
        for (LlmClient client : llmClients) {
            if (isProviderCandidate(client) && client.available()) {
                return client;
            }
        }
        return null;
    }

    /** 索引可用性（启动自检用） */
    public boolean hasIndex() {
        return chunkSyncService.indexStats().getOrDefault("chunk", 0L) instanceof Number n
                && n.longValue() > 0;
    }

    /** 供管理端 reindex 前的提示：当前索引块数 */
    public long indexSize() {
        Object v = chunkSyncService.indexStats().get("chunk");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 空问题校验（供 Controller 复用） */
    public static boolean isBlankQuestion(String question) {
        return !StringUtils.hasText(question);
    }

    /** 索引来源类型常量（避免 Controller 硬编码字符串） */
    public static String sourceTypeOf(AiChunk chunk) {
        return chunk == null ? AiChunk.SOURCE_QUESTION : chunk.getSourceType();
    }
}
