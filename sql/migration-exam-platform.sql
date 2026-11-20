-- =====================================================================
-- 试卷考试平台迁移（WS-1 / T1.2）
-- 目标：把 exam_paper 从「随机组卷临时落库」扩展为「可人工编排的固定套卷」
-- 要求：MySQL 8.0 幂等，可连续执行两次不报错
--   · 建表用 CREATE TABLE IF NOT EXISTS
--   · 加列 / 加索引先查 information_schema，再用 PREPARE 动态执行
-- =====================================================================
USE exam_prep;

-- ---------------------------------------------------------------------
-- 0. 兜底建表（新库直接跑本脚本也能得到完整的四张基础表）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `exam_paper` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(120) NOT NULL COMMENT '试卷名称',
    `subject_id`       BIGINT       NOT NULL,
    `duration_minutes` INT          NOT NULL DEFAULT 30 COMMENT '考试时长（分钟）',
    `total_score`      INT          NOT NULL DEFAULT 0 COMMENT '总分',
    `question_count`   INT          NOT NULL DEFAULT 0 COMMENT '题目数',
    `created_by`       BIGINT       NULL COMMENT '创建人（随机组卷时为考生本人）',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_paper_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试卷表';

CREATE TABLE IF NOT EXISTS `exam_paper_question` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `paper_id`    BIGINT NOT NULL,
    `question_id` BIGINT NOT NULL,
    `sort`        INT    NOT NULL DEFAULT 1,
    `score`       INT    NOT NULL DEFAULT 1 COMMENT '本题分值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pq_paper_question` (`paper_id`, `question_id`),
    KEY `idx_pq_paper` (`paper_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试卷题目关联表';

CREATE TABLE IF NOT EXISTS `exam_record` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `paper_id`       BIGINT       NOT NULL,
    `user_id`        BIGINT       NOT NULL,
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'ONGOING' COMMENT 'ONGOING/FINISHED/EXPIRED',
    `started_at`     DATETIME     NOT NULL,
    `deadline`       DATETIME     NOT NULL COMMENT '交卷截止时间',
    `submitted_at`   DATETIME     NULL,
    `total_score`    INT          NOT NULL DEFAULT 0,
    `got_score`      DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '得分',
    `correct_count`  INT          NOT NULL DEFAULT 0,
    `answered_count` INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_record_user` (`user_id`),
    KEY `idx_record_paper` (`paper_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试记录表';

CREATE TABLE IF NOT EXISTS `exam_answer_detail` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `record_id`   BIGINT        NOT NULL,
    `question_id` BIGINT        NOT NULL,
    `user_answer` VARCHAR(1000) NULL COMMENT '用户答案',
    `is_correct`  TINYINT       NULL,
    `score_got`   DECIMAL(6,1)  NOT NULL DEFAULT 0,
    `answered_at` DATETIME      NULL,
    PRIMARY KEY (`id`),
    KEY `idx_ead_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试答题明细表';

-- ---------------------------------------------------------------------
-- 1. exam_paper 扩展列
--    paper_type  RANDOM（随机组卷）/ OFFICIAL（官方套卷）/ CUSTOM（自建套卷）
--    published   0 未发布 / 1 已发布（学生端只可见已发布）
-- ---------------------------------------------------------------------
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD COLUMN `paper_type` VARCHAR(20) NOT NULL DEFAULT ''RANDOM'' COMMENT ''试卷类型：RANDOM/OFFICIAL/CUSTOM'' AFTER `question_count`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'paper_type');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD COLUMN `exam_code` VARCHAR(30) NULL COMMENT ''考试类别编码'' AFTER `paper_type`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'exam_code');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD COLUMN `year` SMALLINT NULL COMMENT ''真题年份（非真题为 NULL）'' AFTER `exam_code`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'year');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD COLUMN `published` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否发布：0 否，1 是'' AFTER `year`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'published');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD COLUMN `description` VARCHAR(500) NULL COMMENT ''试卷说明'' AFTER `published`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND COLUMN_NAME = 'description');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 2. 索引：学生端按「已发布 + 类别 + 年份 + 科目」筛选套卷
-- ---------------------------------------------------------------------
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD INDEX `idx_paper_published` (`published`, `paper_type`, `exam_code`)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND INDEX_NAME = 'idx_paper_published');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exam_paper` ADD INDEX `idx_paper_year` (`year`, `subject_id`)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_paper' AND INDEX_NAME = 'idx_paper_year');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 3. 历史随机卷回填：paper_type 默认 RANDOM 已覆盖，此处只修正空值
-- ---------------------------------------------------------------------
UPDATE `exam_paper` SET `paper_type` = 'RANDOM' WHERE `paper_type` IS NULL OR `paper_type` = '';
