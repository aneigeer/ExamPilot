-- ============================================================
-- LLM Wiki 双引擎：主题页 / 草稿修订 / 页面链接（可重复执行）
--
-- 执行：mysql -uroot -p exam_prep < sql/migration-wiki.sql
--
-- 设计要点：
-- 1) wiki_page        = 已发布（PUBLISHED）的当前版本，问答热层直接读它
-- 2) wiki_page_revision = LLM 蒸馏出的草稿（PENDING），人工审核后才复制进 wiki_page
-- 3) 生成列 pending_slug + UNIQUE：同一主题同时只允许一条待审草稿，
--    重复蒸馏走更新而不是堆一堆重复稿
-- 4) 全文索引使用 ngram 解析器，中文按 bigram 切词（与 ai_chunk 一致）
-- 5) source_chunk_ids / key_points / links_json 均为 JSON 文本，
--    读取侧解析后再进 VO；不引入 JSON 类型以兼容旧 MySQL 客户端
-- ============================================================

USE exam_prep;

-- ---------- 1. Wiki 主题页（已发布版本） ----------
CREATE TABLE IF NOT EXISTS `wiki_page` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '页面 ID',
    `slug`             VARCHAR(100) NOT NULL COMMENT '稳定标识（kp-知识点ID / doc-哈希）',
    `title`            VARCHAR(200) NOT NULL COMMENT '主题标题',
    `subject_id`       BIGINT       NULL COMMENT '科目 ID',
    `knowledge_id`     BIGINT       NULL COMMENT '知识点 ID（KP 主题页有值）',
    `summary`          VARCHAR(1000) NOT NULL COMMENT '页面摘要（同时进向量索引）',
    `content`          MEDIUMTEXT   NOT NULL COMMENT '页面正文（Markdown）',
    `key_points`       TEXT         NULL COMMENT '要点 JSON 数组',
    `source_chunk_ids` TEXT         NULL COMMENT '来源 ai_chunk.id JSON 数组（强制溯源）',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED / OFFLINE',
    `version`          INT          NOT NULL DEFAULT 1 COMMENT '发布版本号',
    `model`            VARCHAR(64)  NULL COMMENT '蒸馏模型标识',
    `quality_score`    DECIMAL(5,4) NULL COMMENT '质量分 0-1',
    `published_at`     DATETIME     NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wiki_slug` (`slug`),
    KEY `idx_wiki_subject_status` (`subject_id`, `status`, `updated_at`),
    KEY `idx_wiki_knowledge` (`knowledge_id`),
    FULLTEXT KEY `ft_wiki_page` (`title`, `summary`, `key_points`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Wiki 主题页（已发布）';

-- ---------- 2. Wiki 草稿修订（LLM 蒸馏产物，待人工审核） ----------
CREATE TABLE IF NOT EXISTS `wiki_page_revision` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `page_id`          BIGINT       NULL COMMENT '已有页面 ID（首次蒸馏为 NULL）',
    `slug`             VARCHAR(100) NOT NULL,
    `title`            VARCHAR(200) NOT NULL,
    `subject_id`       BIGINT       NULL,
    `knowledge_id`     BIGINT       NULL,
    `summary`          VARCHAR(1000) NOT NULL,
    `content`          MEDIUMTEXT   NOT NULL,
    `key_points`       TEXT         NULL,
    `source_chunk_ids` TEXT         NULL,
    `links_json`       TEXT         NULL COMMENT 'LLM 提议的页面链接 JSON',
    `model`            VARCHAR(64)  NULL,
    `quality_score`    DECIMAL(5,4) NULL,
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    `task_id`          BIGINT       NULL COMMENT '来源 async_task.id',
    `reviewer_id`      BIGINT       NULL,
    `review_note`      VARCHAR(500) NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reviewed_at`      DATETIME     NULL,
    `pending_slug`     VARCHAR(100) GENERATED ALWAYS AS (
        IF(`status` = 'PENDING', `slug`, NULL)
    ) VIRTUAL COMMENT '待审草稿的唯一键（仅 PENDING 有值）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wiki_revision_pending` (`pending_slug`),
    KEY `idx_wiki_revision_status` (`status`, `created_at`),
    KEY `idx_wiki_revision_task` (`task_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Wiki 草稿修订（人工审核）';

-- ---------- 3. Wiki 页面链接（导航图） ----------
CREATE TABLE IF NOT EXISTS `wiki_page_link` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `from_page_id` BIGINT       NOT NULL,
    `to_page_id`   BIGINT       NOT NULL,
    `relation`     VARCHAR(24)  NOT NULL DEFAULT 'RELATED' COMMENT 'RELATED / SIBLING / PREREQUISITE',
    `weight`       DECIMAL(4,3) NOT NULL DEFAULT 0.500,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wiki_link` (`from_page_id`, `to_page_id`),
    KEY `idx_wiki_link_to` (`to_page_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Wiki 页面链接';

-- ---------- 4. async_task 类型说明（无需 DDL，仅注释） ----------
-- WIKI_DISTILL 走独立 stream exam:wiki；active_type 生成列天然保证同类型只跑一个。
