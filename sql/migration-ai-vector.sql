-- ============================================================
-- AI 向量库扩展迁移（配合 FastAPI AI 微服务 + Qdrant）
--
-- 设计依据：docs/AI微服务扩展设计方案.md  B.3
-- 原则：
--   1. **不存向量本体** —— MySQL 8.0 无原生 VECTOR 类型，硬存只能 BLOB，
--      无法建 HNSW 索引，且违背分层。向量只存 Qdrant。
--   2. **MySQL 只加状态位** —— 记录「这个 chunk 是否已向量化」，
--      供异步同步 + 定时对账使用。
--   3. **铁律：Qdrant 是可重建的派生存储，不是数据源。**
--      删掉整个 Qdrant volume，跑一次 reindex 即可从 ai_chunk 完全恢复。
--   4. 可重复执行。
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-ai-vector.sql
-- ============================================================

-- ---------- ① ai_chunk 加向量同步状态位 ----------
-- vector_synced: 0 待同步 / 1 已同步 / 2 失败（下次对账重试）
-- 用 INFORMATION_SCHEMA 判断列是否存在，避免重复执行报错（MySQL 不支持 ADD COLUMN IF NOT EXISTS）
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chunk' AND COLUMN_NAME = 'vector_synced'
);

SET @sql := IF(@has_col = 0,
    'ALTER TABLE `ai_chunk`
       ADD COLUMN `vector_synced` TINYINT NOT NULL DEFAULT 0
         COMMENT ''是否已向量化：0待同步 1已同步 2失败'' AFTER `status`,
       ADD COLUMN `vector_synced_at` DATETIME DEFAULT NULL
         COMMENT ''向量同步时间'' AFTER `vector_synced`',
    'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_vector_synced 索引（同样做存在性判断）
SET @has_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_chunk' AND INDEX_NAME = 'idx_vector_synced'
);
SET @sql := IF(@has_idx = 0,
    'ALTER TABLE `ai_chunk` ADD KEY `idx_vector_synced` (`vector_synced`, `status`)',
    'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- ② 向量索引元信息（可观测性 / 与 Qdrant 对账） ----------
CREATE TABLE IF NOT EXISTS `ai_vector_index_meta` (
  `id`           INT          NOT NULL AUTO_INCREMENT,
  `collection`   VARCHAR(64)  NOT NULL COMMENT 'Qdrant collection 名',
  `model_id`     VARCHAR(64)  NOT NULL COMMENT 'embedding 模型标识；模型换了必须全量重建',
  `dim`          INT          NOT NULL DEFAULT 0 COMMENT '向量维度',
  `chunk_total`  INT          NOT NULL DEFAULT 0 COMMENT 'MySQL 侧应同步总数',
  `chunk_synced` INT          NOT NULL DEFAULT 0 COMMENT '已同步数',
  `status`       VARCHAR(20)  NOT NULL DEFAULT 'IDLE'
                 COMMENT 'IDLE/SYNCING/READY/FAILED/MODEL_MISMATCH',
  `message`      VARCHAR(500) DEFAULT NULL COMMENT '诊断信息，不含堆栈',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_collection` (`collection`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量索引元信息（Qdrant 对账用）';

-- 默认元信息行（collection 名与 docker-compose.ai.yml / FastAPI 配置保持一致）
INSERT INTO `ai_vector_index_meta` (`collection`, `model_id`, `dim`, `status`, `message`)
VALUES ('exam_chunks', 'bge-small-zh-v1.5', 512, 'IDLE', '等待首次同步')
ON DUPLICATE KEY UPDATE `message` = VALUES(`message`);

-- ============================================================
-- 迁移后自检
-- ============================================================
-- SHOW COLUMNS FROM ai_chunk LIKE 'vector%';   -- 应有 vector_synced / vector_synced_at
-- SELECT * FROM ai_vector_index_meta;
-- SELECT vector_synced, COUNT(*) FROM ai_chunk WHERE status='ACTIVE' GROUP BY vector_synced;
--   期望：0=42（待同步）→ 调 reindex 推送后变 1
--
-- ★ 一致性核心断言（T08/T12 验收④）：
--   SELECT COUNT(*) FROM ai_chunk WHERE status='ACTIVE' AND vector_synced=1;
--   必须等于 Qdrant collection exam_chunks 的 points 数
--
-- ★ 换模型时的强制重建（绝不允许同一 collection 混用不同模型的向量）：
--   UPDATE ai_vector_index_meta SET status='FAILED', message='model_id 变更，需全量重建';
--   然后调 POST /api/admin/ai/reindex
