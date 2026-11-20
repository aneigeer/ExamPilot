-- ======================================================================================
-- 题库导入流水线：来源登记表 question_source（幂等建表 / 安全列新增）
--
-- 配套脚本：scripts/import_question_bank.py
-- 执行：mysql -uroot -p exam_prep < sql/migration-question-source.sql
--
-- 设计要点
-- --------
-- 1. 一个原始文件 = 一行；source_path 存绝对路径，前缀唯一键 uk_source_path
--    保证重复 register 只更新不新增（脚本用 ON DUPLICATE KEY UPDATE）。
-- 2. MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema
--    动态拼出「只补缺失列」的单条 ALTER，对已存在的旧表生效。
-- 3. 索引同样做存在性判断，重复执行不报错、不丢数据。
-- 4. 字段契约（其他 agent 依赖，一字不差）：
--      exam_code    : CET4 / CET6 / GUOKAO / GUOKAO_SHENLUN / POSTGRAD_ENG1 /
--                     POSTGRAD_ENG2 / POSTGRAD_MATH1 / POSTGRAD_MATH2 /
--                     POSTGRAD_MATH3 / INTERVIEW / NULL(未识别)
--      doc_kind     : PAPER / ANSWER / ANALYSIS / VOCAB / TEMPLATE / AUDIO /
--                     POST_TABLE / INTERVIEW_KB / OTHER
--      text_layer   : TEXT / SPARSE / SCAN / NATIVE
--      parse_status : PENDING / PARSED / PARTIAL / FAILED / OCR_QUEUED / SKIPPED
-- ======================================================================================

USE exam_prep;

CREATE TABLE IF NOT EXISTS `question_source` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `source_path`    VARCHAR(1000) NOT NULL                COMMENT '原始文件绝对路径（幂等键）',
    `file_name`      VARCHAR(500) DEFAULT NULL             COMMENT '文件名',
    `file_ext`       VARCHAR(20)  DEFAULT NULL             COMMENT '扩展名（含点，小写）',
    `file_size`      BIGINT       DEFAULT NULL             COMMENT '文件字节数',
    `sha256`         CHAR(64)     DEFAULT NULL             COMMENT '文件内容 SHA256（内容变化判据）',
    `exam_code`      VARCHAR(30)  DEFAULT NULL             COMMENT '考试编码，取值见文件头契约',
    `subject_name`   VARCHAR(100) DEFAULT NULL             COMMENT '科目名（写入 question 时解析 subject_id）',
    `year`           SMALLINT     DEFAULT NULL             COMMENT '真题年份；无法解析为 NULL',
    `paper_name`     VARCHAR(300) DEFAULT NULL             COMMENT '试卷/文档名（文件名去扩展名）',
    `doc_kind`       VARCHAR(30)  DEFAULT NULL             COMMENT '文档类别，取值见文件头契约',
    `text_layer`     VARCHAR(20)  DEFAULT NULL             COMMENT '文本层，取值见文件头契约',
    `parse_status`   VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态，取值见文件头契约',
    `question_count` INT          NOT NULL DEFAULT 0       COMMENT '本文件产出的题目数',
    `chunk_count`    INT          NOT NULL DEFAULT 0       COMMENT '本文件产出的 ai_chunk 块数',
    `note`           VARCHAR(500) DEFAULT NULL             COMMENT '跳过原因 / 失败原因 / 契约列缺失说明',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_source_path` (`source_path`(500)),
    KEY `idx_src_cat_year` (`exam_code`, `year`),
    KEY `idx_src_status` (`parse_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '题库导入来源登记（一个原始文件一行，驱动 register/parse 两阶段幂等重跑）';


-- --------------------------------------------------------------------------------------
-- 1. 安全列新增：仅补齐「已存在的旧表」中缺失的列
--    用 UNION ALL 列出全部业务列定义，按 information_schema 过滤后拼成一条 ALTER。
--    新表（上一步刚建）不会命中任何行，@ddl 退化为 'SELECT 1'。
--    默认 group_concat_max_len 仅 1024，16 列定义会被截断，故先调大。
-- --------------------------------------------------------------------------------------
SET SESSION group_concat_max_len = 100000;

SET @ddl := (
    SELECT IF(COUNT(*) = 0, 'SELECT 1',
              CONCAT('ALTER TABLE `question_source` ADD COLUMN ',
                     GROUP_CONCAT(`def` ORDER BY `ord` SEPARATOR ', ADD COLUMN ')))
    FROM (
        SELECT  1 AS `ord`, 'file_name'      AS `col`, '`file_name` VARCHAR(500) DEFAULT NULL'                          AS `def`
        UNION ALL SELECT  2, 'file_ext',       '`file_ext` VARCHAR(20) DEFAULT NULL'
        UNION ALL SELECT  3, 'file_size',      '`file_size` BIGINT DEFAULT NULL'
        UNION ALL SELECT  4, 'sha256',         '`sha256` CHAR(64) DEFAULT NULL'
        UNION ALL SELECT  5, 'exam_code',      '`exam_code` VARCHAR(30) DEFAULT NULL'
        UNION ALL SELECT  6, 'subject_name',   '`subject_name` VARCHAR(100) DEFAULT NULL'
        UNION ALL SELECT  7, 'year',           '`year` SMALLINT DEFAULT NULL'
        UNION ALL SELECT  8, 'paper_name',     '`paper_name` VARCHAR(300) DEFAULT NULL'
        UNION ALL SELECT  9, 'doc_kind',       '`doc_kind` VARCHAR(30) DEFAULT NULL'
        UNION ALL SELECT 10, 'text_layer',     '`text_layer` VARCHAR(20) DEFAULT NULL'
        UNION ALL SELECT 11, 'parse_status',   '`parse_status` VARCHAR(20) NOT NULL DEFAULT ''PENDING'''
        UNION ALL SELECT 12, 'question_count', '`question_count` INT NOT NULL DEFAULT 0'
        UNION ALL SELECT 13, 'chunk_count',    '`chunk_count` INT NOT NULL DEFAULT 0'
        UNION ALL SELECT 14, 'note',           '`note` VARCHAR(500) DEFAULT NULL'
        UNION ALL SELECT 15, 'created_at',     '`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'
        UNION ALL SELECT 16, 'updated_at',     '`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP'
    ) AS `d`
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS `c`
        WHERE `c`.TABLE_SCHEMA = DATABASE()
          AND `c`.TABLE_NAME = 'question_source'
          AND `c`.COLUMN_NAME = `d`.`col`
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- --------------------------------------------------------------------------------------
-- 2. 安全索引新增（旧表可能缺索引；新表已在 CREATE 中建好）
-- --------------------------------------------------------------------------------------
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source' AND INDEX_NAME = 'uk_source_path');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE `question_source` ADD UNIQUE KEY `uk_source_path` (`source_path`(500))',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source' AND INDEX_NAME = 'idx_src_cat_year');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE `question_source` ADD KEY `idx_src_cat_year` (`exam_code`, `year`)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source' AND INDEX_NAME = 'idx_src_status');
SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE `question_source` ADD KEY `idx_src_status` (`parse_status`)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- --------------------------------------------------------------------------------------
-- 3. 自检（只读）
-- --------------------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source'
ORDER BY ORDINAL_POSITION;

SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT COUNT(*) AS 已登记文件数 FROM `question_source`;
