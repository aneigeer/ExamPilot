# 大学生多考试备考知识库与题库系统

软件工程课程设计 / 毕业设计。**三端架构**：Android 学生端 + Web 管理端 + Spring Boot 后端。

不商用、不上架；资料以模拟题、原创题、授权资料、回忆题型为主。

## 当前状态

| 模块 | 状态 |
|---|---|
| 后端（Spring Boot）| ✅ **完成** —— 101 个接口，端到端测试通过 |
| 数据库（31 张表）| ✅ 完成，脚本可重复执行 |
| Android 学生端 | ⬜ 未开始 |
| Web 管理端 | ⬜ 未开始 |

## 一、目录结构

```
大学生多考试备考知识库与题库系统/
├── backend/          后端服务（Java 21 + Spring Boot 3.5.3，101 个接口）
├── admin-web/        管理端 Web（Vue3 + Element Plus）
├── student-app/      学生端 App（Android 原生：Java + XML）
├── sql/              数据库脚本（schema.sql / data.sql，可重复执行）
├── docs/             项目文档（大纲等）
├── .gitignore        版本控制排除规则
└── README.md
```

## 二、技术选型

| 端 | 技术栈 |
|---|---|
| 学生端 | Android 原生 —— Java + XML 布局 + MVVM（ViewModel + LiveData）+ Retrofit/OkHttp + Glide + RecyclerView + BottomNavigationView |
| 管理端 | Vue3 + Vite + TypeScript + Element Plus + ECharts |
| 后端 | JDK 21 + Spring Boot 3.5.3 + Spring Security 6 + JWT（jjwt 0.12.6）+ MyBatis-Plus 3.5.7 + springdoc-openapi 2.8.6 + PDFBox 3.0.7 |
| 数据库 | MySQL 8.0 |
| 构建工具 | Maven（后端）/ Vite（管理端）/ Gradle（学生端）|

**构建参数**：Android `compileSdk 34`、`minSdk 26`（Android 8）。

## 三、快速开始

### 1. 初始化数据库（MySQL 8，可重复执行）

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/data.sql
```

### 2. 配置数据库口令

复制 `backend/src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入本机 MySQL 密码（该文件已被 .gitignore 排除，不会入库）。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

### 4. 打开接口文档

- Swagger UI：<http://localhost:8080/api/swagger-ui.html>
- 服务基地址：<http://localhost:8080/api>

**接口文档不使用手写的 API.md**，全部由代码自动生成（springdoc）。每个接口都标注了访问级别：`PUBLIC`（游客可用）/ `AUTH`（需登录）/ `ROLE:AUDITOR` / `ROLE:ADMIN`。

## 四、示例账号

| 角色 | 学号 | 密码 | 能做什么 |
|---|---|---|---|
| 超管 | `admin` | `admin123` | 用户/角色管理、公告管理、基础数据维护、操作日志 |
| 审核员 | `auditor` | `audit123` | 题目审核、资料审核、举报处理、仪表盘 |
| 学生 | `2021001` | `123456` | 刷题、上传资料、评论评分、收藏、错题本 |

## 五、核心模型

```
考试类别 → 科目 → 知识点 → 题目 / 资料
```

- **考试类别**：校内课程、CET4、CET6、计算机二级
- **题目**：全题型统一存储（P0 先开放 单选/多选/判断/填空/简答 五种，其余题型已预留枚举与表结构）
- **资料**：课件、笔记、知识点总结、模拟卷、回忆题型、原创题
- **预览**：PDF 由 PDFBox 自动转逐页图片；游客仅可看前 3 页，登录后看整本
- **下载**：短时效签名地址（10 分钟），防链接转发盗链

## 六、两个业务闭环

| 闭环 | 流程 |
|---|---|
| 刷题 | 按科目/知识点/题型/难度选题（顺序或随机）→ 刷题 → 客观题自动判分 / 主观题自评 → 练习报告 → 错题本 |
| 资料 | 上传 → 提交审核 → 审核员通过/驳回 → 驳回可修改重交 → 公开展示 → 预览 / 下载 / 评论 / 评分 / 举报 |

## 七、后端接口概览（101 个）

| 模块 | 数量 | 说明 |
|---|---|---|
| 认证 | 4 | 登录、登出、当前用户、改密 |
| 考试知识体系 | 4 | 考试类别、科目、知识点树 |
| 题库 | 3 | 题型、列表（不含答案）、详情（含答案） |
| 刷题 | 8 | 开始/取题/提交/自评/结束/报告/错题本 |
| 课程库 | 6 | 学院、专业、教师、学期、课程 |
| 资料库 | 9 | 列表、详情、预览、下载、上传提交、重新提交、举报、类型 |
| 通知公告 | 6 | 通知列表/未读数/已读 + 公告 |
| 后台管理 | 21 | 仪表盘、审核（题目+资料）、举报、用户/角色、日志、公告管理 |
| 基础数据维护 | 16 | 考试类别/科目/知识点/课程/教师/学院 增删改 |
| 首页 / 搜索 | 3 | 首页聚合、统一搜索 |
| 收藏与历史 | 5 | 收藏切换、收藏列表、下载历史 |
| 题目管理 | 4 | 管理端题目增删改（含完整性校验） |
| 评论评分 | 6 | 评论、点赞、资料评分 |
| 文件 | 4 | 上传、预览图、签名下载 |

## 八、本机开发环境

| 软件 | 版本 | 路径 |
|---|---|---|
| JDK | Eclipse Temurin 21.0.10 LTS | `D:\bigplan\tools\jdk21` |
| Maven | 3.9.15 | `D:\bigplan\tools\maven` |
| MySQL | 8.0.46 | `D:\bigplan\tools\mysql-8.0.46` |
| Android Studio | Quail 1（AI-261.23567.138）| `D:\bigplan\tools\Android Studio Quail 1` |
| Android SDK | platforms android-34 / android-36.1 | `D:\bigplan\tools\androidSDK` |
| Node.js | 22.22.2（npm 10.9.7）| 已在 PATH |

## 九、文档索引

| 文档 | 说明 |
|---|---|
| [docs/大纲-v2.md](docs/大纲-v2.md) | 完整需求与设计大纲（最新版）|
| Swagger UI | 运行后端后访问 <http://localhost:8080/api/swagger-ui.html>（即接口文档）|

## 十、开发约定

1. **接口文档即代码**：接口文档由 springdoc 自动生成，接口的访问级别（PUBLIC / AUTH / ROLE:xxx）写在代码注解里，与实现永不脱节。
2. **权限后端校验**：前端限制不算数，后端逐接口校验；白名单集中在 `SecurityConfig`。
3. **统一响应**：所有接口返回 `{code, message, data, traceId}`；`traceId` 用于排查问题时定位日志。
4. **提交规范**：每个模块自己跑通再提交，保留完整 Git 提交记录备使用。
5. **敏感信息不入库**：数据库口令、JWT 密钥一律走 `application-local.yml`（已被 `.gitignore` 排除）。
6. **脚本幂等**：`sql/*.sql` 可重复执行，不会产生脏数据。
