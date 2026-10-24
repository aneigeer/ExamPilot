-- ============================================================
-- Agent 分层记忆：会话主表 + 运行记录关联（可重复执行）
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-agent-memory.sql
--
-- 设计：
-- 1) ai_agent_session 保存跨轮次的滚动摘要与轮次计数；
-- 2) ai_agent_run.session_id 关联到会话，历史轮次直接查最近成功 run；
-- 3) 摘要只做确定性滚动转录（不额外调用 LLM），由 Java 在每轮结束后更新。
-- ============================================================

USE exam_prep;

CREATE TABLE IF NOT EXISTS `ai_agent_session` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`    VARCHAR(32)  NOT NULL COMMENT '会话稳定标识（客户端可复用）',
    `user_id`       BIGINT       NULL COMMENT '归属用户（游客为 NULL）',
    `summary`       VARCHAR(2000) NULL COMMENT '最近若干轮问答的滚动摘要',
    `turn_count`    INT          NOT NULL DEFAULT 0 COMMENT '已完成轮次',
    `last_question` VARCHAR(500) NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_session_id` (`session_id`),
    KEY `idx_agent_session_user` (`user_id`, `updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 会话（分层记忆）';

-- ai_agent_run 增加 session_id（幂等）
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'session_id');
SET @sql = IF(@col = 0,
    'ALTER TABLE `ai_agent_run` ADD COLUMN `session_id` VARCHAR(32) NULL COMMENT ''所属会话'' AFTER `user_id`',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'exam_prep' AND TABLE_NAME = 'ai_agent_run' AND INDEX_NAME = 'idx_agent_session');
SET @sql = IF(@idx = 0,
    'ALTER TABLE `ai_agent_run` ADD INDEX `idx_agent_session` (`session_id`, `id`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
