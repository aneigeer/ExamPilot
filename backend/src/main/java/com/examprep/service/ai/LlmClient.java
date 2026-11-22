package com.examprep.service.ai;

import java.util.List;

/**
 * 大模型客户端抽象
 *
 * <p><b>设计要点</b>：问答链路只依赖本接口，不依赖任何具体实现。
 * 未来若要把 AI 推理拆成独立微服务（FastAPI / 独立进程），
 * 实现类换成 HTTP 调用即可，{@code AiQaService} 一行不改。
 */
public interface LlmClient {

    /** 本客户端是否可用（无 Key / 已熔断 → false） */
    boolean available();

    /** 实现标识：dashscope / zhipu / extractive */
    String name();

    /**
     * 基于检索上下文生成答案。
     *
     * @param question 用户问题（原文）
     * @param contexts 检索命中的语料块（已按相关度降序，强制溯源）
     * @return 生成结果，永不返回 null；失败时抛 {@link AiUnavailableException} 由上层降级
     */
    LlmAnswer ask(String question, List<ChunkContext> contexts);

    /**
     * 非检索类结构化生成（如主观题判分）
     *
     * <p>★ 为什么不复用 {@link #ask}：那条链路的 System Prompt 要求
     * 「必须基于资料作答并标注引用编号」，而判分需要模型做**结构化比对**、
     * 输出严格 JSON。两者输出契约不同，混用会让模型在两种要求之间摇摆。
     *
     * <p>缺省实现直接抛 {@link AiUnavailableException}（表示「本实现不参与这类任务」），
     * 于是判分链路会自动跳到下一个可用客户端；任何实现都不支持时，
     * 判分如实返回「不可用」并让前端回退自评，而不是伪造分数。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型输出的原始文本
     */
    default String complete(String systemPrompt, String userPrompt) {
        throw new AiUnavailableException(name() + " 不支持非检索类生成（complete）");
    }

    /**
     * 结构化 JSON 生成（判分 / 学习计划等）
     *
     * <p>默认退化为 {@link #complete}，保证所有既有实现与 Mock 零破坏；
     * 支持 OpenAI {@code response_format} 的实现可覆写以强制 JSON 输出。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型输出的原始文本（调用方负责解析与修复）
     */
    default String completeJson(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt);
    }

    /**
     * 单次生成的 Token 用量与模型名。
     *
     * <p>{@code estimated=true} 表示上游未返回 usage，由字符数保守估算回填，
     * 管理端必须能区分「真实计量」与「估算」。
     */
    record LlmUsage(String model, int promptTokens, int completionTokens,
                    int totalTokens, boolean estimated) {

        /** 字符数估算（中文按 ~2 字符/token，英文按 ~4 字符/token 的保守折中） */
        public static LlmUsage estimate(String model, String prompt, String output) {
            int p = Math.max(1, (prompt == null ? 0 : prompt.length()) / 2);
            int c = Math.max(1, (output == null ? 0 : output.length()) / 2);
            return new LlmUsage(model, p, c, p + c, true);
        }
    }

    /** 生成结果 + 用量 */
    record LlmCompletion(String content, LlmUsage usage) {
    }

    /**
     * 带用量的普通生成；默认按字符估算，兼容所有既有实现。
     */
    default LlmCompletion completeWithUsage(String systemPrompt, String userPrompt) {
        String content = complete(systemPrompt, userPrompt);
        return new LlmCompletion(content,
                LlmUsage.estimate(name(), systemPrompt + userPrompt, content));
    }

    /**
     * 带用量的 JSON 生成；默认按字符估算，兼容所有既有实现。
     */
    default LlmCompletion completeJsonWithUsage(String systemPrompt, String userPrompt) {
        String content = completeJson(systemPrompt, userPrompt);
        return new LlmCompletion(content,
                LlmUsage.estimate(name(), systemPrompt + userPrompt, content));
    }

    // ==================================================================
    // 引用解析工具（接口静态方法）
    //
    // 放在接口上而不是抽象类上，是为了让<b>任何</b> LlmClient 实现
    // （包括不继承 AbstractOpenAiCompatLlmClient 的独立微服务客户端）
    // 都能直接复用溯源解析能力，无需依赖具体实现类。
    // ==================================================================

    /**
     * 提取引用编号（正文中的 {@code [1]}、{@code [1][2]}），去重并升序
     *
     * @param content 答案正文
     * @return 引用编号列表；无引用时返回空 list
     */
    static List<Integer> extractCitationIndexes(String content) {
        java.util.List<Integer> indexes = new java.util.ArrayList<>();
        if (content == null || content.isBlank()) {
            return indexes;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\[(\\d{1,2})]").matcher(content);
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            if (!indexes.contains(n)) {
                indexes.add(n);
            }
        }
        indexes.sort(Integer::compareTo);
        return indexes;
    }

    /**
     * 取内容前 160 字作为溯源 snippet
     *
     * @param content 语料块正文
     * @return 截断后的片段（超长加省略号）
     */
    static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    /**
     * 按正文引用编号构造溯源列表
     *
     * <p>只把「模型确实引用了的块」放进 sources——sources 是<b>从正文反推</b>的，
     * 不是模型自述，这正是 L3 幻觉校验（{@link AnswerPostProcessor}）有意义的前提。
     *
     * @param content  答案正文
     * @param contexts 检索上下文（编号从 1 开始）
     * @return 溯源列表；无有效引用时返回空 list
     */
    static List<SourceRef> resolveSources(String content, List<ChunkContext> contexts) {
        List<SourceRef> sources = new java.util.ArrayList<>();
        if (content == null || contexts == null || contexts.isEmpty()) {
            return sources;
        }
        for (int index : extractCitationIndexes(content)) {
            if (index < 1 || index > contexts.size()) {
                continue;
            }
            ChunkContext ctx = contexts.get(index - 1);
            sources.add(SourceRef.of(ctx.chunkId(), ctx.title(), snippet(ctx.content()),
                    ctx.refType(), ctx.refId(), ctx.refPath()));
        }
        return sources;
    }
}
