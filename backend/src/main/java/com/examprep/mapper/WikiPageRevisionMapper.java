package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.WikiPageRevision;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Wiki 草稿修订 Mapper
 */
public interface WikiPageRevisionMapper extends BaseMapper<WikiPageRevision> {

    @Select("SELECT * FROM wiki_page_revision WHERE slug = #{slug} AND status = 'PENDING' LIMIT 1")
    WikiPageRevision selectPendingBySlug(@Param("slug") String slug);

    @Select("SELECT COUNT(*) FROM wiki_page_revision WHERE status = 'PENDING'")
    long countPending();

    @Select("SELECT * FROM wiki_page_revision WHERE task_id = #{taskId} ORDER BY id")
    List<WikiPageRevision> selectByTaskId(@Param("taskId") Long taskId);

    /** 审核通过（终态守卫：只有 PENDING 能流转） */
    @Update("UPDATE wiki_page_revision SET status = 'APPROVED', page_id = #{pageId}, "
            + "reviewer_id = #{reviewerId}, review_note = #{note}, reviewed_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markApproved(@Param("id") Long id, @Param("pageId") Long pageId,
                     @Param("reviewerId") Long reviewerId, @Param("note") String note);

    /** 驳回 */
    @Update("UPDATE wiki_page_revision SET status = 'REJECTED', "
            + "reviewer_id = #{reviewerId}, review_note = #{note}, reviewed_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markRejected(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
                     @Param("note") String note);
}
