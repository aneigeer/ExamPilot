-- =====================================================================
-- 双轨测评中台迁移（WS-3 / T3.1）
-- question_calibration：试题难度与区分度动态校准结果
-- 要求：MySQL 8.0 幂等，可连续执行两次不报错
-- =====================================================================
USE exam_prep;

CREATE TABLE IF NOT EXISTS `question_calibration` (
    `question_id`            BIGINT       NOT NULL COMMENT '题目 ID（主键，一题一行）',
    `attempt_count`          INT          NOT NULL DEFAULT 0 COMMENT '累计有效作答次数',
    `correct_count`          INT          NOT NULL DEFAULT 0 COMMENT '累计答对次数',
    `observed_accuracy`      DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '实测正确率 0-1',
    `calibrated_difficulty`  TINYINT      NOT NULL DEFAULT 3 COMMENT '校准难度 1(易)-5(难)',
    `discrimination_index`   DECIMAL(5,4) NULL COMMENT '区分度：高分组27%正确率 - 低分组27%正确率；样本不足为 NULL',
    `updated_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`question_id`),
    KEY `idx_cal_difficulty` (`calibrated_difficulty`),
    KEY `idx_cal_accuracy` (`observed_accuracy`),
    KEY `idx_cal_discrimination` (`discrimination_index`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试题难度与区分度校准';

-- 增量列兜底（表已存在但版本较旧时补列，幂等）
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `question_calibration` ADD COLUMN `discrimination_index` DECIMAL(5,4) NULL COMMENT ''区分度'' AFTER `calibrated_difficulty`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_calibration' AND COLUMN_NAME = 'discrimination_index');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
