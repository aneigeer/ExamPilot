package com.examprep.service.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.examprep.dto.ai.RecommendationVO;
import com.examprep.entity.Question;
import com.examprep.entity.Resource;
import com.examprep.mapper.QuestionMapper;
import com.examprep.mapper.ResourceMapper;
import com.examprep.mapper.WrongBookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个性化推荐服务（设计方案 C.3 五步算法）
 *
 * <pre>
 *   Step 1  取用户错题本（mastered=0）按 wrong_count DESC 取 Top-5
 *   Step 2  映射到知识点集合 K（去重后取权重最高的 Top-3）
 *   Step 3  对每个知识点 k：
 *            3.1 找同知识点、未做过的题目（排除已在错题本的）→ 推荐「巩固练习」
 *            3.2 找同学科 / 同知识点的已审核资料 → 推荐「配套资料」
 *   Step 4  按 (知识点薄弱度 × 0.6 + 题目难度匹配度 × 0.4) 排序，返回 Top-N
 *   Step 5  ★ 冷启动兜底：错题本为空 或 有效作答 < 3 题
 *            → 退化为「按科目热度推荐」，reason = GENERAL_POPULAR
 * </pre>
 *
 * <p><b>硬约束</b>：{@code reason} 任何情况下非空；<b>绝不返回空列表</b>。
 * 推荐理由不可见等于 personalised 推荐不可信——用户不知道系统为什么推这个。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 冷启动推荐理由（前端据此展示「根据热门推荐」） */
    public static final String REASON_GENERAL_POPULAR = "GENERAL_POPULAR";
    /** 巩固练习（错题同知识点、未做过的题） */
    public static final String REASON_WRONG_KNOWLEDGE = "WRONG_KNOWLEDGE";
    /** 配套资料（同知识点 / 同学科） */
    public static final String REASON_RELATED_RESOURCE = "RELATED_RESOURCE";

    private final WrongBookMapper wrongBookMapper;
    private final QuestionMapper questionMapper;
    private final ResourceMapper resourceMapper;
    private final KnowledgeProfileService profileService;
    private final AiProperties properties;

    private static final int WRONG_TOP_N = 5;
    private static final int KNOWLEDGE_TOP_N = 3;

    /**
     * 生成个性化推荐
     *
     * @param userId 用户 ID
     * @param limit  返回条数
     */
    public List<RecommendationVO> recommend(Long userId, int limit) {
        if (userId == null) {
            return generalPopular(limit, null);
        }
        long answered = profileService.answeredCount(userId);
        List<Map<String, Object>> wrongRows = wrongBookMapper.selectUnmasteredTop(userId, WRONG_TOP_N);

        // ---------- Step 5 冷启动兜底 ----------
        if (wrongRows.isEmpty()
                || answered < properties.getProfile().getColdStartAttempts()) {
            log.info("[AI] 用户 {} 走冷启动兜底（错题 {} 条，有效作答 {} 次）",
                    userId, wrongRows.size(), answered);
            return generalPopular(limit, null);
        }

        // ---------- Step 1-2 错题 → 知识点 ----------
        List<Map<String, Object>> weakKps =
                wrongBookMapper.selectWeakKnowledgeFromWrongBook(userId, KNOWLEDGE_TOP_N);
        if (weakKps.isEmpty()) {
            return generalPopular(limit, null);
        }
        List<Long> knowledgeIds = new ArrayList<>();
        Map<Long, String> knowledgeNames = new LinkedHashMap<>();
        for (Map<String, Object> row : weakKps) {
            Long kpId = toLong(row.get("knowledgeId"));
            if (kpId != null && !knowledgeIds.contains(kpId)) {
                knowledgeIds.add(kpId);
                knowledgeNames.put(kpId, str(row.get("knowledgeName")));
            }
        }

        // ---------- Step 4 输入之一：薄弱度 ----------
        Map<Long, Double> weaknessMap = profileService.weaknessMap(userId);
        // 错题本身携带的知识点薄弱度可能缺失（该知识点无加权作答记录），用「错题数占比」兜底
        double maxWrongCount = wrongRows.stream()
                .mapToDouble(r -> toDouble(r.get("wrongCount"), 1.0))
                .max().orElse(1.0);
        Map<Long, Integer> wrongCountByKp = new java.util.HashMap<>();
        for (Map<String, Object> row : wrongRows) {
            Long kpId = toLong(row.get("knowledgeId"));
            if (kpId != null) {
                wrongCountByKp.merge(kpId, (int) toDouble(row.get("wrongCount"), 1.0), Integer::sum);
            }
        }

        // ---------- Step 3.1 同知识点未做过的题 ----------
        Set<Long> wrongQuestionIds = new HashSet<>(wrongBookMapper.selectWrongQuestionIds(userId));
        List<RecommendationVO> candidates = new ArrayList<>();
        for (Long kpId : knowledgeIds) {
            List<Long> questionIds = questionMapper.selectQuestionIdsByKnowledgeId(kpId);
            if (questionIds.isEmpty()) {
                continue;
            }
            List<Question> questions = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                    .in(Question::getId, questionIds)
                    .eq(Question::getAuditStatus, "APPROVED")
                    .eq(Question::getParentId, 0L)
                    .orderByAsc(Question::getId));
            int added = 0;
            for (Question q : questions) {
                if (added >= 2) {
                    break;
                }
                if (wrongQuestionIds.contains(q.getId())) {
                    continue;
                }
                candidates.add(buildQuestionRec(q, kpId, knowledgeNames.get(kpId),
                        weakness(kpId, weaknessMap, wrongCountByKp, maxWrongCount), wrongRows));
                added++;
            }
        }

        // ---------- Step 3.2 同知识点 / 同学科的已审核资料 ----------
        Set<Long> subjectIds = new LinkedHashSet<>();
        wrongRows.forEach(r -> {
            Long sid = toLong(r.get("subjectId"));
            if (sid != null) {
                subjectIds.add(sid);
            }
        });
        for (Long kpId : knowledgeIds) {
            List<Resource> resources = resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                    .eq(Resource::getAuditStatus, "APPROVED")
                    .and(w -> w.eq(Resource::getKnowledgeId, kpId)
                            .or().in(!subjectIds.isEmpty(), Resource::getSubjectId, subjectIds))
                    .orderByDesc(Resource::getDownloadCount)
                    .last("LIMIT 2"));
            for (Resource r : resources) {
                candidates.add(buildResourceRec(r, kpId, knowledgeNames.get(kpId),
                        weakness(kpId, weaknessMap, wrongCountByKp, maxWrongCount)));
            }
        }

        if (candidates.isEmpty()) {
            log.info("[AI] 用户 {} 个性化候选为空，降级为热门推荐", userId);
            return generalPopular(limit, null);
        }

        // ---------- Step 4 排序 ----------
        candidates.sort(Comparator.comparingDouble(RecommendationVO::getScore).reversed());
        return candidates.size() > limit ? List.copyOf(candidates.subList(0, limit)) : List.copyOf(candidates);
    }

    // ==================================================================
    // Step 5 冷启动兜底
    // ==================================================================

    /**
     * 热门兜底推荐：按下载量 / 浏览量排序，{@code reason = GENERAL_POPULAR}
     *
     * <p>资料不够时用题目补位，保证<b>绝不返回空列表</b>。
     */
    public List<RecommendationVO> generalPopular(int limit, Long subjectId) {
        List<RecommendationVO> result = new ArrayList<>();
        // 注意：不能用 StringUtils.hasText(String.valueOf(subjectId)) 判断——
        // String.valueOf(null) 返回字符串 "null"（非空），会把 null 当成有效过滤值导致查不到任何数据。
        boolean filterBySubject = subjectId != null;
        List<Resource> resources = resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                .eq(Resource::getAuditStatus, "APPROVED")
                .eq(filterBySubject, Resource::getSubjectId, subjectId)
                .orderByDesc(Resource::getDownloadCount)
                .orderByDesc(Resource::getViewCount)
                .last("LIMIT " + Math.max(1, limit)));
        for (Resource r : resources) {
            result.add(buildPopularResource(r));
        }
        if (result.size() < limit) {
            List<Question> questions = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                    .eq(Question::getAuditStatus, "APPROVED")
                    .eq(Question::getParentId, 0L)
                    .eq(filterBySubject, Question::getSubjectId, subjectId)
                    .orderByDesc(Question::getDifficulty)
                    .orderByAsc(Question::getId)
                    .last("LIMIT " + Math.max(1, limit - result.size())));
            for (Question q : questions) {
                result.add(buildPopularQuestion(q));
            }
        }
        if (result.isEmpty()) {
            log.warn("[AI] 热门兜底仍为空：站内暂无已审核资料与题目");
        }
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }

    private RecommendationVO buildPopularResource(Resource r) {
        RecommendationVO vo = new RecommendationVO();
        vo.setType("RESOURCE");
        vo.setRefId(r.getId());
        vo.setTitle(r.getTitle());
        vo.setReason(REASON_GENERAL_POPULAR);
        vo.setKnowledgeId(r.getKnowledgeId());
        vo.setWeakness(0.0);
        vo.setScore(0.0);
        vo.setRefPath("/resources/" + r.getId());
        return vo;
    }

    private RecommendationVO buildPopularQuestion(Question q) {
        RecommendationVO vo = new RecommendationVO();
        vo.setType("QUESTION");
        vo.setRefId(q.getId());
        vo.setTitle(plainStem(q.getStem()));
        vo.setReason(REASON_GENERAL_POPULAR);
        vo.setWeakness(0.0);
        vo.setScore(0.0);
        vo.setRefPath("/questions/" + q.getId());
        return vo;
    }

    // ==================================================================
    // VO 构造
    // ==================================================================

    private RecommendationVO buildQuestionRec(Question q, Long kpId, String kpName,
                                              double weakness, List<Map<String, Object>> wrongRows) {
        RecommendationVO vo = new RecommendationVO();
        vo.setType("QUESTION");
        vo.setRefId(q.getId());
        vo.setTitle(plainStem(q.getStem()));
        vo.setKnowledgeId(kpId);
        vo.setKnowledgeName(kpName);
        vo.setWeakness(round(weakness));
        vo.setReason(buildWrongReason(kpName, wrongRows, kpId));
        vo.setScore(round(weakness * 0.6 + difficultyMatch(q.getDifficulty()) * 0.4));
        vo.setRefPath("/questions/" + q.getId());
        return vo;
    }

    private RecommendationVO buildResourceRec(Resource r, Long kpId, String kpName, double weakness) {
        RecommendationVO vo = new RecommendationVO();
        vo.setType("RESOURCE");
        vo.setRefId(r.getId());
        vo.setTitle(r.getTitle());
        vo.setKnowledgeId(kpId);
        vo.setKnowledgeName(kpName);
        vo.setWeakness(round(weakness));
        vo.setReason(StringUtils.hasText(kpName)
                ? "你在「" + kpName + "」知识点较薄弱，这份资料覆盖该知识点"
                : "根据你的薄弱科目推荐");
        // 资料无难度概念，难度匹配度取中等值 0.5
        vo.setScore(round(weakness * 0.6 + 0.5 * 0.4));
        vo.setRefPath("/resources/" + r.getId());
        return vo;
    }

    /** 推荐理由：你在「X」知识点错 N 次，该题同属此知识点 */
    private String buildWrongReason(String kpName, List<Map<String, Object>> wrongRows, Long kpId) {
        if (!StringUtils.hasText(kpName)) {
            return REASON_WRONG_KNOWLEDGE;
        }
        int wrongCount = 0;
        for (Map<String, Object> row : wrongRows) {
            if (kpId != null && kpId.equals(toLong(row.get("knowledgeId")))) {
                wrongCount += (int) toDouble(row.get("wrongCount"), 1.0);
            }
        }
        if (wrongCount > 0) {
            return "你在「" + kpName + "」知识点错 " + wrongCount + " 次，该题同属此知识点";
        }
        return "你在「" + kpName + "」知识点较薄弱，该题同属此知识点";
    }

    /** 难度匹配度：difficulty 3 视为最匹配（用户当前主要刷中等难度题） */
    private double difficultyMatch(Integer difficulty) {
        int d = difficulty == null ? 3 : Math.max(1, Math.min(5, difficulty));
        return 1.0 - Math.abs(d - 3) / 4.0;
    }

    /** 薄弱度取值：优先画像算法结果，缺失时用「该知识点错题占比」兜底 */
    private double weakness(Long kpId, Map<Long, Double> weaknessMap,
                            Map<Long, Integer> wrongCountByKp, double maxWrongCount) {
        Double fromProfile = weaknessMap.get(kpId);
        if (fromProfile != null && fromProfile > 0) {
            return fromProfile;
        }
        int count = wrongCountByKp.getOrDefault(kpId, 0);
        if (count <= 0 || maxWrongCount <= 0) {
            return 0.3;
        }
        // 兜底值乘 0.6 置信度：只有错题数据、没有作答明细，置信度天然不足
        return Math.min(1.0, count / maxWrongCount) * 0.6;
    }

    private String plainStem(String stem) {
        if (!StringUtils.hasText(stem)) {
            return "未命名题目";
        }
        return stem.trim();
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
