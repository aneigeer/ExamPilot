package com.examprep.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * AI 问答返回（溯源可见）
 */
@Data
@Schema(description = "AI 问答结果")
public class AskVO {

    @Schema(description = "用户问题原文", example = "值传递和地址传递有什么区别")
    private String question;

    @Schema(description = "答案正文（拒答时为「未找到相关资料」）")
    private String answer;

    @Schema(description = "生成器：wiki / dashscope / zhipu / extractive / none（拒答时）",
            example = "extractive")
    private String generator;

    @Schema(description = "回答引擎：WIKI（热层预编译页）/ RAG（冷层检索增强）")
    private String engine;

    @Schema(description = "是否从 Wiki 热层降级到 RAG（Wiki 未命中时为 true）")
    private Boolean fallbackFromWiki;

    @Schema(description = "命中的 Wiki 页面 ID（engine=WIKI 时有值）")
    private Long wikiPageId;

    @Schema(description = "命中的 Wiki 页面 slug")
    private String wikiPageSlug;

    @Schema(description = "命中的 Wiki 页面标题")
    private String wikiTitle;

    @Schema(description = "是否处于降级状态（true 表示主生成器不可用，已走本地抽取）")
    private Boolean degraded;

    @Schema(description = "是否拒答（检索无命中或最高分低于阈值）")
    private Boolean refused;

    @Schema(description = "总耗时（毫秒）")
    private Long costMillis;

    @Schema(description = "检索阶段耗时（毫秒）")
    private Long retrievalMillis;

    @Schema(description = "召回模式：FULLTEXT_AND / FULLTEXT_OR / LIKE / NONE",
            example = "FULLTEXT_AND")
    private String retrievalMode;

    @Schema(description = "召回候选数（重排前）")
    private Integer recallCount;

    @Schema(description = "★ 溯源列表，任何情况下非 null；无来源时为空数组")
    private List<SourceRefVO> sources;

    @Schema(description = "检索到的语料片段（调试 / 使用展示用，可为空）")
    private List<String> contexts;
}
