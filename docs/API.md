# API 接口文档（v1.0）

> 基础地址：`http://localhost:8080/api`　·　在线交互文档：`http://localhost:8080/api/swagger-ui.html`（OpenAPI 3，每个接口均标注访问级别）
> 统一响应：`{ "code": 200, "message": "success", "data": …, "traceId": "…" }`；分页结构：`{ page, size, total, list }`
> 访问级别：**PUBLIC** 匿名可访问 / **AUTH** 需登录 / **ROLE:AUDITOR / ROLE:ADMIN** 需角色
> 错误码：400 参数错误 · 401 未登录 · 403 无权限 · 404 不存在 · 409 重复上传 · 413 文件过大 · 415 类型不支持 · **429 请求过频（登录限流）** · 4001 业务违规（如审核自我回避） · 500 服务器错误

## 1 认证（PUBLIC / AUTH）
| 方法 | 路径 | 级别 | 说明 |
|---|---|---|---|
| POST | /auth/login | PUBLIC | 学号+密码登录 |
| POST | /auth/register | PUBLIC | 自助注册（学校/学院选择；学号全局唯一；密码 ≥8 位含字母数字；成功后直接返回令牌） |
| POST | /auth/guest | PUBLIC | 游客入口 |
| POST | /auth/logout | AUTH | 登出 |
| PUT | /auth/password | AUTH | 修改密码 |
| GET | /auth/me | AUTH | 当前用户信息 |

## 2 首页与搜索（PUBLIC）
| 方法 | 路径 | 级别 |
|---|---|---|
| GET | /home（聚合：公告/考试类别/科目/热门与最新资料/统计） | PUBLIC |
| GET | /search?keyword=&scope=&limit= | PUBLIC |
| GET | /search/resources?keyword=&page=&size= | PUBLIC |

## 3 考试知识体系（PUBLIC）
| 方法 | 路径 |
|---|---|
| GET | /exams　·　GET /subjects?examCategoryId=　·　GET /subjects/{id}　·　GET /subjects/{id}/knowledge-tree |

## 4 题库（列表详情 PUBLIC，操作 AUTH）
| 方法 | 路径 | 级别 |
|---|---|---|
| GET | /questions/types（已开放题型） | PUBLIC |
| GET | /questions?subjectId=&type=&page=&size= | PUBLIC |
| GET | /questions/{id} | PUBLIC |
| POST | /practice/start（模式：SEQUENCE/RANDOM/BY_TYPE/WRONG_BOOK + knowledgeId/难度/客观主观筛选） | AUTH |
| GET | /practice/{id}/next | AUTH |
| POST | /practice/{id}/answer | AUTH |
| POST | /practice/{id}/self-eval | AUTH |
| GET | /practice/{id}/card | AUTH |
| POST | /practice/{id}/finish · GET /practice/{id}/report | AUTH |
| GET | /wrong-book · DELETE /wrong-book/{questionId} | AUTH |
| GET | /wrong-book/review?limit=（待复习错题，SRS 到期队列）· POST /wrong-book/review/{questionId}?correct=（提交一次复习结果） | AUTH |
| GET | /me/practice-trend · GET /me/mastery?subjectId= | AUTH |

## 5 课程库（查询 PUBLIC）
| 方法 | 路径 |
|---|---|
| GET | /colleges · /majors?collegeId= · /teachers?collegeId= · /semesters · /courses?collegeId=&semesterId= · /courses/{id} |
| GET | /follows?targetType= · GET /follows/check | 
| POST | /follows?targetType=&targetId=（关注课程/科目） | AUTH |

## 6 资料（列表详情 PUBLIC，操作 AUTH）
| 方法 | 路径 | 级别 |
|---|---|---|
| GET | /resources?page=&size=&type=&tags=&keyword=&sort=recommended | PUBLIC |
| GET | /resources/{id} · /resources/mine | PUBLIC / AUTH |
| GET | /resources/types · /resources/{id}/preview（游客 3 页低清/登录整本高清） | PUBLIC |
| POST | /resources（提交资料，进入待审核） | AUTH |
| POST | /resources/{id}/download（返回短时效签名 URL） | AUTH |
| POST | /resources/{id}/report?reason=（举报） | AUTH |
| GET | /resources/{id}/rating · POST /resources/{id}/rating（评分） | AUTH |
| POST | /resources/{id}/like | AUTH |
| GET | /comments?targetType=&targetId= · POST /comments · POST /comments/{id}/like · DELETE /comments/{id} | PUBLIC / AUTH |

## 7 文件（上传 AUTH，下载签名）
| 方法 | 路径 | 级别 |
|---|---|---|
| POST | /files/image（App 图片上传，白名单 jpg/jpeg/png/webp/gif，≤10MB，非图片 415） | AUTH |
| POST | /files/document（管理端文档上传，≤200MB） | AUTH |
| POST | /files/audio（听力音频 ≤50MB） | AUTH |
| GET | /files/preview/{fileId}?page=&dpi=（预览图，带水印） | PUBLIC |
| GET | /files/download/{fileId}?expires=&sign=（HMAC 签名短时效） | PUBLIC（签名校验） |
| GET | /files/audio/{fileId} | AUTH |

## 8 个人（AUTH）
| 方法 | 路径 |
|---|---|
| GET | /me/stats · /me/downloads · /me/mastery |
| GET | /me/export（导出个人数据：账号资料/统计/错题/练习记录/收藏/下载/笔记，不含凭证） |
| POST | /me/deactivate（注销账号，body 传 password 与 confirmText="注销账号"；注销即匿名化并逻辑删除） |
| GET/POST | /notes（题目笔记）· PUT/DELETE /notes/{id} |
| GET/POST/DELETE | /favorites（收藏资料/题目，含 /favorites/questions、/favorites/resources） |
| GET | /notifications · PUT /notifications/read-all · GET /notifications/unread-count |
| GET | /announcements · GET /announcements/{id}（公告，PUBLIC） |
| GET | /appeals/mine · POST /appeals?resourceId=&reason=（申诉） |

## 9 管理端（ROLE:AUDITOR / ROLE:ADMIN）
| 方法 | 路径 | 级别 |
|---|---|---|
| GET | /admin/dashboard · /admin/stats/resource-types · /admin/stats/roles | AUDITOR+ |
| GET | /admin/stats/hot-subjects · /admin/stats/active-users · /admin/stats/type-accuracy | AUDITOR+ |
| GET | /admin/audits/questions?type=&keyword= · POST /admin/audits/questions/{id}/approve·reject | AUDITOR+ |
| GET | /admin/audits/resources · /admin/audits/resources/audited · POST …/{id}/approve·reject·offline | AUDITOR+ |
| GET | /admin/reports?status= · POST /admin/reports/{id}/handle（VALID 成立并下架 / INVALID 不成立） | AUDITOR+ |
| GET | /admin/appeals · POST /admin/appeals/{id}/approve·reject | AUDITOR+ |
| GET | /admin/logs（ADMIN 全部）· GET /admin/logs/audit（AUDITOR 审核日志） | ROLE |
| GET/POST/PUT/DELETE | /admin/users、/admin/users/{id}/role、/admin/users/{id}/status | ADMIN |
| GET/POST/PUT/DELETE | /admin/announcements（公告管理） | ADMIN |
| GET/POST/PUT/DELETE | /admin/data/exams、/subjects、/knowledge、/courses、/teachers、/colleges | ADMIN |
| GET/POST/PUT/DELETE | /admin/questions（题目管理）· POST /admin/questions/import · GET /admin/questions/export | AUDITOR+/ADMIN |
| GET | /admin/reports（举报列表） | AUDITOR+ |
| POST | /admin/ai/reindex（语料索引全量重建）· POST /admin/ai/vector/sync · GET·POST /admin/ai/vector/reconcile（向量索引对账/补推） | AUDITOR+ |
| GET | /admin/storage/status · POST /admin/storage/migrate（存储状态 / 本地↔MinIO 迁移） | ADMIN |

## 10 模拟考试与编程运行（P1，AUTH）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /exams/mock/start | 随机组卷开考（科目/题数/时长） |
| GET | /exams/mock/{id} | 取卷（含剩余秒数） |
| POST | /exams/mock/{id}/answer | 覆盖式作答 |
| POST | /exams/mock/{id}/submit · GET /exams/mock/{id}/report | 交卷判分 / 成绩报告 |
| POST | /run | 编程题在线运行（C/JAVA，超时 10s；**仅限本机使用**，公网部署须换独立判题沙箱）<br>⚠️ **默认关闭**：`app.code-run.enabled=false` 时该 Controller 不注册，请求返回 404；需 `--app.code-run.enabled=true` 且完成隔离验收后开启 |

## 11 管理端 Web 页面
`GET /api/admin/index.html`（静态页 PUBLIC，页面内管理 API 均有角色校验）

## 12 AI 导学（RAG 检索增强）
| 方法 | 路径 | 级别 | 说明 |
|---|---|---|---|
| GET | /ai/capability | PUBLIC | 能力探测（App 未登录时也要能判断是否展示 AI 入口） |
| POST | /ai/ask | AUTH | 提问 → 检索（向量 + ngram 双通道）→ 生成 → 强制溯源；五层降级，**恒返回 200**（无相关语料时 `refused=true`） |
| GET | /ai/knowledge-profile | AUTH | 学情画像：薄弱知识点 Top-N（时间衰减 + 难度权重 + 错题加权） |
| GET | /ai/recommendations | AUTH | 「错题 → 知识点 → 相关资料」推送，每条带推荐理由 |

## 13 健康检查（PUBLIC）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /actuator/health | 探活（仅暴露 health/info，details 不对外），供负载均衡/容器编排使用 |

## 14 题库批量导入 / 导出（JSON，ROLE:AUDITOR / ROLE:ADMIN）

`GET /admin/questions/export?subjectId=&type=` 导出为下方结构的 JSON 数组；
`POST /admin/questions/import` 请求体为**同一结构的数组**，逐条走完整校验、**单条失败不影响其他条**，返回 `{total, success, failed, errors[]}`。

> 新导入题目状态为 `PENDING`（待审核）：审核通过后才会进入公开题库，并触发 AI 语料索引同步。
> 大批量入库后如需让 AI 立即可检索，调 `POST /admin/ai/reindex` 重建索引。

字段（`QuestionSaveRequest`）：

| 字段 | 必填 | 说明 |
|---|---|---|
| `subjectId` | ✅ | 科目 ID |
| `type` | ✅ | 题型枚举：`SINGLE_CHOICE` / `MULTI_CHOICE` / `TRUE_FALSE` / `FILL_BLANK` / `MATCHING` / `ORDERING` / `SHORT_ANSWER` / `TERM_EXPLAIN` / `CALCULATION` / `PROGRAMMING` / `READING` / `LISTENING` … |
| `stem` | ✅ | 题干 |
| `material` | | 材料正文（组合题 / 阅读题） |
| `difficulty` | | 难度 1-5（默认 3） |
| `source` / `tags` / `analysis` | | 来源 / 标签（逗号分隔）/ 解析 |
| `score` | | 分值 |
| `options` | 客观题 ✅ | `[{"key":"A","content":"…","isAnswer":true}]`（单选恰一个 true，多选多个） |
| `answerContent` | 填空 / 主观 ✅ | 填空 `{"blanks":["printf"]}`；简答 `{"reference":"参考答案","keywords":["关键词"]}` |
| `gradingPoints` | | 评分要点（主观题） |
| `caseSensitive` | | 填空是否大小写敏感 |
| `knowledgeIds` | | 关联知识点 ID 列表（学情画像与推荐的关键连接键） |
| `audioFileId` | 听力题 ✅ | 先调 `POST /files/audio` 上传 |
| `subQuestions` | 组合题 ✅ | 子题列表（`stem` / `type` / `score` / `options`） |

示例：

```json
[
  { "subjectId": 5, "type": "SINGLE_CHOICE", "stem": "以下哪个运算符的优先级最高？",
    "difficulty": 2, "tags": "运算符", "score": 2,
    "options": [
      { "key": "A", "content": "()", "isAnswer": true },
      { "key": "B", "content": "*", "isAnswer": false }
    ],
    "knowledgeIds": [3] },
  { "subjectId": 4, "type": "SHORT_ANSWER", "stem": "简述顺序表和链表的区别。",
    "analysis": "顺序表随机访问 O(1)、插入删除 O(n)；链表相反。", "difficulty": 3, "score": 5,
    "answerContent": { "reference": "顺序表连续存储、随机访问 O(1)；链表指针链接、插入删除 O(1)。",
                       "keywords": ["顺序表", "链表", "时间复杂度"] },
    "knowledgeIds": [11] }
]
```
