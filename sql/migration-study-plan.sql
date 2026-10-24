-- ============================================================
-- 学习计划 MVP：用户「目标设定」的持久化（最小改动）
--
-- 为什么只加两列而不是建表：
--   学习计划的最小闭环需要「目标设定 + 诊断 + 每日任务」三层，
--   其中**诊断**复用已有的学情画像（answer_detail 加权掌握度），
--   **每日任务**是按需计算的规则引擎输出（不落库，避免计划与真实作答脱节），
--   真正需要持久化的只有「用户的目标」——所以只给 user 表加两列。
--
--   计划的**完成度**不另建表：直接从 practice_record（今日、该知识点的
--   answered_count）派生，保证「完成度」永远等于真实作答，不会出现
--   「计划表说完成了、实际没做题」这类自欺数据。
--
-- 幂等：MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，
--       故先用 information_schema 判断列是否已存在再决定是否 ALTER。
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-study-plan.sql
-- ============================================================
USE exam_prep;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE `user` ADD COLUMN `study_daily_target` INT NOT NULL DEFAULT 20 COMMENT ''学习计划：每日目标题量（1-100）''',
              'DO 0')
    FROM information_schema.columns
    WHERE table_schema = 'exam_prep' AND table_name = 'user'
      AND column_name = 'study_daily_target'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE `user` ADD COLUMN `study_target_subject_id` BIGINT DEFAULT NULL COMMENT ''学习计划：目标科目，NULL 表示不限''',
              'DO 0')
    FROM information_schema.columns
    WHERE table_schema = 'exam_prep' AND table_name = 'user'
      AND column_name = 'study_target_subject_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：两列都应存在
SELECT COUNT(*) AS 应有列数
FROM information_schema.columns
WHERE table_schema = 'exam_prep' AND table_name = 'user'
  AND column_name IN ('study_daily_target', 'study_target_subject_id');
