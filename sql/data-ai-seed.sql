-- ============================================================
-- AI 语料扩充种子数据（RAG 检索层）
--
-- 依据：sql/migration-ai.sql 第 51-83 行预留的扩充入口。
-- 特性：
--   1. 全部幂等：以 source='AI语料扩充-2026' + stem 为判据，
--      重复执行不产生重复题目/答案/知识点关联。
--   2. 只写主观题（SHORT_ANSWER / TERM_EXPLAIN），参考答案进索引；
--      客观题答案绝不入索引（防泄题，见 ChunkExtractor R7）。
--   3. 使用库中真实存在的 subject_id / knowledge_id（信息与计算科学专业课程体系）。
--
-- 执行顺序：
--   mysql -uroot -p exam_prep < sql/data-ai-seed.sql
--   然后调用 POST /api/admin/ai/reindex（AUDITOR/ADMIN）让 ChunkSyncService 建索引块
--
-- 【回滚】DELETE FROM question_option WHERE question_id IN (SELECT id FROM question WHERE source='AI语料扩充-2026');
--        DELETE FROM question_answer  WHERE question_id IN (SELECT id FROM question WHERE source='AI语料扩充-2026');
--        DELETE FROM question_knowledge WHERE question_id IN (SELECT id FROM question WHERE source='AI语料扩充-2026');
--        DELETE FROM question WHERE source='AI语料扩充-2026';
--        DELETE FROM ai_chunk WHERE source_type='QUESTION' AND source_id NOT IN (SELECT id FROM question);
-- ============================================================

USE exam_prep;

SET @SRC = 'AI语料扩充-2026';

-- ---------- 数据结构（subject_id=4）----------

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 4,'SHORT_ANSWER','简述顺序表和链表的区别。',NULL,3,@SRC,'数据结构,线性表,顺序表,链表',
'顺序表用连续空间存储，支持随机访问（O(1)），插入删除平均需移动元素（O(n)），容量一般固定；链表用指针链接结点，只能顺序访问（O(n)），插入删除只需改指针（O(1)），容量可动态增长。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述顺序表和链表的区别。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','顺序表：连续存储、随机访问 O(1)、插入删除 O(n)、容量固定；链表：指针链接、顺序访问 O(n)、插入删除 O(1)、动态扩容。',
                         'keywords',JSON_ARRAY('顺序表','链表','随机访问','时间复杂度','动态扩容')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述顺序表和链表的区别。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 11 FROM question q WHERE q.source=@SRC AND q.stem='简述顺序表和链表的区别。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 4,'SHORT_ANSWER','简述栈和队列的区别。',NULL,2,@SRC,'数据结构,栈,队列',
'栈是后进先出（LIFO）的受限线性表，只在栈顶插入删除；队列是先进先出（FIFO）的受限线性表，一端入队、另一端出队。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述栈和队列的区别。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','栈：后进先出 LIFO，仅在栈顶插入删除；队列：先进先出 FIFO，队尾入队、队头出队。',
                         'keywords',JSON_ARRAY('栈','队列','LIFO','FIFO','受限线性表')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述栈和队列的区别。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 12 FROM question q WHERE q.source=@SRC AND q.stem='简述栈和队列的区别。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 4,'SHORT_ANSWER','二叉树有哪几种遍历方式？分别说明访问顺序。',NULL,2,@SRC,'数据结构,树与二叉树,遍历',
'前序：根—左—右；中序：左—根—右；后序：左—右—根；层序：按层从左到右依次访问。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='二叉树有哪几种遍历方式？分别说明访问顺序。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','前序遍历：根左右；中序遍历：左根右；后序遍历：左右根；层序遍历：自上而下、自左向右逐层访问。',
                         'keywords',JSON_ARRAY('前序','中序','后序','层序','遍历')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='二叉树有哪几种遍历方式？分别说明访问顺序。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 15 FROM question q WHERE q.source=@SRC AND q.stem='二叉树有哪几种遍历方式？分别说明访问顺序。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 4,'SHORT_ANSWER','简述满二叉树与完全二叉树的区别。',NULL,3,@SRC,'数据结构,树与二叉树',
'满二叉树：每层结点数都达到最大，深度为 k 时有 2^k−1 个结点；完全二叉树：除最后一层外每层都满，且最后一层结点集中在左侧。满二叉树一定是完全二叉树，反之不成立。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述满二叉树与完全二叉树的区别。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','满二叉树每层都满，共 2^k−1 个结点；完全二叉树除最后一层外每层都满，最后一层结点靠左连续。',
                         'keywords',JSON_ARRAY('满二叉树','完全二叉树','结点数','深度')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述满二叉树与完全二叉树的区别。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 14 FROM question q WHERE q.source=@SRC AND q.stem='简述满二叉树与完全二叉树的区别。';

-- ---------- C 语言程序设计（subject_id=5）----------

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 5,'SHORT_ANSWER','什么是野指针？如何避免？',NULL,3,@SRC,'C语言,指针',
'野指针是指向不确定内存的指针（未初始化、指向已释放内存或越界）。避免方法：定义时初始化为 NULL、free 后立即置 NULL、不返回局部变量地址、使用前判空。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='什么是野指针？如何避免？');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','野指针：指向不确定内存的指针。避免：初始化置 NULL、释放后置 NULL、不返回局部变量地址、使用前判空。',
                         'keywords',JSON_ARRAY('野指针','空指针','初始化','free','悬垂指针')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='什么是野指针？如何避免？'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 24 FROM question q WHERE q.source=@SRC AND q.stem='什么是野指针？如何避免？';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 5,'SHORT_ANSWER','简述函数递归的两个必要条件。',NULL,2,@SRC,'C语言,函数,递归',
'递归必须同时满足：①存在递归终止条件（基准情形）；②每次递归调用都在向终止条件收敛，保证递归可终止。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述函数递归的两个必要条件。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','必要条件：1) 有终止条件（基准情形，直接可解）；2) 递归调用逐步逼近终止条件，即规模递减、不会无限递归。',
                         'keywords',JSON_ARRAY('递归','终止条件','基准情形','递归出口','栈溢出')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述函数递归的两个必要条件。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 7 FROM question q WHERE q.source=@SRC AND q.stem='简述函数递归的两个必要条件。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 5,'SHORT_ANSWER','值传递和地址传递有什么区别？',NULL,3,@SRC,'C语言,函数,参数传递',
'值传递把实参的值复制给形参，形参改变不影响实参；地址传递把实参地址传给形参（指针/引用），函数内可通过地址修改实参所指内容。C 语言只有值传递，传指针本质是传地址值。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='值传递和地址传递有什么区别？');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','值传递：拷贝实参的值，形参修改不影响实参。地址传递：传入实参地址，形参可修改实参指向的内容。C 只支持值传递，传指针是把地址值拷贝过去。',
                         'keywords',JSON_ARRAY('值传递','地址传递','指针','形参','实参')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='值传递和地址传递有什么区别？'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 10 FROM question q WHERE q.source=@SRC AND q.stem='值传递和地址传递有什么区别？';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 5,'SHORT_ANSWER','简述指针和数组的关系。',NULL,3,@SRC,'C语言,指针,数组',
'数组名在多数表达式中退化为指向首元素的指针；可用指针算术访问数组元素，a[i] 等价于 *(a+i)。但数组名不是指针变量，不能被赋值，sizeof(数组名) 得到的是整个数组大小。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述指针和数组的关系。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','数组名在表达式中退化为首元素指针，a[i] 等价于 *(a+i)；但数组名不可赋值，sizeof(数组名) 是整个数组长度。',
                         'keywords',JSON_ARRAY('指针','数组','首地址','指针算术','sizeof')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述指针和数组的关系。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 9 FROM question q WHERE q.source=@SRC AND q.stem='简述指针和数组的关系。';

-- ---------- 高等数学A（subject_id=1）----------

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 1,'SHORT_ANSWER','叙述函数 f(x) 在点 x0 处连续的定义。',NULL,3,@SRC,'高等数学,极限与连续',
'设函数 f(x) 在 x0 的某邻域内有定义，若 lim(x→x0) f(x) 存在且等于 f(x0)，则称 f(x) 在 x0 处连续。等价于 lim(Δx→0) Δy = 0。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='叙述函数 f(x) 在点 x0 处连续的定义。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','f(x) 在 x0 邻域有定义，且 lim(x→x0) f(x) = f(x0)，即左极限、右极限存在且相等并等于函数值。',
                         'keywords',JSON_ARRAY('连续','极限','函数值','左极限','右极限')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='叙述函数 f(x) 在点 x0 处连续的定义。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 16 FROM question q WHERE q.source=@SRC AND q.stem='叙述函数 f(x) 在点 x0 处连续的定义。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 1,'SHORT_ANSWER','简述导数的几何意义。',NULL,2,@SRC,'高等数学,导数与微分',
'函数 f(x) 在点 x0 处的导数 f''(x0) 表示曲线 y=f(x) 在点 (x0, f(x0)) 处切线的斜率；导数存在即曲线在该点有切线且不垂直于 x 轴。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述导数的几何意义。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','导数 f''(x0) 是曲线 y=f(x) 在点 (x0,f(x0)) 处切线的斜率，反映函数在该点的瞬时变化率。',
                         'keywords',JSON_ARRAY('导数','切线斜率','几何意义','瞬时变化率')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述导数的几何意义。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 17 FROM question q WHERE q.source=@SRC AND q.stem='简述导数的几何意义。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 1,'SHORT_ANSWER','叙述复合函数的求导法则（链式法则）。',NULL,3,@SRC,'高等数学,复合函数求导',
'若 y=f(u)、u=g(x) 均可导，则复合函数 y=f(g(x)) 可导，且 dy/dx = f''(u)·g''(x)，即外层导数乘内层导数。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='叙述复合函数的求导法则（链式法则）。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','y=f(u), u=g(x)，则 dy/dx = f''(u)·g''(x)：先对外层关于中间变量求导，再乘以中间变量对自变量求导。',
                         'keywords',JSON_ARRAY('链式法则','复合函数','外层','内层','求导')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='叙述复合函数的求导法则（链式法则）。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 18 FROM question q WHERE q.source=@SRC AND q.stem='叙述复合函数的求导法则（链式法则）。';

-- ---------- 线性代数（subject_id=2）----------

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 2,'SHORT_ANSWER','简述 n 阶方阵 A 可逆的充分必要条件。',NULL,3,@SRC,'线性代数,矩阵',
'A 可逆 ⇔ |A| ≠ 0 ⇔ A 满秩（r(A)=n）⇔ A 的列（行）向量组线性无关 ⇔ 齐次方程组 Ax=0 只有零解 ⇔ A 可表示为若干初等矩阵之积。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述 n 阶方阵 A 可逆的充分必要条件。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','A 可逆 ⇔ 行列式 |A|≠0 ⇔ r(A)=n（满秩）⇔ 列向量组线性无关 ⇔ Ax=0 仅有零解。',
                         'keywords',JSON_ARRAY('可逆','行列式','满秩','线性无关','零解')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述 n 阶方阵 A 可逆的充分必要条件。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 20 FROM question q WHERE q.source=@SRC AND q.stem='简述 n 阶方阵 A 可逆的充分必要条件。';

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 2,'SHORT_ANSWER','列举行列式的主要性质（至少三条）。',NULL,3,@SRC,'线性代数,行列式',
'① 转置行列式与原行列式相等；② 互换两行（列）行列式变号；③ 某行（列）有公因子可提到行列式外；④ 两行（列）成比例则行列式为 0；⑤ 某行（列）加上另一行（列）的 k 倍，行列式不变。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='列举行列式的主要性质（至少三条）。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','转置不变；互换两行变号；行（列）公因子可外提；两行成比例则为 0；某行加另一行的 k 倍行列式不变。',
                         'keywords',JSON_ARRAY('行列式','转置','变号','公因子','初等变换')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='列举行列式的主要性质（至少三条）。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);
INSERT IGNORE INTO question_knowledge (question_id, knowledge_id)
SELECT q.id, 19 FROM question q WHERE q.source=@SRC AND q.stem='列举行列式的主要性质（至少三条）。';

-- ---------- 数学分析（一）/ C++ / 大学物理2 / 计算机基础 ----------

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 104,'SHORT_ANSWER','叙述数列极限的 ε-N 定义。',NULL,4,@SRC,'数学分析,极限,数列',
'设 {a_n} 为数列，A 为常数。若对任意 ε>0，总存在正整数 N，使当 n>N 时恒有 |a_n − A| < ε，则称数列 {a_n} 收敛于 A，记作 lim a_n = A。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='叙述数列极限的 ε-N 定义。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','∀ε>0，∃正整数 N，当 n>N 时 |a_n−A|<ε，则 lim a_n=A。ε 刻画逼近精度，N 依赖 ε 而存在。',
                         'keywords',JSON_ARRAY('数列极限','ε-N','收敛','任意','存在')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='叙述数列极限的 ε-N 定义。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 114,'SHORT_ANSWER','简述 C++ 中引用与指针的区别。',NULL,3,@SRC,'C++,引用,指针',
'引用必须初始化且不能重新绑定、本质是变量的别名，使用时无需解引用；指针可为空、可重新赋值、需解引用访问。引用更安全，常用于参数传递与返回值。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述 C++ 中引用与指针的区别。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','引用：必须初始化、不可重绑、自动解引用、不可为空；指针：可空、可重赋、需解引用。引用是别名，指针存地址。',
                         'keywords',JSON_ARRAY('引用','指针','别名','解引用','初始化')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述 C++ 中引用与指针的区别。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 115,'SHORT_ANSWER','叙述动量守恒定律及其成立条件。',NULL,3,@SRC,'大学物理,动量守恒,力学',
'系统所受合外力为零时，系统总动量保持不变。成立条件：合外力为零，或外力远小于内力且作用时间极短（近似守恒），或在某一方向上合外力为零则该方向动量守恒。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='叙述动量守恒定律及其成立条件。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','合外力为零时系统总动量守恒；或某方向合外力为零则该方向动量守恒。碰撞、爆炸等内力远大于外力时可近似应用。',
                         'keywords',JSON_ARRAY('动量守恒','合外力','内力','冲量','碰撞')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='叙述动量守恒定律及其成立条件。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);

INSERT INTO question (subject_id, type, stem, material, difficulty, source, tags, analysis, score, parent_id, sort, audit_status, created_by)
SELECT 100,'SHORT_ANSWER','简述冯·诺依曼体系结构的核心思想。',NULL,2,@SRC,'计算机基础,体系结构',
'核心是「存储程序」与「程序控制」：程序和数据同以二进制形式存放在同一存储器中，计算机按存储地址顺序自动逐条取出指令并执行，由运算器、控制器、存储器、输入设备、输出设备五大部件组成。',5,0,1,'APPROVED',1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM question WHERE source=@SRC AND stem='简述冯·诺依曼体系结构的核心思想。');
INSERT INTO question_answer (question_id, answer_content, grading_points, case_sensitive)
SELECT q.id, JSON_OBJECT('reference','存储程序 + 程序控制：程序与数据统一以二进制存于同一存储器，机器自动按序取指执行；由五大部件构成。',
                         'keywords',JSON_ARRAY('冯诺依曼','存储程序','五大部件','指令','二进制')), NULL, 0
FROM question q WHERE q.source=@SRC AND q.stem='简述冯·诺依曼体系结构的核心思想。'
  AND NOT EXISTS (SELECT 1 FROM question_answer a WHERE a.question_id=q.id);

-- ---------- 自检 ----------
-- SELECT COUNT(*) AS seeded FROM question WHERE source='AI语料扩充-2026';
-- 之后执行：POST /api/admin/ai/reindex 让语料进入 ai_chunk
