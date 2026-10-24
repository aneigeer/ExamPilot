# AI 微服务（Python / FastAPI）

> 备考知识库与题库系统 · 独立 AI 推理进程 · 端口 **8000**
> 对应设计文档：`docs/AI模块设计方案.md`（基础）、`docs/AI微服务扩展设计方案.md`（扩展）
> 对应任务：**T07**（服务骨架）、**T08**（向量库 + Embedding）、**T11**（OCR）

---

## 一句话定位

Java 后端负责**业务、鉴权、编排与兜底**；本服务只负责**四件推理的事**：

| 能力 | 端点 | 技术 |
|---|---|---|
| 文本向量化 | `POST /v1/embed` | bge-small-zh-v1.5 **ONNX Runtime INT8**（512 维） |
| 向量检索 + 重排 | `POST /v1/search` | Qdrant（HNSW + payload 过滤） |
| 试卷 OCR 识别 | `POST /v1/ocr` | PaddleOCR（**懒加载**） |
| 大模型代理 | `POST /v1/llm` | GLM（OpenAI 兼容协议） |

**不碰业务库、不做鉴权决策、不写 `question` 表。** Qdrant 是**可重建的派生存储**，
删掉整个 volume 也能从 MySQL `ai_chunk` 一次 rebuild 恢复。

---

## 核心设计：降级是产品功能，不是异常处理

这是本服务唯一贯穿全部代码的设计原则。

```
Java 侧永远有兜底：向量检索 → ngram 全文检索（MySQL FULLTEXT）→ 抽取式回答
                                ↑ 纯 MySQL 内部，零网络、零模型文件、零外部进程
```

因此本服务允许"整个挂掉"，只要**挂得干净**：

* 所有依赖不可用 → **503 + `{"code":52xx,"message":"...","degraded":true}`**
* **绝不返回 500 堆栈**（异常处理器只记日志，对外只给模糊提示）
* **一个依赖都不装时，`GET /v1/health` 仍然返回 200** —— 否则 Java 侧连"AI 服务是否在线"
  都探测不到，降级链路的起点就断了

> 使用说明：**"我们做了刻意的设计：除了 MySQL，所有组件都是可摘除的。
> 杀 Qdrant → 降级 ngram；杀 FastAPI → 降级 ngram + 抽取式；杀 OCR → 人工录入。
> 我们的目标不是让每个失败点都不发生，而是让每个失败点的爆炸半径限制在一个功能内。"**

---

## 目录结构

```
ai-service/
├── app/
│   ├── main.py                 # FastAPI 入口：路由注册、TraceId 中间件、4 类全局异常处理器
│   ├── config.py               # 环境变量读取（纯标准库，含 .env 解析，非法值回退默认）
│   ├── schemas.py              # 22 个 Pydantic 模型 = Java 侧接口契约（字段名不许改）
│   ├── errors.py               # 52xx 错误码 + 8 个异常子类
│   ├── clients/
│   │   ├── embedding_client.py # ONNX Runtime 懒加载 + CLS pooling + L2 归一化 + 批量 encode
│   │   ├── qdrant_client.py    # 建集合/写入/检索/删除/重建/状态 + model_id 一致性校验
│   │   └── llm_client.py       # GLM 代理（OpenAI 兼容体）+ 引用编号提取
│   ├── routers/
│   │   ├── health.py           # GET  /v1/health
│   │   ├── embed.py            # POST /v1/embed
│   │   ├── search.py           # POST /v1/search（含轻量重排）
│   │   ├── index.py            # POST /v1/index/sync、/v1/index/rebuild、GET /v1/index/status
│   │   ├── ocr.py              # POST /v1/ocr（懒加载 PaddleOCR）
│   │   └── llm.py              # POST /v1/llm
│   ├── ocr/
│   │   ├── paddle_ocr_engine.py# PaddleOCR 懒加载封装（2.x / 3.x 输出格式双兼容）
│   │   ├── structure_parser.py # 题号/选项/题型正则解析（纯 Python，零依赖）
│   │   └── pdf_text.py         # pypdf 文本层探测（区分"文本 PDF"与"扫描件"）
│   └── tools/
│       └── warmup_embedding.py # 模型预热脚本（python -m app.tools.warmup_embedding）
├── tests/                      # pytest，5 个文件 100+ 用例
├── models/                     # 模型文件（.gitkeep + README，模型不提交 Git）
├── requirements.txt            # 核心依赖（约 135 MB）
├── requirements-ocr.txt        # OCR 可选依赖（约 500MB–1GB）
├── install.bat                 # ① 建 venv + 装核心依赖
├── install-ocr.bat             # ② 装 OCR 依赖（可选）
├── run.bat                     # ③ 启动 uvicorn
├── pytest.ini                  # 测试配置
├── Dockerfile                  # 容器化（可选，默认用 run.bat）
├── .env.example                # 环境变量样例
├── .gitignore                  # 排除 models/ 与 .env
└── README.md                   # 本文件
```

---

## 环境准备（主理人执行）

### 步骤 0：启动 Docker Desktop（**必做前置**）

```powershell
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
docker info    # 期望能看到 Server Version 与 OSType: linux
```

> **已核实**：本机 `docker info` 曾报 `failed to connect to docker API`，
> 说明引擎当时未运行。**不启动这一步，Qdrant 起不来，向量通道降级。**
> （但不启动也不影响系统可用 —— 只是退化为 ngram 检索。）

### 步骤 1：安装核心依赖

```powershell
cd D:\大学生多考试备考知识库与题库系统\ai-service
.\install.bat
```

**做了什么**：在项目根建 `.venv-ai` 虚拟环境 → 升级 pip → 装 `requirements.txt` → 校验 5 个关键包。

**体积预估**：

| 包 | 体积 | 说明 |
|---|---|---|
| `onnxruntime` | 约 60 MB | ★ **不装 PyTorch**（PyTorch 800MB–2GB，这是选型关键） |
| `numpy` | 约 20 MB | |
| `uvicorn[standard]` | 约 15 MB | |
| `tokenizers` | 约 10 MB | |
| `pillow` | 约 7 MB | |
| `pytest` | 约 5 MB | 只测不用可卸载 |
| `pydantic` + `qdrant-client` + `httpx` + `fastapi` + `pypdf` + `python-multipart` | 约 19 MB | |
| **核心合计** | **约 135 MB** | |
| embedding 模型（手动放置） | 约 28 MB | 放在 `models\bge-small-zh-v1.5\` |
| **总计** | **约 165 MB** | |

**耗时**：3–8 分钟（网络决定）。脚本默认走清华源，失败会提示改官方源。

### 步骤 2：放置 Embedding 模型

把 `bge-small-zh-v1.5` 的 **ONNX INT8 量化版**放到：

```
ai-service\models\bge-small-zh-v1.5\
├── model.onnx        ← 约 28 MB（INT8）/ 约 110 MB（FP32）
└── tokenizer.json    ← 约 11 MB
```

> ⚠️ **必须是 `.onnx`，不能是 `.bin` / `.safetensors`** —— 本服务走 ONNX Runtime 路线。
> 详见 `models/README.md`（含三种获取方式）。

**校验**：

```powershell
..\.venv-ai\Scripts\Activate.ps1
python -m app.tools.warmup_embedding
# 期望：model=bge-small-zh-v1.5 dim=512 load_ms=812 warmed=ok
```

**没模型也能继续** —— 服务照常启动，只是 `/v1/embed`、`/v1/search` 返回 503。

### 步骤 3：启动 Qdrant

```powershell
cd D:\大学生多考试备考知识库与题库系统
docker compose -f docker-compose.ai.yml up -d
docker ps | Select-String qdrant      # 期望看到 examprep-qdrant Up
```

Qdrant 与 MinIO 都在架构师已落盘的 `docker-compose.ai.yml` 里（已 `docker compose config` 校验通过），
**本服务不需要改它**。

### 步骤 4：启动 AI 服务

```powershell
cd D:\大学生多考试备考知识库与题库系统\ai-service
.\run.bat
```

浏览器打开 <http://localhost:8000/docs> —— Swagger 交互文档，**使用加分项**。

### 步骤 5（可选）：安装 OCR 依赖

```powershell
.\install-ocr.bat      # 约 500MB–1GB，10–20 分钟
```

**不装的后果**：`/v1/ocr` 返回 503，Java 侧提示"请手动录入"，**其他功能全部正常**。
建议按设计方案 D2 把 OCR 放功能验证最后一位，不进主动线。

### 步骤 6：跑测试

```powershell
..\.venv-ai\Scripts\Activate.ps1
pytest -q
```

**测试不需要 Docker、不需要模型文件、不需要大模型 Key** —— embedding 与 Qdrant 用桩替代，
OCR 与 LLM 走真实降级路径（本来就该是 503）。装完 `requirements.txt` 即可全绿。

---

## 接口清单（**Java 侧按此对接，字段名不许改**）

### `GET /v1/health` —— 健康检查 · 超时 1000ms

```json
{
  "status": "degraded",
  "embedding": { "ready": true, "loaded": false, "dim": 512, "model_id": "bge-small-zh-v1.5",
                 "detail": "model=bge-small-zh-v1.5 可用但未加载（懒加载，首次调用 /v1/embed 约需 0.2-1s）" },
  "qdrant":    { "ready": true,  "detail": "collection=exam_chunks url=http://127.0.0.1:6333" },
  "ocr":       { "ready": true,  "detail": "未加载（懒加载，首次调用 /v1/ocr 时加载）" },
  "llm":       { "ready": false, "detail": "未配置 LLM_API_KEY，/v1/llm 将返回 503，Java 侧走抽取式" },
  "version": "1.0.0"
}
```

> **恒返回 HTTP 200。** "服务活着但 OCR 没装"不是错误，是**已知的降级态**，
> 就绪与否看 `ready` 字段。异常也会返回 503（说明 FastAPI 进程本身有问题）。

### ★ `ready` 与 `loaded` 的语义区分（**容易误读，必须分清**）

| 字段 | 含义 | 谁该读它 |
|---|---|---|
| `ready` | **能力是否可用**：依赖已装 **且** 模型文件存在 | **Java 侧的 `vectorIndexReady` 读这个** |
| `loaded` | 本进程内是否**真的已加载模型** | 仅排障观测用 |

**为什么必须分开**：模型是懒加载的。若 `ready` 报"是否已加载"，冷启动时会返回 `false`，
Java 侧就会误判"向量通道不可用"而**提前降级到 ngram** —— 明明能用却被跳过。
改为"能力是否可用"后，`ready` 从冷启动到热态**恒为 `true`**，不再抖动。

> 实测：冷启动 `ready=true, loaded=false`；打一次 `/v1/search` 后
> `ready=true, loaded=true, load_ms=262`。

### `POST /v1/index/delete` —— 按 chunk_id 删除向量 · 超时 5000ms

请求 `{"chunk_ids": [88, 89]}`（单次 ≤ 500 条，幂等）
响应 `{"deleted": 2, "requested": 2}`

> **为什么必须有这个接口**：MySQL 把块置 `INVALID` 后，Qdrant 里那个点的
> `payload.status` 仍是**入库时的快照**（`ACTIVE`）——**`/v1/search` 的
> `status='ACTIVE'` 过滤拦不住它**。孤儿向量会挤占 top-K 名额，把有效结果挤出去。
> 这是"过滤能兜底"的常见误解，必须显式删除。

### `POST /v1/embed` —— 文本向量化 · 超时 1200ms

请求 `{"texts": ["内存分配", "指针"]}`（单次 ≤ 64 条）
响应 `{"dim": 512, "vectors": [[...512 个 float...], [...]], "took_ms": 23}`

> 向量已做 **L2 归一化**（范数 = 1.0），所以余弦相似度 == 点积，可直接与 Qdrant COSINE 对齐。

### `POST /v1/search` —— 向量检索 · 超时 1200ms

请求：

```json
{ "query": "动态分配内存的函数", "top_k": 10,
  "filters": { "subject_id": 5, "knowledge_id": 8, "source_type": "QUESTION", "status": "ACTIVE" } }
```

响应：

```json
{ "hits": [ { "chunk_id": 88, "score": 0.87, "payload": { "title": "...", "subject_id": 5, "rerank_score": 0.912 } } ],
  "took_ms": 41, "dim": 512 }
```

> - `filters` 全部可选，多条件为 **AND**
> - **无命中返回 200 + `hits: []`**（不是 404）—— 拒答由 Java 侧闸门决定
> - `payload` **不含正文**（正文唯一来源是 MySQL `ai_chunk`），含 `rerank_score` 轻量重排分
> - 五因子重排的**最终权威仍在 Java 侧** `RetrievalService`，本服务只做不含业务偏好的轻量融合

### `POST /v1/index/sync` —— 批量写入向量 · 超时 60s（CPU 嵌入实测约 12s/500 条）

请求 `{"chunks": [{"chunk_id": 88, "content": "...", "title": "...", "subject_id": 5, ...}]}`（单次 ≤ 500 条）
响应 `{"synced": 2, "skipped": 0, "failed": 0, "took_ms": 88}`

> 失败返回 503，Java 侧 `ChunkSyncService` 置 `vector_synced=2` 待重试。

### `POST /v1/index/rebuild` —— 重建集合 · 超时 30000ms

请求 `{}`（可选指定 `model_id` / `dim`）
响应 `{"status": "READY", "collection": "exam_chunks", "model_id": "bge-small-zh-v1.5", "dim": 512, "message": "..."}`

> 重建会**清空集合**。之后需从 MySQL `ai_chunk` 重新推送
> （Java 侧 `POST /api/admin/ai/reindex` 会自动做这件事）。

### `GET /v1/index/status` —— 索引状态 · 超时 1000ms

响应 `{"collection": "exam_chunks", "model_id": "bge-small-zh-v1.5", "dim": 512, "chunk_synced": 42, "config_model_id": "bge-small-zh-v1.5", "status": "READY", "message": "索引正常"}`

`status` 取值：`READY` / `MODEL_MISMATCH`（换模型未重建）/ `COLLECTION_MISSING` / `ERROR`

> **一致性断言**：`chunk_synced` 应等于 MySQL `SELECT COUNT(*) FROM ai_chunk WHERE vector_synced=1`。

### `POST /v1/ocr` —— 试卷识别 · 超时 30000ms（OCR 慢）

请求 `{"file_path": "D:/tmp/试卷.png", "question_type_hint": "SINGLE_CHOICE"}`
或 `{"image_base64": "iVBORw0KGgo..."}`

响应：

```json
{ "blocks": [ { "type": "stem", "text": "1. 下列哪种排序算法平均复杂度最低",
                "bbox": [12.0, 30.5, 480.2, 52.8], "confidence": 0.96 } ],
  "confidence": 0.94, "page_no": 1, "took_ms": 2300,
  "stem_confidence_threshold": 0.75, "need_manual_input": true }
```

> - `type` ∈ `stem`（题干）/ `option`（选项）/ `answer`（答案）/ `unknown`
> - **`need_manual_input` 恒为 `true`** —— 本服务永不自动入库，结果只进 `question.audit_status='DRAFT'`
> - 首次调用要加载模型（5–15 秒），之后单页 1–3 秒
> - 未装 PaddleOCR → **503 / 5205**（重试无用，Java 应长期熔断）
> - 装了但识别出错 → **503 / 5206**（换图可重试，Java 短熔断）
> - PDF 无文本层 → **503 / 5207**（本服务不做 PDF 转图，需人工补录）

### `POST /v1/llm` —— 大模型代理 · 超时 8000ms

请求 `{"question": "值传递与地址传递的区别", "contexts": ["...", "..."], "max_tokens": 1500}`
响应 `{"answer": "值传递只复制一份...[1] 地址传递...[2]", "sources": [{"chunk_id": null, "index": 1, "snippet": "..."}], "generator": "glm", "cost_ms": 1830, "degraded": false}`

> - `sources` 已从答案的 `[n]` 标注中提取，供 Java 侧做 **L3 幻觉校验**（编号与来源是否自洽）
> - 未配 Key / 上游失败 / 超时 → **503 / 5209**，Java 侧走 `ExtractiveAnswerClient`
> - **本服务绝不返回 `generator=extractive`** —— 降级决策权只在 Java 侧（它是唯一持有 prompt 与溯源上下文的地方）

---

## 错误码对照表（**两段码并存，各有分工**）

Java 侧 `ResultCode.java` 第 62 行已明确注释 **"52xx 段由 FastAPI 侧使用"**，
因此本服务的错误码落在 **52xx**，与 Java 基础段（5001-5007）、Java 扩展段（5011-5020）
**均无冲突**（已用脚本核对过交集为空）。

两段码**并存是刻意的**：

* **Java 侧 5011-5020** 表达"**对用户说什么**"（如"已降级为全文检索"）
* **本服务 52xx** 表达"**哪一环坏了**"（诊断用）

Java 侧按下表做一次映射即可，语义边界比混用一套码清晰得多。

| 本服务 code | 常量 | HTTP | 含义 | Java 侧映射 |
|---|---|---|---|---|
| 5200 | `PARAM_ERROR` | 400 | 参数错误 | 400 `PARAM_ERROR` |
| 5201 | `CAPABILITY_UNAVAILABLE` | 503 | 能力不可用（依赖缺失/未装） | 5011 `AI_FASTAPI_UNAVAILABLE` |
| 5202 | `UPSTREAM_TIMEOUT` | 503 | 上游超时 | 5012 `AI_FASTAPI_TIMEOUT` |
| 5203 | `VECTOR_SEARCH_FAILED` | 503 | 向量检索失败 | 5013 `AI_VECTOR_SEARCH_FAILED` |
| 5204 | `EMBEDDING_FAILED` | 503 | Embedding 推理失败 | 5014 `AI_EMBEDDING_FAILED` |
| 5205 | `OCR_UNAVAILABLE` | 503 | OCR **能力**不可用（没装 / 已关闭） | 5018 `AI_OCR_UNAVAILABLE` |
| 5206 | `OCR_RECOGNIZE_FAILED` | 503 | OCR **识别**失败（装了但这张图识别不出） | 5019 `AI_OCR_RECOGNIZE_FAILED` |
| 5207 | `OCR_STRUCTURE_FAILED` | 503 | OCR **结构化**失败（PDF 无文本层等） | 5020 `AI_OCR_STRUCTURE_FAILED` |
| 5208 | `VECTOR_INDEX_STALE` | 503 | 索引与模型不匹配 | 5015 `AI_VECTOR_INDEX_STALE` |
| 5209 | `LLM_UNAVAILABLE` | 503 | 大模型不可用（无 Key / 失败 / 超时） | 5003 `AI_LLM_UNAVAILABLE` |
| 5210 | `STORAGE_UNAVAILABLE` | 503 | 对象存储不可用（**预留**，本服务不直连 MinIO） | 5017 `AI_STORAGE_UNAVAILABLE` |
| 5299 | `INTERNAL_ERROR` | 500 | 内部错误（**兜底，正常不应出现**） | 降级 ngram |

### ★ 为什么 OCR 要拆成三码（而不是合并成一个）

这是本服务与初版实现的主要差异，也是**熔断策略能否正确**的关键：

| 情况 | 语义 | Java 侧该怎么处理 |
|---|---|---|
| **5205** 没装 paddleocr | **重试多少次都没用** | 长期熔断（如 10 分钟）+ 提示"请手动录入"，不要重试 |
| **5206** 装了但这张图识别不出 | **换张图可能有用** | 短熔断（如 60s）+ 提示"换张图重试" |
| **5207** PDF 无文本层 | **本服务搞不定** | 走人工补录（原图已归档，信息不丢） |

合并成一位 → Java 侧无法区分"重试有用"与"重试没用"，熔断阈值只能取中间值，
结果是**要么该熔断的不熔断（疯狂重试打爆 CPU）**，**要么不该熔断的也熔断（用户频繁失败）**。

**统一错误体**（所有 4xx/5xx 都是这个结构，Java 侧不再二次包装）：

```json
{ "code": 5205, "message": "OCR 能力不可用：未安装 paddleocr/paddlepaddle，请执行 install-ocr.bat 后重启服务；Java 侧请降级为人工录入", "degraded": true }
```

---

## 缺哪个依赖 → 哪个接口 503

| 缺失项 | `/v1/health` | `/v1/embed` | `/v1/search` | `/v1/ocr` | `/v1/llm` | `/v1/index/*` | 系统影响 |
|---|---|---|---|---|---|---|---|
| **onnxruntime** | `embedding.ready=false` | **503 / 5201** | **503 / 5201** | 正常 | 正常 | **503 / 5201** | 向量通道熔断 → 降级 ngram |
| **模型文件缺失** | `embedding.ready=false` | **503 / 5201** | **503 / 5201** | 正常 | 正常 | **503 / 5201** | 同上 |
| **tokenizers** | `embedding.ready=false` | **503 / 5201** | **503 / 5201** | 正常 | 正常 | **503 / 5201** | 同上 |
| **qdrant-client** | `qdrant.ready=false` | 正常 | **503 / 5201** | 正常 | 正常 | **503 / 5201** | 同上 |
| **Qdrant 容器未启动** | `qdrant.ready=false` | 正常 | **503 / 5203** | 正常 | 正常 | **503 / 5203** | 同上 |
| **Qdrant 集合不存在** | `qdrant.ready=false` | 正常 | **503 / 5203** | 正常 | 正常 | `status=COLLECTION_MISSING` | 同上（跑 reindex 恢复） |
| **模型换了未重建** | — | 正常 | **503 / 5208** | 正常 | 正常 | `status=MODEL_MISMATCH` | 触发 reindex |
| **paddleocr** | `ocr.ready=false` | 正常 | 正常 | **503 / 5205** | 正常 | 正常 | OCR 降级人工录入，**其余全好**（重试无用，Java 应长期熔断） |
| **pydantic** | — | — | — | — | — | — | **服务起不来**（FastAPI 硬依赖，必须装） |
| **fastapi / uvicorn** | — | — | — | — | — | — | **服务起不来**（必须装） |
| **httpx** | `llm.ready` 不受影响 | 正常 | 正常 | 正常 | **503 / 5209** | 正常 | 大模型降级抽取式 |
| **LLM_API_KEY 未配** | `llm.ready=false` | 正常 | 正常 | 正常 | **503 / 5209** | 正常 | 同上 |
| **pypdf** | — | 正常 | 正常 | PDF 报 5207 | 正常 | 正常 | 仅影响 PDF 快速路径 |
| **什么都不装** | **200 / degraded** | 503 | 503 | 503 | 503 | 503 | Java 侧全量降级，**系统完整可用** |

---

## 常见故障排查

### 服务起不来

| 现象 | 原因 | 处理 |
|---|---|---|
| `No module named 'fastapi'` | 依赖未装 / 虚拟环境没激活 | 跑 `install.bat` |
| `Address already in use` | 8000 端口被占 | `set PORT=8001` 后再跑 `run.bat` |
| 启动即崩且日志有 `ImportError` | 缺某个重量级依赖 | 看日志里 `ImportError: No module named 'xxx'` |

> **注意**：本服务**不会**因为缺 `onnxruntime` / `qdrant-client` / `paddleocr` 而起不来。
> 这三个是懒加载的，缺了只会让对应接口 503。若真的起不来，说明缺的是 `fastapi`/`pydantic`
> 这类核心依赖 —— 属于安装不完整。

### `/v1/health` 里某项 `ready=false`

| 能力 | `detail` 会告诉你 | 处理 |
|---|---|---|
| `embedding` | "未安装 onnxruntime" | 跑 `install.bat` |
| `embedding` | "模型文件缺失" | 往 `models\bge-small-zh-v1.5\` 放 `model.onnx` + `tokenizer.json` |
| `qdrant` | "未连接" / "不可达" | `docker compose -f docker-compose.ai.yml up -d` |
| `ocr` | "未安装 paddleocr" | 跑 `install-ocr.bat`（**可选，不装不影响其他**） |
| `llm` | "未配置 LLM_API_KEY" | 在 `.env` 里填 Key（**可选，不填走抽取式**） |

### `/v1/search` 返回 5208

**换过 embedding 模型但没重建索引。** 绝不允许同一 collection 混用不同模型的向量
（这是检索结果彻底错乱的典型原因）。处理：

```powershell
curl -X POST http://localhost:8000/v1/index/rebuild -H "Content-Type: application/json" -d "{}"
# 然后在 Java 侧触发全量 reindex 重新推送向量
```

### `/v1/search` 返回 5203 且提示"集合不存在"

第一次用或重建后集合是空的。跑一次 `/v1/index/sync`，或直接走 Java 侧的
`POST /api/admin/ai/reindex`。

### 首次 `/v1/embed` 很慢（1–3 秒）

这是**模型懒加载**的正常表现，只发生一次。首次使用前跑一次预热即可：

```powershell
python -m app.tools.warmup_embedding
# 期望：model=bge-small-zh-v1.5 dim=512 load_ms=812 warmed=ok
```

### 首次 `/v1/ocr` 很慢（5–15 秒）

PaddleOCR 模型懒加载，只发生一次。**这是刻意的设计** —— OCR 模型常驻内存 500MB–1GB，
不该让 embedding 服务白占这份内存，使用时不 OCR 就不该付这个成本。

### 端口冲突 / 局域网访问

```powershell
set PORT=8001
.\run.bat
```

`run.bat` 已支持 `PORT` 环境变量覆盖。

---

## 配置说明

复制 `.env.example` 为 `.env` 后按需修改。**所有变量都有默认值，`.env` 可以完全为空**。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8000` | 监听端口 |
| `LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `CORS_ORIGINS` | `*` | 本机使用用 `*`；生产按需收紧 |
| `EMBEDDING_MODEL_ID` | `bge-small-zh-v1.5` | ★ 改这个必须全量重建索引 |
| `EMBEDDING_DIM` | `512` | 改这个必须同步改 Qdrant collection |
| `EMBEDDING_AUTO_DOWNLOAD` | `false` | 默认不联网下载模型 |
| `QDRANT_URL` | `http://127.0.0.1:6333` | 与 `docker-compose.ai.yml` 一致；<br>★ **免 Docker**：设为 `file:<目录>` 用内置本地向量库持久化，`:memory:` 走纯内存 |
| `QDRANT_COLLECTION` | `exam_chunks` | **必须与 Java `ai_vector_index_meta.collection` 一致** |
| `QDRANT_API_KEY` | 空 | 留空 = 不鉴权（仅本机）；对外暴露必须设置 |
| `LLM_API_KEY` | 空 | ★ 安全默认值：空 = 走抽取式，开箱即用不误扣费 |
| `LLM_MODEL` | `glm-4-flash` | 有免费额度，适合使用 |
| `LLM_TEMPERATURE` | `0.3` | 低温度 = 更稳，不易编造 |
| `OCR_ENABLED` | `true` | `false` 时 `/v1/ocr` 直接 503，连 import 都不做 |
| `OCR_STEM_CONFIDENCE_THRESHOLD` | `0.75` | 题干低于此值 → 判"需人工重录" |
| `OCR_FIELD_CONFIDENCE_THRESHOLD` | `0.90` | 字段低于此值 → 留空并高亮，强制人工补全 |

> `.env` 含密钥，**已在 `.gitignore` 中排除**。启动日志只打印"是否已配置"，
> 绝不回显 Key 内容（`Settings.safe_dump()` 保证，有单测覆盖）。

---

## 容器化（可选）

默认用 `run.bat` 在宿主机跑（改代码不用重建镜像）。需要容器时：

```powershell
cd D:\大学生多考试备考知识库与题库系统
docker build -t examprep-ai-service:1.0.0 -f ai-service/Dockerfile .
docker run -d --name examprep-ai-service -p 8000:8000 `
  -e QDRANT_URL=http://qdrant:6333 `
  --network examprep_default `
  examprep-ai-service:1.0.0
```

镜像约 290 MB（`python:3.13-slim` 约 130 MB + 依赖约 135 MB + 模型约 28 MB），
**不含 PaddleOCR**（500MB–1GB，如需另建 `-ocr` 变体）。容器以非 root 用户运行，带 `HEALTHCHECK`。

---

## 与 Java 侧的配合（给 T09 同学的接口提示）

```yaml
# backend/src/main/resources/application.yml
app:
  ai:
    fastapi:
      base-url: http://127.0.0.1:8000
      connect-timeout-millis: 800
      read-timeout-millis: 1200      # 检索通道（对齐设计方案的 1200ms 预算）
      llm-read-timeout-millis: 8000  # 生成通道
      ocr-read-timeout-millis: 30000 # OCR 通道
      circuit-breaker-threshold: 3
      circuit-breaker-open-millis: 60000
```

**三条必须遵守的对接约定**：

1. **所有调用都要配熔断**，不允许无限等待。本服务已保证最差 503 快速返回。
2. **透传 `X-Trace-Id`**：Java 侧生成或接收后放进请求头，本服务会回写到响应头，
   可按 traceId 查全链路日志（使用场景排障用）。
3. **本服务不做业务决策**：`generator` 永远是 `glm`，拒答由 Java 侧闸门决定，
   OCR 结果永远是 DRAFT 态。这三条边界是双通道降级能成立的前提。

---

## 已知限制（如实说明）

1. **语料量是当前最大瓶颈**，不是代码问题。26 道种子题仍处于 `PENDING` 审核态，
   不进 `ai_chunk` 也不进向量库。向量检索效果完全取决于语料量 ——
   13 道题的向量库等于摆设。需主理人拍板是否批量审核。
2. **本服务不做 PDF 转图**。`/v1/ocr` 收到扫描件 PDF（无文本层）时返回 **5207** 并提示，
   因为 PDF 转图需要 `pdf2image` + `poppler`（额外的系统级依赖，Windows 装起来很麻烦）。
   实际调用路径是：Java 侧用 PDFBox 抽文本 → 抽不到才把**图片**传给本服务。
   （已核实：项目现有 16 个 uploads 文件共 128 KB，量很小，Java 侧逐页转图完全可行。）
3. **重排是轻量的**，不含业务偏好。Java 侧五因子重排（时间衰减、热度、难度、
   薄弱知识点加权）才是最终权威，本服务的 `rerank_score` 只用于向量通道内部的相对排序。
4. **未在本机实测的部分**：`onnxruntime` 真实推理耗时、`qdrant-client` 真实连接、
   PaddleOCR 真实识别准确率 —— 这些需要主理人装完依赖 + 启动 Docker 后才能验证。
   代码层面已按契约实现并有桩测试覆盖，但**真实链路未经端到端验证**。
