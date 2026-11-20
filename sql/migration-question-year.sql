-- ============================================================
-- 题目年份增量迁移：question.year 列 + idx_q_year 索引 + 从 source 回填（可重复执行）
--
-- 背景：
--   前端/用户要求「全部题目随机 + 选择特定年份题目作答 + 模拟考试」。
--   question 表原先没有年份字段，年份只藏在 source 里，例如
--   source = '2022年国考行测（副省级）'。按 LIKE '%2022%' 过滤无法命中索引，
--   且会把 source 里偶然出现 "2022" 的字样（如书名、题号）误判为年份，
--   故独立成 year 列，由后端按等值条件过滤（走 idx_q_year）。
--
-- 执行方式（三选一）：
--   1) 命令行：  mysql -uroot -p exam_prep < sql/migration-question-year.sql
--   2) 容器内：  docker compose exec -T mysql mysql -uroot -p exam_prep < sql/migration-question-year.sql
--   3) 客户端：  用任意 MySQL 客户端连库后，整文件执行
--
-- ⚠️ 执行顺序（重要）：
--   本文件必须**在数据导入之后**执行（schema.sql → data.sql → 本文件）。
--   若在空表上先跑本文件，只会建列 + 建索引，回填 UPDATE 命中 0 行；
--   之后导入的数据不会自动回填，需再执行一次本文件（幂等，重跑安全）。
--
-- 幂等说明：
--   - 加列：先查 information_schema.COLUMNS，列已存在则执行 'SELECT 1' 空语句；
--   - 加索引：先查 information_schema.STATISTICS，索引已存在则执行 'SELECT 1'；
--   - 回填：带 `AND year IS NULL` 条件，重跑只会处理尚未回填的行，
--           不会覆盖人工修正过的 year（人工改过的行 year 已非 NULL，被跳过）。
--   因此本文件可反复执行，不会报错、不会重复加列加索引、不会重复回填。
--
-- 回填口径（不猜）：
--   - 只从 source 前 4 位取年份，且要求 source 以 '20xx年' 开头（REGEXP '^20[0-9]{2}年'）；
--   - 取不到 / 格式不符（如 '模拟题'、'2016-2025合辑'、全角数字、source 为 NULL）
--     一律保持 year = NULL，表示「非真题或年份不可解析」，不做任何推测；
--   - 不做 subject 限定：所有科目同一口径回填。
--
-- 回滚方式（如需）：
--   ALTER TABLE `question` DROP INDEX `idx_q_year`;
--   ALTER TABLE `question` DROP COLUMN `year`;
-- ============================================================

USE exam_prep;

-- ---------- 0. 执行前体检（只读，先看清影响面） ----------
-- 当前全表行数
SELECT COUNT(*) AS total_rows FROM `question`;
-- 有多少行会被本次回填命中（这就是「预估影响行数」的真实值）
SELECT COUNT(*) AS will_backfill
FROM `question`
WHERE `source` REGEXP '^20[0-9]{2}年';

-- ---------- 1. 加列：question.year ----------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'year');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `question` ADD COLUMN `year` SMALLINT NULL DEFAULT NULL COMMENT ''年份（真题年份，非真题为 NULL）'' AFTER `source`',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 索引：idx_q_year ----------
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND INDEX_NAME = 'idx_q_year');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `question` ADD INDEX `idx_q_year` (`year`)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 从 source 回填年份 ----------
-- 说明：CAST(... AS UNSIGNED) 对 '2022年...' 的前 4 位字符 '2022' 得到整数 2022；
--       SUBSTRING 只取 4 位，避免 source 形如 '20223年' 时被解析成 20223。
UPDATE `question`
SET `year` = CAST(SUBSTRING(`source`, 1, 4) AS UNSIGNED)
WHERE `source` REGEXP '^20[0-9]{2}年'
  AND `year` IS NULL;

-- ---------- 4. 执行后核对（只读） ----------
-- 4.1 列与索引是否就绪（各返回 1 行才算成功）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'year';

SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND INDEX_NAME = 'idx_q_year';

-- 4.2 回填覆盖率：year 为空但 source 可解析的行应为 0
SELECT COUNT(*) AS resolvable_but_null
FROM `question`
WHERE `year` IS NULL AND `source` REGEXP '^20[0-9]{2}年';

-- 4.3 年份分布（subject_id = 190 为实测基准科目，其余科目按需替换）
SELECT `year`, COUNT(*) AS cnt
FROM `question`
WHERE `subject_id` = 190
GROUP BY `year`
ORDER BY `year`;
