-- ============================================================
-- 题目「考试类别」增量迁移：question 增加 exam_code / paper_name / origin_path
-- 列 + idx_q_exam_code / idx_q_exam_year 索引 + 从 source 回填 exam_code（可重复执行）
--
-- 背景：
--   前端要「按考试类别作答 / 模拟考试按年份组卷 / 导入状态可查」。
--   question 表原先只有 subject_id + year，没有跨科目的「考试类别」维度；
--   题目由 Python 导入脚本批量写入，脚本会自动探测本文件新增的列并填充
--   exam_code / paper_name / origin_path。本文件负责先把列与索引建好，
--   再对历史数据（导入脚本没写 exam_code 的老题）按来源关键词做一次兜底回填。
--
-- 关联契约（question_source 表由另一 agent 建立，本文件只读、不建表、不改表）：
--   id, source_path, file_name, file_ext, file_size, sha256, exam_code,
--   subject_name, year, paper_name, doc_kind, text_layer, parse_status,
--   question_count, chunk_count, note, created_at, updated_at
--   exam_code 取值：CET4 / CET6 / GUOKAO / GUOKAO_SHENLUN / POSTGRAD_ENG1 /
--                  POSTGRAD_ENG2 / POSTGRAD_MATH1 / POSTGRAD_MATH2 /
--                  POSTGRAD_MATH3 / INTERVIEW
--   parse_status 取值：PENDING / PARSED / PARTIAL / FAILED / OCR_QUEUED / SKIPPED
--
-- 执行方式（三选一）：
--   1) 命令行：  mysql -uroot -p exam_prep < sql/migration-question-exam-code.sql
--   2) 容器内：  docker compose exec -T mysql mysql -uroot -p exam_prep < sql/migration-question-exam-code.sql
--   3) 客户端：  用任意 MySQL 客户端连库后，整文件执行
--
-- ⚠️ 执行顺序（重要）：
--   1. 先执行 sql/migration-question-year.sql（本文件的组合索引依赖 question.year 列）；
--   2. 再执行「另一 agent」的 question_source 建表 SQL（可选段 5 会读它，表不存在会自动跳过）；
--   3. 然后执行本文件；之后若又导入了新数据，可重复执行本文件补回填；
--   4. 重启后端前必须执行完 1~3：新增的 /questions/exam-categories、/questions/stats
--      等接口会查 exam_code 列，列不存在时这些接口会报错。
--
-- 幂等说明（重复执行方式）：
--   - 加列：information_schema.COLUMNS 判断，列已存在则执行 'SELECT 1' 空语句；
--   - 加索引：information_schema.STATISTICS 判断，索引已存在则跳过；
--   - 回填：CASE 的 ELSE 保持原值，且 WHERE exam_code IS NULL，
--           重跑不会覆盖已回填 / 人工修正 / 导入脚本写入的值；
--   - 可选段 5（读 question_source 精确回填）先判断表与列是否就绪，未就绪自动跳过。
--   因此本文件可反复执行，不会报错、不会重复加列加索引、不会重复回填。
--
-- ⚠️ 本文件不 DROP 任何表或列、不修改 sql/schema.sql 原有表定义。
-- ============================================================

USE exam_prep;

-- ---------- 0. 执行前体检（只读；刻意不引用尚未新增的列） ----------
SELECT COUNT(*) AS total_rows FROM `question`;

-- ---------- 1. 加列 ----------
-- 1.1 exam_code：跨科目的考试类别编码
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'exam_code');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `question` ADD COLUMN `exam_code` VARCHAR(30) NULL DEFAULT NULL COMMENT ''考试类别编码：CET4/CET6/GUOKAO/GUOKAO_SHENLUN/POSTGRAD_*/INTERVIEW''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.2 paper_name：试卷 / 资料名称
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'paper_name');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `question` ADD COLUMN `paper_name` VARCHAR(300) NULL DEFAULT NULL COMMENT ''试卷/资料名称''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.3 origin_path：导入来源文件路径（对应 question_source.source_path）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'origin_path');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `question` ADD COLUMN `origin_path` VARCHAR(1000) NULL DEFAULT NULL COMMENT ''导入来源文件路径（对应 question_source.source_path）''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 索引 ----------
-- 2.1 单列：按类别筛选
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND INDEX_NAME = 'idx_q_exam_code');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `question` ADD INDEX `idx_q_exam_code` (`exam_code`)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.2 组合：按类别 + 年份联合筛选（前端「按年份 + 按类别」AND 查询走它）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND INDEX_NAME = 'idx_q_exam_year');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `question` ADD INDEX `idx_q_exam_year` (`exam_code`, `year`)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 回填前体检（此时列已存在） ----------
SELECT COUNT(*) AS already_has_exam_code FROM `question` WHERE `exam_code` IS NOT NULL;
-- 会被关键词回填命中的行数（预估影响行数）
SELECT COUNT(*) AS will_backfill_exam_code
FROM `question`
WHERE `exam_code` IS NULL
  AND CONCAT_WS(' ', `source`, `paper_name`) REGEXP
      '四级|CET4|CET-4|六级|CET6|CET-6|国考|行测|申论|英语一|英语二|英一|英二|数学一|数学二|数学三|数一|数二|数三|面试';

-- ---------- 4. 从 source / paper_name 按关键词回填 exam_code ----------
-- 口径：
--   - 匹配文本 = CONCAT_WS(' ', source, paper_name)（不含题干，避免题干里出现「面试」
--     等字样把普通题误归类）；
--   - 顺序即优先级：先匹配更具体的「申论」，避免「国考申论」被「国考」抢走；
--   - 只回填 exam_code IS NULL 的行（ELSE 保持原值），重跑幂等、不覆盖人工修正；
--   - 关键词命中不了的一律保持 NULL，表示「未归类」，不做猜测。
UPDATE `question`
SET `exam_code` = CASE
    WHEN CONCAT_WS(' ', `source`, `paper_name`) LIKE '%申论%'           THEN 'GUOKAO_SHENLUN'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '国考|行测'       THEN 'GUOKAO'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) LIKE '%面试%'           THEN 'INTERVIEW'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '英语一|英一'     THEN 'POSTGRAD_ENG1'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '英语二|英二'     THEN 'POSTGRAD_ENG2'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '数学一|数一'     THEN 'POSTGRAD_MATH1'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '数学二|数二'     THEN 'POSTGRAD_MATH2'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '数学三|数三'     THEN 'POSTGRAD_MATH3'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '四级|CET4|CET-4' THEN 'CET4'
    WHEN CONCAT_WS(' ', `source`, `paper_name`) REGEXP '六级|CET6|CET-6' THEN 'CET6'
    ELSE `exam_code`
END
WHERE `exam_code` IS NULL
  AND CONCAT_WS(' ', `source`, `paper_name`) REGEXP
      '四级|CET4|CET-4|六级|CET6|CET-6|国考|行测|申论|英语一|英语二|英一|英二|数学一|数学二|数学三|数一|数二|数三|面试';

-- ---------- 5. 可选：从 question_source.exam_code 精确回填（表/列未就绪则跳过） ----------
-- 说明：question_source 由另一 agent 建立；导入脚本已把 s.exam_code 写准，
--       用 origin_path = source_path 关联，对第 4 段覆盖不到的老题做一次精确补充。
--       本段只读 question_source（不建表、不改表），且只更新 exam_code 仍为 NULL 的行。
--       ★ 用 information_schema 判断表与列是否就绪，未就绪时执行 'SELECT 1' 空语句，
--         因此另一 agent 还没建表 / 建到一半时本文件也不会报错。
SET @src_ready = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question_source'
      AND COLUMN_NAME IN ('source_path', 'exam_code'));
SET @ddl = IF(@src_ready = 2,
    'UPDATE `question` q JOIN `question_source` s ON q.`origin_path` = s.`source_path` SET q.`exam_code` = s.`exam_code` WHERE q.`exam_code` IS NULL AND s.`exam_code` IS NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6. 执行后核对（只读） ----------
-- 6.1 三列是否就绪（应返回 3 行）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question'
  AND COLUMN_NAME IN ('exam_code', 'paper_name', 'origin_path');

-- 6.2 两个索引是否就绪（idx_q_exam_code 1 行，idx_q_exam_year 2 行）
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question'
  AND INDEX_NAME IN ('idx_q_exam_code', 'idx_q_exam_year')
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 6.3 类别分布（含未归类）
SELECT COALESCE(`exam_code`, '(未归类)') AS exam_code, COUNT(*) AS cnt
FROM `question`
WHERE `is_deleted` = 0
GROUP BY `exam_code`
ORDER BY cnt DESC;
