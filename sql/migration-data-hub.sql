-- =====================================================================
-- 轻量级数据中台迁移（WS-2 / T2.1）
-- 四张表：
--   data_pipeline_job     数据接入 / ETL 任务流水
--   data_quality_issue    数据质量巡检异常
--   ads_daily_metrics     日级核心指标快照
--   ads_subject_coverage  科目覆盖矩阵快照
-- 要求：MySQL 8.0 幂等，可连续执行两次不报错
--   · 建表统一 CREATE TABLE IF NOT EXISTS
--   · 后加列先查 information_schema 再 PREPARE 动态执行
-- =====================================================================
USE exam_prep;

-- ---------------------------------------------------------------------
-- 1. 数据流水线任务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `data_pipeline_job` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `job_type`       VARCHAR(30)  NOT NULL COMMENT 'IMPORT_QUESTIONS/OCR_BATCH/VECTOR_SYNC/CALIBRATION/METRICS_ETL',
    `source_ref`     VARCHAR(255) NULL COMMENT '触发来源：manual / scheduler / 文件或批次标识',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    `total_rows`     INT          NOT NULL DEFAULT 0 COMMENT '处理总行数',
    `inserted_rows`  INT          NOT NULL DEFAULT 0 COMMENT '新增行数',
    `dedup_rows`     INT          NOT NULL DEFAULT 0 COMMENT '去重命中行数',
    `failed_rows`    INT          NOT NULL DEFAULT 0 COMMENT '失败行数',
    `error_summary`  VARCHAR(1000) NULL COMMENT '失败摘要',
    `started_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`    DATETIME     NULL,
    PRIMARY KEY (`id`),
    KEY `idx_job_type_time` (`job_type`, `started_at`),
    KEY `idx_job_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据流水线任务表';

-- ---------------------------------------------------------------------
-- 2. 数据质量异常
--    issue_key 是幂等键：同一问题重复巡检只更新，不新增
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `data_quality_issue` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT,
    `issue_key`    VARCHAR(160)  NOT NULL COMMENT '幂等键，如 DUPLICATE_STEM:question:123',
    `issue_type`   VARCHAR(30)   NOT NULL COMMENT 'DUPLICATE_STEM/MISSING_ANSWER/NO_CHUNK/ORPHAN_VECTOR/OCR_BACKLOG/EMPTY_SUBJECT',
    `severity`     VARCHAR(10)   NOT NULL DEFAULT 'WARN' COMMENT 'CRITICAL/WARN/INFO',
    `target_table` VARCHAR(40)   NULL COMMENT '问题对象所在表',
    `target_id`    BIGINT        NOT NULL DEFAULT 0 COMMENT '问题对象 ID；聚合类问题为 0',
    `detail`       VARCHAR(1000) NULL COMMENT '问题描述',
    `status`       VARCHAR(12)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
    `detected_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `resolved_at`  DATETIME      NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dqi_key` (`issue_key`),
    KEY `idx_dqi_type_status` (`issue_type`, `status`, `severity`),
    KEY `idx_dqi_target` (`target_table`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据质量异常表';

-- ---------------------------------------------------------------------
-- 3. 日级核心指标（ADS 层）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ads_daily_metrics` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `stat_date`               DATE         NOT NULL COMMENT '统计日期',
    `active_users`            INT          NOT NULL DEFAULT 0 COMMENT '活跃用户数（练习∪模考∪AI 提问）',
    `practice_count`          INT          NOT NULL DEFAULT 0 COMMENT '练习场次',
    `mock_count`              INT          NOT NULL DEFAULT 0 COMMENT '模拟考试场次',
    `avg_accuracy`            DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '练习平均正确率（%）',
    `ai_ask_count`            INT          NOT NULL DEFAULT 0 COMMENT 'AI 提问次数',
    `ai_refused_count`        INT          NOT NULL DEFAULT 0 COMMENT 'AI 拒答次数',
    `ai_degraded_count`       INT          NOT NULL DEFAULT 0 COMMENT 'AI 降级次数',
    `question_approved_total` INT          NOT NULL DEFAULT 0 COMMENT '已过审存活题量（截止当日）',
    `chunk_active_total`      INT          NOT NULL DEFAULT 0 COMMENT 'ACTIVE 向量块总量（截止当日）',
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ads_daily_date` (`stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '日级核心指标快照';

-- ---------------------------------------------------------------------
-- 4. 科目覆盖矩阵（ADS 层）
--    exam_code 用空串代替 NULL，保证唯一键对「未归类科目」同样生效
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ads_subject_coverage` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `exam_code`               VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '考试类别编码，未归类为空串',
    `subject_id`              BIGINT       NOT NULL DEFAULT 0 COMMENT '科目 ID',
    `subject_name`            VARCHAR(80)  NULL COMMENT '科目名称（冗余，便于看板直接展示）',
    `live_question_count`     INT          NOT NULL DEFAULT 0 COMMENT '存活题量（is_deleted=0）',
    `approved_question_count` INT          NOT NULL DEFAULT 0 COMMENT '已过审存活题量',
    `pending_question_count`  INT          NOT NULL DEFAULT 0 COMMENT '待审核存活题量',
    `chunk_active_count`      INT          NOT NULL DEFAULT 0 COMMENT 'ACTIVE 向量块数',
    `courseware_count`        INT          NOT NULL DEFAULT 0 COMMENT '课件数',
    `knowledge_count`         INT          NOT NULL DEFAULT 0 COMMENT '知识点数',
    `coverage_score`          DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '覆盖健康度 0-100',
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ads_cov` (`exam_code`, `subject_id`),
    KEY `idx_ads_cov_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '科目覆盖矩阵快照';

-- ---------------------------------------------------------------------
-- 5. 增量列兜底（表已存在但版本较旧时补列，幂等）
-- ---------------------------------------------------------------------
SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `data_quality_issue` ADD COLUMN `issue_key` VARCHAR(160) NOT NULL DEFAULT '''' COMMENT ''幂等键'' AFTER `id`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_quality_issue' AND COLUMN_NAME = 'issue_key');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ads_subject_coverage` ADD COLUMN `coverage_score` DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT ''覆盖健康度 0-100'' AFTER `knowledge_count`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ads_subject_coverage' AND COLUMN_NAME = 'coverage_score');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `data_pipeline_job` ADD COLUMN `source_ref` VARCHAR(255) NULL COMMENT ''触发来源'' AFTER `job_type`',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_pipeline_job' AND COLUMN_NAME = 'source_ref');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
