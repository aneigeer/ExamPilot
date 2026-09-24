-- P1 第二批增量迁移：听力音频表（可重复执行）
USE exam_prep;

CREATE TABLE IF NOT EXISTS `listening_audio` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `question_id` BIGINT   NOT NULL COMMENT '听力题 ID',
    `file_id`     BIGINT   NOT NULL COMMENT '音频文件 ID（file_record）',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_la_question` (`question_id`),
    KEY `idx_la_file` (`file_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '听力音频表（P1）';
