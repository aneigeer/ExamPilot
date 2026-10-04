# 大学生多考试备考知识库与题库系统

> 面向高校学生的**资料治理 + 智能备考**平台。Android 学生端 + Web 管理端 + Spring Boot 后端三端架构，覆盖「资料上传—审核—预览—下载—举报—下架」治理闭环与「刷题—判分—错题—模考」学习闭环，内置**中南林业科技大学真实教学计划数据**。

软件工程课程设计 / 毕业设计，并作为****（）项目。不商用、不上架。

---

## 一、项目状态（数据均为实测，非估算）

| 模块 | 状态 | 关键指标（2026-10 实测） |
|---|---|---|
| 后端 | ✅ 完成 | Spring Boot 3.5.3 + JDK 21，**137 个接口**，判分单测 12/12 通过 |
| 数据库 | ✅ 完成 | MySQL 8，**41 张表**，schema + data + 4 个可重复执行增量迁移 |
| Android 学生端 | ✅ 完成 | Java + XML 原生，**28 个页面**（24 Activity + 4 Fragment），`备考知识库学生端-v1.4-真机版.apk` 可直接安装 |
| Web 管理端 | ✅ 完成 | Vue3 + Element Plus 单页（CDN 本地化），由后端同源托管，零构建零跨域 |
| 工程文档 | ✅ 完成 | 12 份文档（需求/概要/详细/数据库/API/测试/用户手册/部署/总结 + 验收与注册验证报告） |
| 真实数据 | ✅ 已导入 | 中南林业科技大学 + **23 个学院** + 信息与计算科学专业 **90 门课程**（来自教务教学执行计划） |

### 尚未实现（诚实声明，路线图见第十节）

- ❌ **无 RAG / 大模型问答**：当前不含任何 embedding、向量库、AI 问答接口（全项目 grep 为 0）
- ❌ **无 OCR 试卷识别**
- ❌ **无积分体系**：社区激励以「审核贡献 + 收藏/下载记录」替代积分
- ⚠️ **学情分析仅为统计级**：已有练习/模考/错题统计与趋势数据，**未做画像与个性化推送**

---

## 二、目录结构

```
大学生多考试备考知识库与题库系统/
├── backend/            后端服务（Java 21 + Spring Boot 3.5.3，137 接口，内嵌管理端页面）
├── admin-web/          管理端 Web 源码（Vue3 + Element Plus 单页应用）
├── student-app/        学生端 App（Android 原生：Java + XML，Gradle 工程）
├── sql/                数据库脚本（schema / data / 4 个增量迁移，均可重复执行）
├── docs/               12 份工程文档 + 性能测试脚本
├── docker-compose.yml / nginx.conf / backup.bat     部署与运维
├── 启动后端.bat / 放行8080端口(需管理员).bat          Windows 一键脚本
├── 备考知识库学生端-v1.4-真机版.apk                   学生端安装包（Android 8+ 直接安装）
└── README.md
```

---

## 三、技术选型

| 端 | 技术栈 |
|---|---|
| 学生端 | Android 原生：Java + XML + ViewBinding + Retrofit/OkHttp/Gson + Glide + RecyclerView + Material 3（compileSdk 34 / minSdk 26，Gradle 8.7 + AGP 8.5.2） |
| 管理端 | Vue3 + Element Plus（CDN 本地化单页应用，后端同源托管） |
| 后端 | JDK 21 + Spring Boot 3.5.3 + Spring Security 6 + JWT（jjwt 0.12.6）+ MyBatis-Plus 3.5.7 + springdoc-openapi 2.8.6 + PDFBox 3.0.7 |
| 数据库 | MySQL 8.0（41 张表；主观题/复杂题答案以 MySQL JSON 承载） |
| 构建 | Maven（后端）/ Gradle 8.7 + AGP 8.5.2（学生端） |

---

## 四、快速开始

### 1. 初始化数据库（可重复执行）

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/data.sql
mysql -u root -p < sql/migration-p0b.sql
mysql -u root -p < sql/migration-p1.sql
mysql -u root -p < sql/migration-p1b.sql
mysql -u root -p < sql/migration-register.sql            # 学校 + 23 学院 + 自助注册
mysql -u root -p < sql/migration-info-computing-subjects.sql   # 90 门真实课程
```

### 2. 配置本机口令

复制 `backend/src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入本机 MySQL 密码（该文件已被 `.gitignore` 排除）。JWT 密钥同理在此配置，未配置时后端拒绝启动。

### 3. 启动后端

- Windows：双击 **`启动后端.bat`**（等价于 `java -jar ... --server.port=8080`）
- 或命令行：`cd backend && mvn spring-boot:run`

| 入口 | 地址 |
|---|---|
| 管理端 | <http://localhost:8080/api/admin/index.html> |
| 接口文档 | <http://localhost:8080/api/swagger-ui.html> |
| 健康检查 | <http://localhost:8080/api/home> |

> ⚠️ 手机真机联调：手机与电脑需同一局域网；首次可能需右键 `放行8080端口(需管理员).bat` 以管理员身份运行放行防火墙。

### 4. 学生端

- 真机：安装 `备考知识库学生端-v1.4-真机版.apk`（包内 `BASE_URL` 已指向打包时的局域网 IP）
- 模拟器：把 `student-app/app/build.gradle` 的 `BASE_URL` 改回 `http://10.0.2.2:8080/api/` 后 `gradle assembleDebug`
- 换网络后需重打包（IP 变化），构建命令：`cd student-app && gradle assembleDebug`

### 5. Docker 部署（可选）

```bash
docker compose up -d --build   # MySQL + 后端 + Nginx
```

---

## 五、示例账号与注册

| 角色 | 账号 | 密码 | 权限 |
|---|---|---|---|
| 超级管理员 | `admin` | `admin123` | 用户/角色、公告、基础数据、全局日志、统计、封禁 |
| 审核员 | `auditor` | `audit123` | 题目/资料审核、举报处理、申诉处理、审核日志 |
| 学生 | `2021001` | `123456` | 刷题、模考、上传、下载、收藏、笔记、错题本 |

**学生可自助注册**：App 登录页点「没有账号？立即注册」→ 选择学校（默认中南林业科技大学）→ 选择学院 → 填学号/昵称/密码。学号全局唯一，重复注册返回 409；新注册密码需 **≥8 位且含字母和数字**（预置示例账号为历史数据，不受该策略影响）。

---

## 六、核心模型

```
学校 → 学院 → 专业 → 考试类别 → 科目 → 知识点 → 题目 / 资料
```

- **题型**：22 类枚举，实际题库覆盖 11 类（单选 11、填空 6、简答 6、匹配 5、多选 2、排序 2、名解 2、判断 2、听力/编程/阅读各 1）
- **答案存储**：客观题答案存选项表 `is_answer`；主观与复杂题答案存答案表 MySQL JSON（填空 blanks / 匹配 pairs / 排序 order）
- **预览防盗版**：PDFBox 转逐页图片，游客仅前 3 页、逐页水印，登录用户整本带个人水印
- **下载防盗链**：短时效 HMAC 签名 URL（默认 10 分钟），篡改签名或过期时间直接 403
- **审核状态机**：DRAFT → PENDING → APPROVED/REJECTED → OFFLINE → APPEALING → 恢复/维持；审核员自我回避 + 乐观锁（CAS）防并发矛盾

---

## 七、两个核心闭环

| 闭环 | 流程 |
|---|---|
| 学习 | 题库（类别→科目→知识点）→ 六种模式选题 → 客观自动判分 / 主观自评 → 练习报告与趋势 → 错题本自动归集 → 掌握度标记 |
| 治理 | 上传（App 图片 / 管理端文档）→ 审核（通过/驳回回填理由）→ 预览 → 签名下载 → 举报 → 核验下架 → 申诉 → 恢复/维持；全程操作日志可追溯 |

---

## 八、工程质量与安全（已落地并实测）

| 能力 | 实现要点 |
|---|---|
| 统一异常处理 | 全局 `GlobalExceptionHandler` 统一 `{code,message,data,traceId}`，业务码落在 400–599 时同步 HTTP 状态码 |
| 权限与鉴权 | 四级角色 + JWT；白名单集中于 `SecurityConfig`；接口逐个标注访问级别 |
| 暴力破解防护 | 登录 5 次/分钟、注册 3 次/分钟（按 IP，并优先取 X-Forwarded-For 兼容反向代理场景） |
| 文件安全 | 扩展名 + **文件魔数**双校验（jpg/png/gif/webf/pdf）；路径穿越防护 |
| 密码安全 | BCrypt 存储；注册/改密强制 ≥8 位含字母数字；JWT 密钥未配置即拒绝启动 |
| 数据一致性 | 科目题目数/资料数由 GROUP BY 实时统计，避免冗余字段失真；审核 CAS 乐观锁；唯一键冲突转 409 |
| 稳定性 | 异步回调空上下文保护（真机闪退已修）、分片代码判分幂等、模考超时自动交卷 |
| 测试 | 判分引擎单测 12 例；端到端旅程实测（注册→刷题→交卷→错题→模考→上传→审核→通知→下载→改密）全通 |
| 文档 | 12 份工程文档，含需求/设计/数据库/API/测试/用户手册/部署/总结 |

---

## 九、接口概览（137 个，按控制器实测分布）

| 控制器 | 接口数 | 控制器 | 接口数 |
|---|---|---|---|
| AdminController | 27 | MockExamController | 5 |
| AdminDataController | 20 | FavoriteController | 5 |
| ResourceController | 11 | NoteController | 4 |
| PracticeController | 9 | ExamController | 4 |
| QuestionAdminController | 6 | QuestionController | 3 |
| NoticeController | 6 | MeController | 3 |
| FileController | 6 | FollowController | 3 |
| CourseController | 6 | SearchController | 2 |
| CommentController | 6 | AppealController | 2 |
| AuthController | 6 | SchoolController / HomeController / CodeRunController | 各 1 |

完整清单（含访问级别）见 **docs/API.md**；在线交互版见 Swagger UI。

---

## 十、路线图（对齐）

1. **轻量 RAG 资料问答**：MySQL 全文检索 / BM25 关键词打分召回课件片段 → 国产大模型生成答案 → 答案强制附带来源引用（先做可溯源，再谈向量库）
2. **学情画像与推送**：基于已有练习/错题数据构建知识点权重画像，实现「错题 → 知识点 → 相关资料」自动推送
3. **校内真实试用与反馈闭环**：小规模内测、版本更新日志、满意度调研（硬门槛，第八章要求）
4. 工程增强：分片上传、对象存储（MinIO）替换本地存储、题目 OCR 结构化

---

## 十一、文档索引

| 文档 | 说明 |
|---|---|
| docs/大纲-v2.md | 需求与设计大纲（原始基线，703 行） |
| docs/需求规格说明书.md | 功能与非功能需求、验收标准 |
| docs/概要设计说明书.md | 架构、模块划分、技术选型、关键决策 |
| docs/详细设计说明书.md | 判分/审核状态机/预览水印/签名下载/限流/推荐排序实现细节 |
| docs/数据库设计说明书.md | 41 表清单、ER 图、关键表结构、索引约束 |
| docs/API.md | 137 个接口清单与访问级别 |
| docs/测试报告.md | 单元/端到端/权限/安全测试结果（性能数据待本机压测回填） |
| docs/用户手册.md | 学生端与管理端使用说明（含隐私政策/版权/学术诚信声明） |
| docs/部署文档.md | 本机运行与 Docker Compose 部署、备份恢复 |
| docs/项目总结.md | 交付成果、技术亮点、问题与展望 |
| docs/验收审计报告-2026-09-25.md | 对照大纲的逐条验收审计 |
| docs/注册功能验证报告-2026-10-02.md | 注册/学校/学院功能验证记录 |

---

## 十二、开发约定

1. **接口文档即代码**：Swagger 由 springdoc 自动生成，访问级别写在注解里
2. **权限后端校验**：前端限制不算数，后端逐接口校验，白名单集中管理
3. **统一响应**：`{code, message, data, traceId}`，traceId 用于定位日志
4. **敏感信息不入库**：数据库口令、JWT 密钥走 `application-local.yml`（已 `.gitignore`）
5. **脚本幂等**：`sql/*.sql` 均可重复执行
6. **不虚报**：文档与对外材料中的数字必须可复现（本文所有指标均来自实测命令）
