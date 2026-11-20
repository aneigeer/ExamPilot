# AI 降级演练手册（可复制粘贴）

> 作者：软件工程师｜日期：2026-10-06｜**2026-10-30 增补第 10–12 节**（复测记录 + 待执行方案）
> 用途：**使用场景故障演练**与故障排查。每条都是本机实测跑出来的，不是纸面设计。
> 核心主张：**除了 MySQL，所有组件都可摘除；任一组件挂掉，问答与上传都仍然可用。**

---

## 0. 一句话总纲

我们的 AI 能力是**五层降级**，最外层永远兜得住：

```
第 5 层  App 隐藏 AI 入口        ← 网络完全不通
第 4 层  抽取式生成（进程内）    ← 大模型 / FastAPI 代理挂
第 3 层  ngram 全文检索（MySQL） ← 向量通道挂（FastAPI / Qdrant / ONNX 模型）
第 2 层  向量 + ngram 双通道融合  ← 正常
第 1 层  拒答「未找到相关资料」   ← 两路都无相关语料（反幻觉第一道闸门）
```

**实测最差总耗时 6.6 秒**（首次降级含连接失败探测），熔断触发后 1.5 秒。
**任何一层挂掉都返回 HTTP 200，不返回 5xx。**

---

## 1. 演练前检查（务必先做完）

```bash
# ① 后端 + AI 服务 + Qdrant 是否在线
curl -s -o /dev/null -w "8080=%{http_code}\n" http://localhost:8080/api/home
curl -s http://localhost:8000/v1/health
curl -s http://localhost:6333/healthz

# ② 拿到 token（演练全程复用）
#    口令不落文档：先从本机 config/secrets.local.yml 的 admin.password 取值
export ADMIN_PASSWORD='<从 config/secrets.local.yml 读取，勿写进文档/脚本>'
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"studentNo\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" -o login.json
python -c "import json;open('tok.txt','w').write(json.load(open('login.json',encoding='utf-8'))['data']['token'])"
export TOK=$(cat tok.txt)

# ③ 确认基线是健康的（两条都该作答）
curl -s -X POST http://localhost:8080/api/ai/ask -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"question":"值传递和地址传递有什么区别"}'
```

> ⚠️ **务必先跑预热**：`python -m app.tools.warmup_embedding`
> embedding 模型是**懒加载**的，不预热则第一次查询会因加载模型而超时（实测首次可能数秒）。

---

## 2. 场景一：杀 FastAPI（AI 微服务挂掉）

**这是使用最值得使用的一条** —— 证明「AI 服务挂了系统照样能用」。

```bash
# ① 停掉 uvicorn（Ctrl+C 或 taskkill）
# ② 立刻发问，不要重启
curl -s -X POST http://localhost:8080/api/ai/ask -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"question":"值传递和地址传递有什么区别"}'
```

**实测结果：**

| 阶段 | 现象 | 耗时 | retrievalMode |
|---|---|---|---|
| 第 1 次 | TCP 连接被拒 + health 探测失败 | **6.627s** | `FULLTEXT_OR` |
| 第 2 次 | — | 1.751s | `FULLTEXT_OR` |
| 第 3 次 | 熔断生效，不再发起连接 | **1.497s** | `FULLTEXT_OR` |

```
code=200  refused=False  sources=1        ← 接口 200，答案带溯源
capability: vectorEnabled=true  vectorIndexReady=false
degradedReason = FastAPI 熔断中，剩余 32574 ms
日志: [AI] 熔断器 fastapi 连续失败 3 次，开启熔断 60000 ms
```

**使用说明**：
> "这是我们设计的降级路径，不是故障。AI 微服务不可用时，检索自动从**语义召回**退回
> **字面召回**——因为 ngram 全文索引跑在 MySQL 内部，不需要网络、不需要额外进程。
> 生成也同步退到本地抽取。所以**接口照样 200、答案照样带溯源**。"

> ⚠️ **诚实说明**：首次调用 6.6 秒偏慢（要支付连接失败探测 + 熔断计数），
> 熔断后 1.5 秒。**首次降级有感知、之后无感**，不要吹「完全无感」。

---

## 3. 场景二：杀 Qdrant（向量库挂掉）

```bash
docker stop examprep-qdrant      # 或停掉 Qdrant 进程
curl -s -X POST http://localhost:8080/api/ai/ask -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"question":"值传递和地址传递有什么区别"}'
```

**预期表现**：FastAPI 的 `/v1/search` 返回 503（`ErrorCode.VECTOR_SEARCH_FAILED`=5203）
→ Java 侧熔断计数 +1 → 返回空 hits → `VectorChannel` 视为无结果 → 走 ngram。

**代码依据**：`FastApiClient.search()` 捕获所有异常并返回空列表（不抛异常）；
`AiQaService` 见空列表即走 ngram。**这条路径与场景一走的是同一段代码，行为可对照。**

> ⚠️ **本条未实测**（本轮 Qdrant 由 Python 侧托管，我不便中断他人服务）。
> 代码路径与场景一完全一致且已被单测覆盖，但**没有真实 curl 输出**，使用时不要声称实测过。

---

## 4. 场景三：杀 MinIO（对象存储挂掉）——**上传永不失败**

```bash
docker stop examprep-minio
curl -s -X POST http://localhost:8080/api/files/image \
  -H "Authorization: Bearer $TOK" -F "file=@test.png"
```

**实测结果**（`app.storage.enabled=true` 且配了密钥，但 9000 无人监听）：

```
HTTP=200  耗时=0.126s
file_record: id=11  stored_path=images/20261006/0593c2c6...png   ← 本地相对路径
磁盘: ./backend/uploads/images/20261006/0593c2c6...png  真实存在，69 字节

GET /api/admin/storage/status
  provider = local
  minioAvailable = False
```

**使用说明**：
> "对象存储挂了，**上传依然成功** —— 文件自动落本地磁盘，业务无感知。
> 这是我们刻意设计的铁律：**上传永远不能失败**。MinIO 是增强项，不是单点依赖。"

---

## 5. 场景四：断网（拔网线）

```bash
# 直接断网后
curl -s -X POST http://localhost:8080/api/ai/ask -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"question":"值传递和地址传递有什么区别"}'
```

**预期表现（等价于场景一）**：向量通道挂 + 云端大模型挂 → ngram 检索 + 抽取式生成。

| 组件 | 断网后状态 | 系统如何 |
|---|---|---|
| MySQL + ngram | ✅ 正常 | 本机，不依赖网络 |
| Qdrant | ❌ 不可达 | 向量通道熔断 |
| FastAPI | ❌ 不可达 | 返回空 hits |
| 大模型云 API | ❌ 不可达 | 超时后熔断 → 抽取式 |
| **Java 抽取式** | ✅ **正常** | 纯进程内计算，最后防线 |

> ⚠️ **本条未单独实测**（与场景一同一条降级代码路径，已由场景一实测覆盖）。
> **注意**：抽取式模式**不依赖网络**，所以断网时系统仍能给出带溯源的答案——
> 这是「四层降级」设计的核心价值，使用时值得强调。

---

## 6. 场景五：索引一致性对账

```bash
curl -s http://localhost:8080/api/admin/ai/vector/reconcile -H "Authorization: Bearer $TOK"
```

**核心一致性断言：MySQL 已向量化数 == Qdrant points 数。**

```sql
-- MySQL 侧
SELECT vector_synced, COUNT(*) FROM ai_chunk WHERE status='ACTIVE' GROUP BY vector_synced;
-- Qdrant 侧
curl -s http://localhost:6333/collections/exam_chunks
```

> ⚠️ **当前实测不一致**：`vector_synced=0`（42 条）但 Qdrant 已有 **43 points**。
> 根因已查明：健康探测失败触发了熔断器，`syncIndex()` 提前返回 null 导致状态位没回写，
> **但向量数据其实已写入 Qdrant**。修复方式：熔断窗口过后调
> `POST /api/admin/ai/vector/reconcile` 自动补齐，无需全量 reindex。
> 详见任务 #11。

---

## 7. 场景六：重启全部服务

```bash
# ① 重启后端
java -jar target/exam-prep-backend-1.0.0-SNAPSHOT.jar --server.port=8080 \
  --app.ai.fastapi.enabled=true --app.ai.vector.enabled=true

# ② 预热 embedding（关键！否则第一次查询会因懒加载超时）
cd ai-service && python -m app.tools.warmup_embedding

# ③ 对账
curl -s http://localhost:8080/api/admin/ai/vector/reconcile -H "Authorization: Bearer $TOK"
```

**注意**：重启后 **token 会失效**（JWT secret 不变则仍有效，但需重新登录确认）。

---

## 8. 排障速查表

| 现象 | 最可能原因 | 处置 |
|---|---|---|
| `retrievalMode=NONE` 但索引明明有数据 | FastAPI 挂 / 熔断中 | 看 `capability.degradedReason` |
| `degradedReason=FastAPI 熔断中，剩余 NNNNN ms` | 连续 3 次失败触发 | 等 N 秒，或重启 FastAPI |
| `vector.pushed=false` | 看 `reason` 字段（现已区分未启用/熔断/不可达） | 熔断恢复后调 `vector/reconcile` |
| 上传超时 | MinIO 未设超时（本项目已修） | 看 `[MinIO]` 日志 |
| `minioAvailable=true` 但 9000 没服务 | 旧版本的假阳性（已修） | 重新构建 |
| 第一次 `/v1/search` 返回空 | embedding 懒加载未完成 | 跑 `warmup_embedding` |

---

## 9. 现场使用纪律（建议写进使用稿）

1. **提前 1 小时启动全部服务**，`/health` 全绿再进考场
2. **必做预热**：`warmup_embedding`，避免第一次查询才加载模型
3. **准备两套路径**：① 完整版（全链路）② 降级版（拔网/停服务，**证明降级能力本身就是加分项**）
4. **如果某层挂了，台词是「这就是我们设计的降级路径」而不是「这个功能坏了」**
5. 数字要如实：首次降级 6.6 秒、熔断后 1.5 秒，**不要说「完全无感」**

---

## 10. 2026-10-30 复测记录（真环境实测）

> 复测环境：语料 `chunk_synced=11984`（`status=READY`，`model_id=bge-small-zh-v1.5`，dim=512），
> 后端 `--server.port=8080 --app.ai.fastapi.enabled=true`，AI 服务 `127.0.0.1:8000`，
> 评价口径与 `RetrievalServiceTest` / `HybridRetrieverTest` 一致（域内 12 条 + 域外 10 条）。

### 10.1 场景一复测：杀 FastAPI（向量通道不可用 → 词法通道承接）

**三档对照**（同一套评测集，只改运行模式；`REFUSAL_MODE` 是测试自己打印的标记）：

| 模式 | 域外拒答 | 域内误拒 | Recall@5（词法） | 答案层 | 平均 / P95 |
|---|---|---|---|---|---|
| VECTOR_ONLINE（8000 在线） | 9/10 = 90% | 0/12 | 10/12 = 83.3% | 10/11 = 90.9% | 35.2 / 70 ms |
| **降级**（向量 enabled=true，8000 已停、`health=000`） | 8/10 = 80% | 0/12 | 10/12 = 83.3% | 10/11 = 90.9% | 35.1 / 71 ms |
| 纯离线（`-Dapp.ai.vector.enabled=false`） | 8/10 = 80% | 0/12 | 10/12 = 83.3% | 10/11 = 90.9% | 35.5 / 60 ms |

**观测点与判定**
- `REFUSAL_MODE=LEXICAL_ONLY（vectorEnabled=true, vectorReady=false）` —— 向量通道**被识别为不可用**而不是报错
- `HybridRetrieverTest` 打印「向量通道未就绪 → 跳过在线断言」，**不会把 `bestSimilarity=0` 当成「整库都不像」去拒答**（域内误拒 0/12 即为证据）
- 问答接口仍 200、带 sources；题库/刷题/模考不受影响（不经过 AI 服务）
- 延迟：降级档 P95 71ms（在线 70ms），**没有出现"首次 6.6 秒"那种断崖**——因为 8000 是本地回环、连接失败探测极快；跨机部署时仍需按第 2 节的 6.6 秒口径预期

> ⚠️ **诚实标注**：上表「VECTOR_ONLINE 9/10」是 10-29 在 HEAD 版本（语料 11300 块）测得；
> 10-30 复测（语料 11984 块、工作区**含一条未提交的语义闸门改动**）在线档为 **8/10**。
> 该改动会放行「区块链智能合约怎么写」，是否合入由 team-lead 与作者裁定；本条不影响本手册的降级结论。

### 10.2 场景六复测：重启后端后，数据是否还在

之前只验证过「重启后服务能起来」，这次补一条**数据不丢**的实证（面试记录 10-29 刚落库）：

```bash
# ① 跑一场 3 题面试（start → answer×3）
curl -s -X POST http://127.0.0.1:8080/api/ai/interview/start -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" -d '{"topic":"RAG","total":3,"subject_id":null}'
# ② 逐题提交作答，直到 finished=true
# ③ 直接查库（绕过服务，确认真的落盘）
mysql -uroot -p exam_prep -e "select id,user_id,topic,total_questions,finished,total_score,level from ai_interview_session; \
                              select session_id,question_no,score,is_follow_up from ai_interview_qa;"
# ④ 杀掉后端进程 → 重新 java -jar 启动（同一 jar、同一 --server.port=8080）
# ⑤ 重启后再查历史接口
curl -s "http://127.0.0.1:8080/api/ai/interview/history?page=1&size=5" -H "Authorization: Bearer $TOK"
```

**实测结果**
```
库：ai_interview_session  id=3 user_id=13 topic=RAG total=3 finished=1 total_score=67 level=中等
    ai_interview_qa       session_id=3 → question_no 1 / 2 / 3   score 70 / 70 / 60   is_follow_up 0/0/0
重启后端后：GET /api/ai/interview/history → total=1
    id=3 topic=RAG 计划=3 实评=3 score=67.0 level=中等 finished=1
    GET /api/ai/interview/history/3 → 实评=3，qa_rows=3，逐题题面/得分/命中要点齐全
```
**结论**：面试会话与逐题记录在 `ai_interview_session` / `ai_interview_qa`，**后端重启不丢**（Python 侧会话仍会随进程消失，但那只是"不能继续答"，不影响历史复盘）。

> ⚠️ 启动踩坑记录：本机环境变量里存在 `SERVER__PORT=65435`，Spring 宽松绑定会把它当成
> `server.port`，导致 Tomcat 起在 65435 且与 WorkBuddy 端口冲突。**启动必须显式 `--server.port=8080` 覆盖**。

---

## 11. 待执行演练方案（杀 Qdrant / 杀 MinIO / 断网）

> **状态：仅方案，未执行。** 执行窗口约束（2026-10-30 起）：
> ① OCR 批处理正在跑（43→90 篇，每篇入库后要推向量到 Qdrant 并从 8000 取 embedding），
> 此期间停 Qdrant / 断网会让灌库失败并制造 `vector_synced=0` 欠账；
> ② 语义闸门那条未提交改动尚未定论，拒答指标可能还会动，基线会漂。
> **等这两件事收口后，按「单场景、单次、前后各测一次基线」执行**，结果以 `## 12` 新章节回填。

### 11.1 通用执行纪律（三个场景都适用）

1. **先起后备**：确认 `run.bat` 的启动命令在手边，演练失败能 30 秒内恢复
2. **单场景单次**：一次只动一个组件，前一个场景完全恢复并复测通过再进下一个
3. **前后各测基线**：同一组请求（3 条域内 + 10 条域外 + 1 次上传）在演练前后各跑一次，
   数字写进表格，**不接受「感觉恢复了」**
4. **记录三要素**：时间戳、`chunk_synced`（语料规模会变）、`REFUSAL_MODE`
5. **任何场景结束后必须回到 `VECTOR_ONLINE` 或 `minio` 正常态**，并在手册里留一条恢复记录

### 11.2 场景二：杀 Qdrant（只停向量库，**不要**停 FastAPI）

**目的**：证明"向量库挂 ≠ AI 不可用"，且与场景一走的不是同一条代码路径（场景一是进程级不可达，本场景是 FastAPI 内部 503）。

**① 前置基线（记录）**
```bash
curl -s http://127.0.0.1:8000/v1/index/status          # 记 chunk_synced / status
curl -s http://127.0.0.1:8080/api/ai/capability        # 记 vectorEnabled / vectorIndexReady / indexStats.chunk
# 3 条域内：记 code / retrievalMode / costMillis / sources 数
# 10 条域外：记拒答条数（当前基线 8/10 词法、9/10 在线）
# 1 次上传 + 1 次预览：记 HTTP 码（用于证明"与向量无关"）
```

**② 操作**
```bash
docker stop examprep-qdrant          # 或本机托管模式下停 Qdrant 进程
```
> ⚠️ 本机当前是 Python 侧「本地模式」持久化（`./data/qdrant`），若无 docker，需停掉托管它的进程；**不要用删数据目录**的方式模拟（那会造成向量丢失）。

**③ 观测点（逐项记录）**
| 观测 | 期望 | 判定依据 |
|---|---|---|
| `POST /v1/search` | **503**，`code=5203 VECTOR_SEARCH_FAILED` | Python 侧错误码 |
| `GET /v1/health` | `qdrant.ready=false`，`status=degraded` | health JSON |
| `/api/ai/ask`（域内） | **HTTP 200**、`refused=false`、`sources` 非空、`retrievalMode` 退为 `FULLTEXT_OR`/`LIKE` | 与场景一同一段 Java 代码 |
| `/api/ai/capability` | `vectorIndexReady=false` + `degradedReason` 说明"qdrant 未就绪" | capability JSON |
| 拒答评测 | **≥8/10**（不得跌破 80%） | 域外 10 条 |
| 上传 / 预览 / 刷题 / 登录 | 全部 **200**，耗时与基线同量级 | 这些链路不碰向量 |
| 耗时 | 首次含失败探测（预计秒级），熔断后回落 | 记第 1/2/3 次 |

**④ 回滚**
```bash
docker start examprep-qdrant
for i in $(seq 1 30); do curl -s http://127.0.0.1:8000/v1/health | grep -q '"ready": *true' && break; sleep 2; done
```

**⑤ 恢复后二次基线**
- `/v1/index/status` → `status=READY`、`chunk_synced` 与演练前一致（**若变了说明灌库任务插进来了，本次演练作废重做**）
- 同样 3 条域内问题：`retrievalMode` 回到 `VECTOR_HYBRID` / `VECTOR_ONLY`
- 域外拒答回到演练前水平（9/10）
- **自愈时间**：Java 侧 health 缓存 10s + 熔断 60s，预计 ≤70s 内自动恢复；若超过 2 分钟未恢复，查 `capability.degradedReason` 并调 `POST /api/admin/ai/vector/reconcile`

**⑥ 验收判据**
> 问答仍 200 且带来源；拒答率 ≥8/10；上传/预览/刷题不受影响；Qdrant 恢复后**无需重启后端**即回到 `VECTOR_ONLINE`。

### 11.3 场景三：杀 MinIO（对象存储挂掉）

**目的**：复验「上传永不失败」这条铁律（第 4 节是 10-06 的数据，语义闸门/存储代码此后有改动，需重新取证）。

**① 前置基线（记录）**
```bash
curl -s http://127.0.0.1:8080/api/admin/storage/status -H "Authorization: Bearer $TOK"   # provider / minioAvailable
curl -s -X POST http://127.0.0.1:8080/api/files/image -H "Authorization: Bearer $TOK" -F "file=@test.png"
# 记：HTTP 码 / 耗时 / data.fileId / 库中 stored_path 的前缀（minio:// 还是 images/...）
curl -s -X POST http://127.0.0.1:8080/api/resources/{id}/download -H "Authorization: Bearer $TOK"  # 记签名链接可用性
```

**② 操作**
```bash
docker stop examprep-minio
```

**③ 观测点**
| 观测 | 期望 | 说明 |
|---|---|---|
| 图片上传 | **HTTP 200**（绝不失败） | 铁律 |
| 新 `stored_path` | 变为本地相对路径 `images/…` | 自动落本地 |
| 磁盘 | `backend/uploads/images/<日期>/<uuid>.png` 真实存在 | `ls -la` 取证 |
| `/api/admin/storage/status` | `provider=local`、`minioAvailable=false` | 降级可见 |
| 新文件下载/预览 | 200 | 走本地路径 |
| **旧 `minio://` 文件**下载 | 预期 4xx 或明确错误（**已知边界**） | 如实记录，不说"全都可用" |

**④ 回滚**：`docker start examprep-minio` → `storage/status` 恢复 `provider=minio`

**⑤ 恢复后二次基线**：再上传一次 → `stored_path` 回到 `minio://`（若 `dualWriteLocal=true`，本地应同时有一份副本）；旧 `minio://` 文件下载恢复 200。

**⑥ 验收判据**：上传永不失败；新上传可下载；MinIO 恢复后能切回对象存储；**旧对象的边界必须写进手册**，不得含糊。

### 11.4 场景四：断网（区分「断外网」与「断局域网」）

**目的**：证明系统**不依赖外网**（AI 服务、Qdrant、embedding 全在本机/局域网），并明确 App 在无网时的表现是"明确提示"而不是白屏。

> ★ 演练口径必须分清，否则结论会被误读：
> - **只断外网**（推荐，风险最低）：预期**全链路无变化**——因为没有任何依赖走公网（大模型可选走本地 Ollama）
> - **断局域网**：App 连不上后端，属"客户端不可达"，不是服务端降级

**① 前置基线（App 与 curl 各测一遍）**
```bash
# 服务端（本机回环，任何断网都应保持 200）
curl -s -o /dev/null -w "home=%{http_code}\n"      http://127.0.0.1:8080/api/home
curl -s -o /dev/null -w "ai_health=%{http_code}\n" http://127.0.0.1:8000/v1/health
# App 侧：登录 / 题库列表 / 刷题一题 / 模考提交 / AI 问答，各记一次成功状态与耗时
```

**② 操作**
```powershell
# 只断外网（保留局域网）：先在路由/防火墙上禁公网出口，或用 hosts 屏蔽外部域名
# 断局域网（真断网卡，风险高，建议最后做）：
netsh interface set interface "WLAN" admin=disable
```

**③ 观测点**
| 观测 | 只断外网（期望） | 断局域网（期望） |
|---|---|---|
| `127.0.0.1:8080/api/home` | 200 | 200（本机回环不受影响） |
| `127.0.0.1:8000/v1/health` | 200 | 200 |
| App 登录/题库/刷题/模考 | 全部正常 | 全部失败，但提示明确（Toast/错误页），**不白屏、不闪退** |
| App AI 问答 | 正常（走本机 AI） | 失败提示；`/api/ai/capability` 不可达 → App 应按"AI 入口降级"隐藏/置灰 |
| 服务端日志 | 无异常 | 无异常（服务端没收到请求） |

**④ 回滚**：恢复网卡 `netsh interface set interface "WLAN" admin=enable`（或放开出口策略）

**⑤ 恢复后二次基线**：App 各页面重新可访问；AI 问答恢复；对比耗时与基线差异（首包可能因重连略慢）

**⑥ 验收判据**：断外网时**服务端零影响**；断局域网时客户端**有明确提示且恢复后自愈**；两条都要有自己的截图/日志，不能只写"应该没问题"。

---

## 12. 待回填：3/4/5 执行结果

> 本节省略。执行完 11.2 / 11.3 / 11.4 后，把「前置基线 / 操作 / 观测点实测值 / 回滚 / 恢复后基线」
> 按 10.1 的表格样式回填到此处，并注明实测日期、`chunk_synced`、`REFUSAL_MODE`。
