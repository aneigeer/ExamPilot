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
}
