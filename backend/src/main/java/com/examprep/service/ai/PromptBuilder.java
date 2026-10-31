package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prompt 构造器（设计方案 B.3 的 L2 防线：强制编号引用 + 禁推测约束）
 *
 * <p>核心约束：把检索到的语料块编号为 {@code [1][2][3]}，
 * 并明确要求模型<b>每句话标注来源编号</b>、<b>资料不足时只输出「未找到相关资料」</b>。
 *
 * <p>注意：L2 只是辅助，<b>真正的硬约束在 L3（{@link AnswerPostProcessor}）</b>——
 * 代码层校验「正文中的 [n] 是否在 sources 中存在」，不信任模型自述。
 *
 * <p>另含 P3 观测点：语料正文命中"疑似注入指令"模式时打 WARN 日志，
 * 见 {@link InjectionSignalDetector}。该观测<b>只记录、不改写正文、不影响判定</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final AiProperties properties;

    /** 生成器名称：extractive（抽取式兜底不走大模型，用不到 system prompt） */
    public static final String GENERATOR_DASHSCOPE = "dashscope";
    public static final String GENERATOR_ZHIPU = "zhipu";
    public static final String GENERATOR_EXTRACTIVE = "extractive";
    public static final String GENERATOR_NONE = "none";

    /*
     * 其他已经存在、且必须被当作「大模型生成」的 generatorName（列出来是文档，不是白名单本体）：
     *   ollama      —— 本地主模型 OllamaLlmClient（当前现场的默认生成器）
     *   fastapi     —— 独立 AI 微服务代理 FastApiLlmClient
     *   agent-llm   —— Agent 定稿路径（Python harness 回传）
     * wiki 不走本判定：Wiki 热层命中直接返回预编译页，不经过 L3 校验与 degraded 计算。
     */

    /**
     * 非大模型生成器（<b>反向白名单</b>）：只有这两个是真的「不是模型生成的」
     *
     * <p><b>★ 为什么必须反选而不是正向枚举</b>（这是一次真实事故的教训）：
     * 原实现是 {@code generator.equals("dashscope") || generator.equals("zhipu")}，
     * 结果本地主模型 {@code ollama}、Agent 定稿 {@code agent-llm}、微服务代理 {@code fastapi}
     * 全部被判成「非 LLM」，导致：
     * <ol>
     *   <li>{@code AnswerPostProcessor.verify()} 首行就早退 → <b>L3 四类引用校验一条没执行</b>，
     *       模型编造的 {@code [9]}（上下文只有 3 条）能原样返回给用户；</li>
     *   <li>{@code degraded = answer.degraded() || !isLlmGenerator(...)} 对 ollama 恒为 true →
     *       所有本地模型回答都被标成降级，管理端无法区分「模型答案 / 规则修正 / 抽取式」。</li>
     * </ol>
     * 正向枚举的毛病是「新增一个生成器就静默漏一次」，而漏掉的后果是<b>幻觉防线失效</b>；
     * 反选则是「除非明确不是模型，否则一律当模型校验」——失败方向朝向安全。
     */
    private static final Set<String> NON_LLM_GENERATORS = Set.of(
            GENERATOR_EXTRACTIVE, GENERATOR_NONE);

    /** 拒答标准文案（前后端统一，避免各端自己拼） */
    public static final String REFUSAL_TEXT = "未找到相关资料";

    /**
     * 单次 {@link #userPrompt} 最多逐条输出的注入告警条数
     *
     * <p>只为控制日志体积：一份脏课件可能让 topK 里 5–12 条全命中，
     * 逐条打会把日志刷满、看不出"还有没有别的"；超出的部分在末尾汇总一行。
     * <b>不影响检测本身</b>（命中计数仍然准确）。
     */
    private static final int MAX_INJECTION_WARNINGS = 3;

    /**
     * 构造 system prompt
     */
    public String systemPrompt() {
        return properties.getPrompt().getSystemTemplate();
    }

    /**
     * 构造 user prompt：问题 + 编号上下文
     *
     * @param question 用户问题原文
     * @param contexts 检索命中的语料块（已按相关度降序）
     */
    public String userPrompt(String question, List<ChunkContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("【参考资料】\n");
        // P3 观测：命中明细最多打 MAX_INJECTION_WARNINGS 条，其余在末尾汇总一行，
        // 否则一份脏语料会把日志刷满、反而看不出还有没有别的告警。
        int suspiciousBlocks = 0;
        int loggedWarnings = 0;
        for (int i = 0; i < contexts.size(); i++) {
            ChunkContext ctx = contexts.get(i);
            boolean suspicious = warnIfInjectionLike(i + 1, ctx, loggedWarnings < MAX_INJECTION_WARNINGS);
            if (suspicious) {
                suspiciousBlocks++;
                loggedWarnings++;
            }
            sb.append('[').append(i + 1).append("] ")
                    .append("标题：").append(nullToEmpty(ctx.title())).append('\n')
                    .append("内容：").append(nullToEmpty(ctx.content())).append('\n');
        }
        if (suspiciousBlocks > loggedWarnings) {
            log.warn("[AI][注入告警] 本次提示词共 {} 条语料块命中注入信号，已省略 {} 条明细（上游数据质量需巡检）",
                    suspiciousBlocks, suspiciousBlocks - loggedWarnings);
        }
        sb.append('\n').append("【问题】\n").append(question == null ? "" : question.trim()).append('\n');
        sb.append("\n请依据上述参考资料作答并在句末标注来源编号。");
        return sb.toString();
    }

    /**
     * 语料疑似注入指令时打 WARN（<b>仅观测</b>，不阻断、不改写、不做额外调用）
     *
     * <p>日志字段固定为 {@code [AI][注入告警]}，便于 grep / 日志平台按前缀聚合：
     * {@code slot}（第几条资料）、{@code chunkId}、{@code title}、命中模式与位置。
     * 标题与正文分别扫（标题同样来自 UGC，也会进提示词）。
     *
     * <p><b>注意</b>：命中信息只进日志，<b>绝不写进提示词</b>——哪怕一句"以下内容含可疑指令"
     * 也会改变模型输入，就违背了「只观测」的前提。
     *
     * @param slot     资料序号（1-based，与提示词里的 {@code [n]} 对齐）
     * @param ctx      语料块
     * @param emitWarn 是否输出明细日志（超出上限时只计数、不逐条打）
     * @return 该块是否命中注入信号（供调用方汇总）
     */
    private boolean warnIfInjectionLike(int slot, ChunkContext ctx, boolean emitWarn) {
        List<InjectionSignalDetector.Signal> hits =
                InjectionSignalDetector.detect(nullToEmpty(ctx.content()));
        List<InjectionSignalDetector.Signal> titleHits =
                InjectionSignalDetector.detect(nullToEmpty(ctx.title()));
        if (hits.isEmpty() && titleHits.isEmpty()) {
            return false;
        }
        if (emitWarn) {
            log.warn("[AI][注入告警] 语料疑似指令 slot=[{}] chunkId={} refType={} refId={} "
                            + "title={} 正文命中={} 标题命中={}",
                    slot, ctx.chunkId(), ctx.refType(), ctx.refId(), ctx.title(),
                    hits, titleHits);
        }
        return true;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 是否大模型生成器（<b>除 extractive / none 之外都算</b>）
     *
     * <p>取值口径：{@code dashscope / zhipu / ollama / fastapi / agent-llm} 都返回 true。
     * 空/空白按「非模型」处理（调用方拿不到生成器名时不该硬按模型路径校验，
     * 那种情况本身就该被当作降级）。
     *
     * <p><b>影响面（改这个方法前先看这里）</b>——全仓共 3 个调用点，改判定等于同时改三处行为：
     * <ol>
     *   <li>{@code AnswerPostProcessor:49} —— <b>L3 引用校验的闸门</b>：返回 false 就整段跳过校验，
     *       模型编造的 {@code [9]} 会原样返回给用户（本方法曾用正向枚举，正是这么漏的）；</li>
     *   <li>{@code AiQaService:204} —— {@code degraded = answer.degraded() || !isLlmGenerator(...)}；</li>
     *   <li>{@code AgentInternalController:233} —— 同一公式的内部定稿接口。</li>
     * </ol>
     *
     * <p><b>{@code wiki} 是否会被误伤：不会</b>。Wiki 热层命中走的是 {@code AiQaService} 的
     * <b>提前 return 分支</b>（直接构造 AskVO 并显式 {@code setDegraded(false)}），
     * 既不经过 {@code verify()}，也不经过上面第 2 个调用点。所以 wiki 落在「算 LLM」这一侧
     * 是安全的——反向白名单不需要为它开特例。
     */
    public static boolean isLlmGenerator(String generator) {
        if (generator == null || generator.isBlank()) {
            return false;
        }
        return !NON_LLM_GENERATORS.contains(generator.trim().toLowerCase(Locale.ROOT));
    }
}
