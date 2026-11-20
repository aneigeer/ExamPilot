package com.examprep.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 数据中台聚合查询
 *
 * <p>所有统计 SQL 集中在此，口径统一由 {@code DataHubService.metricDictionary()} 对外说明。
 * 约定：
 * <ul>
 *   <li>列别名一律用 camelCase，避免不同驱动对别名大小写的处理差异；</li>
 *   <li>题量统计必须显式写 {@code is_deleted = 0}；</li>
 *   <li>聚合结果统一返回 {@code List<Map>}，由服务层做数值兜底（COUNT 空窗口返回 0 而非 NULL）。</li>
 * </ul>
 */
@Mapper
public interface DataHubMapper {

    // ==================================================================
    // ADS：日指标与覆盖矩阵
    // ==================================================================

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM practice_record WHERE DATE(started_at) = #{date}) AS practiceCount,
              (SELECT COUNT(*) FROM exam_record WHERE DATE(started_at) = #{date}) AS mockCount,
              (SELECT COALESCE(SUM(correct_count), 0) FROM practice_record WHERE DATE(started_at) = #{date}) AS correctSum,
              (SELECT COALESCE(SUM(answered_count), 0) FROM practice_record WHERE DATE(started_at) = #{date}) AS answeredSum,
              (SELECT COUNT(*) FROM ai_agent_run WHERE DATE(created_at) = #{date}) AS aiAskCount,
              (SELECT COUNT(*) FROM ai_agent_run WHERE DATE(created_at) = #{date} AND status = 'REFUSED') AS aiRefusedCount,
              (SELECT COUNT(*) FROM ai_agent_run WHERE DATE(created_at) = #{date} AND degraded = 1) AS aiDegradedCount,
              (SELECT COUNT(*) FROM (
                  SELECT user_id AS uid FROM practice_record WHERE DATE(started_at) = #{date}
                  UNION
                  SELECT user_id FROM exam_record WHERE DATE(started_at) = #{date}
                  UNION
                  SELECT user_id FROM ai_agent_run WHERE DATE(created_at) = #{date} AND user_id IS NOT NULL
              ) u) AS activeUsers,
              (SELECT COUNT(*) FROM question WHERE is_deleted = 0 AND audit_status = 'APPROVED') AS questionApprovedTotal,
              (SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE') AS chunkActiveTotal
            """)
    Map<String, Object> dailyMetrics(@Param("date") LocalDate date);

    /**
     * 科目覆盖矩阵：以 subject 为主表，左连考试类别取 exam_code；
     * 题量 / 向量块 / 课件 / 知识点均按 subject_id 聚合（子查询避免多表 JOIN 乘数）。
     */
    @Select("""
            SELECT COALESCE(ec.code, '') AS examCode,
                   s.id                   AS subjectId,
                   s.name                 AS subjectName,
                   (SELECT COUNT(*) FROM question q
                     WHERE q.subject_id = s.id AND q.is_deleted = 0) AS liveQuestionCount,
                   (SELECT COUNT(*) FROM question q
                     WHERE q.subject_id = s.id AND q.is_deleted = 0
                       AND q.audit_status = 'APPROVED') AS approvedQuestionCount,
                   (SELECT COUNT(*) FROM question q
                     WHERE q.subject_id = s.id AND q.is_deleted = 0
                       AND q.audit_status = 'PENDING') AS pendingQuestionCount,
                   (SELECT COUNT(*) FROM ai_chunk c
                     WHERE c.subject_id = s.id AND c.status = 'ACTIVE'
                       AND c.source_type = 'QUESTION') AS chunkActiveCount,
                   (SELECT COUNT(*) FROM courseware w
                     WHERE w.subject_id = s.id AND w.is_deleted = 0) AS coursewareCount,
                   (SELECT COUNT(*) FROM knowledge_point k
                     WHERE k.subject_id = s.id AND k.is_deleted = 0) AS knowledgeCount
            FROM subject s
            LEFT JOIN exam_category ec ON ec.id = s.exam_category_id
            WHERE s.is_deleted = 0 AND s.status = 1
            ORDER BY examCode, s.id
            """)
    List<Map<String, Object>> subjectCoverage();

    // ==================================================================
    // 资产总览
    // ==================================================================

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM question) AS questionTotalPhysical,
              (SELECT COUNT(*) FROM question WHERE is_deleted = 0) AS questionLive,
              (SELECT COUNT(*) FROM question WHERE is_deleted = 0 AND audit_status = 'APPROVED') AS questionApproved,
              (SELECT COUNT(*) FROM question WHERE is_deleted = 0 AND audit_status = 'PENDING') AS questionPending,
              (SELECT COUNT(*) FROM ai_chunk) AS chunkTotal,
              (SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE') AS chunkActive,
              (SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE' AND vector_synced = 1) AS chunkVectorSynced,
              (SELECT COUNT(*) FROM subject WHERE is_deleted = 0) AS subjectTotal,
              (SELECT COUNT(*) FROM courseware WHERE is_deleted = 0) AS coursewareTotal,
              (SELECT COUNT(*) FROM knowledge_point WHERE is_deleted = 0) AS knowledgeTotal,
              (SELECT COUNT(*) FROM data_quality_issue WHERE status = 'OPEN') AS openIssueTotal,
              (SELECT COUNT(*) FROM data_quality_issue WHERE status = 'OPEN' AND severity = 'CRITICAL') AS openCriticalTotal,
              (SELECT COUNT(*) FROM data_quality_issue WHERE status = 'OPEN' AND severity = 'WARN') AS openWarnTotal,
              (SELECT COUNT(*) FROM data_quality_issue WHERE status = 'OPEN' AND severity = 'INFO') AS openInfoTotal,
              (SELECT MAX(finished_at) FROM data_pipeline_job
                WHERE job_type = 'METRICS_ETL' AND status = 'SUCCESS') AS lastEtlAt
            """)
    Map<String, Object> assetOverview();

    // ==================================================================
    // 质量巡检 SQL
    // ==================================================================

    /** 同科目 + 同类别下题干完全重复：返回「非保留项」的每一道题 */
    @Select("""
            SELECT id AS questionId, subjectId, examCode, dupCount, keeperId, stemSample FROM (
              SELECT q.id AS id,
                     q.subject_id AS subjectId,
                     COALESCE(q.exam_code, '') AS examCode,
                     COUNT(*) OVER (PARTITION BY q.subject_id, COALESCE(q.exam_code, ''), MD5(TRIM(q.stem))) AS dupCount,
                     FIRST_VALUE(q.id) OVER (PARTITION BY q.subject_id, COALESCE(q.exam_code, ''), MD5(TRIM(q.stem))
                                             ORDER BY q.id) AS keeperId,
                     LEFT(q.stem, 120) AS stemSample
              FROM question q
              WHERE q.is_deleted = 0 AND q.parent_id = 0
                AND q.stem IS NOT NULL AND TRIM(q.stem) <> ''
            ) t
            WHERE t.dupCount > 1 AND t.id <> t.keeperId
            ORDER BY t.dupCount DESC, t.id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> duplicateStemQuestions(@Param("limit") int limit);

    /** APPROVED 客观题缺答案 / 缺选项 */
    @Select("""
            SELECT q.id AS questionId, q.subject_id AS subjectId, q.type AS type
            FROM question q
            WHERE q.is_deleted = 0 AND q.audit_status = 'APPROVED'
              AND (
                (q.type IN ('SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE')
                  AND NOT EXISTS (SELECT 1 FROM question_option o
                                   WHERE o.question_id = q.id AND o.is_answer = 1))
                OR (q.type = 'FILL_BLANK'
                  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id = q.id))
              )
            ORDER BY q.id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> missingAnswerQuestions(@Param("limit") int limit);

    /** APPROVED 存活题没有 ACTIVE 向量块 */
    @Select("""
            SELECT q.id AS questionId, q.subject_id AS subjectId
            FROM question q
            WHERE q.is_deleted = 0 AND q.audit_status = 'APPROVED'
              AND NOT EXISTS (SELECT 1 FROM ai_chunk c
                               WHERE c.source_type = 'QUESTION' AND c.source_id = q.id
                                 AND c.status = 'ACTIVE')
            ORDER BY q.id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> noChunkQuestions(@Param("limit") int limit);

    /** ACTIVE 块指向已删除 / 不存在的题目、资料、课件、知识点（按来源类型聚合） */
    @Select("""
            SELECT c.source_type AS sourceType,
                   COUNT(*)      AS chunkCount,
                   MIN(c.source_id) AS sampleId
            FROM ai_chunk c
            LEFT JOIN question q ON c.source_type = 'QUESTION' AND q.id = c.source_id AND q.is_deleted = 0
            LEFT JOIN resource r ON c.source_type = 'RESOURCE' AND r.id = c.source_id AND r.is_deleted = 0
            LEFT JOIN courseware w ON c.source_type = 'COURSEWARE' AND w.id = c.source_id AND w.is_deleted = 0
            LEFT JOIN knowledge_point k ON c.source_type = 'KNOWLEDGE' AND k.id = c.source_id AND k.is_deleted = 0
            WHERE c.status = 'ACTIVE'
              AND ((c.source_type = 'QUESTION' AND q.id IS NULL)
                OR (c.source_type = 'RESOURCE' AND r.id IS NULL)
                OR (c.source_type = 'COURSEWARE' AND w.id IS NULL)
                OR (c.source_type = 'KNOWLEDGE' AND k.id IS NULL))
            GROUP BY c.source_type
            ORDER BY chunkCount DESC
            """)
    List<Map<String, Object>> orphanVectorStats();

    /** OCR 积压：OCR_QUEUED / FAILED 的来源文件数 */
    @Select("""
            SELECT parse_status AS parseStatus, COUNT(*) AS sourceCount
            FROM question_source
            WHERE parse_status IN ('OCR_QUEUED', 'FAILED')
            GROUP BY parse_status
            """)
    List<Map<String, Object>> ocrBacklogStats();

    /** 启用但没有任何存活题目的科目 */
    @Select("""
            SELECT s.id AS subjectId, s.name AS subjectName, COALESCE(ec.code, '') AS examCode
            FROM subject s
            LEFT JOIN exam_category ec ON ec.id = s.exam_category_id
            WHERE s.is_deleted = 0 AND s.status = 1
              AND NOT EXISTS (SELECT 1 FROM question q WHERE q.subject_id = s.id AND q.is_deleted = 0)
            ORDER BY s.id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> emptySubjects(@Param("limit") int limit);

    // ==================================================================
    // 流水线
    // ==================================================================

    @Select("""
            SELECT id, job_type AS jobType, source_ref AS sourceRef, status,
                   total_rows AS totalRows, inserted_rows AS insertedRows, dedup_rows AS dedupRows,
                   failed_rows AS failedRows, error_summary AS errorSummary,
                   started_at AS startedAt, finished_at AS finishedAt
            FROM data_pipeline_job
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> recentJobs(@Param("limit") int limit);
}
