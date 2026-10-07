package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AnswerDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnswerDetailMapper extends BaseMapper<AnswerDetail> {

    /**
     * 知识点掌握度：按知识点聚合用户的答题数与正确数
     */
    @Select("""
            SELECT k.id AS knowledgeId, k.name AS knowledgeName,
                   COUNT(*) AS answered,
                   SUM(CASE WHEN a.is_correct = 1 THEN 1 ELSE 0 END) AS correct
            FROM answer_detail a
            JOIN practice_record p ON p.id = a.practice_record_id
            JOIN question q ON q.id = a.question_id
            JOIN question_knowledge qk ON qk.question_id = q.id
            JOIN knowledge_point k ON k.id = qk.knowledge_id
            WHERE p.user_id = #{userId}
              AND a.user_answer IS NOT NULL
              AND (#{subjectId} IS NULL OR q.subject_id = #{subjectId})
            GROUP BY k.id, k.name
            ORDER BY answered DESC
            """)
    List<Map<String, Object>> selectMastery(@Param("userId") Long userId, @Param("subjectId") Long subjectId);

    /**
     * 刷题趋势：按天聚合
     */
    @Select("""
            SELECT DATE(a.answered_at) AS day,
                   COUNT(*) AS answered,
                   SUM(CASE WHEN a.is_correct = 1 THEN 1 ELSE 0 END) AS correct
            FROM answer_detail a
            JOIN practice_record p ON p.id = a.practice_record_id
            WHERE p.user_id = #{userId}
              AND a.user_answer IS NOT NULL
              AND a.answered_at >= #{since}
            GROUP BY DATE(a.answered_at)
            ORDER BY day
            """)
    List<Map<String, Object>> selectDailyTrend(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * ★ 加权掌握度聚合（设计方案 C.2 Step 1-4，AI 学情画像专用）
     *
     * <p>在 {@link #selectMastery} 基础上把三组权重项算进 SQL，返回
     * 「加权得分和 / 权重和」，Java 侧一次除法即得 mastery(k)，无需把明细拉回内存。
     *
     * <p><b>三个权重项</b>：
     * <ul>
     *   <li><b>时间衰减</b> {@code decay_i = POW(0.5, TIMESTAMPDIFF(DAY, answered_at, CURDATE()) / halfLifeDays)}
     *       —— 30 天前的作答权重减半（半衰期可配）</li>
     *   <li><b>难度权重</b> {@code w_i = base + step * difficulty}
     *       —— 难题答对价值更高，答错更该警惕</li>
     *   <li><b>错题本加权</b> {@code wrongBoost = 1 + factor * LEAST(COALESCE(wrong_count,0), cap)}
     *       —— 重复错的题权重放大，但封顶防止爆炸</li>
     * </ul>
     *
     * <p><b>单次作答得分 s_i</b>（Step 1）：
     * {@code is_correct=1 → 1.0}；{@code self_eval='UNSURE' → 0.5}；
     * {@code is_correct=0 或 self_eval='DONT_KNOW' → 0.0}；
     * {@code is_correct IS NULL 且无自评 → 0.5}（中性，不奖不罚）。
     *
     * <p>难度与权重全部作为参数传入（{@code @Param}），不硬编码——
     * 使用时现场改 {@code application.yml} 的一个数即可看效果。
     *
     * @param halfLifeDays 时间衰减半衰期（天）
     * @param weightBase   难度权重基址
     * @param weightStep   难度权重步长
     * @param wrongFactor  错题本加权因子
     * @param wrongCap     错题次数上限
     * @return 每行一个知识点：knowledgeId / knowledgeName / subjectId /
     *         attempts / weightedScore / weightSum / lastAnsweredAt
     */
    @Select("""
            SELECT k.id         AS knowledgeId,
                   k.name       AS knowledgeName,
                   q.subject_id AS subjectId,
                   COUNT(*)     AS attempts,
                   SUM(
                       (CASE
                            WHEN a.is_correct = 1 THEN 1.0
                            WHEN a.self_eval = 'UNSURE' THEN 0.5
                            WHEN a.is_correct = 0 OR a.self_eval = 'DONT_KNOW' THEN 0.0
                            ELSE 0.5
                        END)
                       * (#{weightBase} + #{weightStep} * COALESCE(q.difficulty, 3))
                       * POW(0.5, GREATEST(TIMESTAMPDIFF(DAY, a.answered_at, CURDATE()), 0) / #{halfLifeDays})
                       * (1 + #{wrongFactor} * LEAST(COALESCE(wb.wrong_count, 0), #{wrongCap}))
                   ) AS weightedScore,
                   SUM(
                       (#{weightBase} + #{weightStep} * COALESCE(q.difficulty, 3))
                       * POW(0.5, GREATEST(TIMESTAMPDIFF(DAY, a.answered_at, CURDATE()), 0) / #{halfLifeDays})
                       * (1 + #{wrongFactor} * LEAST(COALESCE(wb.wrong_count, 0), #{wrongCap}))
                   ) AS weightSum,
                   MAX(a.answered_at) AS lastAnsweredAt
            FROM answer_detail a
            JOIN practice_record p ON p.id = a.practice_record_id
            JOIN question q ON q.id = a.question_id
            JOIN question_knowledge qk ON qk.question_id = q.id
            JOIN knowledge_point k ON k.id = qk.knowledge_id
            LEFT JOIN wrong_book wb ON wb.user_id = p.user_id
                                   AND wb.question_id = a.question_id
                                   AND wb.mastered = 0
            WHERE p.user_id = #{userId}
              AND a.user_answer IS NOT NULL
              AND a.answered_at IS NOT NULL
              AND (#{subjectId} IS NULL OR q.subject_id = #{subjectId})
            GROUP BY k.id, k.name, q.subject_id
            ORDER BY attempts DESC
            """)
    List<Map<String, Object>> selectWeightedMastery(@Param("userId") Long userId,
                                                   @Param("subjectId") Long subjectId,
                                                   @Param("halfLifeDays") int halfLifeDays,
                                                   @Param("weightBase") double weightBase,
                                                   @Param("weightStep") double weightStep,
                                                   @Param("wrongFactor") double wrongFactor,
                                                   @Param("wrongCap") int wrongCap);

    /**
     * 用户有效作答总次数（冷启动判定：少于阈值则走热门兜底推荐）
     */
    @Select("""
            SELECT COUNT(*) FROM answer_detail a
            JOIN practice_record p ON p.id = a.practice_record_id
            WHERE p.user_id = #{userId}
              AND a.user_answer IS NOT NULL
            """)
    long countAnswered(@Param("userId") Long userId);
}
