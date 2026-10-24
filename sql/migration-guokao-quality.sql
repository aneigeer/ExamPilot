-- ============================================================
-- 国考题库「可用性」修正：把无法作答的题改为待审核（PENDING）—— 可重复执行
--
-- 背景：sql/data-guokao.sql 从 PDF 文字层导入国考行测真题。有两类题
--       在文字层里**天然缺内容**，直接放进公共题库会让学生刷到做不了的题：
--   ① 资料分析（共 438 题）：题目依赖「共享的数据表/图」，而数据表在 PDF 里
--      多为图片或表格，文字层抽不到 → 429 题题干不足 150 字，明显缺材料。
--   ② 图推/依赖插图题：题干出现「图形/如图/下图/图表」等，作答必须看图。
--
-- 处置：置 audit_status = PENDING —— 既移出公共题库（列表/刷题只取 APPROVED），
--       也从 AI 语料索引中剔除；**数据仍保留在库**，日后补齐图/表或做 OCR 后可再审核通过。
--
-- 幂等：只更新当前为 APPROVED 的行，重复执行影响 0 行。
-- 执行：mysql -uroot -p exam_prep < sql/migration-guokao-quality.sql
-- ============================================================

USE exam_prep;

UPDATE `question`
SET `audit_status` = 'PENDING',
    `updated_at` = NOW()
WHERE `subject_id` = 190
  AND `audit_status` = 'APPROVED'
  AND (`tags` LIKE '%资料分析%'
       OR `stem` LIKE '%图形%'
       OR `stem` LIKE '%如图%'
       OR `stem` LIKE '%下图%'
       OR `stem` LIKE '%图表%');

-- ---------- 自检 ----------
-- 仍可用于刷题/问答的题数（期望 ≈2500）：
SELECT COUNT(*) AS `可用题目数` FROM `question`
 WHERE `subject_id` = 190 AND `audit_status` = 'APPROVED';
-- 转为待审核的题数：
SELECT COUNT(*) AS `待审核题目数` FROM `question`
 WHERE `subject_id` = 190 AND `audit_status` = 'PENDING';
