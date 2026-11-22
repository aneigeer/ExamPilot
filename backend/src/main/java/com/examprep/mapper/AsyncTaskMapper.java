package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AsyncTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 异步任务 Mapper（全注解 SQL）
 */
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {

    /** 查同类型活动任务（快速失败用；唯一性最终以 DB UNIQUE 为准） */
    @Select("SELECT * FROM async_task WHERE type = #{type} "
            + "AND status IN ('PENDING','RUNNING') LIMIT 1")
    AsyncTask selectActiveByType(@Param("type") String type);

    /**
     * 启动恢复：把「已开始执行」的残留 RUNNING 置 FAILED
     *
     * <p>只处理 {@code started_at IS NOT NULL}：从未启动的 PENDING 不动，
     * 留给 worker 消费，避免与 XAUTOCLAIM 续跑语义混淆。
     */
    @Update("UPDATE async_task SET status = 'FAILED', error = #{error}, finished_at = NOW() "
            + "WHERE status = 'RUNNING' AND started_at IS NOT NULL")
    int failStaleRunning(@Param("error") String error);

    /** 进度回调：PENDING → RUNNING（首次回调时写 started_at） */
    @Update("UPDATE async_task SET status = 'RUNNING', started_at = IFNULL(started_at, NOW()), "
            + "progress = #{progress} WHERE id = #{id} AND status IN ('PENDING','RUNNING')")
    int markProgress(@Param("id") Long id, @Param("progress") Integer progress);

    /** 完成回调（终态守卫：已是 FAILED/CANCELLED/SUCCESS 时不再改写） */
    @Update("UPDATE async_task SET status = 'SUCCESS', progress = 100, result = #{result}, "
            + "finished_at = NOW() WHERE id = #{id} "
            + "AND status NOT IN ('SUCCESS','FAILED','CANCELLED')")
    int markSuccess(@Param("id") Long id, @Param("result") String result);

    /** 失败回调（终态守卫同上） */
    @Update("UPDATE async_task SET status = 'FAILED', error = #{error}, finished_at = NOW() "
            + "WHERE id = #{id} AND status NOT IN ('SUCCESS','FAILED','CANCELLED')")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** 按幂等键查最近一条任务（唯一索引保证最多一条） */
    @org.apache.ibatis.annotations.Select("SELECT * FROM async_task WHERE idempotency_key = #{key} LIMIT 1")
    AsyncTask selectByIdempotencyKey(@org.apache.ibatis.annotations.Param("key") String key);
}
