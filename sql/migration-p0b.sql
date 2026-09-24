-- P0-B 增量迁移：资料标签 / 题目笔记 / 资料点赞（可重复执行）
USE exam_prep;

-- 资料标签列（resource 表）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'resource' AND COLUMN_NAME = 'tags');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `resource` ADD COLUMN `tags` VARCHAR(200) DEFAULT NULL COMMENT ''资料标签，逗号分隔：重点必考/含答案/无答案/近年高频'' AFTER `description`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 题目笔记表（一人一题一篇）
CREATE TABLE IF NOT EXISTS `question_note` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT        NOT NULL COMMENT '所属用户',
    `question_id` BIGINT        NOT NULL COMMENT '题目',
    `content`     VARCHAR(2000) NOT NULL COMMENT '笔记内容',
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_note_user_question` (`user_id`, `question_id`),
    KEY `idx_note_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目笔记（P0-B）';

-- 资料点赞表
CREATE TABLE IF NOT EXISTS `resource_like` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `resource_id` BIGINT   NOT NULL,
    `user_id`     BIGINT   NOT NULL,
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rlike_resource_user` (`resource_id`, `user_id`),
    KEY `idx_rlike_resource` (`resource_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资料点赞表（P0-B）';
