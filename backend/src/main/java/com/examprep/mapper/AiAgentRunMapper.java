package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiAgentRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 运行轨迹 Mapper
 */
public interface AiAgentRunMapper extends BaseMapper<AiAgentRun> {

    /** 同一会话最近若干成功轮次（倒序取，服务层再反转成时间正序） */
    @Select("SELECT * FROM ai_agent_run WHERE session_id = #{sessionId} AND user_id = #{userId} "
            + "AND status IN ('SUCCESS','REFUSED') AND answer IS NOT NULL AND answer <> '' "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<AiAgentRun> selectRecentTurns(@Param("sessionId") String sessionId,
                                       @Param("userId") Long userId,
                                       @Param("limit") int limit);
}
