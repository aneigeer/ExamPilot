package com.examprep.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examprep.entity.AiAgentRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行轨迹 Mapper
 *
 * <p>项目约定：<b>不用 XML</b>，聚合 SQL 一律 {@code @Select} 注解。
 * 指标口径见 {@code docs/Agent阶段三接口契约.md} §2，所有查询共用同一组窗口边界
 * {@code created_at >= #{from} AND created_at <= #{to}}（{@code to} 取自 {@link #selectNow()}）。
 *
 * <p>禁用字符串拼接 days：窗口边界由服务层算好后以 {@code #{from}/#{to}} 绑定参数传入。
 */
public interface AiAgentRunMapper extends BaseMapper<AiAgentRun> {

    /** 同一会话最近若干成功轮次（倒序取，服务层再反转成时间正序） */
    @Select("SELECT * FROM ai_agent_run WHERE session_id = #{sessionId} AND user_id = #{userId} "
            + "AND status IN ('SUCCESS','REFUSED') AND answer IS NOT NULL AND answer <> '' "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<AiAgentRun> selectRecentTurns(@Param("sessionId") String sessionId,
                                       @Param("userId") Long userId,
                                       @Param("limit") int limit);

    /**
     * 数据库当前时间（指标窗口终点，保证所有子查询共用同一边界，避免多次 NOW() 漂移）。
     *
     * @return DB 时区的当前时间
     */
    @Select("SELECT NOW()")
    LocalDateTime selectNow();

    /**
     * 一次扫描出全部计数与耗时聚合（空窗口仍返回一行：total=0，SUM/AVG/MAX 为 NULL）。
     *
     * <p>返回列：{@code total, running, successCnt, refusedCnt, failedCnt, cancelledCnt,
     * degradedCount, degradedDenominator, samples, avgMs, maxMs}。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）
     * @return 单行聚合结果
     */
    @Select("SELECT COUNT(*) AS total, "
            + "SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS running, "
            + "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCnt, "
            + "SUM(CASE WHEN status = 'REFUSED' THEN 1 ELSE 0 END) AS refusedCnt, "
            + "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCnt, "
            + "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelledCnt, "
            + "SUM(CASE WHEN status IN ('SUCCESS','REFUSED') AND degraded = 1 THEN 1 ELSE 0 END) "
            + "  AS degradedCount, "
            + "SUM(CASE WHEN status IN ('SUCCESS','REFUSED') THEN 1 ELSE 0 END) "
            + "  AS degradedDenominator, "
            + "SUM(CASE WHEN status <> 'RUNNING' AND total_ms > 0 THEN 1 ELSE 0 END) AS samples, "
            + "ROUND(AVG(CASE WHEN status <> 'RUNNING' AND total_ms > 0 THEN total_ms END)) AS avgMs, "
            + "MAX(CASE WHEN status <> 'RUNNING' AND total_ms > 0 THEN total_ms END) AS maxMs, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "AVG(CASE WHEN total_tokens > 0 THEN total_tokens END) AS avgTokens, "
            + "COALESCE(SUM(estimated_cost_cny), 0) AS totalCost "
            + "FROM ai_agent_run WHERE created_at >= #{from} AND created_at <= #{to}")
    Map<String, Object> selectSummary(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /**
     * 整体 P95 耗时（MySQL 8 窗口函数 + 最近秩法：{@code rn = CEIL(0.95 * n)}）。
     *
     * <p>域 = 终态且 {@code total_ms > 0}；无样本返回 {@code null}。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）
     * @return P95 毫秒数，或 {@code null}
     */
    @Select("SELECT MIN(total_ms) FROM ("
            + "  SELECT total_ms, ROW_NUMBER() OVER (ORDER BY total_ms) AS rn, "
            + "         COUNT(*) OVER () AS cnt "
            + "  FROM ai_agent_run "
            + "  WHERE created_at >= #{from} AND created_at <= #{to} "
            + "    AND status <> 'RUNNING' AND total_ms > 0"
            + ") t WHERE rn = CEIL(cnt * 0.95)")
    Long selectP95(@Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);

    /**
     * 按 generator 分组计数与成功率/平均耗时（NULL 归入 unknown，最多 20 组）。
     *
     * <p>返回列：{@code generator, cnt, terminalCnt, successCnt, avgMs}。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）
     * @return 分组行
     */
    @Select("SELECT COALESCE(generator, 'unknown') AS generator, "
            + "COUNT(*) AS cnt, "
            + "SUM(CASE WHEN status <> 'RUNNING' THEN 1 ELSE 0 END) AS terminalCnt, "
            + "SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCnt, "
            + "ROUND(AVG(CASE WHEN status <> 'RUNNING' AND total_ms > 0 THEN total_ms END)) AS avgMs "
            + "FROM ai_agent_run WHERE created_at >= #{from} AND created_at <= #{to} "
            + "GROUP BY COALESCE(generator, 'unknown') "
            + "ORDER BY cnt DESC, generator ASC LIMIT 20")
    List<Map<String, Object>> selectGeneratorStats(@Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /**
     * 按 generator 分组的 P95 耗时（窗口函数按 generator 分区，最近秩法）。
     *
     * <p>返回列：{@code generator, p95Ms}；无样本的分组不会出现。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）
     * @return 分组 P95
     */
    @Select("SELECT generator, MIN(total_ms) AS p95Ms FROM ("
            + "  SELECT COALESCE(generator, 'unknown') AS generator, total_ms, "
            + "         ROW_NUMBER() OVER (PARTITION BY COALESCE(generator, 'unknown') "
            + "                            ORDER BY total_ms) AS rn, "
            + "         COUNT(*) OVER (PARTITION BY COALESCE(generator, 'unknown')) AS cnt "
            + "  FROM ai_agent_run "
            + "  WHERE created_at >= #{from} AND created_at <= #{to} "
            + "    AND status <> 'RUNNING' AND total_ms > 0"
            + ") t WHERE rn = CEIL(cnt * 0.95) GROUP BY generator")
    List<Map<String, Object>> selectGeneratorP95(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * 错误 Top5：域 = 终态且 {@code TRIM(error) <> ''}，按次数倒序、原文升序。
     *
     * <p>返回列：{@code error, cnt}。错误原文不做归一化（已知限制，见契约 §5.4）。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）
     * @return 错误行（最多 5 条）
     */
    @Select("SELECT error AS error, COUNT(*) AS cnt FROM ai_agent_run "
            + "WHERE created_at >= #{from} AND created_at <= #{to} "
            + "  AND status <> 'RUNNING' AND TRIM(COALESCE(error, '')) <> '' "
            + "GROUP BY error ORDER BY cnt DESC, error ASC LIMIT 5")
    List<Map<String, Object>> selectTopErrors(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    /**
     * 查询指定用户在时间窗口内的 Agent 运行次数与累计 Token（用于每日配额闸门）。
     */
    @Select("SELECT COUNT(*) AS requestCount, COALESCE(SUM(total_tokens), 0) AS tokenSum "
            + "FROM ai_agent_run WHERE user_id = #{userId} "
            + "AND created_at >= #{from} AND created_at <= #{to}")
    Map<String, Object> selectUserDailyUsage(@Param("userId") Long userId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * 分批删除保留期之前的运行轨迹（数据最小化）。
     *
     * <p>为什么带 {@code LIMIT}：一次性删几十万行会长时间持锁，把在线写入（SSE 落终态）
     * 一起拖住。调用方循环删到 0 为止，每批只锁很短时间。
     *
     * @param cutoff 删除早于该时间的行
     * @param batch  单批上限
     * @return 实际删除行数
     */
    @Delete("DELETE FROM ai_agent_run WHERE created_at < #{cutoff} LIMIT #{batch}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /**
     * 统计保留期之前的行数（只读，给管理端预览用）。
     *
     * @param cutoff 统计早于该时间的行
     * @return 行数
     */
    /** 按模型聚合 Token 与成本（P2 可观测性看板） */
    @Select("SELECT COALESCE(model_name, 'unknown') AS model, COUNT(*) AS runs, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(estimated_cost_cny), 0) AS cost "
            + "FROM ai_agent_run WHERE created_at >= #{from} AND created_at <= #{to} "
            + "AND total_tokens > 0 GROUP BY model_name ORDER BY tokens DESC LIMIT 10")
    List<Map<String, Object>> selectTokenByModel(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) FROM ai_agent_run WHERE created_at < #{cutoff}")
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
