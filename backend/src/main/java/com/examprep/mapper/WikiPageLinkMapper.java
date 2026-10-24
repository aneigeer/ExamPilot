package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.WikiPage;
import com.examprep.entity.WikiPageLink;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Wiki 页面链接 Mapper
 */
public interface WikiPageLinkMapper extends BaseMapper<WikiPageLink> {

    @Insert("INSERT IGNORE INTO wiki_page_link(from_page_id, to_page_id, relation, weight) "
            + "VALUES(#{fromPageId}, #{toPageId}, #{relation}, #{weight})")
    int insertIgnore(@Param("fromPageId") Long fromPageId, @Param("toPageId") Long toPageId,
                     @Param("relation") String relation, @Param("weight") double weight);

    @Delete("DELETE FROM wiki_page_link WHERE from_page_id = #{pageId}")
    int deleteByFrom(@Param("pageId") Long pageId);

    /** 出边目标页（只返回已发布页面，避免悬空边把用户带到 404） */
    @Select("SELECT p.* FROM wiki_page_link l JOIN wiki_page p ON p.id = l.to_page_id "
            + "WHERE l.from_page_id = #{pageId} AND p.status = 'PUBLISHED' "
            + "ORDER BY l.weight DESC, p.id")
    List<WikiPage> selectLinkedPages(@Param("pageId") Long pageId);

    /** 入边来源页（详情页「被引用」反向导航） */
    @Select("SELECT p.* FROM wiki_page_link l JOIN wiki_page p ON p.id = l.from_page_id "
            + "WHERE l.to_page_id = #{pageId} AND p.status = 'PUBLISHED' "
            + "ORDER BY l.weight DESC, p.id")
    List<WikiPage> selectBacklinks(@Param("pageId") Long pageId);

    /** 全量链接（导航图） */
    @Select("SELECT * FROM wiki_page_link ORDER BY from_page_id, to_page_id")
    List<WikiPageLink> selectAllLinks();
}
