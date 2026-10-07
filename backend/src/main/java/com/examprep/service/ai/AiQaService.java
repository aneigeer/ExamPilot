package com.examprep.service.ai;

import com.examprep.dto.ai.AskRequest;
import com.examprep.dto.ai.AskVO;
import com.examprep.dto.ai.CapabilityVO;
import com.examprep.dto.ai.SourceRefVO;
import com.examprep.entity.AiChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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

    private final RetrievalService retrievalService;
    private final KnowledgeProfileService profileService;
    private final ExtractiveAnswerClient extractiveAnswerClient;
    private final AnswerPostProcessor answerPostProcessor;
    private final PromptBuilder promptBuilder;
    private final ChunkSyncService chunkSyncService;
    private final AiProperties properties;

    /** 向量通道（T09）：可选增强，不可用时自动跳过 */
    private final VectorChannel vectorChannel;

    /** 双通道融合器（T09） */
    private final VectorChunkMerger vectorChunkMerger;

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
     *   10 DashScope     云端直连，质量最高
     *   15 FastApiLlm    独立 AI 微服务代理（统一网关/限流/审计时用）
     *   20 Zhipu         云端直连
     *   30 Extractive    本地抽取（兜底，另被 isProviderCandidate 双重排除）
     * </pre>
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

        // ---------- 阶段1【主通道】向量检索（可用则用，不可用静默跳过）----------
        List<ChunkContext> vectorContexts = retrieveByVector(question, req, weakKpIds);

        // ---------- 阶段2【兜底通道】ngram 全文检索（永远可用，纯 MySQL 无网络依赖）----------
        RetrievalService.RetrievalResult ngram = retrievalService.retrieve(
                question, req.getSubjectId(), req.getExamCategoryId(), weakKpIds);

        AskVO vo = new AskVO();
        vo.setQuestion(question);
        vo.setRecallCount(ngram.recallCount() + vectorContexts.size());

        // ---------- 阶段3 双通道融合 → Top-K ----------
        List<ChunkContext> contexts;
        String mode;
        if (!vectorContexts.isEmpty()) {
            contexts = vectorChunkMerger.merge(vectorContexts, ngram.contexts(),
                    question, weakKpIds);
            mode = vectorChannel.describeMode(vectorContexts.size(), ngram.contexts().size());
        } else {
            // 向量通道无结果 → 完全走 ngram（含其自身的覆盖率闸门与拒答判定）
            contexts = ngram.contexts();
            mode = ngram.mode();
        }
        vo.setRetrievalMode(mode);

        // ---------- 阶段4 拒答闸门（L1，反幻觉第一道）----------
        // 两路皆空，或融合后最高分低于阈值 → 直接拒答，不进生成环节
        double best = contexts.isEmpty() ? 0.0 : contexts.get(0).finalScore();
        double threshold = properties.getRetrieval().getRejectThreshold();
        if (contexts.isEmpty() || best < threshold) {
            log.info("[AI] 拒答：最高分 {} < 阈值 {}（向量 {} 块 / ngram 召回 {}，模式 {}），问题「{}」",
                    String.format("%.4f", best), threshold, vectorContexts.size(),
                    ngram.recallCount(), mode, question);
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
        vo.setAnswer(verified.content());
        vo.setGenerator(verified.generator());
        vo.setDegraded(degraded);
        vo.setRefused(answerPostProcessor.isRefusal(verified));
        vo.setSources(toSourceVO(verified.sources()));
        vo.setContexts(contexts.stream().map(ChunkContext::title).toList());
        vo.setCostMillis(System.currentTimeMillis() - start);
        return vo;
    }

    /**
     * 阶段1：向量通道检索
     *
     * <p><b>整个方法不抛任何异常</b>：向量通道是「可选增强」，
     * FastAPI / Qdrant / 模型任一不可用都收敛成空列表，由上层自动降级到 ngram。
     */
    private List<ChunkContext> retrieveByVector(String question, AskRequest req,
                                                Set<Long> weakKpIds) {
        if (!vectorChannel.isEnabled()) {
            return List.of();
        }
        try {
            if (!vectorChannel.isReady()) {
                log.info("[AI] 向量通道不可用（未开启/熔断/健康检查未通过），静默降级到 ngram 通道");
                return List.of();
            }
            return vectorChannel.retrieve(question,
                    properties.getVector().getRecallLimit(), req.getSubjectId());
        } catch (Exception e) {
            log.warn("[AI] 向量通道异常（静默降级到 ngram 通道）: {}", e.getMessage());
            return List.of();
        }
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
        boolean llmConfigured = llm.hasKey();
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
        vo.setDegradedReason(degradedReason(llmConfigured, active, circuitOpen));
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

    private String degradedReason(boolean llmConfigured, LlmClient active, boolean circuitOpen) {
        if (active != null) {
            return null;
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
