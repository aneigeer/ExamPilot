-- ============================================================
-- 30. AI 用户/角色级每日 Token 与请求配额覆盖表（幂等迁移）
-- 支持管理员对特定用户设置高于或低于角色默认值的每日 Token / 请求上限。
-- ============================================================

CREATE TABLE IF NOT EXISTS `ai_quota_override` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`             BIGINT       NOT NULL COMMENT '用户 ID（全局唯一）',
    `daily_token_limit`   INT          NULL     COMMENT '每日 Token 上限（NULL 表示走角色默认值）',
    `daily_request_limit` INT          NULL     COMMENT '每日请求数上限（NULL 表示走角色默认值）',
    `remark`              VARCHAR(255) NULL     COMMENT '调整原因备注',
    `updated_by`          BIGINT       NULL     COMMENT '操作管理员 ID',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_quota_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 每日 Token 与请求配额单用户覆盖表';
