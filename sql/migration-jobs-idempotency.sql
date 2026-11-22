-- =====================================================================
-- 异步任务幂等键（P6 / T6-2）
-- =====================================================================
USE exam_prep;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `async_task` ADD COLUMN `idempotency_key` VARCHAR(128) NULL COMMENT ''幂等键'' AFTER `payload`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_task' AND COLUMN_NAME = 'idempotency_key');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `async_task` ADD UNIQUE KEY `uk_async_task_idempotency_key` (`idempotency_key`)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'async_task'
      AND INDEX_NAME = 'uk_async_task_idempotency_key');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
