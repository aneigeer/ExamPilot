package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiInterviewQa;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 面试逐题作答 Mapper
 *
 * <p><b>为什么有一个批量统计</b>：历史列表要显示每场的「实评题数」。
 * 逐场 {@code COUNT(*)} 就是 N+1（一页 10 场 = 11 次查询）；
 * 这里用一条 {@code IN + GROUP BY} 查回来在内存里拼，列表只打 2 次库。
 */
@Mapper
public interface AiInterviewQaMapper extends BaseMapper<AiInterviewQa> {

    /**
     * 批量统计各会话的作答条数
     *
     * @param sessionIds 会话 ID 列表（调用方保证非空）
     * @return 每行 {@code sessionId} / {@code cnt}
     */
    @Select("""
            <script>
            SELECT session_id AS sessionId, COUNT(*) AS cnt
            FROM ai_interview_qa
            WHERE session_id IN
            <foreach collection="sessionIds" item="sid" open="(" separator="," close=")">
              #{sid}
            </foreach>
            GROUP BY session_id
            </script>
            """)
    List<Map<String, Object>> countBySessions(@Param("sessionIds") List<Long> sessionIds);
}
