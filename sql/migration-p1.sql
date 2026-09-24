-- P1 增量迁移：模拟考试 / 申诉 / 关注（可重复执行）
USE exam_prep;

-- 试卷表（模拟考试即时组卷也落一张卷，便于对齐大纲表设计）
CREATE TABLE IF NOT EXISTS `exam_paper` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(120) NOT NULL COMMENT '试卷名称',
    `subject_id`       BIGINT       NOT NULL,
    `duration_minutes` INT          NOT NULL DEFAULT 30 COMMENT '考试时长（分钟）',
    `total_score`      INT          NOT NULL DEFAULT 0 COMMENT '总分',
    `question_count`   INT          NOT NULL DEFAULT 0 COMMENT '题目数',
    `created_by`       BIGINT       NULL COMMENT '创建人（随机组卷时为考生本人）',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_paper_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试卷表';

-- 试卷题目关联表
CREATE TABLE IF NOT EXISTS `exam_paper_question` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `paper_id`    BIGINT NOT NULL,
    `question_id` BIGINT NOT NULL,
    `sort`        INT    NOT NULL DEFAULT 1,
    `score`       INT    NOT NULL DEFAULT 1 COMMENT '本题分值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pq_paper_question` (`paper_id`, `question_id`),
    KEY `idx_pq_paper` (`paper_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试卷题目关联表';

-- 考试记录表
CREATE TABLE IF NOT EXISTS `exam_record` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `paper_id`       BIGINT       NOT NULL,
    `user_id`        BIGINT       NOT NULL,
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'ONGOING' COMMENT 'ONGOING/FINISHED/EXPIRED',
    `started_at`     DATETIME     NOT NULL,
    `deadline`       DATETIME     NOT NULL COMMENT '交卷截止时间',
    `submitted_at`   DATETIME     NULL,
    `total_score`    INT          NOT NULL DEFAULT 0,
    `got_score`      DECIMAL(6,1) NOT NULL DEFAULT 0 COMMENT '得分',
    `correct_count`  INT          NOT NULL DEFAULT 0,
    `answered_count` INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_record_user` (`user_id`),
    KEY `idx_record_paper` (`paper_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试记录表';

-- 考试答题明细表
CREATE TABLE IF NOT EXISTS `exam_answer_detail` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `record_id`   BIGINT        NOT NULL,
    `question_id` BIGINT        NOT NULL,
    `user_answer` VARCHAR(1000) NULL COMMENT '用户答案',
    `is_correct`  TINYINT       NULL,
    `score_got`   DECIMAL(6,1)  NOT NULL DEFAULT 0,
    `answered_at` DATETIME      NULL,
    PRIMARY KEY (`id`),
    KEY `idx_ead_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试答题明细表';

-- 申诉表
CREATE TABLE IF NOT EXISTS `appeal` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `target_type` VARCHAR(20)  NOT NULL DEFAULT 'RESOURCE',
    `target_id`   BIGINT       NOT NULL,
    `reason`      VARCHAR(500) NOT NULL COMMENT '申诉理由',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    `handler_id`  BIGINT       NULL,
    `handle_note` VARCHAR(500) NULL,
    `handled_at`  DATETIME     NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_appeal_user` (`user_id`),
    KEY `idx_appeal_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '申诉表（P1）';

-- 关注表（课程 / 科目）
CREATE TABLE IF NOT EXISTS `follow` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT      NOT NULL,
    `target_type` VARCHAR(20) NOT NULL COMMENT 'COURSE/SUBJECT',
    `target_id`   BIGINT      NOT NULL,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follow` (`user_id`, `target_type`, `target_id`),
    KEY `idx_follow_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '关注表（P1）';
