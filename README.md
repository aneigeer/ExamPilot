# 大学生多考试备考知识库与题库系统

软件工程课程设计 / 毕业设计。**三端架构**：Android 学生端 + Web 管理端 + Spring Boot 后端。

不商用、不上架；资料以模拟题、原创题、授权资料、回忆题型为主。

## 当前状态（v1.0 全部完成）

| 模块 | 状态 |
|---|---|
| 后端（Spring Boot 3.5.3） | ✅ **完成** —— **132 个接口**（P0-A / P0-B / P1 全覆盖），Swagger 在线文档，判分单元测试 12/12 通过 |
| 数据库（MySQL 8） | ✅ **完成** —— **40 张表**，schema + data + 3 个增量迁移，脚本可重复执行 |
| Android 学生端 | ✅ **完成** —— Java + XML 原生，25+ 页面，**APK v1.1 可直接安装**（`备考知识库学生端-v1.1.apk`） |
| Web 管理端 | ✅ **完成** —— Vue3 + Element Plus，13 个功能页，由后端同源托管免部署 |
| 项目文档 | ✅ **完成** —— 需求规格/概要设计/详细设计/数据库设计/API/测试报告/用户手册/部署文档/项目总结/文档材料（见 docs/） |

## 一、目录结构

```
大学生多考试备考知识库与题库系统/
├── backend/          后端服务（Java 21 + Spring Boot 3.5.3，132 个接口，内嵌管理端页面）
├── admin-web/        管理端 Web 源码（Vue3 + Element Plus 单页应用，CDN 本地化）
├── student-app/      学生端 App（Android 原生：Java + XML，Gradle 工程）
├── sql/              数据库脚本（schema / data / 三个增量迁移，可重复执行）
├── docs/             项目文档（大纲、需求规格、设计、API、测试报告、用户手册、部署文档、项目总结等）
├── docker-compose.yml / nginx.conf / backup.bat   部署与运维
├── 备考知识库学生端-v1.1.apk   学生端安装包（debug 签名，Android 8+ 直接安装）
└── README.md
```

## 二、技术选型

| 端 | 技术栈 |
|---|---|
| 学生端 | Android 原生 —— Java + XML 布局 + ViewBinding + Retrofit/OkHttp/Gson + Glide + RecyclerView + BottomNavigationView + FloatingActionButton（compileSdk 34 / minSdk 26，Gradle 8.7 + AGP 8.5.2） |
| 管理端 | Vue3 + Element Plus（CDN 本地化单页应用，由后端同源托管，零构建零跨域） |
| 后端 | JDK 21 + Spring Boot 3.5.3 + Spring Security 6 + JWT（jjwt 0.12.6）+ MyBatis-Plus 3.5.7 + springdoc-openapi 2.8.6 + PDFBox 3.0.7 |
| 数据库 | MySQL 8.0（40 张表，答案表 MySQL JSON 承载全题型结构） |
| 构建工具 | Maven（后端）/ Gradle 8.7 + AGP 8.5.2（学生端） |

## 三、快速开始

### 1. 初始化数据库（MySQL 8，可重复执行）

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/data.sql
mysql -u root -p < sql/migration-p0b.sql
mysql -u root -p < sql/migration-p1.sql
mysql -u root -p < sql/migration-p1b.sql
```

### 2. 配置数据库口令

复制 `backend/src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入本机 MySQL 密码（该文件已被 .gitignore 排除）。

### 3. 启动后端（内嵌管理端页面）

```bash
cd backend
mvn spring-boot:run
```

- 管理端：<http://localhost:8080/api/admin/index.html>
- 接口文档：<http://localhost:8080/api/swagger-ui.html>（访问级别 PUBLIC / AUTH / ROLE:xxx 逐接口标注；另见 docs/API.md 静态清单）

### 4. 学生端

- 模拟器：直接安装 `备考知识库学生端-v1.1.apk`（默认地址 10.0.2.2 指向宿主机）
- 真机：修改 `student-app/app/build.gradle` 的 `BASE_URL` 为电脑局域网 IP 后重新构建（命令见 docs/部署文档.md）
- 构建命令：`cd student-app && gradle assembleDebug`

### 5. Docker 部署（可选）

```bash
docker compose up -d --build   # MySQL + 后端 + Nginx（见 docs/部署文档.md）
```

## 四、示例账号

| 角色 | 账号 | 密码 | 能做什么 |
|---|---|---|---|
| 超管 | `admin` | `admin123` | 用户/角色管理、公告、基础数据维护、全部日志、统计 |
| 审核员 | `auditor` | `audit123` | 题目/资料审核、举报处理、申诉处理、审核日志 |
| 学生 | `2021001` | `123456` | 刷题、模拟考试、上传资料、下载、评论评分、收藏、笔记、错题本 |

## 五、核心模型

```
考试类别 → 科目 → 知识点 → 题目 / 资料
```

- **题型**：23 种枚举全预留，已开放 17 种（P0 五种：单选/多选/判断/填空/简答；自评五种：名词解释/计算/应用/作文/翻译；复杂七种：匹配/排序/完形/阅读/听力/组合/编程）
- **客观题**答案存选项表 is_answer；**主观题/复杂题**答案存答案表 MySQL JSON（按题型不同结构）
- **预览**：PDFBox 转逐页图片，游客前 3 页低清带水印，登录整本高清带水印
- **下载**：短时效 HMAC 签名 URL（10 分钟），防盗链
- **审核状态机**：DRAFT → PENDING → APPROVED/REJECTED → OFFLINE → APPEALING → 恢复/维持；审核员自我回避

## 六、两个业务闭环

| 闭环 | 流程 |
|---|---|
| 刷题 | 题库 tab（类别→科目→知识点）→ 六种模式选题 → 客观自动判分 / 主观自评 → 练习报告 → 错题本 → 掌握度 |
| 资料 | 上传（App 图片 / 管理端文档）→ 审核 → 预览 → 签名下载 → 举报 → 核验下架 → 申诉 → 恢复/维持 |

## 七、后端接口概览（132 个，按模块）

认证 5 · 首页聚合与搜索 3 · 考试体系 4 · 题库与刷题错题本 13 · 课程库 11 · 资料库与文件 25 · 通知公告 6 · 评论评分点赞 6 · 收藏/下载历史/掌握度 8 · 笔记 4 · 模拟考试与编程运行 6 · 题目管理与导入导出审核 6 · 基础数据维护 20 · 审核/举报/仪表盘/日志/统计 14+ · 申诉 5 · 关注 3 · 用户管理 3

完整清单（含访问级别）见 **docs/API.md**；在线交互版见 Swagger UI。

## 八、文档索引

| 文档 | 说明 |
|---|---|
| docs/需求规格说明书.md | 功能与非功能需求、验收标准 |
| docs/概要设计说明书.md | 架构、模块划分、技术选型、关键决策 |
| docs/详细设计说明书.md | 判分/审核状态机/预览水印/签名下载/限流/推荐排序实现细节 |
| docs/数据库设计说明书.md | 40 表清单、ER 图、关键表结构、索引约束 |
| docs/API.md | 接口清单与访问级别 |
| docs/测试报告.md | 单元/端到端/权限/性能/安全测试结果 |
| docs/用户手册.md | 学生端与管理端使用说明（含隐私政策/版权/学术诚信声明） |
| docs/部署文档.md | 本机运行与 Docker Compose 部署、备份恢复 |
| docs/项目总结.md | 交付成果、技术亮点、问题与展望 |
| docs/大纲-v2.md | 需求与设计大纲（原始基线） |

## 九、开发约定

1. **接口文档即代码**：Swagger 由 springdoc 自动生成，访问级别写在代码注解里，与实现永不脱节
2. **权限后端校验**：前端限制不算数，后端逐接口校验；白名单集中在 `SecurityConfig`
3. **统一响应**：`{code, message, data, traceId}`；traceId 用于定位日志
4. **提交规范**：每个模块自己跑通再提交，Git 记录备使用
5. **敏感信息不入库**：数据库口令、JWT 密钥走 `application-local.yml`（已 .gitignore）
6. **脚本幂等**：`sql/*.sql` 可重复执行
