package com.examprep.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Wiki 蒸馏输入查询（只读，不建表）
 *
 * <p>聚类依据是 {@code ai_chunk.knowledge_id}：同一知识点的题目 / 资料 / 知识点摘要
 * 天然构成一个「跨文档主题」，正好符合 Wiki 主题页的粒度。
 */
@Mapper
public interface WikiDistillMapper {

    /**
     * 可蒸馏的知识点簇（按有效块数降序）
     *
     * @param subjectId 科目过滤，null 为全部
     * @param minChunks 最少块数
     * @param limit     最多簇数
     */
    @Select("""
            <script>
            SELECT c.knowledge_id AS knowledgeId,
                   c.subject_id   AS subjectId,
                   kp.name        AS knowledgeName,
                   COUNT(*)       AS chunkCount
            FROM ai_chunk c
            JOIN knowledge_point kp ON kp.id = c.knowledge_id
            WHERE c.status = 'ACTIVE'
              AND c.knowledge_id IS NOT NULL
            <if test="subjectId != null"> AND c.subject_id = #{subjectId}</if>
            GROUP BY c.knowledge_id, c.subject_id, kp.name
            HAVING COUNT(*) &gt;= #{minChunks}
            ORDER BY COUNT(*) DESC, c.knowledge_id
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> selectKnowledgeClusters(@Param("subjectId") Long subjectId,
                                                      @Param("minChunks") int minChunks,
                                                      @Param("limit") int limit);

    /**
     * 某个知识点簇的代表块（知识点摘要优先，其次题目，最后资料 / 课件）
     */
    @Select("""
            SELECT id, title, content, source_type AS sourceType, source_id AS sourceId,
                   subject_id AS subjectId, knowledge_id AS knowledgeId
            FROM ai_chunk
            WHERE status = 'ACTIVE' AND knowledge_id = #{knowledgeId}
            ORDER BY FIELD(source_type, 'KNOWLEDGE', 'QUESTION', 'RESOURCE', 'COURSEWARE'), id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectClusterChunks(@Param("knowledgeId") Long knowledgeId,
                                                  @Param("limit") int limit);
}
