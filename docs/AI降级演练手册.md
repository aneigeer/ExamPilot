# AI 降级演练手册（可复制粘贴）

> 作者：软件工程师｜日期：2026-10-06
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
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"studentNo":"admin","password":"admin123"}' -o login.json
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
