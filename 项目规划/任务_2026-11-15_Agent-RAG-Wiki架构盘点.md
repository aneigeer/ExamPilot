# Agent / RAG / LLM Wiki 架构盘点（2026-11-15）

> 方式：只读代码 + 只读 SQL + 运行时接口；未改任何代码/配置
> 目的：回答三个问题 —— Agent 架构怎么样、RAG 做得怎么样、Wiki 做得怎么样、架构包含什么
> 口径：**「做得怎么样」按「设计完整度 / 数据覆盖度 / 实测效果」三轴评价**，不按代码行数吹

---

## 0. 一句话

**三块都"有骨架、有细节"，但都卡在同一个地方：数据没喂饱、口径没统一、效果没验证到企业级。**
- Agent：闭环完整（规划→工具→观测→定稿），有记忆、检查点、预算、兜底；但 7B 模型能力受限，子 Agent 关闭。
- RAG：**真·双通道混合检索**（不是单向量），五因子融合 + 5 道闸门；但分数口径今天是乱的，Recall 只有 12 条样本。
- Wiki：设计比多数项目扎实（蒸馏 + grounding 校验 + 源守卫 + 版本表），但**只有 47 页、30 个块**，覆盖不到语料的 0.1%。

---

## 1. 全景

```
Android / Web 学生端 / 管理端
        │ HTTP + JWT
        ▼
Java 后端（41 表 / 137 接口 / Spring Boot）
  ├─ AiAgentController        POST /ai/agent/stream（SSE）、GET /ai/agent/runs/{id}
  ├─ AiQaService              问答主链路：检索 → 闸门 → 生成 → L3 校验
  ├─ HybridRetriever          双通道调度（向量 + ngram）
  ├─ RetrievalService         ngram 通道 + 覆盖率/覆盖度/越界词 三道闸门
  ├─ VectorChunkMerger        五因子融合（1.0 / 0.30 / 0.25 / 0.15 / 0.10 + 双通道 0.25）
  ├─ AgentMemoryService       记忆（ai_agent_memory）
  ├─ AgentSessionService      会话（ai_agent_session）
  ├─ AgentStreamService       SSE 流式与运行记录（ai_agent_run）
  └─ AgentInternalController  /internal/agent/{tools/search, llm, answer}（X-Internal-Token）
        │
        ▼
Python AI 服务（FastAPI）
  ├─ agent/     planner(273) · harness(698) · tools(441) · context_manager(196) · subagent(304) · events(30)
  ├─ routers/   agent · ask · search · wiki · index · embed · ocr · interview · llm · route · health
  ├─ wiki/      distiller（蒸馏） · source_guard（源守卫）
  ├─ skills/    registry（COURSE_QA / COURSEWARE_QA / STUDY_PLAN / INTERVIEW …）
  └─ clients/   llm（OpenAI 兼容） · embedding（bge-small-zh 512 维） · qdrant · java_bridge
        │
        ▼
Ollama（exam-prep-7b） · Qdrant（exam_chunks 97,246 / wiki_pages） · MySQL（ngram 全文索引）
```

---

## 2. Agent 架构包含什么

| 组件 | 文件 / 规模 | 职责 |
|---|---|---|
| **Planner** | `agent/planner.py`（273 行） | `agent-planner-v4`：LLM 规划 → JSON 决策（thought/action/tool/arguments）；解析失败回退 `RulePlanner`；参数三路回收（别名键 / 字符串化 / 顶层平铺） |
| **Harness** | `agent/harness.py`（698 行） | 主循环：思考 → 调工具 → 观测 → 再思考 / 定稿；SSE 事件编排；预算控制；指代消解（`_resolve_arguments`）；结构化兜底（`_structured_answer`） |
| **Tools** | `agent/tools.py`（441 行） | **7 个工具**：`search_knowledge`、`search_wiki`、`get_wiki_page`、`get_weak_knowledge`、`get_wrong_book`、`get_study_plan`、`get_question`；含参数校验与「必填+正确写法」回喂 |
| **ContextManager** | `agent/context_manager.py`（196 行） | token 估算、上下文预算裁剪、历史块与摘要组装 |
| **Subagent** | `agent/subagent.py`（304 行） | 复杂问题拆子问题 → 逐个跑 → 汇总；**默认关闭**（`AGENT_SUBAGENT_ENABLED=False`） |
| **Events** | `agent/events.py`（30 行） | SSE 事件类型：`meta → thought → tool_call → tool_result → final → done` |
| **记忆** | `ai_agent_memory` 表 + `AgentMemoryService` | 长期记忆（有 CRUD 与注入测试 `MemoryCrudTest` / `MemoryInjectionTest`） |
| **会话 / 检查点** | `ai_agent_session`、`ai_agent_checkpoint`、`ai_agent_run` | 多轮上下文、断点续跑、运行审计（`CheckpointResumeTest`） |
| **预算参数** | `config.py:350-365` | `max_steps=6`、`max_tool_calls=4`、`max_prompt_chars=8000`、`tool_timeout=10s`、`subagent_max_questions=3` |
| **意图路由** | `skills/registry.py` + `router/schemas.py` | Skill 枚举：`COURSE_QA` / `COURSEWARE_QA` / `STUDY_PLAN` / `INTERVIEW` …；`implemented=False` 的降级到 `COURSE_QA` |
| **模型** | `clients/llm_client.py`（OpenAI 兼容） | 当前指向本地 Ollama `exam-prep-7b`；配置层强制 loopback + 本地白名单 |

**实测表现（D08，50 条）**：`50/50` 请求成功、**44 次工具调用 44/44 配对**、`ok_false=1`、**3 次回退规则规划器**（1 次 ReadTimeout + 2 次 JSON 解析失败）、4 行 `degraded`。

**评价**
- ✅ 闭环完整：规划 → 执行 → 观测 → 定稿，每一步都有事件、有审计、有兜底。
- ✅ 工程细节到位：参数校验会回喂「正确写法」；指代消解补检索词；有检查点可续跑。
- ⚠️ **模型是天花板**：7B 在 4096 token 窗口下，结构化输出不稳（3/50 回退）、会编造 ID（`pageId=82804` 无来源）。
- ⚠️ **子 Agent 关闭**：`subagent_enabled=False`，复杂问题只能串行 6 步内解决。
- ⚠️ 无工具并行调用；`max_steps=6` 对多跳问题偏紧。

---

## 3. RAG 做得怎么样

### 3.1 链路（7 步）

```
① 向量召回 20 块（bge-small-zh → Qdrant exam_chunks）
② ngram 召回 50 块（MySQL 全文索引；不可用时 LIKE 降级，ftScore=0）
③ 覆盖率闸门：剔除覆盖率 < 0.5 的块
④ 双通道融合：五因子重排（全文 1.0 / 标题 0.30 / 知识点 0.25 / 新鲜 0.15 / 热度 0.10）+ 双通道 +0.25
⑤ Top-5 截断
⑥ 拒答闸门（5 道）：分数 0.15 / 覆盖率 0.5 / 内容覆盖度 0.45 / 语义余弦 0.58 / 越界词表
⑦ L3 引用校验 + 抽取式兜底
```

### 3.2 数据与指标

| 项 | 值 |
|---|---:|
| Qdrant 总块 | **97,246**（COURSEWARE 89,318 / QUESTION 7,892 / KNOWLEDGE 30 / RESOURCE 6） |
| 待向量化 | 0 |
| 拒答（D07，10 条） | **6/10**（门槛 9/10，FAIL） |
| Recall@5 | **83.3%**（**仅 12 条样本**，门槛 90%） |
| L4 真题抽样 top-5 | 19/20（人工抽样，非自动化指标） |
| 单次检索耗时 | 16–77 ms（ngram）/ 640–980 ms（向量通道） |

### 3.3 评价

- ✅ **真混合检索**：不是"向量一把梭"，双通道 + 五因子 + 分层闸门，这个设计在同类课程项目里算扎实。
- ✅ 有抽取式兜底、有引用编号、有 L3 校验。
- ❌ **分数口径不统一（今天刚实测）**：同一批问题 `bestScore` 出现 **79.39 / 114.24 / 72.50**（双通道，未归一化的全文原始分）与 **0.8456**（纯向量），差两个数量级 → `reject-threshold=0.15` 对双通道等于没设，语义闸门又被 `lexicalHasEvidence ≥ 0.15` 旁路。
- ❌ **没有 cross-encoder 重排**：五因子是手工权重，不是学习出来的相关性。
- ❌ **评测样本太小**：Recall 只用了 12 条；拒答 10 条。这样的指标不足以支撑"企业级"结论。
- ⚠️ 向量通道与 ngram 通道的分数量纲不同，却被放进同一个 `finalScore` 排序。

---

## 4. LLM Wiki 做得怎么样

### 4.1 组成

| 组件 | 位置 | 职责 |
|---|---|---|
| 页面存储 | `wiki_page`（47 行）/ `wiki_page_link` / **`wiki_page_revision`** | 页面、关联、**版本历史** |
| 向量集合 | Qdrant `wiki_pages`（独立 collection） | 语义检索 |
| 蒸馏器 | `wiki/distiller.py` | 簇（cluster）→ LLM 生成 → `grounding_ratio` 校验 → 不合格走 `_extractive` 兜底；单次最多 20 页 |
| 源守卫 | `wiki/source_guard.py` | `chunk_id` 解析、ACTIVE 过滤、页面源失效检测（`page_sources_active`）、簇剪枝（`prune_clusters`） |
| 检索接口 | `routers/wiki.py` | `index_sync` / `index_delete` / `search` |
| Agent 工具 | `search_wiki`、`get_wiki_page` | Agent 侧按关键词/按 ID 读页 |

### 4.2 数据

| 项 | 值 | 判断 |
|---|---:|---|
| `wiki_page` | **47 页** | 对 89,318 块语料而言，覆盖 **< 0.1%** |
| `KNOWLEDGE` 块 | **30** | 极少 |
| 单次蒸馏上限 | 20 页 | 按此速度，49 次才能覆盖 1,000 页 |

### 4.3 评价

- ✅ **设计比"让 LLM 随便写 Wiki"扎实得多**：有 grounding 校验（生成内容与源文的二元组重合率）、有 `_extractive` 兜底、有**源失效守卫**（源块被删/失效时能识别）、有**版本表**。
- ✅ 与 Agent 打通：`search_wiki` / `get_wiki_page` 都是标准工具，不是旁路。
- ❌ **规模没起来**：47 页 / 30 块，等于"机制跑通了，内容还没长"。
- ❌ 无增量更新策略（新语料入库后 Wiki 不会自动扩充）；无页面质量分/热度；无人工审核流。
- ⚠️ `distill_max_pages=20` 是硬上限，全量蒸馏需要人工分批跑。

---

## 5. 三块的耦合关系（一张表看清）

| 调用方 | 被调方 | 通道 | 鉴权 |
|---|---|---|---|
| Android/Web | Java 后端 | HTTP + JWT | 学生/管理员令牌 |
| Java 后端 | Python AI | HTTP | `X-Internal-Token` |
| Python Agent | Java 业务（题库/错题/薄弱点/计划） | `AgentInternalController` | 同上 |
| Python RAG | Qdrant（`exam_chunks`） | gRPC/HTTP | 本地 |
| Python Wiki | Qdrant（`wiki_pages`） | 同上 | 本地 |
| Python Agent | Ollama | OpenAI 兼容 `/chat/completions` | 本地白名单 |

> 拒答闸门放在 **Java 侧**（`RetrievalService` + `AiQaService`），不在 Python。好处是统一；坏处是 Python 单测测不到闸门，只能靠 Java 的 388 个用例。

---

## 6. 往企业级走，这三块各差什么

| 模块 | 最差的 3 件事 | 修法 |
|---|---|---|
| **Agent** | ① 7B 结构化输出不稳（3/50 回退）② 子 Agent 关闭 ③ 无工具并行 | 换 14B/32B 或加 JSON schema 约束解码；打开并压测 subagent；工具并行（同一轮多个 `tool_call`） |
| **RAG** | ① 分数口径不统一（今天实测）② 无 cross-encoder 重排 ③ 评测样本 12/10 条 | P0 统一 `finalScore` 量纲；引入 bge-reranker；把评测集扩到 **214 条全量** |
| **Wiki** | ① 47 页 / 30 块，覆盖 <0.1% ② 无增量更新 ③ 无质量分 | 按语料分批蒸馏（先 500 页）；定时增量；加 grounding 分数门槛与人工抽检 |

---

## 7. 边界

- 本文只读：`ls` / `wc` / `grep` / 只读 SQL / 只读 GET；**未改任何代码或配置**，未启停服务（8000 仍在跑，供前一任务用）。
- 数字时点：2026-11-15 15:1x；`wiki_page=47`、`ai_chunk=97,246` 为当时实测。
- 「做得怎么样」是**工程判断**，不是验收结论；档位仍以 `qa-acceptance` 的报告为准。
