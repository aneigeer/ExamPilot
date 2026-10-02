-- ============================================================
-- 大学生多考试备考知识库与题库系统 · 迁移脚本：学校概念 + 23 个学院 + 自助注册
-- 数据库：MySQL 8.0（在已有 exam_prep 库上执行，可重复执行）
-- 内容：
--   1. 新建 school 表并插入「中南林业科技大学」
--   2. college / user 表增加 school_id 列
--   3. 旧 3 个使用学院改名复用（保留 id，不删行），并补齐其余 20 个学院
-- 版本：v1.3    日期：2026-10-09
-- ============================================================

USE `exam_prep`;

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 学校表（参考 college 表结构）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `school` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(100) NOT NULL COMMENT '学校名称',
    `code`       VARCHAR(30)  DEFAULT NULL COMMENT '学校编码',
    `sort`       INT          NOT NULL DEFAULT 0,
    `status`     TINYINT      NOT NULL DEFAULT 1,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_school_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学校表';

-- 防重复插入（可重复执行）
INSERT IGNORE INTO `school` (`id`, `name`, `code`, `sort`, `status`) VALUES
(1, '中南林业科技大学', 'CSUFT', 1, 1);

-- ------------------------------------------------------------
-- 2. college 表增加 school_id（放 id 之后）
-- ------------------------------------------------------------
SET @col_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'college' AND COLUMN_NAME = 'school_id');
SET @sql := IF(@col_exist = 0,
    'ALTER TABLE `college` ADD COLUMN `school_id` BIGINT NOT NULL DEFAULT 1 COMMENT ''所属学校'' AFTER `id`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'college' AND INDEX_NAME = 'idx_college_school');
SET @sql := IF(@idx_exist = 0,
    'ALTER TABLE `college` ADD INDEX `idx_college_school` (`school_id`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. user 表增加 school_id（放 college_id 之后）
-- ------------------------------------------------------------
SET @col_exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'user' AND COLUMN_NAME = 'school_id');
SET @sql := IF(@col_exist = 0,
    'ALTER TABLE `user` ADD COLUMN `school_id` BIGINT NOT NULL DEFAULT 1 COMMENT ''所属学校'' AFTER `college_id`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_school');
SET @sql := IF(@idx_exist = 0,
    'ALTER TABLE `user` ADD INDEX `idx_user_school` (`school_id`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 旧 3 个使用学院改名复用（保留 id，不删行：已有用户/专业外挂引用）
--    sort 按真实 23 学院清单排定
-- ------------------------------------------------------------
UPDATE `college` SET `name` = '计算机与数学学院', `code` = 'CSM', `school_id` = 1, `sort` = 12 WHERE `id` = 1;
UPDATE `college` SET `name` = '材料与能源学院',   `code` = 'MSE', `school_id` = 1, `sort` = 2  WHERE `id` = 2;
UPDATE `college` SET `name` = '外国语学院',       `code` = 'FL',  `school_id` = 1, `sort` = 16 WHERE `id` = 3;

-- ------------------------------------------------------------
-- 5. 补齐其余 20 个学院（INSERT IGNORE 防重复；固定 sort 与清单一致）
--    注意：id 1/2/3 已被旧学院占用（改名后即 计算机与数学/材料与能源/外国语），
--    其余 20 个按 sort 顺序列出，id 自增从 4 开始。
-- ------------------------------------------------------------
INSERT IGNORE INTO `college` (`name`, `code`, `school_id`, `sort`, `status`) VALUES
('林学院',               'FOREST', 1, 1,  1),
('生态环境学院',         'ECO',    1, 3,  1),
('生命科学与技术学院',   'LIFE',   1, 4,  1),
('园林与建筑学院',       'LA',     1, 5,  1),
('食品科学与工程学院',   'FOOD',   1, 6,  1),
('水土保持学院',         'SWC',    1, 7,  1),
('机械与智能制造学院',   'MECH',   1, 8,  1),
('家居与艺术设计学院',   'HD',     1, 9,  1),
('化学与化工学院',       'CHEM',   1, 10, 1),
('电子信息与物理学院',   'EEP',    1, 11, 1),
('国家公园与旅游学院',   'NPAT',   1, 13, 1),
('低空经济学院',         'LAE',    1, 14, 1),
('经济管理学院',         'EM',     1, 15, 1),
('法学院',               'LAW',    1, 17, 1),
('马克思主义学院',       'MARX',   1, 18, 1),
('体育与音乐学院',       'SM',     1, 19, 1),
('国际学院',             'INTL',   1, 20, 1),
('班戈学院',             'BANGOR', 1, 21, 1),
('创新创业学院',         'IE',     1, 22, 1),
('继续教育学院',         'CE',     1, 23, 1);
