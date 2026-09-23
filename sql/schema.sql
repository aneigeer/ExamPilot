-- ============================================================
-- 大学生多考试备考知识库与题库系统 · 数据库建表脚本
-- 数据库：MySQL 8.0   字符集：utf8mb4   引擎：InnoDB
-- 版本：v1.0    日期：2026-09-23
-- 说明：P0 核心表（已移除积分体系）
-- ============================================================

DROP DATABASE IF EXISTS `exam_prep`;
CREATE DATABASE `exam_prep` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `exam_prep`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 一、用户与权限
-- ============================================================

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `student_no`    VARCHAR(32)  NOT NULL                COMMENT '学号（唯一，展示时脱敏）',
    `nickname`      VARCHAR(50)  NOT NULL                COMMENT '昵称',
    `password_hash` VARCHAR(100) NOT NULL                COMMENT '密码哈希（BCrypt）',
    `real_name`     VARCHAR(50)  DEFAULT NULL            COMMENT '真实姓名',
    `college_id`    BIGINT       DEFAULT NULL            COMMENT '学院ID',
    `major_id`      BIGINT       DEFAULT NULL            COMMENT '专业ID',
    `grade`         VARCHAR(20)  DEFAULT NULL            COMMENT '年级，如 2023',
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'STUDENT' COMMENT '角色：GUEST/STUDENT/AUDITOR/ADMIN',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1正常 0禁用',
    `last_login_at` DATETIME     DEFAULT NULL            COMMENT '最后登录时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_student_no` (`student_no`),
    KEY `idx_user_college` (`college_id`),
    KEY `idx_user_role` (`role`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ============================================================
-- 二、校内课程扩展（学院 / 专业 / 教师 / 学期 / 开课班）
-- ============================================================

DROP TABLE IF EXISTS `college`;
CREATE TABLE `college` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(50) NOT NULL COMMENT '学院名称',
    `code`       VARCHAR(30) DEFAULT NULL COMMENT '学院编码',
    `sort`       INT         NOT NULL DEFAULT 0,
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_college_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学院表';

DROP TABLE IF EXISTS `major`;
CREATE TABLE `major` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `college_id` BIGINT      NOT NULL COMMENT '所属学院',
    `name`       VARCHAR(50) NOT NULL COMMENT '专业名称',
    `code`       VARCHAR(30) DEFAULT NULL,
    `sort`       INT         NOT NULL DEFAULT 0,
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_major_college` (`college_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '专业表';

DROP TABLE IF EXISTS `teacher`;
CREATE TABLE `teacher` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(50) NOT NULL COMMENT '教师姓名',
    `college_id` BIGINT      DEFAULT NULL COMMENT '所属学院',
    `title`      VARCHAR(30) DEFAULT NULL COMMENT '职称',
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_teacher_college` (`college_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '教师表';

DROP TABLE IF EXISTS `semester`;
CREATE TABLE `semester` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(30) NOT NULL COMMENT '学期名称，如 2024-2025-2',
    `start_date` DATE        DEFAULT NULL,
    `end_date`   DATE        DEFAULT NULL,
    `is_current` TINYINT     NOT NULL DEFAULT 0 COMMENT '是否当前学期',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semester_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学期表';

-- ============================================================
-- 三、考试知识体系（考试类别 → 科目 → 知识点）
-- ============================================================

DROP TABLE IF EXISTS `exam_category`;
CREATE TABLE `exam_category` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `code`       VARCHAR(30) NOT NULL COMMENT '编码：SCHOOL/CET4/CET6/NCRE',
    `name`       VARCHAR(50) NOT NULL COMMENT '考试类别名称',
    `type`       VARCHAR(20) NOT NULL COMMENT '类型：SCHOOL校内课程 / CERT证书考试',
    `icon`       VARCHAR(255) DEFAULT NULL COMMENT '图标地址',
    `description` VARCHAR(255) DEFAULT NULL,
    `sort`       INT         NOT NULL DEFAULT 0,
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考试类别表';

DROP TABLE IF EXISTS `subject`;
CREATE TABLE `subject` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `exam_category_id` BIGINT       NOT NULL COMMENT '所属考试类别',
    `name`             VARCHAR(50)  NOT NULL COMMENT '科目名称',
    `description`      VARCHAR(255) DEFAULT NULL,
    `question_count`   INT          NOT NULL DEFAULT 0 COMMENT '题目数（冗余统计）',
    `resource_count`   INT          NOT NULL DEFAULT 0 COMMENT '资料数（冗余统计）',
    `sort`             INT          NOT NULL DEFAULT 0,
    `status`           TINYINT      NOT NULL DEFAULT 1,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_subject_exam` (`exam_category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '科目表';

DROP TABLE IF EXISTS `knowledge_point`;
CREATE TABLE `knowledge_point` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `subject_id` BIGINT      NOT NULL COMMENT '所属科目',
    `parent_id`  BIGINT      NOT NULL DEFAULT 0 COMMENT '父知识点，0为根',
    `name`       VARCHAR(80) NOT NULL COMMENT '知识点名称',
    `level`      TINYINT     NOT NULL DEFAULT 1 COMMENT '层级：1章节 2知识点 3考点',
    `sort`       INT         NOT NULL DEFAULT 0,
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted` TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_kp_subject` (`subject_id`),
    KEY `idx_kp_parent` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '知识点表（树形）';

DROP TABLE IF EXISTS `course`;
CREATE TABLE `course` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(80) NOT NULL COMMENT '课程/开课班名称',
    `subject_id`  BIGINT      DEFAULT NULL COMMENT '关联科目',
    `teacher_id`  BIGINT      DEFAULT NULL COMMENT '授课教师',
    `college_id`  BIGINT      DEFAULT NULL COMMENT '所属学院',
    `major_id`    BIGINT      DEFAULT NULL COMMENT '适用专业',
    `semester_id` BIGINT      DEFAULT NULL COMMENT '开课学期',
    `class_name`  VARCHAR(50) DEFAULT NULL COMMENT '班级名称',
    `credit`      DECIMAL(3,1) DEFAULT NULL COMMENT '学分',
    `status`      TINYINT     NOT NULL DEFAULT 1,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_course_semester` (`college_id`, `semester_id`, `teacher_id`),
    KEY `idx_course_subject` (`subject_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程/开课班表';

-- ============================================================
-- 四、题库（全题型统一存储）
-- ============================================================

DROP TABLE IF EXISTS `question`;
CREATE TABLE `question` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `subject_id`   BIGINT       NOT NULL COMMENT '所属科目',
    `type`         VARCHAR(30)  NOT NULL COMMENT '题型枚举，见下方注释',
    `stem`         TEXT         NOT NULL COMMENT '题干',
    `material`     MEDIUMTEXT   DEFAULT NULL COMMENT '材料正文（组合题/阅读题用）',
    `difficulty`   TINYINT      NOT NULL DEFAULT 3 COMMENT '难度 1-5',
    `source`       VARCHAR(100) DEFAULT NULL COMMENT '来源',
    `tags`         VARCHAR(255) DEFAULT NULL COMMENT '标签，逗号分隔',
    `analysis`     TEXT         DEFAULT NULL COMMENT '解析',
    `score`        INT          NOT NULL DEFAULT 1 COMMENT '分值',
    `parent_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '父题ID，0为独立题（组合题用）',
    `sort`         INT          NOT NULL DEFAULT 0 COMMENT '子题排序',
    `audit_status` VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：DRAFT/PENDING/APPROVED/REJECTED/OFFLINE',
    `created_by`   BIGINT       DEFAULT NULL COMMENT '创建人',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_q_subject` (`subject_id`),
    KEY `idx_q_type` (`type`),
    KEY `idx_q_difficulty` (`difficulty`),
    KEY `idx_q_status` (`audit_status`),
    KEY `idx_q_parent` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目表。题型枚举：SINGLE_CHOICE/MULTI_CHOICE/TRUE_FALSE/FILL_BLANK/MATCHING/ORDERING/CLOZE/READING/LISTENING/WORD_FILL/SHORT_ANSWER/ESSAY/TERM_EXPLAIN/CALCULATION/APPLICATION/PROGRAMMING/CASE_ANALYSIS/WRITING/TRANSLATION/ERROR_CORRECTION/PROOF/COMPOSITE';

DROP TABLE IF EXISTS `question_option`;
CREATE TABLE `question_option` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `question_id` BIGINT       NOT NULL COMMENT '题目ID',
    `option_key`  VARCHAR(10)  NOT NULL COMMENT '选项标识：A/B/C/D 或 1/2/3',
    `content`     VARCHAR(500) NOT NULL COMMENT '选项内容',
    `sort`        INT          NOT NULL DEFAULT 0,
    `is_answer`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否正确答案',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_opt_question` (`question_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目选项表（客观题答案存 is_answer）';

DROP TABLE IF EXISTS `question_answer`;
CREATE TABLE `question_answer` (
    `id`             BIGINT     NOT NULL AUTO_INCREMENT,
    `question_id`    BIGINT     NOT NULL COMMENT '题目ID',
    `answer_content` JSON       DEFAULT NULL COMMENT '答案内容（JSON，按题型存不同结构）',
    `grading_points` JSON       DEFAULT NULL COMMENT '评分要点（JSON 数组）',
    `case_sensitive` TINYINT    NOT NULL DEFAULT 0 COMMENT '填空是否大小写敏感',
    `created_at`     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_answer_question` (`question_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目答案表。JSON 结构示例：填空 {"blanks":["答案1","答案2"]}；简答 {"reference":"参考答案","keywords":["关键词"]}；作文 {"modelEssay":"范文","wordLimit":120}；翻译 {"referenceTranslation":"译文","direction":"zh2en"}';

DROP TABLE IF EXISTS `question_knowledge`;
CREATE TABLE `question_knowledge` (
    `question_id`  BIGINT   NOT NULL,
    `knowledge_id` BIGINT   NOT NULL,
    `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`question_id`, `knowledge_id`),
    KEY `idx_qk_knowledge` (`knowledge_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目-知识点关联表';

-- ============================================================
-- 五、资料库（文件 / 资料 / 版本）
-- ============================================================

DROP TABLE IF EXISTS `file_record`;
CREATE TABLE `file_record` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `stored_path`   VARCHAR(500) NOT NULL COMMENT '存储相对路径',
    `md5`           VARCHAR(64)  NOT NULL COMMENT '文件MD5（去重复用）',
    `file_size`     BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    `mime_type`     VARCHAR(100) DEFAULT NULL,
    `ext`           VARCHAR(20)  DEFAULT NULL COMMENT '扩展名',
    `uploader_id`   BIGINT       DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_file_md5` (`md5`),
    KEY `idx_file_uploader` (`uploader_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件表';

DROP TABLE IF EXISTS `resource`;
CREATE TABLE `resource` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `title`            VARCHAR(150) NOT NULL COMMENT '资料标题',
    `exam_category_id` BIGINT       NOT NULL COMMENT '考试类别',
    `subject_id`       BIGINT       DEFAULT NULL COMMENT '科目',
    `knowledge_id`     BIGINT       DEFAULT NULL COMMENT '知识点',
    `course_id`        BIGINT       DEFAULT NULL COMMENT '关联课程（校内）',
    `type`             VARCHAR(30)  NOT NULL COMMENT '类型：COURSEWARE课件/NOTE笔记/SUMMARY总结/MOCK模拟卷/RECALL回忆题型/ORIGINAL原创题',
    `description`      VARCHAR(500) DEFAULT NULL COMMENT '简介',
    `current_file_id`  BIGINT       NOT NULL COMMENT '当前版本文件ID',
    `page_count`       INT          NOT NULL DEFAULT 0 COMMENT '总页数（PDF转图片后）',
    `download_count`   INT          NOT NULL DEFAULT 0 COMMENT '下载量',
    `view_count`       INT          NOT NULL DEFAULT 0 COMMENT '浏览量',
    `rating_avg`       DECIMAL(3,2) NOT NULL DEFAULT 0.00 COMMENT '平均分',
    `rating_count`     INT          NOT NULL DEFAULT 0 COMMENT '评分人数',
    `uploader_id`      BIGINT       NOT NULL COMMENT '上传人',
    `audit_status`     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_res_exam` (`exam_category_id`),
    KEY `idx_res_subject` (`subject_id`),
    KEY `idx_res_status` (`audit_status`),
    KEY `idx_res_uploader` (`uploader_id`),
    KEY `idx_res_download` (`download_count`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资料表';

DROP TABLE IF EXISTS `resource_version`;
CREATE TABLE `resource_version` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `resource_id`  BIGINT      NOT NULL COMMENT '资料ID',
    `version_no`   INT         NOT NULL DEFAULT 1 COMMENT '版本号',
    `file_id`      BIGINT      NOT NULL COMMENT '该版本文件ID',
    `audit_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `submit_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_rv_resource` (`resource_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资料版本表';

-- 资料预览图（PDF 转图片后逐页存这里，游客只返回前 3 页）
DROP TABLE IF EXISTS `resource_page`;
CREATE TABLE `resource_page` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `resource_id` BIGINT       NOT NULL COMMENT '资料ID',
    `page_no`     INT          NOT NULL COMMENT '页码，从1开始',
    `image_path`  VARCHAR(500) NOT NULL COMMENT '预览图存储路径',
    `image_width` INT          DEFAULT NULL,
    `image_height` INT         DEFAULT NULL,
    `is_thumbnail` TINYINT     NOT NULL DEFAULT 0 COMMENT '是否低清缩略图',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_resource_no` (`resource_id`, `page_no`, `is_thumbnail`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资料预览图表';

-- ============================================================
-- 六、审核与举报
-- ============================================================

DROP TABLE IF EXISTS `audit_record`;
CREATE TABLE `audit_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `target_type` VARCHAR(20)  NOT NULL COMMENT '对象类型：QUESTION/RESOURCE',
    `target_id`   BIGINT       NOT NULL COMMENT '对象ID',
    `auditor_id`  BIGINT       NOT NULL COMMENT '审核员ID',
    `result`      VARCHAR(20)  NOT NULL COMMENT '结果：APPROVED/REJECTED/OFFLINE',
    `reason`      VARCHAR(500) DEFAULT NULL COMMENT '驳回/下架原因',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_target` (`target_type`, `target_id`),
    KEY `idx_audit_auditor` (`auditor_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审核记录表';

DROP TABLE IF EXISTS `report`;
CREATE TABLE `report` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `target_type` VARCHAR(20)  NOT NULL COMMENT '对象类型：QUESTION/RESOURCE',
    `target_id`   BIGINT       NOT NULL,
    `reporter_id` BIGINT       NOT NULL COMMENT '举报人',
    `reason`      VARCHAR(50)  NOT NULL COMMENT '举报原因',
    `description` VARCHAR(500) DEFAULT NULL,
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/VALID/INVALID',
    `handler_id`  BIGINT       DEFAULT NULL COMMENT '处理人',
    `handle_note` VARCHAR(500) DEFAULT NULL,
    `handled_at`  DATETIME     DEFAULT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_report_target` (`target_type`, `target_id`),
    KEY `idx_report_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '举报表';

-- ============================================================
-- 七、学习记录（练习 / 答题 / 自评 / 错题 / 下载）
-- ============================================================

DROP TABLE IF EXISTS `practice_record`;
CREATE TABLE `practice_record` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT      NOT NULL,
    `subject_id`     BIGINT      DEFAULT NULL,
    `knowledge_id`   BIGINT      DEFAULT NULL,
    `mode`           VARCHAR(20) NOT NULL DEFAULT 'SEQUENCE' COMMENT '模式：SEQUENCE顺序/RANDOM随机/BY_TYPE按题型/WRONG_BOOK错题重做',
    `question_type`  VARCHAR(30) DEFAULT NULL COMMENT '按题型练习时的题型',
    `total_count`    INT         NOT NULL DEFAULT 0 COMMENT '总题数',
    `answered_count` INT         NOT NULL DEFAULT 0 COMMENT '已答题数',
    `correct_count`  INT         NOT NULL DEFAULT 0 COMMENT '正确数（主观题按自评折算）',
    `status`         VARCHAR(20) NOT NULL DEFAULT 'ONGOING' COMMENT '状态：ONGOING/FINISHED',
    `started_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`    DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_pr_user` (`user_id`),
    KEY `idx_pr_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '练习记录表';

DROP TABLE IF EXISTS `answer_detail`;
CREATE TABLE `answer_detail` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `practice_record_id` BIGINT      NOT NULL COMMENT '练习记录ID',
    `question_id`        BIGINT      NOT NULL,
    `user_answer`        TEXT        DEFAULT NULL COMMENT '用户答案（JSON或文本）',
    `is_correct`         TINYINT     DEFAULT NULL COMMENT '是否正确（主观题为NULL）',
    `self_eval`          VARCHAR(20) DEFAULT NULL COMMENT '自评：KNOW会/UNSURE模糊/DONT_KNOW不会',
    `score_got`          DECIMAL(4,1) NOT NULL DEFAULT 0 COMMENT '得分（自评折算）',
    `answered_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ad_record` (`practice_record_id`),
    KEY `idx_ad_question` (`question_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '答题明细表';

DROP TABLE IF EXISTS `self_evaluation`;
CREATE TABLE `self_evaluation` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT      NOT NULL,
    `question_id`        BIGINT      NOT NULL,
    `practice_record_id` BIGINT      DEFAULT NULL,
    `result`             VARCHAR(20) NOT NULL COMMENT 'KNOW会/UNSURE模糊/DONT_KNOW不会',
    `created_at`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_se_user_question` (`user_id`, `question_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '主观题自评表';

DROP TABLE IF EXISTS `wrong_book`;
CREATE TABLE `wrong_book` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT      NOT NULL,
    `question_id`   BIGINT      NOT NULL,
    `source`        VARCHAR(20) NOT NULL DEFAULT 'PRACTICE' COMMENT '来源：PRACTICE答错/SELF_EVAL自评不会',
    `wrong_count`   INT         NOT NULL DEFAULT 1 COMMENT '错误次数',
    `last_wrong_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `mastered`      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已掌握（重做正确后置1）',
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wrong_user_question` (`user_id`, `question_id`),
    KEY `idx_wrong_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '错题本表';

DROP TABLE IF EXISTS `download_record`;
CREATE TABLE `download_record` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT   NOT NULL,
    `resource_id` BIGINT   NOT NULL,
    `ip`          VARCHAR(64) DEFAULT NULL,
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_download_user_resource` (`user_id`, `resource_id`),
    KEY `idx_download_resource` (`resource_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '下载记录表（同用户同资料只记一次）';

DROP TABLE IF EXISTS `favorite`;
CREATE TABLE `favorite` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT      NOT NULL,
    `target_type` VARCHAR(20) NOT NULL COMMENT 'QUESTION/RESOURCE',
    `target_id`   BIGINT      NOT NULL,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fav_user_target` (`user_id`, `target_type`, `target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收藏表（题目/资料通用）';

-- ============================================================
-- 八、通知 / 公告 / 日志
-- ============================================================

DROP TABLE IF EXISTS `notification`;
CREATE TABLE `notification` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL COMMENT '接收人',
    `type`       VARCHAR(30)  NOT NULL COMMENT '类型：AUDIT_RESULT/REPORT_RESULT/SYSTEM',
    `title`      VARCHAR(150) NOT NULL,
    `content`    VARCHAR(500) DEFAULT NULL,
    `ref_type`   VARCHAR(20)  DEFAULT NULL COMMENT '关联对象类型',
    `ref_id`     BIGINT       DEFAULT NULL COMMENT '关联对象ID',
    `read_at`    DATETIME     DEFAULT NULL COMMENT '已读时间，NULL为未读',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_notify_user_read` (`user_id`, `read_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知表';

DROP TABLE IF EXISTS `announcement`;
CREATE TABLE `announcement` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `title`        VARCHAR(150) NOT NULL,
    `content`      TEXT         NOT NULL,
    `publisher_id` BIGINT       NOT NULL,
    `status`       VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED',
    `top_flag`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶',
    `publish_at`   DATETIME     DEFAULT NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_ann_status` (`status`, `top_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公告表';

DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       DEFAULT NULL,
    `username`    VARCHAR(50)  DEFAULT NULL COMMENT '操作人（冗余，防用户删除后无法追溯）',
    `module`      VARCHAR(50)  NOT NULL COMMENT '模块：AUTH/AUDIT/USER/EXAM/RESOURCE',
    `action`      VARCHAR(50)  NOT NULL COMMENT '动作：LOGIN/CREATE/UPDATE/DELETE/AUDIT',
    `target_type` VARCHAR(30)  DEFAULT NULL,
    `target_id`   BIGINT       DEFAULT NULL,
    `detail`      VARCHAR(1000) DEFAULT NULL,
    `ip`          VARCHAR(64)  DEFAULT NULL,
    `user_agent`  VARCHAR(255) DEFAULT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_log_user` (`user_id`),
    KEY `idx_log_module` (`module`),
    KEY `idx_log_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 建表完成：共 28 张 P0 核心表
-- 已按 v2 大纲移除：积分账户表、积分流水表、签到记录表
-- ============================================================
