package com.examprep.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 管理端数据统计增强（审计 B5）：热门科目 / 活跃用户 / 各题型正确率
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final JdbcTemplate jdbcTemplate;

    /** 热门科目 TOP10：按资料数与下载量 */
    public List<Map<String, Object>> hotSubjects() {
        return jdbcTemplate.queryForList("""
                SELECT s.name AS name,
                       COUNT(r.id) AS resourceCount,
                       IFNULL(SUM(r.download_count), 0) AS downloadCount
                FROM subject s
                LEFT JOIN resource r ON r.subject_id = s.id AND r.is_deleted = 0
                GROUP BY s.id, s.name
                ORDER BY downloadCount DESC, resourceCount DESC
                LIMIT 10
                """);
    }

    /** 活跃用户 TOP10：按练习次数 */
    public List<Map<String, Object>> activeUsers() {
        return jdbcTemplate.queryForList("""
                SELECT u.nickname AS nickname,
                       COUNT(p.id) AS practiceCount,
                       IFNULL(SUM(p.correct_count), 0) AS correctTotal
                FROM `user` u
                JOIN practice_record p ON p.user_id = u.id
                GROUP BY u.id, u.nickname
                ORDER BY practiceCount DESC
                LIMIT 10
                """);
    }

    /** 各题型正确率：基于答题明细 */
    public List<Map<String, Object>> typeAccuracy() {
        return jdbcTemplate.queryForList("""
                SELECT q.type AS type,
                       COUNT(*) AS total,
                       SUM(CASE WHEN d.is_correct = 1 THEN 1 ELSE 0 END) AS correct,
                       ROUND(SUM(CASE WHEN d.is_correct = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 1) AS accuracy
                FROM answer_detail d
                JOIN question q ON q.id = d.question_id
                WHERE d.user_answer IS NOT NULL AND d.is_correct IS NOT NULL
                GROUP BY q.type
                ORDER BY total DESC
                """);
    }
}
