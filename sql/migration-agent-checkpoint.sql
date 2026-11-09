-- ============================================================
-- 阶段四：Agent 步骤检查点（任务中断恢复，MVP 只读恢复）（可重复执行）
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-agent-checkpoint.sql
--
-- 设计要点：
-- 1) 每步落一条（thought / tool_call / tool_result），(run_id, step_no) 唯一 —— 同一 run 的
--    同一步不可能写两条，也天然挡住事件重放导致的重复落库。
-- 2) payload_json **只存步骤意图 + 工具名 + 参数摘要**，不存用户问题原文、不存工具返回原文：
--    恢复只需要知道「走到第几步、本来要干什么」，把原文存进来等于在库里再复制一份对话内容，
--    与数据最小化冲突（且 ai_agent_run 已有保留期清理，checkpoint 不该成为绕过清理的后门）。
-- 3) status：RECORDED（正常落库）→ RESUMED（已被用于一次恢复）。
--    「已存在 RESUMED 记录」即表示该 run 已恢复过，重复恢复请求返回 409；
--    条件更新 `WHERE status='RECORDED'` 影响 0 行 → 并发重复恢复也只会有一次成功。
-- 4) 不做写操作恢复、不做跨版本兼容：恢复=用原 question/sessionId 重开一轮，
--    不是"从第 N 步继续执行"（Python harness 无此能力，前端也只需要重试）。
-- ============================================================

USE exam_prep;

CREATE TABLE IF NOT EXISTS `ai_agent_checkpoint` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `run_id`       BIGINT       NOT NULL COMMENT '关联 ai_agent_run.id',
    `session_id`   VARCHAR(32)  NULL COMMENT '所属会话（恢复时复用）',
    `step_no`      INT          NOT NULL COMMENT '第几步（1-based，与轨迹事件顺序一致）',
    `payload_json` VARCHAR(2000) NULL COMMENT '步骤摘要 JSON（意图+工具名+参数摘要，不含原文）',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'RECORDED' COMMENT 'RECORDED / RESUMED',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_checkpoint_run_step` (`run_id`, `step_no`),
    KEY `idx_checkpoint_run` (`run_id`, `id`),
    KEY `idx_checkpoint_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 步骤检查点（中断恢复）';
