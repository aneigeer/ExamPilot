-- =====================================================================
-- LLM Wiki 治理字段（P4 / OKF v0.3 对齐）
-- trust_tier: VERIFIED / AUTO_DISTILLED / STALE_REVIEW / RETIRED
-- =====================================================================
USE exam_prep;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `wiki_page` ADD COLUMN `trust_tier` VARCHAR(32) NOT NULL DEFAULT ''VERIFIED'' COMMENT ''信任分级'' AFTER `status`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'trust_tier');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `wiki_page` ADD COLUMN `source_hash` VARCHAR(64) NULL COMMENT ''引用来源块的聚合 hash'' AFTER `source_chunk_ids`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'source_hash');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `wiki_page` ADD COLUMN `expires_at` DATETIME NULL COMMENT ''过期时间'' AFTER `source_hash`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'expires_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `wiki_page` ADD COLUMN `stale_reason` VARCHAR(255) NULL COMMENT ''标旧原因'' AFTER `expires_at`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wiki_page' AND COLUMN_NAME = 'stale_reason');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
