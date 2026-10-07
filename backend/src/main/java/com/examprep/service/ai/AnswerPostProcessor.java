package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 答案后处理器 —— <b>L3 幻觉校验（设计方案 B.3 反幻觉第三层）</b>
 *
 * <p><b>核心规则（代码级硬约束，不是口头约定）</b>：
 * 答案正文中出现的引用编号，必须都能在 {@code sources} 里找到对应来源。
 * 只要出现「正文有 [3] 但 sources 里没有 3」这种自相矛盾，
 * 就判定为幻觉，<b>丢弃大模型正文，改用抽取式</b>。
 *
 * <p>为什么必须做代码校验：prompt 里写「禁止编造」只是<b>请求</b>，
 * 模型是否遵守不可验证；而「引用编号与 sources 自洽」是<b>可验证的结构化约束</b>。
 * 使用时可以直接说：我们不信任模型的自述，只信任能在代码里校验的约束。
 *
 * <p><b>三层防线回顾</b>：
 * <ol>
 *   <li>L1 检索层：无命中根本不进生成（{@code RetrievalService} 拒答）</li>
 *   <li>L2 Prompt 层：强制编号引用 + 禁推测（{@link PromptBuilder}）</li>
 *   <li>L3 后处理层：<b>本类</b></li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerPostProcessor {

    private final ExtractiveAnswerClient extractiveAnswerClient;

    /**
     * L3 校验 + 幻觉兜底
     *
     * @param question 用户问题（幻觉兜底时交给抽取式重新生成）
     * @param answer   待校验的生成结果
     * @param contexts 检索上下文（幻觉兜底时作为抽取式输入）
     * @return 校验通过的原结果，或替换为抽取式结果；<b>永不返回 null</b>
     */
    public LlmAnswer verify(String question, LlmAnswer answer, List<ChunkContext> contexts) {
        if (answer == null) {
            return LlmAnswer.refuse(PromptBuilder.REFUSAL_TEXT);
        }
        // 抽取式本身就是原文，无需校验（也不该被误判）
        if (!PromptBuilder.isLlmGenerator(answer.generator())) {
            return answer;
        }

        String content = answer.content() == null ? "" : answer.content();
        List<SourceRef> sources = answer.sources() == null ? List.of() : answer.sources();
        List<Integer> cited = AbstractOpenAiCompatLlmClient.extractCitationIndexes(content);

        // 规则 1：正文有引用编号，但 sources 为空 → 模型编造了来源
        if (!cited.isEmpty() && sources.isEmpty()) {
            return fallbackToExtractive(question, contexts, content, cited,
                    "正文引用编号 " + cited + " 但 sources 为空");
        }

        // 规则 2：正文引用了超出上下文档位的编号（如上下文只有 3 块却引用了 [7]）→ 幻觉
        int contextSize = contexts == null ? 0 : contexts.size();
        for (int index : cited) {
            if (index < 1 || (contextSize > 0 && index > contextSize)) {
                return fallbackToExtractive(question, contexts, content, cited,
                        "正文引用了不存在的档位 [" + index + "]（上下文共 " + contextSize + " 块）");
            }
        }

        // 规则 3：正文完全没有引用编号 → 无溯源的答案在使用场景下不可接受，降级为抽取式
        if (cited.isEmpty()) {
            return fallbackToExtractive(question, contexts, content, cited,
                    "正文未给出任何引用编号，无法溯源");
        }

        // 规则 4：sources 里存在正文未引用的条目 → 溯源列表虚高，裁剪掉
        List<SourceRef> trimmed = trimUncitedSources(sources, cited);
        if (trimmed.size() != sources.size()) {
            log.warn("[AI] L3 裁剪未被引用的来源：{} → {}", sources.size(), trimmed.size());
            return new LlmAnswer(content, trimmed, answer.generator(), answer.costMillis(), true);
        }

        // 全部通过
        return answer;
    }

    /** 幻觉时丢弃模型输出、切抽取式（<b>日志可见</b>，便于功能验证与排查） */
    private LlmAnswer fallbackToExtractive(String question, List<ChunkContext> contexts,
                                          String modelContent, List<Integer> cited, String reason) {
        log.warn("[AI] L3 幻觉校验拦截：{}。已丢弃大模型正文（{} 字），切换抽取式",
                reason, modelContent == null ? 0 : modelContent.length());
        LlmAnswer fallback = extractiveAnswerClient.ask(question, contexts);
        return new LlmAnswer(fallback.content(), fallback.sources(), fallback.generator(),
                fallback.costMillis(), true);
    }

    /** 保留正文引用到的来源，按正文首次引用顺序排列 */
    private List<SourceRef> trimUncitedSources(List<SourceRef> sources, List<Integer> cited) {
        if (sources.size() <= cited.size()) {
            return sources;
        }
        return sources.stream()
                .filter(s -> {
                    int index = indexOf(sources, s);
                    return cited.contains(index);
                })
                .toList();
    }

    /** 引用编号是 1-based，且按 sources 顺序对应 */
    private int indexOf(List<SourceRef> sources, SourceRef target) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) == target) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 判断答案是否应被视为拒答
     *
     * @param answer 生成结果
     * @return true 表示「未找到相关资料」类拒答
     */
    public boolean isRefusal(LlmAnswer answer) {
        if (answer == null || !StringUtils.hasText(answer.content())) {
            return true;
        }
        String content = answer.content();
        return "none".equals(answer.generator())
                || answer.sources() == null
                || answer.sources().isEmpty()
                || content.contains(PromptBuilder.REFUSAL_TEXT);
    }
}
