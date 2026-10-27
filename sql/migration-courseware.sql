-- ======================================================================================
-- 课件语料导入（Word 版教学资料 → courseware + ai_chunk）
--
-- 目标：把 634 份 docx（约 375.7 万字）变成可被 AI 问答检索的教学语料。
--
-- ★ 幂等：本脚本可重复执行。
-- ★ 不修改 ai_chunk 表结构（只写数据）。
--
-- 执行：
--   mysql -uroot -p exam_prep < sql/migration-courseware.sql   # 交互式输入口令
--
-- 配套脚本：ai-service/tools/ingest_courseware.py
--
-- ⚠️ 注意：本机库里 courseware 表**已存在**（0 行），结构见下方 CREATE TABLE。
--    因此 CREATE TABLE 用 IF NOT EXISTS（新库兜底），缺失的列用 ALTER 补齐。
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema + 预处理语句判断。
-- ======================================================================================


-- --------------------------------------------------------------------------------------
-- 1. courseware 表 —— 课件元信息（一篇 docx = 一条记录）
-- --------------------------------------------------------------------------------------
-- 为什么单独建表而不是复用 resource：
--   resource 是「学生上传的资料」，带 uploader_id、审核状态、下载量，走 UGC 审核流；
--   courseware 是「教学课件批量导入」，无上传者、无需审核、按目录分类。
--   两者生命周期与语义都不同，硬塞进 resource 会污染既有的资料审核与统计逻辑。
--
-- 幂等键有两个，各有用途：
--   uk_source_file(category?) —— 见下 —— **文件级**：同一份文件重跑只会更新
--   uk_category_title        —— **业务级**：防止两个不同文件产生同名课件
-- --------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `courseware` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`       VARCHAR(255) NOT NULL                COMMENT '课件标题（文件名去数字前缀）',
  `category`    VARCHAR(64)  DEFAULT NULL            COMMENT '来源目录：01 AI大模型面试系列 / 02 AI大模型 / 03 大厂项目架构',
  `subject_id`  BIGINT       DEFAULT NULL            COMMENT '关联科目 ID（subject.id）',
  `char_count`  INT          NOT NULL DEFAULT 0      COMMENT '正文字符数（灌库时统计）',
  `chunk_count` INT          NOT NULL DEFAULT 0      COMMENT '切块数（写入 ai_chunk 的条数）',
  `source_file` VARCHAR(512) DEFAULT NULL            COMMENT '原始文件相对路径，文件级幂等键',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`  TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 未删 / 1 已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_file` (`source_file`),
  UNIQUE KEY `uk_category_title` (`category`, `title`),
  KEY `idx_category` (`category`),
  KEY `idx_subject` (`subject_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='课件语料元信息（正文在 ai_chunk）';


-- --------------------------------------------------------------------------------------
-- 2. 补齐缺失列（对已存在的旧表生效）
-- --------------------------------------------------------------------------------------
-- 旧表缺 chunk_count（无法记录切块数），这里幂等地补上。

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'courseware' AND COLUMN_NAME = 'chunk_count'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `courseware` ADD COLUMN `chunk_count` INT NOT NULL DEFAULT 0 COMMENT ''切块数（写入 ai_chunk 的条数）'' AFTER `char_count`',
  'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 旧库的 collation 可能是 utf8mb4_unicode_ci，与 ai_chunk（0900_ai_ci）不同。
-- 跨表 JOIN 时若 collation 不一致会报 "Illegal mix of collations"，这里统一。
SET @coll := (
  SELECT CCSA.collation_name
  FROM information_schema.COLUMNS C
  JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA
    ON CCSA.collation_name = C.collation_name
  WHERE C.TABLE_SCHEMA = DATABASE() AND C.TABLE_NAME = 'courseware'
    AND C.COLUMN_NAME = 'title' LIMIT 1
);
SET @ddl := IF(@coll IS NOT NULL AND @coll <> 'utf8mb4_0900_ai_ci',
  'ALTER TABLE `courseware` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
  'DO 0'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- --------------------------------------------------------------------------------------
-- 3. 新增 3 个科目
-- --------------------------------------------------------------------------------------
-- 幂等做法：subject 表在 name 上无唯一索引，不能用 ON DUPLICATE KEY UPDATE，
-- 改用 INSERT ... SELECT ... WHERE NOT EXISTS。
--
-- exam_category_id 取 1（校内课程）：
--   这三门是「自学/教学课件」，不是四六级/考研/公务员等应试科目，
--   放「校内课程」语义最接近，且不会污染应试类科目的统计。
--   ★ 如需调整，改这里的 1 和脚本 SUBJECT_MAP 所在文件的说明，两处必须一致。
-- --------------------------------------------------------------------------------------
INSERT INTO `subject` (`exam_category_id`, `name`, `description`, `question_count`, `resource_count`, `sort`, `status`)
SELECT 1, 'AI 大模型面试', '大模型面试专题课件（Transformer/微调/RAG/推理优化/落地实践）', 0, 0, 200, 1
WHERE NOT EXISTS (SELECT 1 FROM `subject` WHERE `name` = 'AI 大模型面试');

INSERT INTO `subject` (`exam_category_id`, `name`, `description`, `question_count`, `resource_count`, `sort`, `status`)
SELECT 1, 'AI 大模型', '大模型原理、工程化与行业实践课件', 0, 0, 201, 1
WHERE NOT EXISTS (SELECT 1 FROM `subject` WHERE `name` = 'AI 大模型');

INSERT INTO `subject` (`exam_category_id`, `name`, `description`, `question_count`, `resource_count`, `sort`, `status`)
SELECT 1, '大厂项目架构', '大厂高并发/分布式/中间件架构案例课件', 0, 0, 202, 1
WHERE NOT EXISTS (SELECT 1 FROM `subject` WHERE `name` = '大厂项目架构');


-- --------------------------------------------------------------------------------------
-- 4. 校验
-- --------------------------------------------------------------------------------------
-- 期望：3 行。脚本会自动按 name 解析 id，不需要手填。
SELECT `id`, `name`, `exam_category_id`, `status`
FROM `subject`
WHERE `name` IN ('AI 大模型面试', 'AI 大模型', '大厂项目架构')
ORDER BY `id`;

-- 期望：courseware 含 chunk_count 列
SELECT `COLUMN_NAME`, `COLUMN_TYPE`, `IS_NULLABLE`, `COLUMN_DEFAULT`
FROM `information_schema`.`COLUMNS`
WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'courseware'
ORDER BY `ORDINAL_POSITION`;


-- ======================================================================================
-- 附：灌库完成后的核对 SQL（手动跑）
-- ======================================================================================
-- ① 课件数与分块总数
-- SELECT category, COUNT(*) AS docs, SUM(char_count) AS chars, SUM(chunk_count) AS chunks
-- FROM courseware WHERE is_deleted=0 AND status='ACTIVE' GROUP BY category;
--
-- ② ai_chunk 里课件块数（应与 ① 的 chunks 合计一致）
-- SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE' AND status='ACTIVE';
--
-- ③ 待向量化的块数（脚本写入时置 0，交给 reindex 推送）
-- SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE' AND vector_synced=0;
--
-- ④ 抽一条看格式（应以《标题》开头）
-- SELECT id, title, chunk_no, LEFT(content, 120) FROM ai_chunk
-- WHERE source_type='COURSEWARE' ORDER BY id LIMIT 3;
-- ======================================================================================
