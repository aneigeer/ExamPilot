package com.examprep.service.wiki;

import com.examprep.entity.WikiPage;
import com.examprep.mapper.WikiPageLinkMapper;
import com.examprep.mapper.WikiPageMapper;
import com.examprep.service.ai.FastApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wiki 热层检索（问答与 Agent 共用）
 *
 * <p>三路召回，按分数取最高：
 * <ol>
 *   <li><b>向量</b>：页面摘要向量（Qdrant {@code wiki_pages}），负责语义匹配；</li>
 *   <li><b>ngram</b>：MySQL 全文索引，负责专有名词 / 数字 / 代码标识符；</li>
 *   <li><b>图遍历</b>：命中页的 1 跳关联页按衰减分补位，负责「相关主题」召回。</li>
 * </ol>
 *
 * <p>只有最高分 ≥ {@code app.wiki.hit-threshold} 才算热层命中；
 * 否则返回 {@code null}，由 {@code AiQaService} 降级到 RAG 冷层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiQueryService {

    private final WikiPageMapper pageMapper;
    private final WikiPageLinkMapper linkMapper;
    private final FastApiClient fastApiClient;
    private final WikiProperties properties;
    private final WikiService wikiService;
    private final ObjectMapper objectMapper;

    /**
     * 热层命中
     *
     * @param page    命中页面
     * @param score   最终分
     * @param channel VECTOR / NGRAM / LINK
     * @param viaLink 是否由图遍历补位
     */
    public record WikiHit(WikiPage page, double score, String channel, boolean viaLink) {
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public double hitThreshold() {
        return properties.getHitThreshold();
    }

    /**
     * 问答热层查询
     *
     * @return 命中页面；未命中 / 未启用 / 无页面时返回 {@code null}
     */
    public WikiHit query(String question, Long subjectId) {
        List<WikiHit> hits = search(question, subjectId, 1);
        if (hits.isEmpty()) {
            return null;
        }
        WikiHit hit = hits.get(0);
        if (hit.score() < properties.getHitThreshold()) {
            log.debug("[Wiki] 热层未命中：最高分 {} < 阈值 {}",
                    String.format("%.4f", hit.score()), properties.getHitThreshold());
            return null;
        }
        return hit;
    }

    /**
     * 热层检索（不应用命中阈值，Agent 工具与观测用）
     */
    public List<WikiHit> search(String question, Long subjectId, int topK) {
        if (!properties.isEnabled() || !StringUtils.hasText(question) || wikiService.countPublished() <= 0) {
            return List.of();
        }
        int limit = Math.max(1, topK);
        Map<Long, Candidate> candidates = new LinkedHashMap<>();

        // ---------- ① 向量通道 ----------
        for (FastApiClient.WikiVectorHit hit : fastApiClient.searchWiki(
                question, properties.getVectorLimit(), subjectId)) {
            candidates.computeIfAbsent(hit.pageId(), id -> new Candidate())
                    .offer(hit.score(), "VECTOR", false);
        }

        // ---------- ② ngram + 字面覆盖率通道 ----------
        List<WikiPage> rows = pageMapper.searchFulltext(
                question, subjectId, Math.max(10, properties.getVectorLimit()));
        for (WikiPage row : rows) {
            double ft = row.getFtScore() == null ? 0.0 : row.getFtScore();
            double titleCoverage = bigramCoverage(question, row.getTitle());
            double summaryCoverage = bigramCoverage(question, row.getSummary());
            double pointCoverage = bigramCoverage(question, row.getKeyPoints());
            double literal = 0.6 * titleCoverage + 0.25 * summaryCoverage + 0.15 * pointCoverage;

            // ★ 两个独立判据取并集：
            //   ft_score：语料足够大时有效；
            //   覆盖率：语料很少时 MATCH 的 IDF 接近 0，只能靠字面覆盖率兜住冷启动。
            boolean hit = ft >= properties.getNgramMinScore()
                    || titleCoverage >= properties.getTitleCoverageThreshold()
                    || literal >= properties.getLiteralCoverageThreshold();
            if (!hit) {
                continue;
            }
            double normalized = Math.min(1.0, ft / Math.max(1.0, properties.getNgramScoreScale()));
            double signal = Math.max(literal, normalized * 0.6);
            double score = Math.min(1.0,
                    Math.max(properties.getHitThreshold() + 0.01, 0.6 + 0.4 * signal));
            candidates.computeIfAbsent(row.getId(), id -> new Candidate())
                    .offer(score, "NGRAM", false);
        }

        // ---------- ③ 图遍历（1 跳） ----------
        if (properties.isLinkExpand() && !candidates.isEmpty()) {
            List<Map.Entry<Long, Candidate>> strong = candidates.entrySet().stream()
                    .filter(e -> e.getValue().score >= properties.getHitThreshold() * 0.8)
                    .sorted(Comparator.comparingDouble((Map.Entry<Long, Candidate> e) -> e.getValue().score).reversed())
                    .limit(5)
                    .toList();
            try {
                for (Map.Entry<Long, Candidate> entry : strong) {
                    double parentScore = entry.getValue().score;
                    for (WikiPage linked : linkMapper.selectLinkedPages(entry.getKey())) {
                        candidates.computeIfAbsent(linked.getId(), id -> new Candidate())
                                .offer(parentScore * properties.getLinkDecay(), "LINK", true);
                    }
                }
            } catch (Exception e) {
                // 链接表缺失 / 脏数据：跳过图遍历即可，绝不能让热层查询抛异常影响问答
                log.warn("[Wiki] 图遍历失败（跳过 1 跳扩展）：{}", e.getMessage());
            }
        }

        // ---------- ④ 排序 + 回表 ----------
        List<Map.Entry<Long, Candidate>> ranked = new ArrayList<>(candidates.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<Long, Candidate> e) -> e.getValue().score).reversed());
        List<WikiHit> hits = new ArrayList<>();
        for (Map.Entry<Long, Candidate> entry : ranked) {
            if (hits.size() >= limit) {
                break;
            }
            WikiPage page = pageMapper.selectById(entry.getKey());
            if (page == null || !page.isPublished()) {
                continue;
            }
            Candidate candidate = entry.getValue();
            hits.add(new WikiHit(page, candidate.score, candidate.channel, candidate.viaLink));
        }
        return hits;
    }

    /** 供 Agent / 观测转换（解析 key_points） */
    public com.examprep.dto.wiki.WikiHitVO toVO(WikiHit hit) {
        com.examprep.dto.wiki.WikiHitVO vo = new com.examprep.dto.wiki.WikiHitVO();
        vo.setId(hit.page().getId());
        vo.setSlug(hit.page().getSlug());
        vo.setTitle(hit.page().getTitle());
        vo.setSubjectId(hit.page().getSubjectId());
        vo.setKnowledgeId(hit.page().getKnowledgeId());
        vo.setSummary(hit.page().getSummary());
        vo.setContent(hit.page().getContent());
        vo.setKeyPoints(WikiJsonSupport.stringList(objectMapper, hit.page().getKeyPoints()));
        vo.setScore(hit.score());
        vo.setChannel(hit.channel());
        return vo;
    }

    /**
     * 字符 bigram 覆盖率：问题中有多大比例的二字符片段出现在目标文本里
     *
     * <p>与语料规模无关，专门解决「Wiki 页面很少时 MATCH 的 IDF 接近 0」问题。
     */
    static double bigramCoverage(String query, String text) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(text)) {
            return 0.0;
        }
        Set<String> queryGrams = bigrams(query);
        if (queryGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> textGrams = bigrams(text);
        int hit = 0;
        for (String gram : queryGrams) {
            if (textGrams.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / queryGrams.size();
    }

    private static Set<String> bigrams(String text) {
        String compact = text.replaceAll("\\s+", "");
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < compact.length(); i++) {
            String gram = compact.substring(i, i + 2);
            if (!gram.isBlank()) {
                grams.add(gram);
            }
        }
        return grams;
    }

    /** 候选分数容器：保留最高分及来源通道 */
    private static final class Candidate {
        private double score = -1.0;
        private String channel = "";
        private boolean viaLink;

        void offer(double value, String source, boolean linked) {
            if (value > score) {
                score = value;
                channel = source;
                viaLink = linked;
            }
        }
    }
}
