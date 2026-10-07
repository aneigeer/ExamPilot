package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.WrongBook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface WrongBookMapper extends BaseMapper<WrongBook> {

    /**
     * 取用户未掌握的错题 Top-N（AI 推荐算法 Step 1）
     *
     * <p>{@code mastered = 0} 表示「还没做对过」。按 {@code wrong_count DESC} 排，
     * 同次数时取最近错的。
     *
     * @param userId 用户 ID
     * @param limit  取前 N 条
     * @return 每行：questionId / stem / subjectId / knowledgeId / knowledgeName /
     *         difficulty / questionType / analysis / wrongCount / lastWrongAt
     */
    @Select("""
            SELECT wb.question_id   AS questionId,
                   q.stem           AS stem,
                   q.subject_id     AS subjectId,
                   q.difficulty     AS difficulty,
                   q.type           AS questionType,
                   q.analysis       AS analysis,
                   qk.knowledge_id  AS knowledgeId,
                   k.name           AS knowledgeName,
                   wb.wrong_count   AS wrongCount,
                   wb.last_wrong_at AS lastWrongAt
            FROM wrong_book wb
            JOIN question q ON q.id = wb.question_id AND q.is_deleted = 0
            LEFT JOIN question_knowledge qk ON qk.question_id = q.id
            LEFT JOIN knowledge_point k ON k.id = qk.knowledge_id
            WHERE wb.user_id = #{userId}
              AND wb.mastered = 0
            ORDER BY wb.wrong_count DESC, wb.last_wrong_at DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectUnmasteredTop(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 用户未掌握错题关联的知识点（推荐算法 Step 2：错题 → 知识点）
     *
     * <p>按「关联错题数 × 最高错误次数」排序，取权重最高的 Top-N 个知识点。
     */
    @Select("""
            SELECT qk.knowledge_id      AS knowledgeId,
                   k.name              AS knowledgeName,
                   COUNT(*)            AS wrongQuestionCount,
                   MAX(wb.wrong_count)  AS maxWrongCount
            FROM wrong_book wb
            JOIN question q ON q.id = wb.question_id AND q.is_deleted = 0
            JOIN question_knowledge qk ON qk.question_id = q.id
            JOIN knowledge_point k ON k.id = qk.knowledge_id
            WHERE wb.user_id = #{userId}
              AND wb.mastered = 0
            GROUP BY qk.knowledge_id, k.name
            ORDER BY wrongQuestionCount DESC, maxWrongCount DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectWeakKnowledgeFromWrongBook(@Param("userId") Long userId,
                                                               @Param("limit") int limit);

    /**
     * 用户未掌握错题的题目 ID（推荐时排除，避免重复推「已经错的题」）
     */
    @Select("SELECT question_id FROM wrong_book WHERE user_id = #{userId} AND mastered = 0")
    List<Long> selectWrongQuestionIds(@Param("userId") Long userId);
}
