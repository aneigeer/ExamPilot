package com.examprep.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评中台聚合查询（试题校准 / 学情画像 / 模考概览）
 *
 * <p>列别名统一 camelCase，服务层按 Map key 直接读取。
 */
@Mapper
public interface AssessmentMapper {

    // ==================================================================
    // 试题校准
    // ==================================================================

    /**
     * 题目作答聚合（练习 + 模考），样本 >= 10 才纳入。
     *
     * <p>区分度用「作答者在本场练习/考试中的整体表现」排序：
     * 高分组（前 27%）与低分组（后 27%）在该题上的正确率之差。
     * 同一场次内所有题目的 perf 相同，PERCENT_RANK 会产生大量并列，
     * 因此额外返回 perfLevels，只有 >= 2 个不同表现档位时区分度才有意义。
     */
    @Select("""
            WITH attempts AS (
              SELECT ead.question_id AS questionId,
                     ead.is_correct  AS correct,
                     (er.got_score / NULLIF(er.total_score, 0)) AS perf
              FROM exam_answer_detail ead
              JOIN exam_record er ON er.id = ead.record_id
              WHERE ead.user_answer IS NOT NULL AND ead.is_correct IS NOT NULL
                AND er.total_score > 0
              UNION ALL
              SELECT ad.question_id,
                     ad.is_correct,
                     (pr.correct_count / NULLIF(pr.answered_count, 0))
              FROM answer_detail ad
              JOIN practice_record pr ON pr.id = ad.practice_record_id
              WHERE ad.user_answer IS NOT NULL AND ad.is_correct IS NOT NULL
                AND pr.answered_count > 0
            ),
            ranked AS (
              SELECT questionId, correct,
                     PERCENT_RANK() OVER (PARTITION BY questionId ORDER BY perf DESC) AS prDesc
              FROM attempts
            )
            SELECT questionId,
                   COUNT(*) AS attemptCount,
                   SUM(CASE WHEN correct = 1 THEN 1 ELSE 0 END) AS correctCount,
                   AVG(CASE WHEN prDesc <= 0.27 THEN correct END) AS highAccuracy,
                   AVG(CASE WHEN prDesc >= 0.73 THEN correct END) AS lowAccuracy,
                   COUNT(DISTINCT prDesc) AS perfLevels
            FROM ranked
            GROUP BY questionId
            HAVING COUNT(*) >= 10
            ORDER BY questionId
            LIMIT #{limit}
            """)
    List<Map<String, Object>> calibrationAggregates(@Param("limit") int limit);

    /** 校准分布：各难度等级的题目数 */
    @Select("""
            SELECT calibrated_difficulty AS difficulty, COUNT(*) AS cnt
            FROM question_calibration
            GROUP BY calibrated_difficulty
            ORDER BY calibrated_difficulty
            """)
    List<Map<String, Object>> calibrationDistribution();

    /** 异常题清单：高错误率（<30%）或负区分度 */
    @Select("""
            SELECT c.question_id           AS questionId,
                   q.stem                  AS stem,
                   q.type                  AS type,
                   q.subject_id            AS subjectId,
                   c.attempt_count         AS attemptCount,
                   c.observed_accuracy     AS observedAccuracy,
                   c.calibrated_difficulty AS calibratedDifficulty,
                   c.discrimination_index  AS discriminationIndex
            FROM question_calibration c
            JOIN question q ON q.id = c.question_id AND q.is_deleted = 0
            WHERE (c.attempt_count >= 10 AND c.observed_accuracy < 0.30)
               OR (c.discrimination_index IS NOT NULL AND c.discrimination_index < 0)
            ORDER BY c.observed_accuracy ASC, c.question_id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> anomalyQuestions(@Param("limit") int limit);

    /** 校准覆盖率：有作答记录的题目数 / 已校准题目数 */
    @Select("""
            SELECT
              (SELECT COUNT(DISTINCT question_id) FROM (
                  SELECT question_id FROM answer_detail WHERE user_answer IS NOT NULL AND is_correct IS NOT NULL
                  UNION ALL
                  SELECT question_id FROM exam_answer_detail WHERE user_answer IS NOT NULL AND is_correct IS NOT NULL
              ) t) AS attemptedQuestionTotal,
              (SELECT COUNT(*) FROM question_calibration) AS calibratedQuestionTotal
            """)
    Map<String, Object> calibrationCoverage();

    // ==================================================================
    // 学情画像
    // ==================================================================

    @Select("""
            SELECT COUNT(*) AS sessionCount,
                   COALESCE(SUM(answered_count), 0) AS answeredCount,
                   COALESCE(SUM(correct_count), 0) AS correctCount
            FROM practice_record
            WHERE user_id = #{userId} AND started_at >= #{since}
            """)
    Map<String, Object> practiceSummary(@Param("userId") Long userId,
                                        @Param("since") LocalDateTime since);

    @Select("""
            SELECT er.id AS recordId, p.name AS paperName, p.paper_type AS paperType,
                   er.status AS status, er.got_score AS gotScore, er.total_score AS totalScore,
                   er.started_at AS startedAt, er.submitted_at AS submittedAt
            FROM exam_record er
            LEFT JOIN exam_paper p ON p.id = er.paper_id
            WHERE er.user_id = #{userId}
            ORDER BY er.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> mockTrend(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT q.subject_id AS subjectId,
                   MAX(s.name) AS subjectName,
                   COUNT(*) AS answered,
                   SUM(CASE WHEN ad.is_correct = 1 THEN 1 ELSE 0 END) AS correct
            FROM answer_detail ad
            JOIN practice_record pr ON pr.id = ad.practice_record_id
            JOIN question q ON q.id = ad.question_id
            LEFT JOIN subject s ON s.id = q.subject_id
            WHERE pr.user_id = #{userId} AND pr.started_at >= #{since}
              AND ad.user_answer IS NOT NULL AND ad.is_correct IS NOT NULL
            GROUP BY q.subject_id
            ORDER BY answered DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> subjectMastery(@Param("userId") Long userId,
                                             @Param("since") LocalDateTime since,
                                             @Param("limit") int limit);

    @Select("""
            SELECT qk.knowledge_id AS knowledgeId,
                   MAX(k.name) AS knowledgeName,
                   COUNT(*) AS answered,
                   SUM(CASE WHEN ad.is_correct = 1 THEN 1 ELSE 0 END) AS correct
            FROM answer_detail ad
            JOIN practice_record pr ON pr.id = ad.practice_record_id
            JOIN question_knowledge qk ON qk.question_id = ad.question_id
            JOIN knowledge_point k ON k.id = qk.knowledge_id AND k.is_deleted = 0
            WHERE pr.user_id = #{userId} AND pr.started_at >= #{since}
              AND ad.user_answer IS NOT NULL AND ad.is_correct IS NOT NULL
            GROUP BY qk.knowledge_id
            ORDER BY answered DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> knowledgeMastery(@Param("userId") Long userId,
                                               @Param("since") LocalDateTime since,
                                               @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) AS finishedCount,
                   AVG(total_score) AS avgScore,
                   MAX(total_score) AS bestScore
            FROM ai_interview_session
            WHERE user_id = #{userId} AND finished = 1 AND is_deleted = 0
            """)
    Map<String, Object> interviewSummary(@Param("userId") Long userId);

    // ==================================================================
    // 管理端：模考概览
    // ==================================================================

    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status IN ('FINISHED', 'EXPIRED') THEN 1 ELSE 0 END) AS finished,
                   AVG(got_score) AS avgScore,
                   AVG(CASE WHEN total_score > 0 THEN got_score * 100 / total_score END) AS avgRate
            FROM exam_record
            WHERE started_at >= #{since}
            """)
    Map<String, Object> mockOverview(@Param("since") LocalDateTime since);
}
