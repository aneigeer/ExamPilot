package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiAgentSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 会话 Mapper
 */
public interface AiAgentSessionMapper extends BaseMapper<AiAgentSession> {

    @Select("SELECT * FROM ai_agent_session WHERE session_id = #{sessionId} LIMIT 1")
    AiAgentSession selectBySessionId(@Param("sessionId") String sessionId);

    /** 原子追加一轮：turn_count + 1，摘要整体覆盖为截断后的滚动文本 */
    @Update("UPDATE ai_agent_session SET summary = #{summary}, turn_count = turn_count + 1, "
            + "last_question = #{question}, updated_at = NOW() WHERE session_id = #{sessionId}")
    int recordTurn(@Param("sessionId") String sessionId, @Param("summary") String summary,
                   @Param("question") String question);
}
