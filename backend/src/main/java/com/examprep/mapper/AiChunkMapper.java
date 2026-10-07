package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 语料块 Mapper
 *
 * <p><b>约定</b>：复杂 SQL 一律用 {@code @Select("""...""")} 文本块，
 * 全项目 0 个 Mapper XML，聚合查询返回 {@code List<Map<String,Object>>}
 * （依赖 {@code map-underscore-to-camel-case: true}）。
 */
@Mapper
public interface AiChunkMapper extends BaseMapper<AiChunk> {

    /**
     * 全文召回（ngram 中文分词倒排索引 + 相关度打分）
     *
     * <p>SQL 照设计方案 A.7。{@code MATCH ... AGAINST ... IN BOOLEAN MODE}
     * 由存储引擎直接返回相关度分数 {@code ftScore}，这是 LIKE 做不到的。
     *
     * @param booleanQuery   布尔查询串（由 BooleanQueryBuilder 构造，形如 {@code +值传递 +地址传递}）
     * @param examCategoryId 考试类别，null 表示不隔离
     * @param subjectId      科目，null 表示不隔离
     * @param recallLimit    召回条数（默认 50）
     */
    @Select("""
            SELECT c.id, c.source_type, c.source_id, c.chunk_no, c.title, c.content,
                   c.subject_id, c.knowledge_id, c.difficulty, c.ref_type, c.ref_id,
                   c.page_no, c.source_updated_at,
                   (CASE WHEN c.ref_type = 'RESOURCE' AND r.id IS NOT NULL
                         THEN COALESCE(r.download_count, 0) + COALESCE(r.view_count, 0)
                         ELSE 0 END) AS hot_count,
                   MATCH(c.title, c.content) AGAINST (#{booleanQuery} IN BOOLEAN MODE) AS ft_score
            FROM ai_chunk c
            LEFT JOIN resource r ON c.ref_type = 'RESOURCE' AND r.id = c.ref_id
            WHERE c.status = 'ACTIVE'
              AND MATCH(c.title, c.content) AGAINST (#{booleanQuery} IN BOOLEAN MODE)
              AND (#{examCategoryId} IS NULL OR c.exam_category_id = #{examCategoryId})
              AND (#{subjectId}       IS NULL OR c.subject_id       = #{subjectId})
            ORDER BY ft_score DESC
            LIMIT #{recallLimit}
            """)
    List<Map<String, Object>> searchByFulltext(@Param("booleanQuery") String booleanQuery,
                                              @Param("examCategoryId") Long examCategoryId,
                                              @Param("subjectId") Long subjectId,
                                              @Param("recallLimit") int recallLimit);

    /**
     * LIKE 降级召回（对应风险 R8：ngram 插件不可用时的兜底路径）
     *
     * <p>用 OR 连接多个关键词，兼容 {@code <script>} 动态 SQL。
     * 无相关度分数，{@code ftScore} 恒为 0，由 Java 侧按标题命中与新鲜度补分。
     *
     * @param keywords    关键词列表（已去重、已丢弃长度 &lt; 2 的词）
     * @param subjectId   科目，null 表示不隔离
     * @param recallLimit 召回条数
     */
    @Select("""
            <script>
            SELECT c.id, c.source_type, c.source_id, c.chunk_no, c.title, c.content,
                   c.subject_id, c.knowledge_id, c.difficulty, c.ref_type, c.ref_id,
                   c.page_no, c.source_updated_at,
                   (CASE WHEN c.ref_type = 'RESOURCE' AND r.id IS NOT NULL
                         THEN COALESCE(r.download_count, 0) + COALESCE(r.view_count, 0)
                         ELSE 0 END) AS hot_count,
                   0 AS ft_score
            FROM ai_chunk c
            LEFT JOIN resource r ON c.ref_type = 'RESOURCE' AND r.id = c.ref_id
            WHERE c.status = 'ACTIVE'
              <choose>
                <when test="keywords != null and keywords.size() > 0">
                  AND (
                  <foreach collection="keywords" item="kw" separator=" OR ">
                    (c.title LIKE CONCAT('%', #{kw}, '%') OR c.content LIKE CONCAT('%', #{kw}, '%'))
                  </foreach>
                  )
                </when>
                <otherwise>AND 1 = 0</otherwise>
              </choose>
              <if test="subjectId != null">AND c.subject_id = #{subjectId}</if>
            ORDER BY c.source_updated_at DESC
            LIMIT #{recallLimit}
            </script>
            """)
    List<Map<String, Object>> searchByLike(@Param("keywords") List<String> keywords,
                                           @Param("subjectId") Long subjectId,
                                           @Param("recallLimit") int recallLimit);

    /**
     * 某来源的全部块（增量同步时比对 content_hash 用）
     */
    @Select("""
            SELECT id, source_type, source_id, chunk_no, title, content,
                   exam_category_id, subject_id, knowledge_id, difficulty,
                   ref_type, ref_id, page_no, content_hash, source_updated_at,
                   status, vector_synced, vector_synced_at, created_at, updated_at
            FROM ai_chunk
            WHERE source_type = #{sourceType} AND source_id = #{sourceId}
            ORDER BY chunk_no ASC
            """)
    List<AiChunk> selectBySource(@Param("sourceType") String sourceType,
                                 @Param("sourceId") Long sourceId);

    /**
     * 按 ID 批量取块（向量通道回表取正文用）
     *
     * <p>★ Qdrant payload 里<b>不存正文</b>（正文唯一来源是 MySQL），
     * 所以向量命中后必须回本表取 {@code content} 才能进 prompt。
     */
    @Select("""
            <script>
            SELECT id, source_type, source_id, chunk_no, title, content,
                   exam_category_id, subject_id, knowledge_id, difficulty,
                   ref_type, ref_id, page_no, content_hash, source_updated_at,
                   status, vector_synced, vector_synced_at
            FROM ai_chunk
            WHERE id IN
            <foreach collection="ids" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            </script>
            """)
    List<AiChunk> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 某来源的块数量（判断分块数是否变少，变少则把多余块置 INVALID）
     */
    @Select("""
            SELECT COUNT(*) FROM ai_chunk
            WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND status = 'ACTIVE'
            """)
    int countActiveBySource(@Param("sourceType") String sourceType,
                            @Param("sourceId") Long sourceId);

    /**
     * 统计有效块总数（reindex 接口返回 chunk 统计）
     */
    @Select("SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE'")
    long countActive();

    /**
     * 按来源类型统计有效块数
     */
    @Select("""
            SELECT source_type, COUNT(*) AS cnt FROM ai_chunk
            WHERE status = 'ACTIVE' GROUP BY source_type
            """)
    List<Map<String, Object>> countActiveGroupBySourceType();

    /**
     * 索引中最大的来源更新时间（启动自检：落后于业务表则打 warn）
     */
    @Select("SELECT MAX(source_updated_at) FROM ai_chunk WHERE status = 'ACTIVE'")
    LocalDateTime maxSourceUpdatedAt();

    /**
     * 来源下架 / 失效：置 INVALID 而非物理删除，保留可追溯性
     */
    @Update("""
            UPDATE ai_chunk SET status = 'INVALID'
            WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND status = 'ACTIVE'
            """)
    int invalidateBySource(@Param("sourceType") String sourceType,
                           @Param("sourceId") Long sourceId);

    /**
     * 清理某个来源下超出有效块序号范围的残留块（内容变短导致分块数减少时使用）
     *
     * @param keepChunkNo 保留的最大块序号（含）；小于该序号的 ACTIVE 块全部置 INVALID
     */
    @Update("""
            UPDATE ai_chunk SET status = 'INVALID'
            WHERE source_type = #{sourceType} AND source_id = #{sourceId}
              AND status = 'ACTIVE' AND chunk_no > #{keepChunkNo}
            """)
    int invalidateChunkNoGreaterThan(@Param("sourceType") String sourceType,
                                     @Param("sourceId") Long sourceId,
                                     @Param("keepChunkNo") int keepChunkNo);

    /**
     * 置向量同步状态（异步推送成功后调用）
     *
     * @param synced 0 待同步 / 1 已同步 / 2 失败
     */
    @Update("""
            UPDATE ai_chunk
            SET vector_synced = #{synced},
                vector_synced_at = CASE WHEN #{synced} = 1 THEN NOW() ELSE vector_synced_at END
            WHERE id IN
            <foreach collection="ids" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            """)
    int markVectorSynced(@Param("ids") List<Long> ids, @Param("synced") int synced);

    /**
     * 向量同步状态统计（与 Qdrant 对账用）
     *
     * @return 每行：vectorSynced / cnt
     */
    @Select("""
            SELECT vector_synced AS vectorSynced, COUNT(*) AS cnt
            FROM ai_chunk
            WHERE status = 'ACTIVE'
            GROUP BY vector_synced
            """)
    List<Map<String, Object>> countByVectorSynced();

    /**
     * 待推送 / 推送失败的块（对账重试用）
     *
     * @param limit 单次最多取多少条（避免一次推太多拖垮 FastAPI）
     */
    @Select("""
            SELECT id, source_type, source_id, chunk_no, title, content,
                   exam_category_id, subject_id, knowledge_id, difficulty,
                   ref_type, ref_id, page_no, content_hash, source_updated_at, status
            FROM ai_chunk
            WHERE status = 'ACTIVE'
              AND (vector_synced IS NULL OR vector_synced <> 1)
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<AiChunk> selectVectorPending(@Param("limit") int limit);

    /** ACTIVE 且已向量化的块数（核心一致性断言：应等于 Qdrant points 数） */
    @Select("SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE' AND vector_synced = 1")
    long countVectorSynced();

    /** 全量重建前把所有块重置为待同步 */
    @Update("""
            UPDATE ai_chunk
            SET vector_synced = 0, vector_synced_at = NULL
            WHERE status = 'ACTIVE' AND (vector_synced IS NULL OR vector_synced <> 0)
            """)
    int resetAllVectorPending();
}
