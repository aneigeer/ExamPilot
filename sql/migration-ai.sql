-- ============================================================
-- AI 模块数据迁移（RAG 检索层语料底座）
--
-- 设计依据：docs/AI模块设计方案.md  A.4
-- 特性：
--   1. 可重复执行（CREATE TABLE IF NOT EXISTS），重复应用不报错、不丢数据
--   2. FULLTEXT ... WITH PARSER ngram —— MySQL 8.0 内置中文二元切分全文解析器
--   3. content_hash 幂等判据：无变化不重建，避免重复触发全文索引刷新
--   4. status 而非物理删除：来源下架时置 INVALID，保留可追溯性
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-ai.sql
-- ============================================================

-- ngram 插件检测（MySQL 8.0 内置默认 ACTIVE；未启用时下方建表会报错，
-- 给出明确提示而不是让 DBA 面对一个语法不明的错误）
SELECT PLUGIN_NAME, PLUGIN_STATUS, PLUGIN_TYPE
FROM information_schema.PLUGINS
WHERE PLUGIN_NAME = 'ngram';

CREATE TABLE IF NOT EXISTS `ai_chunk` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `source_type`      VARCHAR(20)  NOT NULL COMMENT '来源类型：QUESTION题目/RESOURCE资料/KNOWLEDGE知识点',
  `source_id`        BIGINT       NOT NULL COMMENT '来源业务ID',
  `chunk_no`         INT          NOT NULL DEFAULT 0 COMMENT '块序号，0为未分块（题目/知识点恒为0）',
  `title`            VARCHAR(200) NOT NULL COMMENT '来源标题（冗余存储，命中后直接展示，避免回表）',
  `content`          MEDIUMTEXT   NOT NULL COMMENT '切块后的检索文本',
  `exam_category_id` BIGINT       DEFAULT NULL COMMENT '考试类别，用于跨考试隔离检索',
  `subject_id`       BIGINT       DEFAULT NULL COMMENT '科目',
  `knowledge_id`     BIGINT       DEFAULT NULL COMMENT '知识点（画像与推荐的关键外键）',
  `difficulty`       TINYINT      NOT NULL DEFAULT 3 COMMENT '难度 1-5，来源于题目；资料/知识点固定3',
  `ref_type`         VARCHAR(20)  NOT NULL COMMENT '跳转类型：QUESTION/RESOURCE/KNOWLEDGE',
  `ref_id`           BIGINT       NOT NULL COMMENT '跳转业务ID',
  -- 设计文档 A.5 要求 PDF 切块保留页码；A.4 原稿未列出该列，此处按 A.5 增补（仅追加，不影响既有索引）
  `page_no`          INT          DEFAULT NULL COMMENT 'PDF 来源页码（1 起）；非 PDF 来源为 NULL',
  `content_hash`     CHAR(32)     NOT NULL COMMENT 'MD5(content)，幂等更新判据，无变化不重建',
  `source_updated_at` DATETIME    NOT NULL COMMENT '来源业务对象的更新时间，用于增量与时间衰减',
  `status`           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INVALID，来源被下架则置INVALID',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_chunk` (`source_type`,`source_id`,`chunk_no`),
  KEY `idx_subject`   (`subject_id`),
  KEY `idx_knowledge` (`knowledge_id`),
  KEY `idx_exam_cat`  (`exam_category_id`),
  KEY `idx_status_src`(`status`,`source_updated_at`),
  -- ★ 核心：MySQL 内置 ngram 中文分词全文索引（已实测 ACTIVE，零新增依赖）
  FULLTEXT KEY `ft_title_content` (`title`,`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='AI 检索语料块（RAG 检索层，ngram 中文全文索引）';

-- ============================================================
-- 语料扩充入口（预留）
--
-- 【当前状态】语料扩充待补（主理人决定推迟）。
--   实测全站可检索文本仅约 1300 字（13 道已审核题目 + 5 份已审核资料），
--   足以验证「检索 → 重排 → 生成 → 溯源」全链路，但召回覆盖面有限。
--
-- 【将来补语料时怎么做】不需要改任何 Java 代码，按以下顺序执行即可：
--   1) 在 sql/data-ai-seed.sql 中编写 INSERT 语句，至少包含：
--        question            （题干 stem / 解析 analysis / 标签 tags）
--        question_answer     （主观题才写 reference，客观题答案禁止写入，见 R7）
--        question_knowledge  （题目与知识点关联，画像与推荐的连接键）
--        resource            （标题 title / 简介 description / 标签 tags / 热度）
--   2) 执行：mysql -uroot -p exam_prep < sql/data-ai-seed.sql
--   3) 调用 POST /api/admin/ai/reindex（AUDITOR / ADMIN 角色）
--      或直接 UPDATE resource/question SET audit_status='APPROVED' 后调用审核通过接口，
--      两种路径都会触发 ChunkSyncService 的索引同步。
--
-- 【脚本骨架（供后续填充，勿删）】
-- ============================================================
-- INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
-- VALUES (5, 'SHORT_ANSWER', '简述栈与队列的区别。', NULL, 3, 'AI 语料扩充', '数据结构,栈,队列',
--         '栈后进先出（LIFO），队列先进先出（FIFO）；两者都是受限线性表。', 5, 0, 1, 'APPROVED', 1);
-- INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
-- VALUES (LAST_INSERT_ID(), JSON_OBJECT('reference', '栈后进先出；队列先进先出。',
--                                       'keywords', JSON_ARRAY('栈','队列','FIFO','LIFO')), NULL, 0);
-- INSERT INTO question_knowledge (question_id, knowledge_id) VALUES (LAST_INSERT_ID(), /* 知识点ID */ 1);
--
-- INSERT INTO resource (title, exam_category_id, subject_id, knowledge_id, course_id, type, description, tags,
--                        current_file_id, page_count, download_count, view_count, rating_avg, rating_count,
--                        uploader_id, audit_status)
-- VALUES ('数据结构高频考点总结', 1, 4, NULL, NULL, 'SUMMARY', '栈、队列、树、图的常考性质汇总。',
--         '重点必考,无答案', 1, 1, 0, 0, 0.00, 0, 1, 'APPROVED');

-- ============================================================
-- 迁移后自检
-- ============================================================
-- SHOW CREATE TABLE ai_chunk;   -- 应含 WITH PARSER ngram
-- SELECT source_type, status, COUNT(*) FROM ai_chunk GROUP BY source_type, status;
-- SELECT id, source_type, source_id, LEFT(content, 120) FROM ai_chunk LIMIT 10;
