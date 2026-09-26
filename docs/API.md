# API 接口文档（v1.0）

> 基础地址：`http://localhost:8080/api`　·　在线交互文档：`http://localhost:8080/api/swagger-ui.html`（OpenAPI 3，每个接口均标注访问级别）
> 统一响应：`{ "code": 200, "message": "success", "data": …, "traceId": "…" }`；分页结构：`{ page, size, total, list }`
> 访问级别：**PUBLIC** 匿名可访问 / **AUTH** 需登录 / **ROLE:AUDITOR / ROLE:ADMIN** 需角色
> 错误码：400 参数错误 · 401 未登录 · 403 无权限 · 404 不存在 · 409 重复上传 · 413 文件过大 · 415 类型不支持 · **429 请求过频（登录限流）** · 4001 业务违规（如审核自我回避） · 500 服务器错误

## 1 认证（PUBLIC / AUTH）
| 方法 | 路径 | 级别 | 说明 |
|---|---|---|---|
| POST | /auth/login | PUBLIC | 学号+密码登录 |
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

## 10 模拟考试与编程运行（P1，AUTH）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /exams/mock/start | 随机组卷开考（科目/题数/时长） |
| GET | /exams/mock/{id} | 取卷（含剩余秒数） |
| POST | /exams/mock/{id}/answer | 覆盖式作答 |
| POST | /exams/mock/{id}/submit · GET /exams/mock/{id}/report | 交卷判分 / 成绩报告 |
| POST | /run | 编程题在线运行（C/JAVA，超时 10s；本机使用用，公网需独立判题沙箱） |

## 11 管理端 Web 页面
`GET /api/admin/index.html`（静态页 PUBLIC，页面内管理 API 均有角色校验）
