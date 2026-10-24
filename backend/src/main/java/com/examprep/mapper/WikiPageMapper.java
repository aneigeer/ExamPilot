package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examprep.entity.WikiPage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Wiki 主题页 Mapper（全注解 SQL）
 */
public interface WikiPageMapper extends BaseMapper<WikiPage> {

    /** 已发布页面分页（学生端 / 管理端页面列表） */
    @Select("<script>"
            + "SELECT * FROM wiki_page WHERE status = 'PUBLISHED' "
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if> "
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "      OR summary LIKE CONCAT('%', #{keyword}, '%'))</if> "
            + "ORDER BY updated_at DESC, id DESC"
            + "</script>")
    IPage<WikiPage> selectPublished(Page<WikiPage> page,
                                    @Param("subjectId") Long subjectId,
                                    @Param("keyword") String keyword);

    /** 管理端全状态分页 */
    @Select("<script>"
            + "SELECT * FROM wiki_page WHERE 1 = 1 "
            + "<if test='status != null and status != \"\"'> AND status = #{status}</if> "
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "      OR slug LIKE CONCAT('%', #{keyword}, '%'))</if> "
            + "ORDER BY updated_at DESC, id DESC"
            + "</script>")
    IPage<WikiPage> selectAdmin(Page<WikiPage> page,
                                @Param("status") String status,
                                @Param("keyword") String keyword);

    /**
     * ngram 全文召回（热层的字面通道）
     *
     * <p>与向量通道并存：向量负责语义（"内存不够用" → "动态分配内存"），
     * ngram 负责专有名词 / 数字 / 代码标识符。ft_score 是 MySQL 相关度，非 0-1。
     */
    @Select("<script>"
            + "SELECT *, MATCH(title, summary, key_points) AGAINST(#{q} IN NATURAL LANGUAGE MODE) AS ft_score "
            + "FROM wiki_page WHERE status = 'PUBLISHED' "
            + "AND MATCH(title, summary, key_points) AGAINST(#{q} IN NATURAL LANGUAGE MODE) "
            + "<if test='subjectId != null'> AND subject_id = #{subjectId}</if> "
            + "ORDER BY ft_score DESC LIMIT #{limit}"
            + "</script>")
    List<WikiPage> searchFulltext(@Param("q") String q,
                                  @Param("subjectId") Long subjectId,
                                  @Param("limit") int limit);

    /** 已发布页面数（命中缓存 / 空库短路） */
    @Select("SELECT COUNT(*) FROM wiki_page WHERE status = 'PUBLISHED'")
    long countPublished();

    /** 页面目录（蒸馏时给 LLM 参考，让它按真实 slug 建链接） */
    @Select("SELECT id, slug, title, subject_id, knowledge_id, summary, status, version "
            + "FROM wiki_page WHERE status = 'PUBLISHED' ORDER BY subject_id, id LIMIT #{limit}")
    List<WikiPage> selectCatalog(@Param("limit") int limit);

    /** 同一科目下的已发布页面（审核建同级链接用） */
    @Select("SELECT * FROM wiki_page WHERE status = 'PUBLISHED' AND subject_id = #{subjectId} "
            + "ORDER BY knowledge_id, id")
    List<WikiPage> selectPublishedBySubject(@Param("subjectId") Long subjectId);

    @Select("SELECT * FROM wiki_page WHERE slug = #{slug} LIMIT 1")
    WikiPage selectBySlug(@Param("slug") String slug);

    /** 全量已发布页面（重建 Wiki 向量索引用） */
    @Select("SELECT * FROM wiki_page WHERE status = 'PUBLISHED' ORDER BY id")
    List<WikiPage> selectAllPublished();
}
