package com.examprep.service.ai;

import com.examprep.dto.ai.KnowledgeWeakVO;
import com.examprep.mapper.AnswerDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学情画像服务（设计方案 C.2 七步算法）
 *
 * <p><b>不建画像表</b>：用户画像实时算，避免冗余表与答题明细不一致。
 * 聚合 SQL 复用 {@code AnswerDetailMapper.selectWeightedMastery}（在 selectMastery 基础上加权重项）。
 *
 * <p><b>七步</b>：
 * <pre>
 *   Step 1  单次作答得分 s_i ∈ [0,1]（SQL 内 CASE WHEN）
 *   Step 2  时间衰减 decay_i = 0.5 ^ (Δdays / halfLifeDays)      —— 30 天减半
 *   Step 3  难度权重 w_i = base + step × difficulty              —— 难题答对价值更高
 *   Step 4  错题本加权 wrongBoost = 1 + factor × MIN(wrong_count, cap)
 *   Step 5  掌握度 mastery(k) = Σ(s·w·decay·boost) / Σ(w·decay·boost)
 *   Step 6  薄弱度 weakness(k) = (1 - mastery(k)) × confidence(k)
 *           其中 confidence = MIN(1, attempts / 3)  ★ 防「做 1 题就判薄弱」
 *   Step 7  ORDER BY weakness DESC, attempts DESC LIMIT N
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProfileService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AnswerDetailMapper answerDetailMapper;
    private final AiProperties properties;

    /**
     * 薄弱知识点 Top-N（已按 weakness 降序）
     *
     * @param userId    用户 ID
     * @param limit     返回条数
     * @param subjectId 科目过滤，null 表示全部
     */
    public List<KnowledgeWeakVO> weakKnowledge(Long userId, int limit, Long subjectId) {
        List<Map<String, Object>> rows = answerDetailMapper.selectWeightedMastery(
                userId, subjectId,
                properties.getProfile().getHalfLifeDays(),
                properties.getProfile().getDifficultyWeightBase(),
                properties.getProfile().getDifficultyWeightStep(),
                properties.getProfile().getWrongBoostFactor(),
                properties.getProfile().getWrongBoostCap());
        if (rows.isEmpty()) {
            log.info("[AI] 用户 {} 暂无加权作答记录，画像返回空列表", userId);
            return List.of();
        }

        List<KnowledgeWeakVO> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int attempts = (int) toDouble(row.get("attempts"), 0);
            if (attempts <= 0) {
                continue;
            }
            double weightSum = toDouble(row.get("weightSum"), 0.0);
            double weightedScore = toDouble(row.get("weightedScore"), 0.0);
            // Step 5：掌握度 = 加权得分和 / 权重和，夹到 [0,1] 防数值误差越界
            double mastery = weightSum <= 0 ? 0.0
                    : Math.max(0.0, Math.min(1.0, weightedScore / weightSum));
            // Step 6：置信度 —— 关键设计，避免「做 1 道错题就判薄弱度 100%」
            double confidence = Math.min(1.0, (double) attempts
                    / Math.max(1, properties.getProfile().getConfidenceThreshold()));
            double weakness = (1.0 - mastery) * confidence;

            KnowledgeWeakVO vo = new KnowledgeWeakVO();
            vo.setKnowledgeId(toLong(row.get("knowledgeId")));
            vo.setKnowledgeName(str(row.get("knowledgeName")));
            vo.setSubjectId(toLong(row.get("subjectId")));
            vo.setAttempts(attempts);
            vo.setMastery(round(mastery));
            vo.setWeakness(round(weakness));
            vo.setConfidence(round(confidence));
            Object last = row.get("lastAnsweredAt");
            vo.setLastAnsweredAt(formatTime(last));
            items.add(vo);
        }

        // Step 7：按薄弱度降序，同分按作答次数降序
        items.sort(Comparator.comparingDouble(KnowledgeWeakVO::getWeakness)
                .thenComparing(KnowledgeWeakVO::getAttempts)
                .reversed());
        return items.size() > limit ? List.copyOf(items.subList(0, limit)) : List.copyOf(items);
    }

    /**
     * 用户薄弱知识点 ID 集合（检索加权用，对应 A.7 ③ 的 +0.15）
     *
     * <p>游客传空集即可——<b>游客不建画像</b>，问答链路不做这步加权。
     *
     * @param topN 取前 N 个（默认 5，避免为一次问答跑太多聚合）
     */
    public Set<Long> weakKnowledgeIds(Long userId, int topN) {
        if (userId == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (KnowledgeWeakVO vo : weakKnowledge(userId, topN, null)) {
            if (vo.getWeakness() != null && vo.getWeakness() > 0 && vo.getKnowledgeId() != null) {
                ids.add(vo.getKnowledgeId());
            }
        }
        return ids;
    }

    /**
     * 薄弱度查表（推荐算法 Step 4 用）
     *
     * @return knowledgeId → weakness；无记录时返回空 map
     */
    public Map<Long, Double> weaknessMap(Long userId) {
        Map<Long, Double> map = new HashMap<>();
        for (KnowledgeWeakVO vo : weakKnowledge(userId, 20, null)) {
            if (vo.getKnowledgeId() != null) {
                map.put(vo.getKnowledgeId(), vo.getWeakness() == null ? 0.0 : vo.getWeakness());
            }
        }
        return map;
    }

    /** 有效作答总次数（冷启动判定） */
    public long answeredCount(Long userId) {
        return userId == null ? 0L : answerDetailMapper.countAnswered(userId);
    }

    private String formatTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DT);
        }
        String s = String.valueOf(value);
        return s.length() > 19 ? s.substring(0, 19) : s;
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
        return StringUtils.hasText(String.valueOf(value)) ? String.valueOf(value) : "";
    }
}
