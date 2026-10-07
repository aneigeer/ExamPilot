package com.examprep.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抽取式兜底客户端（设计方案 B.2 算法步骤 1-6）
 *
 * <p><b>不是简单截断</b>：按句读切句 → 算每句对 query term 的覆盖率 →
 * 保留覆盖率 ≥ 阈值的句子 → 首句加来源前缀。这样抽出来的答案是「有提炼的原文」，
 * 而不是把第一段原样糊给用户。
 *
 * <p><b>generator 如实标注为 {@code extractive}</b>，App 端显示「本地抽取」，
 * <b>不冒充大模型</b>。抽取式天然零幻觉，因为它就是原文。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class ExtractiveAnswerClient implements LlmClient {

    /** 中英文句末标点 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?；;])|\\n+");

    private final AiProperties properties;
    private final BooleanQueryBuilder booleanQueryBuilder;

    @Override
    public boolean available() {
        // 兜底客户端永远可用：这是「无 Key / 熔断 / 超时」时用户仍能拿到答案的唯一保障
        return true;
    }

    @Override
    public String name() {
        return PromptBuilder.GENERATOR_EXTRACTIVE;
    }

    @Override
    public LlmAnswer ask(String question, List<ChunkContext> contexts) {
        long start = System.currentTimeMillis();
        if (contexts == null || contexts.isEmpty()) {
            return LlmAnswer.refuse(PromptBuilder.REFUSAL_TEXT);
        }
        // 覆盖率计算用词级 term（bigram 会让覆盖率恒为 1，失去筛选意义）
        List<String> terms = booleanQueryBuilder.terms(question);
        int maxSentences = properties.getKnowledge().getExtractiveMaxSentences();
        double threshold = properties.getKnowledge().getExtractiveCoverageThreshold();

        // ①-③ 遍历块（已按相关度降序），抽句子；Top-1 块至少抽 1 句，避免首块无命中导致空答案
        List<SourceRef> sources = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        int sentenceCount = 0;

        for (int i = 0; i < contexts.size() && sentenceCount < maxSentences; i++) {
            ChunkContext ctx = contexts.get(i);
            List<String> sentences = splitSentences(ctx.content());
            List<String> picked = new ArrayList<>();
            for (String sentence : sentences) {
                if (sentenceCount + picked.size() >= maxSentences) {
                    break;
                }
                if (sentence.length() < 4) {
                    continue;
                }
                double coverage = coverage(sentence, terms);
                if (coverage >= threshold || (i == 0 && picked.isEmpty() && sentence.length() >= 8)) {
                    picked.add(sentence);
                }
            }
            if (picked.isEmpty()) {
                continue;
            }
            // ④ 段首加前缀「根据《{title}》：」
            answer.append("根据《").append(nullToEmpty(ctx.title())).append("》：")
                    .append(String.join("", picked)).append('\n');
            // ⑤ sources 填入实际引用的 chunk（附首句在 chunk 内的偏移，供 App 高亮）
            int offset = ctx.content() == null ? -1 : ctx.content().indexOf(picked.get(0));
            sources.add(new SourceRef(ctx.chunkId(), ctx.title(), snippet(ctx.content()),
                    ctx.refType(), ctx.refId(), ctx.refPath(), offset));
            sentenceCount += picked.size();
        }

        if (answer.isEmpty()) {
            // 兜底再兜底：连一句都抽不出时直接给首块摘要，并明确告知来自原文
            ChunkContext top = contexts.get(0);
            answer.append("根据《").append(nullToEmpty(top.title())).append("》：")
                    .append(AbstractOpenAiCompatLlmClient.snippetPublic(top.content()));
            sources.add(SourceRef.of(top.chunkId(), top.title(),
                    AbstractOpenAiCompatLlmClient.snippetPublic(top.content()),
                    top.refType(), top.refId(), top.refPath()));
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[AI] 抽取式生成完成，引用 {} 个来源，耗时 {} ms", sources.size(), cost);
        // degraded=true：明确告知上层这是降级结果，App 端展示「本地抽取模式」横幅
        return new LlmAnswer(answer.toString().trim(), sources, name(), cost, true);
    }

    /** 按句读切句，保留标点，过滤空白句 */
    private List<String> splitSentences(String content) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            return result;
        }
        Matcher matcher = SENTENCE_SPLIT.matcher(content);
        while (matcher.find()) {
            String s = matcher.group().trim();
            if (StringUtils.hasText(s)) {
                result.add(s);
            }
        }
        if (result.isEmpty()) {
            result.add(content.trim());
        }
        return result;
    }

    /** 句子对 query term 的覆盖率 = 命中 term 数 / query term 总数 */
    private double coverage(String sentence, List<String> terms) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        String lower = sentence.toLowerCase();
        int hit = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String term : terms) {
            if (seen.add(term) && lower.contains(term.toLowerCase())) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    private String snippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
