-- RSI 自改进运行记录：每轮"度量 → 学原型句 → 回归闸门"的结果，用于取证与曲线
CREATE TABLE IF NOT EXISTS rsi_run (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    stage         VARCHAR(40)  NOT NULL COMMENT '改进阶段，如 ROUTER_PROTOTYPE',
    metric_before JSON         NULL     COMMENT '改进前指标 {hit, acc}',
    metric_after  JSON         NULL     COMMENT '改进后指标 {hit, acc}',
    learned_count INT          NOT NULL DEFAULT 0 COMMENT '本轮累计学习原型句条数',
    detail        TEXT         NULL     COMMENT '学习到的原型句（按 skill 分组）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_stage_time (stage, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RSI 评测驱动自改进运行记录';
