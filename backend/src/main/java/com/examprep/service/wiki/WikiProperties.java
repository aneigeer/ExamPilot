package com.examprep.service.wiki;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM Wiki 配置（{@code app.wiki.*}）
 *
 * <p>双引擎策略：
 * <ul>
 *   <li><b>Wiki 热层</b>：预编译主题页 + 向量/ngram/图遍历，命中即直接返回，响应快、结构可读；</li>
 *   <li><b>RAG 冷层</b>：Wiki 未命中时降级到原有混合检索，保证长尾召回；</li>
 *   <li><b>异步蒸馏</b>：每日定时任务把跨文档知识提炼成草稿，人工审核后发布。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.wiki")
public class WikiProperties {

    /** 热层总开关；false 时问答完全走 RAG，行为与未引入 Wiki 时一致 */
    private boolean enabled = true;

    /** 热层命中阈值（向量余弦相似度） */
    private double hitThreshold = 0.55;

    /** 问答返回的 Wiki 页数上限（当前只用 Top1，保留字段便于后续多页综合） */
    private int topK = 3;

    /** 向量召回候选数 */
    private int vectorLimit = 10;

    /**
     * ngram 字面通道的最低原始相关度（MATCH ... AGAINST 的 ft_score）
     *
     * <p>ft_score 不是 0-1，阈值需按语料规模标定；低于该值视为字面不相关，
     * 不进入热层，避免「只命中一个 bigram」就抢答。
     */
    private double ngramMinScore = 8.0;

    /** ft_score 归一化尺度（score = ft_score / scale，上限 1） */
    private double ngramScoreScale = 20.0;

    /**
     * 标题字面覆盖率阈值（问题 bigram 被标题覆盖的比例）
     *
     * <p>★ 为什么不能只靠 ft_score：Wiki 页面数量少时，MATCH 的 IDF 接近 0，
     * 即使标题完全命中 ft_score 也可能只有 1e-7。因此用一个与语料规模无关的
     * 「标题 bigram 覆盖率」做字面通道的独立判据，避免小语料冷启动阶段热层永远不命中。
     */
    private double titleCoverageThreshold = 0.25;

    /** 标题 + 摘要 + 要点的综合字面覆盖率阈值 */
    private double literalCoverageThreshold = 0.50;

    /** 是否允许沿链接扩展 1 跳（图遍历） */
    private boolean linkExpand = true;

    /** 链接扩展时的分数衰减（父页分 × decay） */
    private double linkDecay = 0.85;

    /** 蒸馏配置 */
    private Distill distill = new Distill();

    /** Wiki 向量集合（与 RAG 的 exam_chunks 分离，互不影响） */
    private Vector vector = new Vector();

    @Data
    public static class Distill {

        /** 每日定时蒸馏开关 */
        private boolean enabled = true;

        /** 执行时间（Spring cron，默认每天 03:30） */
        private String cron = "0 30 3 * * ?";

        /** 单次最多蒸馏主题数（本地 7B 模型约 20–40 秒/页） */
        private int limit = 10;

        /** 每个主题送入 LLM 的原始块数 */
        private int maxChunks = 6;

        /** 主题最少块数（太少无法跨文档提炼） */
        private int minChunks = 2;
    }

    @Data
    public static class Vector {
        /** Qdrant collection 名（Python 侧同名） */
        private String collection = "wiki_pages";

        /** 是否在审核通过后自动推送向量索引 */
        private boolean autoIndex = true;
    }
}
