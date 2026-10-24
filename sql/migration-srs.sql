-- ============================================================
-- 错题「间隔重复」（SRS）迁移：给 wrong_book 增加复习调度字段（可重复执行）
--
-- 设计：以「连续答对次数」为阶段 stage，间隔按 1/2/4/7/15/30/60 天递增；
--       答错即回到 stage=0 且次日再复习；stage 走完全部间隔后视为已掌握。
-- 执行：mysql -uroot -p exam_prep < sql/migration-srs.sql
-- ============================================================

USE exam_prep;

-- ---------- 1. 下次复习时间 ----------
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'wrong_book' AND COLUMN_NAME = 'next_review_at');
SET @sql = IF(@col = 0,
    'ALTER TABLE `wrong_book` ADD COLUMN `next_review_at` DATETIME DEFAULT NULL COMMENT ''下次复习时间（SRS 调度，NULL 视为立即到期）'' AFTER `mastered`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 复习阶段（连续答对次数） ----------
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'wrong_book' AND COLUMN_NAME = 'review_stage');
SET @sql = IF(@col = 0,
    'ALTER TABLE `wrong_book` ADD COLUMN `review_stage` INT NOT NULL DEFAULT 0 COMMENT ''SRS 复习阶段：连续答对次数，决定下次间隔'' AFTER `next_review_at`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 待复习查询索引 ----------
SET @idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'wrong_book' AND INDEX_NAME = 'idx_wrong_review');
SET @sql = IF(@idx = 0,
    'ALTER TABLE `wrong_book` ADD INDEX `idx_wrong_review` (`user_id`, `mastered`, `next_review_at`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. 存量错题立即到期（否则老数据永远不进复习队列） ----------
UPDATE `wrong_book`
SET `next_review_at` = NOW()
WHERE `next_review_at` IS NULL AND `mastered` = 0;

-- ---------- 自检 ----------
-- SELECT COUNT(*) FROM wrong_book WHERE mastered = 0 AND (next_review_at IS NULL OR next_review_at <= NOW());
