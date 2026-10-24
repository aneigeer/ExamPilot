-- ============================================================
-- Agent 能力补完：异步任务 / Agent 运行轨迹 / 离线评估（可重复执行）
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-agent.sql
--
-- 关键设计：
-- 1) async_task.active_type 生成列 + UNIQUE：同类型 PENDING/RUNNING 只能有一条，
--    由数据库兜底防重复提交竞态（不做“先查后插”）。
-- 2) ai_agent_run.running_user_id 生成列 + UNIQUE：每个用户同时只能有 1 个
--    RUNNING 会话，避免并发闸门的 TOCTOU。
-- 3) 生成列为 VIRTUAL，不占存储；应用侧实体不参与 insert/update。
-- ============================================================

USE exam_prep;

-- ---------- 1. 异步任务 ----------
CREATE TABLE IF NOT EXISTS `async_task` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
    `type`        VARCHAR(32)  NOT NULL COMMENT '任务类型：EVAL_RETRIEVAL / EVAL_AGENT',
    `stream`      VARCHAR(32)  NOT NULL COMMENT 'Redis Stream 名称',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
    `progress`    INT          NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    `payload`     LONGTEXT     NULL COMMENT '任务参数 JSON',
    `result`      LONGTEXT     NULL COMMENT '任务结果 JSON',
    `error`       VARCHAR(1000) NULL COMMENT '失败原因',
    `created_by`  BIGINT       NULL COMMENT '提交人 user_id',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `started_at`  DATETIME     NULL COMMENT '开始执行时间（PENDING 为 NULL）',
    `finished_at` DATETIME     NULL COMMENT '结束时间',
    `active_type` VARCHAR(32)  GENERATED ALWAYS AS (
        IF(`status` IN ('PENDING', 'RUNNING'), `type`, NULL)
    ) VIRTUAL COMMENT '活动任务类型（仅 PENDING/RUNNING 有值，用于唯一约束）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_async_active_type` (`active_type`),
    KEY `idx_async_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '异步任务（Redis Stream 任务状态）';

-- ---------- 2. Agent 运行轨迹 ----------
CREATE TABLE IF NOT EXISTS `ai_agent_run` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NULL COMMENT '发起用户（游客为 NULL）',
    `trace_id`    VARCHAR(32)  NULL COMMENT '链路追踪 ID',
    `question`    VARCHAR(500) NOT NULL COMMENT '用户问题',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/REFUSED/FAILED/CANCELLED',
    `answer`      MEDIUMTEXT   NULL COMMENT '最终答案',
    `generator`   VARCHAR(32)  NULL COMMENT '生成器标识',
    `step_count`  INT          NOT NULL DEFAULT 0 COMMENT 'Agent 步数',
    `tool_calls`  INT          NOT NULL DEFAULT 0 COMMENT '工具调用次数',
    `total_ms`    INT          NOT NULL DEFAULT 0 COMMENT '总耗时',
    `degraded`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否降级',
    `steps_json`  LONGTEXT     NULL COMMENT '完整事件数组（轨迹回放）',
    `error`       VARCHAR(1000) NULL COMMENT '失败原因',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` DATETIME     NULL,
    `running_user_id` BIGINT GENERATED ALWAYS AS (
        IF(`status` = 'RUNNING', `user_id`, NULL)
    ) VIRTUAL COMMENT 'RUNNING 会话的用户 ID（用于每用户 1 并发约束）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_running_user` (`running_user_id`),
    KEY `idx_agent_user_time` (`user_id`, `created_at`),
    KEY `idx_agent_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 运行轨迹';

-- ---------- 3. 离线评估运行记录 ----------
CREATE TABLE IF NOT EXISTS `ai_eval_run` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT,
    `layer`        VARCHAR(16)   NOT NULL COMMENT 'RETRIEVAL / AGENT',
    `status`       VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
    `total`        INT           NOT NULL DEFAULT 0 COMMENT '用例总数',
    `passed`       INT           NOT NULL DEFAULT 0 COMMENT '通过数',
    `metrics_json` LONGTEXT      NULL COMMENT '指标汇总 JSON',
    `detail_json`  LONGTEXT      NULL COMMENT '逐条结果 JSON',
    `report_path`  VARCHAR(500)  NULL COMMENT 'HTML 报告路径',
    `error`        VARCHAR(1000) NULL,
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`  DATETIME      NULL,
    PRIMARY KEY (`id`),
    KEY `idx_eval_layer_time` (`layer`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '离线评估运行记录';

-- ---------- 4. ai_eval_run 关联 async_task（幂等） ----------
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'ai_eval_run' AND COLUMN_NAME = 'task_id');
SET @sql = IF(@col = 0,
    'ALTER TABLE `ai_eval_run` ADD COLUMN `task_id` BIGINT NULL COMMENT ''关联 async_task.id'' AFTER `id`, ADD UNIQUE KEY `uk_eval_task` (`task_id`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
