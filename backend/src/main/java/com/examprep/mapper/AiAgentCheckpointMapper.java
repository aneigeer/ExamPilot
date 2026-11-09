package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiAgentCheckpoint;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent 步骤检查点 Mapper
 *
 * <p>写入走 {@link #insertCheckpoint}（显式 SQL，时间戳由 DB 生成）；
 * 恢复标记走 {@link #markResumed}（<b>条件更新</b>，靠影响行数判断"是否已被恢复过"）。
 */
public interface AiAgentCheckpointMapper extends BaseMapper<AiAgentCheckpoint> {

    /**
     * 落一条检查点。
     *
     * <p>{@code (run_id, step_no)} 唯一：同一 run 的同一步不可能写两条，
     * 事件重放导致的重复落库会被唯一键挡下（调用方已 try/catch，不会影响会话）。
     *
     * @param runId       运行 ID
     * @param sessionId   会话 ID
     * @param stepNo      步骤序号（1-based）
     * @param payloadJson 摘要 JSON（不含原文）
     * @return 受影响行数
     */
    @Insert("INSERT INTO ai_agent_checkpoint (run_id, session_id, step_no, payload_json, status) "
            + "VALUES (#{runId}, #{sessionId}, #{stepNo}, #{payloadJson}, 'RECORDED')")
    int insertCheckpoint(@Param("runId") Long runId,
                         @Param("sessionId") String sessionId,
                         @Param("stepNo") Integer stepNo,
                         @Param("payloadJson") String payloadJson);

    /**
     * 某 run 的全部检查点（按 step 升序）。
     *
     * @param runId 运行 ID
     * @return 检查点列表
     */
    @Select("SELECT * FROM ai_agent_checkpoint WHERE run_id = #{runId} ORDER BY step_no ASC, id ASC")
    List<AiAgentCheckpoint> selectByRunId(@Param("runId") Long runId);

    /**
     * 某 run 的检查点条数（判断"有没有可恢复的痕迹"）。
     *
     * @param runId 运行 ID
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM ai_agent_checkpoint WHERE run_id = #{runId}")
    long countByRunId(@Param("runId") Long runId);

    /**
     * 把某 run 的 RECORDED 检查点标记为 RESUMED。
     *
     * <p><b>返回 0 就代表"已经被恢复过"</b>——这就是幂等闸门，不需要额外列或加锁。
     *
     * @param runId 运行 ID
     * @return 受影响行数
     */
    @Update("UPDATE ai_agent_checkpoint SET status = 'RESUMED' "
            + "WHERE run_id = #{runId} AND status = 'RECORDED'")
    int markResumed(@Param("runId") Long runId);

    /**
     * 回滚恢复标记（启动失败时用，避免"恢复失败却把唯一一次机会用掉了"）。
     *
     * @param runId 运行 ID
     * @return 受影响行数
     */
    @Update("UPDATE ai_agent_checkpoint SET status = 'RECORDED' "
            + "WHERE run_id = #{runId} AND status = 'RESUMED'")
    int revertResumed(@Param("runId") Long runId);
}
