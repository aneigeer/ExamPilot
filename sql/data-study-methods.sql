-- 补充备考核心方法与常见跨考高频知识点（幂等，可重复执行）
USE exam_prep;

INSERT INTO `knowledge_point` (`id`, `subject_id`, `parent_id`, `name`, `level`, `sort`, `status`, `is_deleted`)
VALUES
    (101, 7,   0, '四级阅读正确率提升方法',       1, 10, 1, 0),
    (102, 200, 0, '考研数学求极限常用方法',       1, 10, 1, 0),
    (103, 203, 0, '考研英语一和英语二的区别',     1, 10, 1, 0),
    (104, 217, 0, '面试项目经历STAR介绍方法',     1, 10, 1, 0),
    (105, 2,   0, '线性代数特征值与特征向量求解', 1, 10, 1, 0)
ON DUPLICATE KEY UPDATE
    `subject_id` = VALUES(`subject_id`),
    `name`       = VALUES(`name`),
    `status`     = 1,
    `is_deleted` = 0;

INSERT INTO `ai_chunk`
    (`source_type`, `source_id`, `chunk_no`, `title`, `content`,
     `exam_category_id`, `subject_id`, `knowledge_id`, `difficulty`,
     `ref_type`, `ref_id`, `page_no`, `content_hash`, `source_updated_at`,
     `status`, `vector_synced`)
VALUES
    ('KNOWLEDGE', 101, 0, '四级阅读正确率提升方法',
     '四级阅读怎么提高正确率？核心分为四步：1. 先读题干划定位词（人名、数字、专有名词、核心名词），不要先通读全文；2. 回原文定位段落，重点精读定位句及其前后一句；3. 识别同义替换，正确答案通常是对原文核心意思的同义改写，而不是原词机械拼接；4. 排除绝对化、偷换主语、无中生有和过度推理等干扰项。长篇阅读注重段落首尾句与关键词快速匹配，仔细阅读重在精准定位与逻辑衔接。',
     2, 7, 101, 2, 'KNOWLEDGE', 101, NULL,
     MD5('KNOWLEDGE:101:v1'), NOW(), 'ACTIVE', 0),

    ('KNOWLEDGE', 102, 0, '考研数学求极限常用方法',
     '考研数学求极限有哪些常用方法？主要有七类方法：1. 等价无穷小替换（乘除因子可直接替换，加减需结合泰勒展开）；2. 泰勒公式展开（处理 0/0 型加减相消问题最稳妥）；3. 洛必达法则（适用于 0/0 和 ∞/∞ 型且导数存在、化简后比值极限存在）；4. 两个重要极限与 1^∞ 型标准公式 e^{lim (u-1)v}；5. 夹逼准则（常用于数列和式与放缩极限）；6. 单调有界准则（常用于递推数列）；7. 定积分定义与抓大头（无穷多项和或高阶幂比值）。',
     7, 200, 102, 3, 'KNOWLEDGE', 102, NULL,
     MD5('KNOWLEDGE:102:v1'), NOW(), 'ACTIVE', 0),

    ('KNOWLEDGE', 103, 0, '考研英语一和英语二的区别',
     '考研英语一和英语二的区别主要体现在四方面：1. 适用专业：英语一主要面向学硕及部分专硕，英语二主要面向大多数专硕；2. 词汇与长难句难度：英语一大纲允许约 3% 超纲词汇，长难句嵌套和主旨推理更深，英语二语言更平实；3. 翻译题型：英语一是阅读理解式 5 句划线长难句翻译（10 分），英语二是整段短文翻译（15 分）；4. 作文与新题型：英语一大作文多为图画作文（20 分），英语二多为图表作文（15 分）；英语二新题型常考信息匹配与小标题，难度低于英语一的七选五和排序题。',
     7, 203, 103, 2, 'KNOWLEDGE', 103, NULL,
     MD5('KNOWLEDGE:103:v1'), NOW(), 'ACTIVE', 0),

    ('KNOWLEDGE', 104, 0, '面试项目经历STAR介绍方法',
     '面试时如何介绍自己的项目经历？建议采用 STAR 法则配合技术深挖：1. Situation（背景与定位，15 秒讲清项目解决什么业务痛点及规模）；2. Task（个人职责，明确自己独立负责的核心模块而非泛泛而谈团队成果）；3. Action（核心技术方案与难点攻关，重点讲架构选型权衡、踩坑排查过程和关键代码/算法优化）；4. Result（量化收益，如延迟降低、准确率提升、吞吐量改善或稳定性指标）。同时提前准备 2~3 个面试官可能追问的延伸点。',
     690, 217, 104, 2, 'KNOWLEDGE', 104, NULL,
     MD5('KNOWLEDGE:104:v1'), NOW(), 'ACTIVE', 0),

    ('KNOWLEDGE', 105, 0, '线性代数特征值与特征向量求解',
     '线性代数中特征值怎么求？求解 n 阶矩阵 A 的特征值和特征向量分三步：1. 构造特征方程 |λE - A| = 0（或 |A - λE| = 0），因式分解求出全部特征值 λ；2. 对每个特征值 λ_i，求解齐次线性方程组 (λ_i E - A)x = 0；3. 用初等行变换化简系数矩阵，求出该方程组的基础解系，基础解系的非零线性组合（不全为零）即为对应特征值 λ_i 的全部特征向量。若是实对称矩阵，不同特征值对应的特征向量正交，还可通过正交化与单位化用于相似对角化。',
     1, 2, 105, 3, 'KNOWLEDGE', 105, NULL,
     MD5('KNOWLEDGE:105:v1'), NOW(), 'ACTIVE', 0)
ON DUPLICATE KEY UPDATE
    `title`             = VALUES(`title`),
    `content`           = VALUES(`content`),
    `exam_category_id`  = VALUES(`exam_category_id`),
    `subject_id`        = VALUES(`subject_id`),
    `knowledge_id`      = VALUES(`knowledge_id`),
    `content_hash`      = VALUES(`content_hash`),
    `source_updated_at` = NOW(),
    `status`            = 'ACTIVE',
    `vector_synced`     = 0;
