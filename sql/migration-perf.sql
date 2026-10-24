-- ============================================================
-- 性能优化增量迁移：资料列表组合索引 + 热度分物化列（可重复执行）
--
-- 背景：
--   1) 资料列表固定按 audit_status='APPROVED' 过滤，再 ORDER BY created_at
--      或「推荐加权表达式」排序。原先只有单列索引（idx_res_status / idx_res_subject），
--      过滤能命中索引但排序仍需 filesort。
--   2) 推荐加权是含 DATEDIFF(NOW(), created_at) 的表达式，索引永远用不上；
--      物化成 hot_score 列后可按列排序走索引。
--
-- 特性：全部幂等（information_schema 判存 + 动态 DDL），重复执行不报错。
-- 执行：mysql -uroot -p exam_prep < sql/migration-perf.sql
-- ============================================================

USE exam_prep;

-- ---------- 1. 热度分物化列 ----------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'resource' AND COLUMN_NAME = 'hot_score');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `resource` ADD COLUMN `hot_score` DECIMAL(14,6) NOT NULL DEFAULT 0 COMMENT ''推荐热度分（定时任务物化，替代含 NOW() 的排序表达式）'' AFTER `rating_count`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 组合索引：过滤 + 排序一趟走完 ----------
-- (audit_status, created_at)：默认「最新」排序
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'resource' AND INDEX_NAME = 'idx_res_status_created');
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE `resource` ADD INDEX `idx_res_status_created` (`audit_status`, `created_at`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- (audit_status, hot_score)：推荐排序
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'resource' AND INDEX_NAME = 'idx_res_status_hot');
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE `resource` ADD INDEX `idx_res_status_hot` (`audit_status`, `hot_score`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 首次回填热度分（与 Java 定时任务同一公式） ----------
UPDATE `resource`
SET `hot_score` = (download_count * 2 + IF(rating_count >= 3, IFNULL(rating_avg, 0) * LEAST(rating_count, 5), 0))
                  / (DATEDIFF(NOW(), created_at) + 7)
WHERE audit_status = 'APPROVED' AND is_deleted = 0;

-- ---------- 迁移后自检 ----------
-- SHOW INDEX FROM resource WHERE Key_name IN ('idx_res_status_created','idx_res_status_hot');
-- SELECT id, title, download_count, rating_avg, hot_score FROM resource
--   WHERE audit_status='APPROVED' ORDER BY hot_score DESC LIMIT 10;
