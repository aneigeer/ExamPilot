package com.examprep.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入状态统计（管理端 GET /admin/data/import-stats）
 *
 * <p>数据源是 {@code question_source} 表（由另一 agent 建立，本类<b>只读、不建表、不改表</b>）。
 * 契约列：id, source_path, file_name, file_ext, file_size, sha256, exam_code,
 * subject_name, year, paper_name, doc_kind, text_layer, parse_status,
 * question_count, chunk_count, note, created_at, updated_at。
 *
 * <p><b>防御性设计</b>：question_source 可能尚未建表、或建到一半（列不全）。
 * 两种情况都返回「空结构」而不是抛 500：
 * <ul>
 *   <li>先用 information_schema.TABLES 判断表是否存在，不存在直接返回空结构；</li>
 *   <li>表存在但聚合 SQL 报 {@link DataAccessException}（列没建全等）也降级为空结构。</li>
 * </ul>
 * 用 JdbcTemplate 原生 SQL 而非 MyBatis-Plus Mapper，是因为本轮不为该表建实体 / Mapper，
 * 且 JdbcTemplate 已是项目既有依赖（见 AdminStatsService），零新增依赖。
 */
@Service
@RequiredArgsConstructor
public class ImportStatsService {

    private final JdbcTemplate jdbcTemplate;

    /** 空结构：available=false + 空列表 + 0 + null，供前端直接渲染 */
    private static Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("total", 0L);
        out.put("byParseStatus", List.of());
        out.put("byExamCode", List.of());
        out.put("byYear", List.of());
        out.put("lastImportAt", null);
        return out;
    }

    /**
     * @return {
     *   available: boolean,                 // question_source 表是否可用
     *   total: long,                        // 源文件总数
     *   byParseStatus: [{key, count}],      // 按 parse_status 聚合
     *   byExamCode:    [{key, count}],      // 按 exam_code 聚合
     *   byYear:        [{key, count}],      // 按 year 聚合（key 为年份数字），倒序
     *   lastImportAt:  "yyyy-MM-dd HH:mm:ss" | null   // 最近导入时间 = MAX(updated_at, created_at)
     * }
     */
    public Map<String, Object> stats() {
        // 1. 表存在性判断（question_source 由另一 agent 建立，本轮可能还没建好）
        Integer tableExists;
        try {
            tableExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source'",
                    Integer.class);
        } catch (DataAccessException e) {
            return empty();
        }
        if (tableExists == null || tableExists == 0) {
            return empty();
        }

        // 2. 表存在：聚合。任何 SQL 异常（列未建全等）一律降级为空结构，接口不 500
        try {
            Map<String, Object> out = empty();
            out.put("available", true);

            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM `question_source`", Long.class);
            out.put("total", total == null ? 0L : total);

            out.put("byParseStatus", jdbcTemplate.queryForList(
                    "SELECT COALESCE(parse_status, 'UNKNOWN') AS `key`, COUNT(*) AS `count` "
                            + "FROM `question_source` "
                            + "GROUP BY COALESCE(parse_status, 'UNKNOWN') ORDER BY `count` DESC"));

            out.put("byExamCode", jdbcTemplate.queryForList(
                    "SELECT COALESCE(exam_code, 'UNKNOWN') AS `key`, COUNT(*) AS `count` "
                            + "FROM `question_source` "
                            + "GROUP BY COALESCE(exam_code, 'UNKNOWN') ORDER BY `count` DESC"));

            out.put("byYear", jdbcTemplate.queryForList(
                    "SELECT `year` AS `key`, COUNT(*) AS `count` FROM `question_source` "
                            + "WHERE `year` IS NOT NULL GROUP BY `year` ORDER BY `year` DESC"));

            out.put("lastImportAt", jdbcTemplate.queryForObject(
                    "SELECT DATE_FORMAT(MAX(COALESCE(updated_at, created_at)), '%Y-%m-%d %H:%i:%s') "
                            + "FROM `question_source`", String.class));
            return out;
        } catch (DataAccessException e) {
            return empty();
        }
    }
}
