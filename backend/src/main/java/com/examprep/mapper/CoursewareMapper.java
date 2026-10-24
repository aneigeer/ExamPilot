package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.Courseware;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 课件元信息 Mapper
 *
 * <p><b>批量统计 chunk 数</b>：列表页要展示 634 份课件各自的块数，
 * 若逐条 count 就是 634 次查询（N+1）。这里用一条 GROUP BY 查回来在内存里拼，
 * 列表渲染只打 2 次库。
 */
@Mapper
public interface CoursewareMapper extends BaseMapper<Courseware> {

    /**
     * 按来源类型批量统计各来源的 ACTIVE 块数
     *
     * @param sourceType 来源类型（COURSEWARE）
     * @return 每行 {@code sourceId} / {@code cnt}
     */
    @Select("""
            SELECT source_id AS sourceId, COUNT(*) AS cnt
            FROM ai_chunk
            WHERE source_type = #{sourceType} AND status = 'ACTIVE'
            GROUP BY source_id
            """)
    List<Map<String, Object>> countChunksGroupBySource(@Param("sourceType") String sourceType);
}
