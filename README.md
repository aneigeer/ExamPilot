# 大学生多考试备考知识库与题库系统

软件工程课程设计 / 毕业设计完整原型。**三端架构**：Android 学生端 + Web 管理端 + Spring Boot 后端。

不商用、不上架；资料以模拟题、原创题、授权资料、回忆题型为主。

---

## 一、目录结构

```
大学生多考试备考知识库与题库系统/
├── backend/          后端服务（Java 21 + Spring Boot 3.3）
├── admin-web/        管理端 Web（Vue3 + Element Plus）
├── student-app/      学生端 App（Android 原生：Java + XML）
├── sql/              数据库脚本（schema.sql / data.sql）
├── docs/             项目文档（大纲、接口文档、ER 图、测试报告）
├── .gitignore        版本控制排除规则
└── README.md
```

## 二、技术选型

| 端 | 技术栈 |
|---|---|
| 学生端 | Android 原生 —— Java + XML 布局 + MVVM（ViewModel + LiveData）+ Retrofit/OkHttp + Glide + RecyclerView + BottomNavigationView |
| 管理端 | Vue3 + Vite + TypeScript + Element Plus + ECharts |
| 后端 | JDK 21 + Spring Boot 3.3.x + Spring Security 6 + JWT + MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 构建工具 | Maven（后端）/ Vite（管理端）/ Gradle（学生端）|
| 部署 | Nginx + Docker Compose |

**构建参数**：Android `compileSdk 34`、`minSdk 26`（Android 8）。

## 三、核心模型

```
考试类别 → 科目 → 知识点 → 题目 / 资料
```

- **考试类别**：校内课程、CET4、CET6、计算机二级
- **题目**：全题型统一存储（P0 先开放 单选/多选/判断/填空/简答 五种）
- **资料**：课件、笔记、知识点总结、模拟卷、回忆题型、原创题

## 四、两个资料闭环

| 闭环 | 流程 |
|---|---|
| 刷题 | 选考试/科目/知识点 → 刷题 → 客观题自动判分 / 主观题自评 → 错题本 |
| 资料 | 上传 → 审核 → 预览 → 下载 → 举报 → 下架 |

## 五、本机开发环境

| 软件 | 版本 | 路径 |
|---|---|---|
| JDK | Eclipse Temurin 21.0.10 LTS | `D:\bigplan\tools\jdk21` |
| Maven | 3.9.15 | `D:\bigplan\tools\maven` |
| MySQL | 8.0.46 | `D:\bigplan\tools\mysql-8.0.46` |
| Android Studio | Quail 1（AI-261.23567.138）| `D:\bigplan\tools\Android Studio Quail 1` |
| Android SDK | platforms android-34 / android-36.1 | `D:\bigplan\tools\androidSDK` |
| Node.js | 22.22.2（npm 10.9.7）| 已在 PATH |

## 六、文档索引

| 文档 | 说明 |
|---|---|
| [docs/大纲-v2.md](docs/大纲-v2.md) | 完整需求与设计大纲（最新版）|

## 七、开发约定

1. **接口先行**：前后端联调前先定 `docs/API.md`，字段以文档为准。
2. **权限后端校验**：前端限制不算数，后端逐接口校验（PUBLIC / AUTH / ROLE:xxx）。
3. **提交规范**：每个模块自己跑通再提交，保留完整 Git 提交记录备使用。
4. **敏感信息不入库**：数据库口令、签名密钥一律走本地配置，已被 `.gitignore` 排除。
