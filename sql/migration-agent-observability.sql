-- =====================================================================
-- Agent 可观测性：Token 与成本字段（P2 / T2-1）
-- 幂等：逐列判断 information_schema 后再 ALTER
-- =====================================================================
USE exam_prep;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `model_name` VARCHAR(100) NULL COMMENT ''实际使用的模型名'' AFTER `generator`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'model_name');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `prompt_tokens` INT NOT NULL DEFAULT 0 COMMENT ''输入 Token'' AFTER `model_name`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'prompt_tokens');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT ''输出 Token'' AFTER `prompt_tokens`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'completion_tokens');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `total_tokens` INT NOT NULL DEFAULT 0 COMMENT ''总 Token'' AFTER `completion_tokens`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'total_tokens');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `estimated_cost_cny` DECIMAL(10,6) NOT NULL DEFAULT 0.000000 COMMENT ''估算成本（元）'' AFTER `total_tokens`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'estimated_cost_cny');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
