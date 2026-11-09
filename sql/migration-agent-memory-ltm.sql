-- ============================================================
-- 阶段四：学生长期记忆（LTM，可增删改查 + 逻辑删除）（可重复执行）
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-agent-memory-ltm.sql
--
-- 设计要点：
-- 1) 长期记忆是**学生自己可见、可改、可删**的偏好/背景（如「目标岗位：省考」「薄弱：资料分析」），
--    与 ai_agent_session.summary（系统自动滚动生成的短期摘要）分表存放，避免学生改动污染对话摘要。
-- 2) 唯一键 (user_id, mem_key)：同一学生同一语义只能有一条，避免「模型学到 3 个矛盾的称呼」。
--    mem_key 限 32 字（应用层 DTO 同步校验），足够表达「目标考试」「薄弱模块」这类短键。
-- 3) is_deleted 逻辑删除：学生删除后要能审计/追溯，且物理删除会与唯一键冲突——
--    因此「删除后再添加同 key」由应用层做**复活**（UPDATE is_deleted=0 + 覆盖 value），不是 INSERT。
-- 4) status 区分 ACTIVE / DISABLED：ACTIVE 才注入提示词，DISABLED 保留但不生效。
-- 5) 单用户上限 20 条由应用层校验（409），DB 不做硬限制（便于将来调参）。
-- 6) source 默认 USER（学生手填）；AGENT 为将来「对话归纳」预留。
-- ============================================================

USE exam_prep;

CREATE TABLE IF NOT EXISTS `ai_agent_memory` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL COMMENT '归属学生（长期记忆只服务登录用户）',
    `mem_key`    VARCHAR(32)  NOT NULL COMMENT '记忆键（≤32 字，如 目标考试 / 薄弱模块）',
    `mem_value`  VARCHAR(200) NOT NULL COMMENT '记忆值（学生原文，≤200 字由应用层校验）',
    `source`     VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '来源：USER（学生手填）/ AGENT（对话归纳）',
    `status`     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 注入提示词 / DISABLED 保留不生效',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 有效 / 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_memory_user_key` (`user_id`, `mem_key`),
    KEY `idx_agent_memory_user_updated` (`user_id`, `is_deleted`, `status`, `updated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 长期记忆（学生可见可改可删）';
