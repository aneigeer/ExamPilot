-- ======================================================================================
-- AI 面试记录持久化（学情画像基础）
--
-- 背景：Python 侧会话存在进程内存（30 分钟 TTL），Java 侧只在内存里记 session→userId，
--       **重启即全丢**，用户面完一场什么都留不下，也无法据此做画像/复盘。
--       本迁移把「一场面试会话」与「逐题作答」落库。
--
-- ★ 幂等：本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- ★ 不修改任何既有表。
--
-- 执行：
--   mysql -uroot -p exam_prep < sql/migration-interview.sql
-- ======================================================================================

-- --------------------------------------------------------------------------------------
-- 1. 面试会话
-- --------------------------------------------------------------------------------------
-- 为什么 python_session_id 要唯一：Python 侧的 session_id 是会话的**外部唯一标识**，
-- Java 代理层用它做幂等（重复 start 回调 / 重放）与「重启后把旧会话接回来」的锚点。
-- 不加唯一键的话，一旦调用重试就会出现两行同一场面试，历史页会出现重复记录。
-- --------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_interview_session` (
  `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`             BIGINT       NOT NULL                COMMENT '学生用户 ID（user.id）',
  `python_session_id`   VARCHAR(64)  NOT NULL                COMMENT 'Python AI 服务的会话 ID',
  `topic`               VARCHAR(128) DEFAULT NULL            COMMENT '面试主题（空=随机）',
  `total_questions`     INT          NOT NULL DEFAULT 0      COMMENT '计划题数',
  `finished`            TINYINT      NOT NULL DEFAULT 0      COMMENT '0=进行中 1=已结束',
  `total_score`         DOUBLE       DEFAULT NULL            COMMENT '总分（结束时回填）',
  `level`               VARCHAR(32)  DEFAULT NULL            COMMENT '等级（优秀/中等/待提升…）',
  `started_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  `finished_at`         DATETIME     DEFAULT NULL            COMMENT '结束时间',
  `is_deleted`          TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_python_session` (`python_session_id`),
  KEY `idx_user_started` (`user_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 面试会话';

-- --------------------------------------------------------------------------------------
-- 2. 逐题作答
-- --------------------------------------------------------------------------------------
-- 为什么追问**新增一行**而不是覆盖原行（is_follow_up=1 标记）：
--   追问是独立的一次作答与独立评分（题号不前进，但内容与得分完全不同）。
--   覆盖会丢掉「第一遍答得怎样」的证据，而这正是复盘与画像最需要的信号
--   （先 30 分、追问后 40 分，说明提示下能改进；两次都低说明概念没掌握）。
--   代价是同一 question_no 可能出现 2 行，读取时按 created_at 升序拼成该题的完整对话。
-- --------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_interview_qa` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id`     BIGINT       NOT NULL                COMMENT 'ai_interview_session.id',
  `question_no`    INT          NOT NULL                COMMENT '题号（追问轮不前进，与主表一致）',
  `question`       TEXT                                 COMMENT '题面（或追问内容）',
  `answer`         TEXT                                 COMMENT '学生作答原文',
  `score`          DOUBLE       DEFAULT NULL            COMMENT '本题得分',
  `verdict`        VARCHAR(255) DEFAULT NULL            COMMENT '评语',
  `hit_points`     TEXT                                 COMMENT '命中要点（JSON 数组字符串）',
  `miss_points`    TEXT                                 COMMENT '遗漏要点（JSON 数组字符串）',
  `reference_text` TEXT                                 COMMENT '参考答案',
  `is_follow_up`   TINYINT      NOT NULL DEFAULT 0      COMMENT '1=同题追问轮',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作答时间',
  PRIMARY KEY (`id`),
  KEY `idx_session_qno` (`session_id`, `question_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 面试逐题作答';
