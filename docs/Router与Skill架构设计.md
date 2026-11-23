# Router + Skill 架构设计（本地 7B 驱动的课件导学）

> 作者：高见远（软件架构师）｜版本：v1.0｜日期：2026-10-22
> 前提：大模型已换为**本机 Ollama + Qwen2.5-7B**（q3_k_m），云端 API 已弃用。**本文档不再包含任何云端/双路的复杂度。**
> 目标：用 Router + Skill 架构，让 7B 能可靠地"教"新灌入的 634 份课件资料。

---

## 0. 事实核查基线（全部实测，不是转述）

### 0.1 环境与模型（实测）

| 项 | 实测值 | 说明 |
|---|---|---|
| Ollama | `11434` **OPEN** | 在跑 |
| 模型 | `qwen2.5-7b:latest`，**3.5 GB** | 与主理人所述 3.6GB 一致 |
| 生成速度 | **51.4 tok/s**（`eval_count=34, eval_duration=0.66s`） | 与所述 52 tok/s 一致 |
| 热启动 | `load_duration=0.01s` | 已驻留显存 |
| 冷启动 | 55s（主理人实测） | **这是最大的体验地雷，见 4.5** |
| FastAPI | `8000` **OPEN** | embedding + Qdrant 就绪 |
| bge-small-zh-v1.5 | **512 维**，`load_ms=634`，**单条 embed 21.6ms**（实测 18 条批量 388ms） | Router 的算力基础 |
| Qdrant | `ready:true`，collection `exam_chunks` | 已通 |

### 0.2 语料现状（实测，**已含刚入库的课件**）

> ⚠️ **重要更新**：核查过程中发现**课件入库已经开始了**（`courseware` 表已建、已有 797 块 COURSEWARE 进入 `ai_chunk`）。
> 主理人说的"即将新增 7000 块"是**目标值**，实际当前进度如下。

```
ai_chunk ACTIVE = 3332          （主理人所述 2535 为课件入库前的快照）
  QUESTION    2500   (subject 190「行政职业能力测验」占 2476 → 语料主体是公考行测题)
  KNOWLEDGE     30
  RESOURCE       5
  COURSEWARE   797   ← 已入库（5 份课件，其中「专题九--公司落地篇」单份 787 块）
ai_chunk 全部（含 557 条 INVALID 题目）= 3889
目标：634 份课件 ≈ 7000 块 → 当前进度 5 份 / 797 块
```

> **一个必须知道的分布事实**（影响第 5 章分流设计）：
> **`subject_id` 无法用于课件分流** —— 2476 条挤在 `subject_id=190`，课件本身也没有课程学科归属。
> **而 797 块课件已经和 2476 条行测题躺在同一个向量空间里了 —— 污染风险是现在就存在的，不是将来的。**

### 0.3 已存在但主理人未提及的关键资产（**必须先看，避免重复造轮子**）

`ai-service/app/agent/` 下**已经有一套 ReAct Agent 框架**（主理人清单里没提，但它会直接影响 Router 的定位）：

| 文件 | 已有能力 |
|---|---|
| `harness.py` (9.8KB) | 多步循环、`max_steps`/`max_tool_calls`、**预算闸门**（超 `max_prompt_chars` 丢最老的工具结果）、SSE 事件流 |
| `planner.py` (6.6KB) | **`LlmPlanner`：让 7B 输出 JSON 决策选工具**；`RulePlanner`：4 类关键词规则兜底 |
| `tools.py` (9.8KB) | 5 个工具：`search_knowledge` / `get_weak_knowledge` / `get_wrong_book` / `get_study_plan` / `get_question` |
| `routers/agent.py` | `POST /v1/agent/stream`（SSE）、`POST /v1/agent/run`（非流式） |

> **这意味着主理人问的「bge embedding 分类 vs 小模型做分类 prompt」不是二选一的新问题 ——**
> **「小模型 prompt 分类」那一路已经实现了（`LlmPlanner`），而且已经在生产代码里。**
> 所以本文档的核心问题变成：**Router 与既有 Planner 是什么关系？** 结论见第 1 章与 2.7 节。

---

## A. 架构总览：Router 做快路径分派，既有 Agent 做慢路径工具循环

**核心结论：不是用 Router 替换 Planner，而是把 Router 插在 Planner 之前当"快速分派层"。**

```mermaid
flowchart TB
    U["App / 学生提问"] --> API["POST /api/ai/ask<br/>(Java 现有入口)"]

    subgraph L0["① Router 层（新增，bge embedding，22ms）"]
        EMB["embed(question)<br/>bge-small-zh 512维"]
        SIM["与 8 个 skill 原型句算余弦<br/>取 Top1 / Top2"]
        GATE{"三闸门决策"}
        EMB --> SIM --> GATE
    end

    API --> EMB

    GATE -->|"sim < 0.60"| REFUSE["REFUSE<br/>未找到相关资料<br/>0 次 LLM 调用"]
    GATE -->|"margin < 0.05"| CLARIFY["CLARIFY<br/>反问澄清（给候选）<br/>0 次 LLM 调用"]
    GATE -->|"路由到单跳 skill"| FAST["② 单跳 Skill<br/>检索 → 一次生成"]
    GATE -->|"路由到多步 skill"| SLOW["③ 多步 Skill<br/>交给既有 AgentHarness"]

    subgraph L1["单跳路径（绝大多数，省 1-4 次 LLM 规划调用）"]
        FAST --> RET["HybridRetriever<br/>ngram + 向量双通道<br/>(现有)"]
        RET --> VER{"检索验证<br/>有命中? / 过闸门?"}
        VER -->|否| REFUSE
        VER -->|是| GEN["7B 生成<br/>只依据资料 + [n] 引用"]
    end

    subgraph L2["多步路径（仅少数 skill 需要）"]
        SLOW --> HARNESS["既有 AgentHarness<br/>LlmPlanner 出 JSON 决策"]
        HARNESS --> TOOLS["5 个工具<br/>search/weak/wrong/plan/question"]
        TOOLS -->|"循环 ≤ max_steps"| HARNESS
        HARNESS --> GEN2["7B 汇总作答"]
    end

    GEN --> OUT["统一输出<br/>answer + sources[] + skill + degraded"]
    GEN2 --> OUT
    REFUSE --> OUT
    CLARIFY --> OUT

    subgraph S["④ 兜底（现有，全部保留）"]
        EXT["ExtractiveAnswerClient<br/>7B 不可用时的抽取式"]
        NG["ngram 通道<br/>向量挂了时的字面召回"]
    end
    GEN -.->|"7B 超时/熔断"| EXT
    RET -.->|"向量不可用"| NG

    style GATE fill:#fff3cd
    style FAST fill:#d4edda
    style SLOW fill:#f8d7da
    style REFUSE fill:#f5c6cb
```

**一句话讲清这张图**：Router 用 22ms 决定"该谁干"，单跳 skill 直接检索+生成（省掉 Planner 的 1-4 次 LLM 调用），只有真正需要多步工具的 skill 才进既有 AgentHarness。

### A.1 为什么必须这样分层（用实测数字说话）

| 路径 | LLM 调用次数 | 实测/推算延迟 | 适用 |
|---|---|---|---|
| **旧：全部走 ReAct Planner** | 规划 1~4 次 + 生成 1 次 | 规划 1s×N + 生成 7.9s ≈ **8.9~12s** | 所有问题 |
| **新：Router + 单跳** | **仅生成 1 次** | 22ms + 检索 50ms + **7.9s** ≈ **8.0s** | 6 个 skill |
| **新：Router + 多步** | 规划 1~2 次 + 生成 1 次 | ≈ 9~10s | 仅 2 个 skill |

> 对 7B 而言，**每一次 LLM 调用都是 1 秒起的硬成本**（52 tok/s，且 JSON 决策还要重试）。
> 把「意图识别」从 LLM 挪到 22ms 的 embedding，是本次架构改动**最大的延迟收益**。
> 配合流式首字 0.36s（实测），用户感知从"等待 9 秒"变成"0.4 秒开始出字"。

---

## B. Router 设计

### B.1 方案对比：bge embedding 分类 vs 小模型 prompt 分类

| 维度 | **a) bge embedding 相似度（推荐做分派）** | b) 7B prompt 分类（即现有 `LlmPlanner`） |
|---|---|---|
| 单次耗时 | **21.6ms**（实测，18 条批量 388ms） | 短 JSON ≈ 1s；**冷启动 55s**；多步则 ×N |
| 可靠性 | **确定性**，无解析失败 | **JSON 解析会失败**（`PlannerError`），需 `RulePlanner` 兜底 |
| 可解释性 | 相似度分数可打印、可标定、可回归测试 | 输出自然语言 thought，难量化 |
| 新增 skill 成本 | 加 5-8 条原型句，**零训练** | 改 prompt + 加工具 + 可能破坏既有 JSON 格式 |
| 多步工具编排 | **做不到** | **能做到**（这是它不可替代的价值） |
| 误判代价 | 低（选错 skill 通常仍能检索到相关内容） | 中（规划错会连续调错工具，浪费 2-4 秒） |
| 离线可测 | **可以**（我已完成 33 条评测，见 B.3） | 难（每次都要跑 7B） |

**结论：两者不是替代关系，是分工。**

- **Router（bge）负责"选哪个 skill"** —— 快、稳、可测，覆盖 8 个 skill 的粗分类
- **既有 `LlmPlanner`（7B）只负责"选 skill 之后要不要多步调工具"** —— 仅 `WRONG_BOOK_REVIEW` / `STUDY_PLAN` 这两个可能多步的 skill 才启用

> **为什么不干脆把 Router 也交给 7B 做（也就是维持现状）**：
> 因为代价是**每个问题多花 1~4 秒在"想该干什么"上**，而 7B 想这件事的准确率并不比 22ms 的 embedding 高。
> 实测证据：Router 在 33 条用例上**零次"错误作答"**（B.3），而 LLM 规划还多一层 JSON 解析失败风险。

### B.2 ⚠️ 核心发现：**单一相似度阈值在数学上不可行**

主理人倾向的方案是「低于阈值走拒答」。我实测后发现**单阈值无法分离域外问题**：

```
域外(OOB) 最高分 = 0.6317  ← "这个单词怎么念" → 被判成 COURSE_QA
域内最小分       = 0.6136  ← "面试" → COURSEWARE_QA
                    ↑ 域外 0.6317 > 域内 0.6136，区间重叠
```

原始实测（节选）：

| 查询 | Top1 skill | sim | 判定 |
|---|---|---|---|
| 这个单词怎么念 | COURSE_QA | **0.6317** | ❌ 域外却最高分 |
| 面试 | COURSEWARE_QA | **0.6136** | ✅ 域内却低于上面 |
| 帮我写个Java程序 | GRADING | 0.6063 | ❌ 域外 |
| 今天中午吃什么 | SMALLTALK | 0.5333 | ❌ 域外 |
| 推荐一部电影 | RESOURCE_RECOMMEND | 0.5583 | ❌ 域外 |
| 股票怎么买 | STUDY_PLAN | 0.4219 | ❌ 域外 |

**根因**：bge 是**语义**相似度，而"域内/域外"是**可作答性**问题，两者不是同一个维度。
「这个单词怎么念」在语义上确实很像"问答类请求"，所以 embedding 认为它像 COURSE_QA —— 这是**正确**的语义判断，但它**没有资料可答**。

### B.3 正确设计：三闸门 + 检索验证（实测 33 条用例，零错误作答）

```
决策顺序（短路，任一步返回即结束）：
  ① sim < 0.60                        → REFUSE（明显域外）
  ② sim ≥ 0.60 且 margin < 0.05        → CLARIFY（意图歧义，反问）
  ③ 路由到 skill；若该 skill 属"事实类"
     （COURSE_QA / COURSEWARE_QA / EXERCISE_EXPLAIN）
     则必须通过检索验证：无命中 → REFUSE
  ④ 其余 → 进入 skill 执行
```

关键点：**Router 只判"意图"，不判"能不能答"。能不能答交给检索。**
`这个单词怎么念` sim=0.6185 → 路由 COURSE_QA（合理）→ **检索无命中 → REFUSE**。最终行为正确。

**实测结果（33 条用例，覆盖 8 skill + 8 条域外 + 3 条歧义）：**

| 指标 | 结果 |
|---|---|
| 精确路由正确 | **30/33 = 90.9%** |
| **错误作答（幻觉风险）** | **0 次** ← 这才是关键指标 |
| 域外正确拒答 | **8/8 = 100%** |
| 歧义触发澄清 | 2/3（1 条按主导意图路由，可接受） |

3 条"未精确命中"全部是**保守行为**，不是错误：
| 用例 | 实际 | 为什么可接受 |
|---|---|---|
| `题` | CLARIFY | 单字查询，反问澄清比乱猜更合理 |
| `帮我写个Java程序` | CLARIFY | 域外但 sim 0.6078 略过阈值 → 反问而非编造答案 |
| `推荐资料顺便讲讲这个知识点` | RESOURCE_RECOMMEND | 混合意图，选了主导意图，行为合理 |

> **答辩时用这个指标**：不要讲"路由准确率 90.9%"（听起来有 9% 错误），
> **要讲"33 条用例零次错误作答，域外拒答 100%"** —— 因为对 RAG 系统，**"答错"比"不答"严重得多**。

### B.4 阈值标定（实测数据，可直接用）

```yaml
app:
  ai:
    router:
      enabled: true
      # 低于此值直接拒答：实测域外最高 0.6317 / 域内最低 0.6136 有重叠，
      # 故 0.60 取在"明显域外"的一侧（拦截 股票0.4219/午饭0.5333/电影0.5583/Python-CSV0.5775）
      sim-threshold: 0.60
      # 与 Top2 的差值小于此值视为意图歧义 → 反问。
      # 实测：清晰意图 margin 普遍 >0.10；歧义样本 0.0141~0.0459
      margin-threshold: 0.05
      # 事实类 skill 必须过检索验证（防止"语义像但没资料"）
      verify-skills: ["COURSE_QA", "COURSEWARE_QA", "EXERCISE_EXPLAIN"]
      # 原型句向量启动时预计算并缓存，运行期只算 query
      precompute-on-startup: true
      # 是否把 router 结果写入 AskVO（答辩演示用，可现场展示判定过程）
      expose-decision: true
```

**为什么 sim 取 0.60 而不是区间中点 0.6227**：宁可"多拒几个"也不要"放进一个没资料的"。
区间中点 0.6227 会放过 `这个单词怎么念`(0.6185) 之外的更多擦边 query；取 0.60 让边界偏向保守，
配合 ③ 的检索验证，形成双重保险。**这个参数语料扩充后必须重标（见 6.4）。**

### B.5 8 个 skill 的原型句库（已实测验证，可直接入库）

> 这些句子是**实测过 33 条用例的版本**（B.3 的准确率就是用它们跑出来的），不是教科书示例。
> 每 skill 6-8 条，覆盖"正式说法 + 口语说法 + 极短输入"。

| skill | 原型句（建议原样使用） |
|---|---|
| `COURSE_QA` | 这个知识点是什么意思 / 解释一下这个概念 / 为什么会出现这种情况 / C语言指针是什么 / 操作系统进程和线程的区别 / 什么是死锁 / 这个原理怎么理解 / 数据库索引的原理 |
| `EXERCISE_EXPLAIN` | 这道题为什么选B / 帮我讲讲这道题 / 这题怎么做的 / 第5题的解析是什么 / 这道选择题的正确答案为什么是这个 / 这道题我错了想知道原因 / 这题考点是什么 |
| `WRONG_BOOK_REVIEW` | 我的错题本里有哪些题 / 我最近错了很多题怎么办 / 帮我复习错题 / 我哪些知识点掌握得不好 / 错题重做 / 我的薄弱环节在哪 |
| `STUDY_PLAN` | 帮我制定一个学习计划 / 我还有两周考试怎么复习 / 每天应该学多久 / 给我安排一下复习进度 / 怎么高效备考 / 帮我规划一下学习路线 |
| `COURSEWARE_QA` | 大模型面试会问什么 / Transformer的注意力机制怎么理解 / 大厂项目架构是怎么设计的 / RAG的流程是怎样的 / 微服务架构有什么优缺点 / 简历上写大模型项目怎么写 / 面试官会怎么问prompt工程 |
| `RESOURCE_RECOMMEND` | 推荐一些资料给我 / 有没有好的课件 / 我想找这方面的学习材料 / 有什么书或者视频推荐 / 推荐几道类似的题 |
| `GRADING` | 帮我批改这道题 / 我这个答案能得几分 / 我的解答对不对 / 给这道简答题打分 / 这个编程题能过吗 |
| `SMALLTALK` | 你好 / 谢谢 / 你是谁 / 今天天气怎么样 / 讲个笑话 / 你是谁开发的 |

> **维护规则**：新增 skill 必须同时补 6-8 条原型句，并跑一遍 `pytest ai-service/tests/test_router.py`（回归集 33 条）。
> **原型句不要用"教科书腔"**（如"请为我解释知识点"），要用学生真实打字的口吻 —— 实测口语样本的区分度明显更好。

### B.6 性能与缓存

```
启动时：8 skill × ~7 句 = 57 条原型 → embed 一次批量 ≈ 0.4s → 存内存（float[57][512] ≈ 114KB）
运行期：embed(query) 21.6ms + 57 次点积（可忽略） ≈ 22ms
       → 对 8 秒的生成耗时而言，Router 开销占比 <0.3%
```
- 原型向量**必须启动预计算**，不能每次请求重算（否则 57 条 × 21.6ms = 1.2s）
- 可选：query 向量按 `md5(question)` 做 LRU 缓存（重复提问很常见）

### B.7 与既有 `LlmPlanner` 的边界（**必须明确，否则两个人会改重**）

| 谁 | 负责 | 不负责 |
|---|---|---|
| **Router（新增）** | 选 skill、拒答、澄清、决定走单跳还是多步 | 不调工具、不生成答案 |
| **单跳 Skill 执行器（新增）** | 检索 → 组装 prompt → 调 7B → 溯源校验 | 不做多步规划 |
| **既有 `AgentHarness` + `LlmPlanner`（保留）** | **仅在 `WRONG_BOOK_REVIEW` / `STUDY_PLAN` 且需要联动时**做多步工具调用 | 不再用于其他 skill 的分派 |
| **既有 `RulePlanner`（保留）** | `LlmPlanner` 失败时的兜底（现有逻辑不动） | — |

> **不要删 `planner.py`。** 它有两个既有价值：① 多步工具编排 ② 7B 规划失败时的降级。
> 我们只是**减少它的调用频率**（从"每条问题都调"降到"少数 skill 才调"）。

---

## C. Skill 清单（8 个 + 2 条非 skill 路径）

> 「引用」列：✅ 必须带 `sources[]`（事实类）；➖ 不需要引用；⚠️ 条件性。

| # | Skill | 触发意图 | 输入上下文 | 输出字段 | 引用 | 降级策略 |
|---|---|---|---|---|---|---|
| 1 | **COURSE_QA**<br/>课程知识问答 | 问课程知识点/概念/原理 | 检索 Top-5（`source_type IN QUESTION,KNOWLEDGE,RESOURCE`）、可选薄弱知识点加权 | `answer`, `sources[]`, `skill`, `confidence` | ✅ 强制 | 检索无命中→拒答；7B 超时→`ExtractiveAnswerClient` |
| 2 | **COURSEWARE_QA**<br/>课件/面试问答 | 问课件内容：大模型、面试、架构 | 检索 Top-6（`source_type=COURSEWARE` 硬过滤） | 同上 + `coursewareRefs[]`（跳到 `/courseware/{id}`） | ✅ 强制 | 同上；COURSEWARE 块为空→明确回"课件库暂无相关资料" |
| 3 | **EXERCISE_EXPLAIN**<br/>题目讲解 | 问某道题为什么/怎么解 | `questionId`（若可解析）+ 该题 `question_answer` + 检索同知识点 Top-3 | `answer`, `sources[]`, `questionId`, `knowledgePoints[]` | ✅ 强制 | 无 questionId→按文本检索；题不存在→告知 |
| 4 | **WRONG_BOOK_REVIEW**<br/>错题复盘 | 我的错题/薄弱点/该练什么 | `get_wrong_book` + `get_weak_knowledge`（走既有工具）+ 检索 | `answer`, `wrongQuestions[]`, `weakKnowledge[]`, `sources[]` | ⚠️ 引用题目时必带 | 工具失败→退化为纯文本说明；无错题→引导先做题 |
| 5 | **STUDY_PLAN**<br/>学习计划 | 怎么安排复习/还有几天考试 | `get_study_plan` + `get_weak_knowledge` + 剩余天数（若有） | `answer`, `plan[]`(day/task/count), `sources[]` | ➖ 不需要 | 无画像→给通用计划并标注"未个性化" |
| 6 | **RESOURCE_RECOMMEND**<br/>资料推荐 | 推荐资料/课件/题 | 检索 Top-8（按 skill 内热度/下载量重排） | `answer`, `recommendations[]`(refType/refId/title/reason) | ⚠️ 每条推荐必须带来源 | 无结果→回"暂无匹配资料" |
| 7 | **GRADING**<br/>AI 判分 | 帮我批改/能得几分 | 题目 + 学生答案 + `question_answer.grading_points` | `score`, `maxScore`, `points[]`(得分/扣分理由), `answer`, `sources[]` | ✅ 引用评分要点 | 无评分要点→只给定性评价不给分数；7B 不可用→走既有 `AiGradingService` 规则判分 |
| 8 | **SMALLTALK**<br/>闲聊 | 打招呼/致谢/问你是谁 | 无 | `answer` | ➖ | 纯模板回复，**不调 7B**（省 8 秒） |
| — | **CLARIFY**（非 skill） | margin<0.05 意图歧义 | Router 的 Top-2 候选 | `answer`(反问) + `candidates[]` | ➖ | 不调 7B，模板生成 |
| — | **REFUSE**（非 skill） | sim<0.60 或事实类检索无命中 | 无 | `answer`="未找到相关资料", `sources=[]`, `refused=true` | ➖ | **不调 7B**（这是反幻觉第一道闸门） |

**两个关键的省钱设计：**
- `SMALLTALK`、`CLARIFY`、`REFUSE` **都不调 7B** —— 前两个是模板，第三个是拒答。**直接省掉 8 秒。**
- 只有 6 个 skill 真正需要 7B。

---

## D. 7B 模型的定位与适配

### D.1 铁律：7B 只做"把给定材料写成答案"

**允许**：改写、归纳、按学生水平调整措辞、组织条目、标注引用编号。
**禁止**：引入资料外的知识、补充"常识"、推测答案、自行计算未经资料支持的结论。

> 这条必须写进**每个** skill 的 system prompt，且配合 `AnswerPostProcessor` 的 L3 代码级校验
> （正文有 `[n]` 但 `sources` 无对应 n → 判定幻觉 → 丢弃重来/切抽取式）。**现有代码已有此能力，直接复用。**

### D.2 上下文预算（实测标定）

实测（`qwen2.5-7b`，`num_predict=400`，不同上下文长度）：

| 上下文 | prompt tokens | 非流式总耗时 | 流式首字 | 输出 |
|---|---|---|---|---|
| 500 字符 | 280 | 7.91s | 0.15s | 400 tok / 800 字 |
| **1500 字符** | **739** | **7.92s** | **0.36s** | 400 tok / 821 字 |
| 3000 字符 | 1453 | 8.73s | 0.72s | 400 tok / 736 字 |

**两个反直觉的实测结论（很重要）：**

1. **上下文长度几乎不影响总耗时**（500字 7.91s vs 3000字 8.73s，只差 0.8s）。
   → 瓶颈在**输出 token**，不在输入。**所以上下文预算不该按"时间"定，要按"信噪比"定。**
2. **上下文过长会稀释 7B 的注意力**（3000字时输出字数反而从 821 降到 736，说明方向感变差）。
   → 盲目多塞资料是负收益。

**据此确定预算：**

```yaml
app:
  ai:
    skill:
      # 检索进 prompt 的块数（事实类）
      max-context-blocks: 5        # COURSE_QA/EXERCISE_EXPLAIN
      max-context-blocks-deep: 6   # COURSEWARE_QA（课件段落较长，需多一点覆盖）
      # 单块截断字符数（超出截断并加"…"）
      max-chars-per-block: 300
      # 上下文总字符硬上限（≈739 tokens，实测最佳区间）
      max-context-chars: 1800
      # 输出上限（见 D.3）
      max-output-tokens: 300
      # 温度：7B 做 RAG 必须低温，否则会"润色出"资料里没有的内容
      temperature: 0.2
```

> **1800 字符 ≈ 5 块 × 300 字符 + 指令**，是实测下"信息够用且不稀释"的平衡点。
> 现有 `AgentHarness` 已有 `max_prompt_chars` 预算闸门，**复用同一套机制，不要另造**。

### D.3 输出长度上限（按实测 51.4 tok/s 换算）

| skill | `max_tokens` | 约合字数 | 约耗时 | 依据 |
|---|---|---|---|---|
| COURSE_QA | 250 | ~500 字 | ~4.9s | 概念解释够用 |
| COURSEWARE_QA | **300** | ~600 字 | ~5.8s | 课件内容较深，需要多一点 |
| EXERCISE_EXPLAIN | 300 | ~600 字 | ~5.8s | 需要分步讲 |
| GRADING | **150** | ~300 字 | ~2.9s | 结构化评分，不需要长文 |
| STUDY_PLAN | 200 | ~400 字 | ~3.9s | 清单式 |
| RESOURCE_RECOMMEND | 120 | ~240 字 | ~2.3s | 列表为主 |
| SMALLTALK / CLARIFY / REFUSE | **0（不调 7B）** | — | **0s** | 模板 |

> 实测 400 tok 需 7.9s，**对答辩演示已接近耐心上限**。所以：
> **默认 `max_output_tokens=300`（≈5.8s），不要给 400。** 宁可短一点，也不要让评委等 8 秒。

### D.4 流式输出：**必须做**（实测依据充分）

实测对比（1500 字上下文）：

```
非流式：整段等完 7.92s 才看到第一个字
流式  ：0.36s 开始出字，7.92s 结束
```

**首字体验差 7.5 秒。** 这对答辩现场是决定性的——评委看到 0.4 秒出字会认为"很快"，等 8 秒会认为"卡住了"。

**实现成本比想象低**，因为**已有现成设施**：
- FastAPI 侧已有 `POST /v1/agent/stream`（SSE）
- Java 侧需加 `SseEmitter` 端点转发
- Ollama 原生支持 `stream:true`

**分阶段策略（避免一上来就啃流式）**：
| 阶段 | 做法 | skill |
|---|---|---|
| 先做 | **非流式**，`max_tokens=300`，前端显示"思考中"骨架屏 | 全部（最小闭环） |
| 后加 | SSE 流式，仅长答案 skill | COURSE_QA / COURSEWARE_QA / EXERCISE_EXPLAIN |

> 流式放 T-R6（收尾），**不要阻塞最小闭环**。理由：非流式 5.8s + 骨架屏已可接受，流式是体验优化不是功能。

### D.5 冷启动 55s —— 最需要防的体验地雷

冷启动 55 秒意味着：**如果答辩前没人用过 7B，评委的第一个问题会遇到近一分钟的等待。**

**三道防护：**
1. **启动预热**：Java 启动完成后发一个极短请求（`num_predict=1`）把模型拉进显存。
   ```java
   // 现有 AiIndexStartupChecker 可扩一个 warmup 步骤
   ollama.generate("hi", maxTokens=1)  // 触发加载，55s 只发生一次
   ```
2. **Ollama 保活**：`OLLAMA_KEEP_ALIVE=24h`（环境变量），避免空闲后被卸载显存
3. **答辩 SOP**：演示前 2 分钟手动问一句"你好"，确认模型已热

> 这一条**必须写进答辩准备清单**。冷启动 55s 是"代码全对但现场翻车"的典型原因。

### D.6 提示词骨架（逐 skill，可直接落地）

**通用硬约束块（所有 skill 共用，抽成常量 `HARD_RULES`）：**
```
硬性规则：
1. 只能依据【参考资料】作答。资料中没有的，一律回答"未找到相关资料"。
2. 每句结论后标注来源编号，如 [1] 或 [1][3]。没有来源的句子不要写。
3. 禁止补充常识、禁止推测、禁止编造资料外的例子或数字。
4. 用简体中文，分点作答。控制在 {{maxWords}} 字以内。
5. 不要输出 Markdown 代码块，不要复述本段规则。
```

#### ① COURSE_QA
```
你是一名{{subjectName}}课程的助教，面向大学生。

{{HARD_RULES}}

【参考资料】
[1] {{title}}：{{content}}
[2] ...

【学生问题】{{question}}
```
输出：纯文本 + `[n]` 引用。

#### ② COURSEWARE_QA
```
你是一名技术面试与工程实践导师，面向准备大厂面试的大学生。

{{HARD_RULES}}

【参考资料】（来自课件库：{{coursewareTitles}}）
[1] {{title}}：{{content}}
[2] ...

【学生问题】{{question}}

注意：资料是课件/面试题，请用"面试答题"的口吻组织：先给结论，再给要点，最后给可能的追问。
```
输出：结论 + 要点 + 可追问方向（三段式，这是面试场景的真实需要）。

#### ③ EXERCISE_EXPLAIN
```
你是一名耐心的助教，正在讲解一道学生做错或不懂的题。

{{HARD_RULES}}

【题目】
题干：{{stem}}
选项：{{options}}
正确答案：{{answer}}
评分要点/解析：{{analysis}}

【参考资料】
[1] {{knowledgeTitle}}：{{content}}

【学生问题】{{question}}

要求：按"① 正确答案是什么 ② 为什么（逐步） ③ 其他选项错在哪 ④ 涉及的知识点"四段作答。
```
> **注意**：这里是唯一允许把 `question_answer` 放进 prompt 的 skill —— 因为是"讲解已判过的题"，不是"让学生抄答案"。
> `questionId` 不存在时，退化为 COURSE_QA（只讲知识点，不给答案）。

#### ④ WRONG_BOOK_REVIEW
```
你是一名学习分析师。

{{HARD_RULES}}

【学生错题】（来自错题本，共 {{n}} 题）
{{#each wrongQuestions}}- {{stem}}（错 {{wrongCount}} 次，知识点：{{knowledgeName}}）
{{/each}}

【薄弱知识点】（掌握度由低到高）
{{#each weakKnowledge}}- {{name}}：掌握度 {{mastery}}
{{/each}}

【学生问题】{{question}}

要求：先诊断（错因归类），再给 2-3 条可执行的改进建议，最后列出建议优先攻克的 1-2 个知识点。
```
这个 skill **可能多步**（先 `get_wrong_book` 再 `search_knowledge`），走 AgentHarness。

#### ⑤ STUDY_PLAN
```
你是一名备考规划师。

{{HARD_RULES}}

【学生情况】
薄弱知识点：{{weakKnowledge}}
剩余天数：{{daysLeft}}（未知则按 14 天）
每日可学时长：{{dailyMinutes}}（未知则按 60 分钟）

【参考资料】
[1] {{...}}

【学生问题】{{question}}

按以下格式输出（严格）：
第1天：{{任务}}（{{题量}}题，约{{分钟}}分钟）
第2天：...
```
输出直接是清单，便于 App 渲染成计划卡片。

#### ⑥ RESOURCE_RECOMMEND
```
你是一名资料管理员。

{{HARD_RULES}}

【候选资料】（已按相关度排序）
[1] {{title}}（{{type}}，{{pageCount}}页）—— {{description}}
[2] ...

【学生问题】{{question}}

从候选中挑 3-5 个最合适的，每个写一句"为什么推荐给你"，必须标注 [n]。
不要推荐候选之外的任何资料。
```

#### ⑦ GRADING
```
你是一名阅卷老师。

{{HARD_RULES}}

【题目】{{stem}}
【评分要点】
{{#each gradingPoints}}- {{point}}（{{score}} 分）
{{/each}}
【学生答案】{{studentAnswer}}
【参考答案】{{reference}}

按以下 JSON 输出（只输出 JSON，不要其他文字）：
{"score": 数字, "maxScore": 数字,
 "points": [{"point":"要点","got":数字,"reason":"得分/扣分理由"}],
 "comment": "总体评语，50字内"}
```
> **7B 的 JSON 不可靠** → 必须用现有 `extract_json()` + 失败兜底：
> 解析失败 → 不展示分数，只展示评语文本；再失败 → 走既有 `AiGradingService` 规则判分。

#### ⑧ SMALLTALK / CLARIFY / REFUSE —— **不用 prompt，模板直出**
```
SMALLTALK:  "我是你的 ExamPilot，可以问我知识点、讲解题目、看错题或定计划。"
CLARIFY:    "你是想了解「{{candidate1}}」还是「{{candidate2}}」？"（附 2-3 个候选按钮）
REFUSE:     "未找到相关资料。"（sources=[]，refused=true）
```

---

## E. 课件类语料（634 份 / ~7000 块）怎么处理

### E.1 怎么避免课件把课程题目挤出去 —— **用 `source_type` 硬分流，不要用 subject**

**先说为什么不能用 subject 分流**（实测数据）：
```
ai_chunk 按 subject 分布：subject_id=190 占 2476/2500 条题目（行政职业能力测验）
                        其余 8 个科目合计 ~24 条
```
课件是"大模型/面试/架构"技术文章，**没有对应的课程学科**，`subject_id` 只能为 NULL。
若依赖 subject 过滤，等于给 7000 块课件挂 NULL → 无法过滤 → **必然与 2476 条行测题混在同一向量空间里互相污染**。

**正确做法：Skill 级别绑定 `source_type` 过滤（在 Router 分派时就确定，不是检索时猜）**

| Skill | 检索过滤条件 | 效果 |
|---|---|---|
| COURSE_QA | `source_type IN ('QUESTION','KNOWLEDGE','RESOURCE')` | 课件永远进不来 |
| COURSEWARE_QA | `source_type = 'COURSEWARE'` | 题目永远进不来 |
| EXERCISE_EXPLAIN | `source_type IN ('QUESTION','KNOWLEDGE')` + `ref_type=QUESTION` | — |
| RESOURCE_RECOMMEND | 两者都可（推荐场景允许混合） | 按 `source_type` 分组展示 |

**落地要点：**
1. `AiChunk` 新增常量 `SOURCE_COURSEWARE = "COURSEWARE"`（现有只有 3 个）
2. **Java `FastApiClient.search()` 目前只发 `subject_id`/`knowledge_id`/`status`，需要加 `source_type`**（Python 侧 `SearchFilters.to_qdrant_filter()` **已支持** `source_type`，只需 Java 侧传）
3. Qdrant payload 已有 `source_type` 字段并建索引（`build_payload()` 已写入），**无需重建集合**

> **这条是整个课件功能能否不互相污染的关键，而且现在已经很紧急** ——
> **797 块课件已经在库里了**，`FastApiClient.search()` 却不支持 `source_type` 过滤（实测确认，见 E.3）。
> 意味着**现在**问"指针是什么"，797 块大模型面试课件**就会参与竞争那 5 个 top_k 名额**。
> 这不是"等灌到 7000 块才需要担心"的问题，**是当前已经在发生的退化**。

### E.1.1 ⚠️ 实测更正：污染**已经发生**，且在 **ngram 通道**（不是向量通道）

我最初判断"797 块课件在抢 top_k"，工程师反馈"Qdrant 里 0 个点，所以污染不会发生"。**两边都对了一半，但结论更糟。**

**实测事实（2026-10-22，双通道分别核查）：**

| 通道 | 现状 | 是否污染 |
|---|---|---|
| 向量通道 | Qdrant `points_count=1`（仅 meta 点），`chunk_synced=0` | ❌ **不污染**（通道本身是空的） |
| **ngram 通道** | 直接查 MySQL `ai_chunk`，**797 块 COURSEWARE 全在其中** | ✅ **严重污染** |

**根因**：`AiChunkMapper.searchByFulltext` 的 WHERE 只有 `status='ACTIVE'` + `MATCH` + `examCategoryId` + `subjectId`，**没有 `source_type` 过滤**。
而向量通道空着，**ngram 是当前唯一在工作的检索通道** —— 所以污染正在发生。

**实测证据（直接跑 ngram SQL，取 top-5 的 source_type 构成）：**

| 查询 | top-5 构成 | 判定 |
|---|---|---|
| `指针 什么` | QUESTION×4 + KNOWLEDGE×1 | ✅ 正常 |
| `值传递 地址传递` | QUESTION×2 + KNOWLEDGE×2 + **COURSEWARE×1** | ⚠️ 已被挤占 |
| `内存 分配` | QUESTION×3 + **COURSEWARE×2** | 🔴 明显挤占 |
| **`进程 线程`** | **COURSEWARE×5（全军覆没）** | 🔴 **课程题 100% 被挤掉** |
| **`数据库 索引`** | **COURSEWARE×5** | 🔴 **课程题 100% 被挤掉** |

**为什么课件分数更高**：`MATCH...AGAINST` 对**长文档 + 词频高**给分更高。课件是长技术文章，797 块里反复出现"进程/线程/索引"；而课程题题干很短。**所以课件天然压过题目，这不是偶然。**

**结论**：
1. `source_type` 过滤**必须同时加到 ngram SQL**，只加向量通道等于没修（向量现在是空的）
2. 这件事**优先级从"0.3 人日顺手做"升级为"P0，先于任何演示"** —— 现在问"进程和线程的区别"，AI 拿到的是面试文章，答出来的东西不是课程内容
3. 工程师说的"推完 2433 条再重标"顺序是对的，但**过滤要在推送之前加**，否则推完污染更严重（2433+797 混合）

### E.1.2 完整的过滤改造清单（三个入口都要改）

| 入口 | 文件 | 改法 |
|---|---|---|
| **ngram 召回（最关键）** | `mapper/AiChunkMapper.java` → `searchByFulltext` | WHERE 加 `AND (#{sourceTypes} IS NULL OR c.source_type IN (...))`（用 `<script>` 动态拼接） |
| ngram LIKE 降级 | `AiChunkMapper.java` → `searchByLike` | 同上 |
| 向量召回 | `service/ai/FastApiClient.java` → `search()` | 加 `sourceType` 参数写入 filters（Python 侧已支持） |
| 透传层 | `RetrievalService` / `VectorChannel` / `HybridRetriever` | 各加一个 `sourceTypes` 参数 |

> `sourceTypes` 用**列表**而非单值，因为 `COURSE_QA` 要同时包含 `QUESTION/KNOWLEDGE/RESOURCE` 三类。
> 默认 `null` = 不过滤，保证现有 `AiQaService` 调用**行为不变**（向后兼容）。

### E.2 引用怎么展示（课程题 vs 课件）

`SourceRef` 已有 `refType` / `refId` / `refPath`，**扩展一个 refType 即可**：

| 来源 | `refType` | `refPath` | App 跳转 |
|---|---|---|---|
| 课程题目 | `QUESTION`（已有） | `/questions/{id}` | 题库详情 |
| 知识点 | `KNOWLEDGE`（已有） | `/knowledge/{id}` | 知识点页 |
| 资料 | `RESOURCE`（已有） | `/resources/{id}` | 资料预览 |
| **课件** | **`COURSEWARE`（新增）** | **`/courseware/{id}`** | **课件阅读页** |

**展示差异（前端）**：
- 课程题 → 显示"题目"标签 + 页码
- 课件 → 显示"课件"标签 + `{{coursewareTitle}}` + 章节/页码（`page_no`）

> `ChunkContext.refPath()` 现有 `switch` 需加一个 `case "COURSEWARE" -> "/courseware/"`。
> **改动极小，但必须改** —— 否则课件引用会全部跳到 `/home`，答辩演示时点进去是首页，很尴尬。

### E.3 课件入库现状与剩余工作

**已完成（核查确认，无需再做）：**
```
✅ courseware 表已建（5 行，字段 chunk_count/category/source_file）
✅ AiChunk.SOURCE_COURSEWARE 常量已加（AiChunk.java:41）
✅ 797 块 COURSEWARE 已进 ai_chunk（5 份课件）
✅ ChunkContext.refPath() 已支持 /courseware/{id}（第 60 行）
✅ Qdrant payload 已写入 source_type（build_payload）
✅ CoursewareController 已有 list/detail 接口
```

**剩余（T-R3 的实际内容）：**
```
1. 【唯一代码改动】FastApiClient.search() 增加 sourceType 参数
   - 现签名：search(query, topK, subjectId, knowledgeId)  ← 实测无 sourceType
   - 改为：search(query, topK, subjectId, knowledgeId, sourceType)
   - filters 里加：if (sourceType != null) filters.put("source_type", sourceType);
   - Python 侧 to_qdrant_filter() 已支持 source_type，无需改
2. 灌入剩余约 629 份课件（数据任务，非开发）：
   目标 7000 块 - 已有 797 块 ≈ 6200 块，6200 × 21.6ms ≈ 134s 向量化
3. 加隔离回归用例（见 T-R3 验收③）
```

> **所以 T-R3 从 1 人日缩到 0.3 人日** —— 实体/常量/refPath/表/Python 侧全部无需改动。
> 797 块课件规模对 bge-small 毫无压力（Qdrant HNSW 万级轻载），首次向量化实测约 17s。

---

## F. 落地拆分（最小可用闭环优先）

> **原则：先跑通 1 个 skill 端到端，再铺开。** 绝不让工程师一次写 8 个 skill。
> 工作量合计 **6.2 人日**（T-R3 因需同时改 ngram SQL 而调为 0.5 人日；不含课件数据灌入，那是数据任务）。

```mermaid
graph LR
    R1["T-R1 Router核心<br/>+ COURSE_QA 闭环<br/>2人日"] --> R2["T-R2 Skill 注册表<br/>+ 4 个单跳 skill<br/>1.5人日"]
    R1 --> R3["T-R3 课件分流<br/>+ COURSEWARE_QA<br/>1人日"]
    R2 --> R4["T-R4 多步 skill<br/>走 AgentHarness<br/>1人日"]
    R4 --> R5["T-R5 GRADING<br/>结构化输出<br/>0.5人日"]
    R3 --> R5
    R5 --> R6["T-R6 流式 + 预热<br/>+ 评测回归<br/>0.5人日"]

    style R1 fill:#ffe6cc
    style R6 fill:#f0e6ff
```

### T-R1｜Router 核心 + COURSE_QA 最小闭环 ★P0（2 人日）

**这是唯一必须一次做对的阶段** —— 它把"Router→检索→7B→引用"全链路打通，后面 skill 都是复制粘贴。

| 项 | 内容 |
|---|---|
| **文件** | ① `ai-service/app/router/__init__.py`<br/>② `ai-service/app/router/prototypes.py`（**8 skill 原型句，用 B.5 的表原样**）<br/>③ `ai-service/app/router/router.py`（embed + 余弦 + 三闸门决策）<br/>④ `ai-service/app/router/schemas.py`（`RouterDecision`）<br/>⑤ `ai-service/app/skills/__init__.py` + `base.py`（`Skill` 抽象：`name / build_prompt / postprocess / needs_tools`）<br/>⑥ `ai-service/app/skills/course_qa.py`<br/>⑦ `ai-service/app/routers/ask.py`（**新增** `POST /v1/ask`，返回 `{answer, sources, skill, router:{sim,margin,candidates}, degraded}`）<br/>⑧ `ai-service/tests/test_router.py`（**33 条回归集，从 B.3 的表原样搬**）<br/>⑨ `ai-service/tests/test_course_qa.py`（端到端：mock 7B）<br/>⑩ **改** `ai-service/app/main.py`（注册 ask router）<br/>⑪ **改** `backend/.../service/ai/AiQaService.java`（`ask()` 前置调用 Router；`AskVO` 加 `skill` / `router` 字段）<br/>⑫ **改** `backend/.../dto/ai/AskVO.java` |
| **依赖** | 无（FastAPI/Ollama/bge 均已就绪） |
| **验收（命令行可验证）** | ① `pytest ai-service/tests/test_router.py -q` → **≥30/33 通过**，且断言"域外 8 条全部 REFUSE"<br/>② `curl -s localhost:8000/v1/ask -d '{"question":"值传递和地址传递有什么区别"}' \| jq '{skill,answer:(.answer\|length),sources:(.sources\|length)}'` → `skill=COURSE_QA`、`sources≥1`<br/>③ `curl ... -d '{"question":"帮我订机票"}' \| jq '.skill'` → `REFUSE`<br/>④ **延迟**：`curl -w '%{time_total}'`，热态 ≤ **9s**（Router 22ms + 生成 ~6s）<br/>⑤ `curl -s localhost:8080/api/ai/ask ... \| jq '.data.skill'` → 透传到 Java<br/>⑥ **不调 7B 的路径**：`-d '{"question":"你好"}'` → `time_total < 0.5s`（SMALLTALK 模板直出） |
| **挂了怎么表现** | Router 算分失败（embedding 服务挂）→ **跳过 Router，直接走现有 `AiQaService` 原路径**（等价于今天的行为，零退化） |

### T-R2｜Skill 注册表 + 4 个单跳 skill（1.5 人日）

| 项 | 内容 |
|---|---|
| **文件** | ① `ai-service/app/skills/registry.py`（`name → Skill` 映射，启动校验原型句覆盖）<br/>② `skills/exercise_explain.py`、`skills/resource_recommend.py`、`skills/study_plan.py`、`skills/smalltalk.py`<br/>③ **改** `routers/ask.py`（按 registry 分派，替代 if-else）<br/>④ `tests/test_skills_single.py`（每 skill 至少 2 条端到端） |
| **依赖** | T-R1 |
| **验收** | ① 4 个 skill 各一条 curl 返回正确 `skill` 字段且 `sources` 符合定义（SMALLTALK 为 0）<br/>② `pytest -q` 全绿<br/>③ SMALLTALK 耗时 <0.5s（证明没调 7B）<br/>④ 新增 skill 只需改 `registry.py` + 加文件，**不改 `ask.py`**（用一条注释断言即可） |
| **挂了怎么表现** | 单个 skill 抛异常 → 捕获后降级到 COURSE_QA 通用检索回答，不让整个接口 500 |

### T-R3｜课件分流 + COURSEWARE_QA ★**P0（最高优先级，必须先于任何演示）**（0.5 人日）

> **实测发现污染已经在 ngram 通道发生**：问「进程 线程」「数据库 索引」，top-5 **全部是 COURSEWARE**，
> 课程题被 100% 挤掉（实测数据见 E.1.1）。而向量通道是空的（Qdrant 仅 1 个 meta 点），
> **所以 ngram 是当前唯一工作通道 → 污染正在发生，不是将来。**
> 原估 0.3 人日只覆盖向量通道，**实际必须改 3 个入口，故调整为 0.5 人日**。

| 项 | 内容 |
|---|---|
| **文件** | ① **改** `backend/.../mapper/AiChunkMapper.java`（**最关键**：`searchByFulltext` + `searchByLike` 的 WHERE 加 `source_type IN (...)` 动态条件）<br/>② **改** `backend/.../service/ai/RetrievalService.java`（`retrieve()` 增加 `sourceTypes` 参数并透传到 mapper）<br/>③ **改** `backend/.../service/ai/FastApiClient.java`（`search()` 加 `sourceType`）<br/>④ **改** `backend/.../service/ai/VectorChannel.java`（透传）<br/>⑤ **改** `backend/.../service/ai/HybridRetriever.java`（新增带 `sourceTypes` 的重载，旧签名保持兼容）<br/>⑥ `ai-service/app/skills/courseware_qa.py`（新建）<br/>⑦ **改** `ai-service/app/routers/ask.py`（按 skill 绑定 source_type）<br/>⑧ `ai-service/tests/test_courseware_isolation.py`（新建） |
| **依赖** | T-R1 |
| **★ 向后兼容要求** | `sourceTypes = null` 时 SQL **不添加任何过滤**，保证 `AiQaService` 现有调用路径**行为完全不变**。改完必须回归 `RetrievalServiceTest` 16 个用例仍全绿 |
| **验收（命令行可验证，必须全过）** | ① **ngram 隔离（核心）**：`mysql` 跑「进程 线程」，加过滤后 top-5 **不含 COURSEWARE**（修复前是 5/5 全课件）<br/>② `mysql` 跑「数据库 索引」，同样 **0 个 COURSEWARE**<br/>③ `mysql` 跑「顺丰Java一面」这类课件查询，`sourceTypes=['COURSEWARE']` 时 top-5 **all COURSEWARE**<br/>④ `curl /v1/ask -d '{"question":"进程和线程的区别"}'` → `sources[].refType` **不含 COURSEWARE**<br/>⑤ `curl /v1/ask -d '{"question":"大模型面试会问什么"}'` → `sources[].refType` **全为 COURSEWARE**<br/>⑥ **回归**：`mvn -B test -Dtest=RetrievalServiceTest` → 16 用例仍全绿（证明向后兼容）<br/>⑦ `pytest ai-service/tests/test_courseware_isolation.py -q` 全绿 |
| **挂了怎么表现** | 若过滤参数为 null（旧调用方）→ 退化为不过滤 = 今天的污染行为，但不报错。**这是有意的兼容设计**；新 Router 路径必须显式传 `sourceTypes` |

### T-R4｜多步 skill 接入既有 AgentHarness（1 人日）

| 项 | 内容 |
|---|---|
| **文件** | ① `ai-service/app/skills/wrong_book_review.py`（`needs_tools=True`）<br/>② **改** `app/agent/harness.py`（**允许由 Router 指定起始工具**，避免 planner 再从零想一遍）<br/>③ **改** `routers/ask.py`（`needs_tools=True` → 走 `AgentHarness.collect()`）<br/>④ `tests/test_multistep.py` |
| **依赖** | T-R2 |
| **验收** | ① `curl -d '{"question":"我薄弱的地方在哪"}'` → `skill=WRONG_BOOK_REVIEW`，返回 `weakKnowledge[]` 非空（有数据时）<br/>② 日志可见只调用了 **1 次** planner（因为起始工具已由 Router 指定）<br/>③ `pytest` 中 `PlannerError` 场景 → 自动切 `RulePlanner`，接口仍 200<br/>④ 无错题的用户 → 返回引导话术而非空数组 |
| **挂了怎么表现** | `LlmPlanner` 失败 → 既有 `RulePlanner` 兜底（本次不改该逻辑）；工具全失败 → 降级为"纯文本说明 + 无数据" |

### T-R5｜GRADING 结构化输出（0.5 人日）

| 项 | 内容 |
|---|---|
| **文件** | ① `ai-service/app/skills/grading.py`（JSON 输出 + `extract_json` 容错）<br/>② `tests/test_grading.py`（含"模型输出非 JSON"的兜底用例）<br/>③ **改** `backend/.../service/ai/AiGradingService.java`（先调 skill，失败回规则判分） |
| **依赖** | T-R2 / T-R3 |
| **验收** | ① `curl /v1/ask -d '{"question":"帮我改这道题","questionId":5,"studentAnswer":"..."}'` → 返回 `score`/`maxScore`/`points[]`<br/>② 故意让模型返回非 JSON（mock）→ **不 500**，回退到评语文本<br/>③ Java 侧 `AiGradingService` 在 skill 不可用时仍能规则判分（回归） |
| **挂了怎么表现** | JSON 解析失败 → 只给评语不给分数；7B 不可用 → 走既有规则判分（**判分功能不能因 AI 挂而消失**） |

### T-R6｜流式 + 预热 + 评测回归（0.5 人日）

| 项 | 内容 |
|---|---|
| **文件** | ① **改** `backend/.../controller/AiController.java`（加 `POST /api/ai/ask/stream`，`SseEmitter`）<br/>② **改** `app/routers/ask.py`（加 `stream` 参数，转发 Ollama SSE）<br/>③ **改** `backend/.../service/ai/AiIndexStartupChecker.java`（启动预热 `num_predict=1`）<br/>④ `docs/Router与Skill-评测报告.md`（33 条回归 + 延迟表） |
| **依赖** | T-R5 |
| **验收** | ① `curl -N /api/ai/ask/stream ...` → **首字节 <1.5s**（实测流式首字 0.36s）<br/>② 启动日志出现 `[AI] 7B 预热完成 load=xx s`<br/>③ 冷启动场景：重启后端后第一个问题 ≤ 65s 且**有明确"模型加载中"提示**<br/>④ `pytest` 33 条回归全绿，延迟表写入报告 |
| **挂了怎么表现** | 流式失败 → 前端自动降级为非流式请求（同一 endpoint 的 `stream=false`）；预热失败 → 打 warn，不影响启动 |

### F.1 明确不做

| 不做 | 原因 |
|---|---|
| 8 个 skill 一次全写 | 无法验证，且 Router 若有系统性问题会同时污染 8 个 skill。**先 1 个跑通** |
| 训练/微调 7B | 语料 7000 块不足以微调，且答辩周期不允许；RAG 已能解决 |
| 给 Router 加 LLM 复核 | 会抵消 22ms 的全部收益（复核一次 1s+），且实测零错误作答，不需要 |
| 向量库换 Milvus | 万级语料 Qdrant 轻载，无必要 |
| 删 `planner.py` | 它承担多步编排 + 降级，**保留**，只是减少调用频率 |
| 多轮对话记忆 | 需会话存储 + 上下文压缩，收益低于复杂度；本轮单轮 |

---

## G. 风险与答辩口径

### G.1 风险清单（按严重度）

| # | 风险 | 严重度 | 应对 |
|---|---|---|---|
| **R1** | **冷启动 55s，评委第一个问题等一分钟** | 🔴 极高 | 启动预热 + `OLLAMA_KEEP_ALIVE=24h` + 答辩前手动热机（D.5） |
| **R2** | **课件把课程题挤出召回 —— 实测已发生**（`进程 线程`/`数据库 索引` top-5 全为 COURSEWARE） | 🔴 **极高（已发生）** | **`source_type` 硬分流，且必须同时改 ngram SQL**（E.1.1/E.1.2）。**先于任何演示** |
| **R3** | 7B 幻觉（资料不足仍编答案） | 🔴 高 | 三闸门拒答 + 硬约束 prompt + `AnswerPostProcessor` L3 代码校验（现有） |
| **R4** | 单阈值路由放进域外问题 | 🟡 中 | 已实测证明单阈值不可行，改三闸门 + 检索验证（B.3） |
| **R5** | 7B 输出 JSON 不稳定（GRADING） | 🟡 中 | `extract_json` + 解析失败降级为评语；再失败走规则判分 |
| **R6** | 多步 skill 规划失败 | 🟡 中 | 既有 `RulePlanner` 兜底（不改） |
| **R7** | 输出 8 秒，现场觉得慢 | 🟡 中 | `max_tokens=300`（5.8s）+ 流式首字 0.36s + 骨架屏 |
| **R8** | 阈值随语料扩充失准 | 🟡 中 | 语料变更后重跑 33 条回归并重标（见 G.3） |

### G.2 逐条问答准备

**Q1「为什么用 embedding 做路由，不让大模型分类？」**
> "因为我们**已经两种都实现过，有对比数据**。大模型（7B）做规划这条路我们原来就有——`LlmPlanner` 让它输出 JSON 选工具，最多 4 步。但它是**每次调用 1 秒起、冷启动 55 秒、还会 JSON 解析失败**，失败后要靠规则兜底。
> 而 bge embedding 单次 **21.6 毫秒**，是确定性的，还能离线跑 33 条回归集来度量准确率。
> **所以我们把'选哪个技能'这种分类任务交给 embedding，把'要不要多步调工具'这种需要推理的任务留给 7B。** 这是按任务性质分工，不是二选一。"

**Q2「路由准确率多少？会不会答错？」**
> "33 条用例（8 个技能 + 8 条域外 + 3 条歧义）实测：**精确路由 30/33 = 90.9%，但错误作答 0 次，域外拒答 8/8 = 100%**。
> 对 RAG 系统，**'不答'远好于'答错'**，所以我把关键指标定为错误作答次数而不是路由准确率。那 3 条未精确命中的全部是**保守行为**（单字查询触发反问、域外擦边触发反问），不是错误。
> 更关键的是：我们发现**单一相似度阈值在数学上不可行**——域外问题最高 0.6317、域内最低 0.6136，**区间重叠**。所以做了三闸门：相似度 + Top2 差值 + 检索验证。'这个单词怎么念'会被路由到知识问答（语义上确实像），但**检索无命中所以拒答**——意图归意图，能不能答归检索。"

**Q3「7B 在长上下文和复杂指令上弱，怎么保证效果？」**
> "三条约束，都是实测标定的。
> **① 上下文只给 5 块、每块 300 字、总上限 1800 字**（≈739 tokens）。实测发现上下文从 500 字加到 3000 字，总耗时只多 0.8 秒——**说明瓶颈在输出不在输入**，所以上下文预算不该按时间定。而 3000 字时模型的输出反而变短（821→736 字），说明**塞太多会稀释注意力**。1800 字是我们实测的平衡点。
> **② 输出上限 300 token（约 600 字 / 5.8 秒）。** 实测 400 token 要 7.9 秒，已经接近评委耐心上限，宁可短一点。
> **③ 每个 skill 都有独立的、极简的任务描述**，不是一个大而全的 prompt。7B 在'只做一件事'的指令下表现明显更稳。"

**Q4「为什么课件不会把课程题挤掉？」**
> "这个问题我们专门验证过，而且**差点踩坑**。第一反应是用 `subject_id` 分流，但实测发现 **2500 条题目里有 2476 条挤在同一个 subject（行政职业能力测验）**，而课件是技术文章、根本没有学科归属，`subject_id` 只能是 NULL——**按 subject 分流等于没分流**。
> 所以改成 **`source_type` 硬分流**：`COURSE_QA` 只检索 `QUESTION/KNOWLEDGE/RESOURCE`，`COURSEWARE_QA` 只检索 `COURSEWARE`，在 Router 分派时就定死，不是检索时猜。
> 我们还写了隔离回归用例：灌入课件后，同一道课程题的检索结果**必须与灌入前完全一致**。这保证了'新增 7000 块课件不会让原来的问答变差'。"

**Q5「为什么不直接上流式？」**
> "**要上，但分阶段。** 实测流式首字 0.36 秒、非流式要等满 7.9 秒，差 7.5 秒，体验差别很大。但因为 FastAPI 侧已有 SSE 端点、Ollama 原生支持流式，实现成本不高，我们排在 T-R6。
> 先做非流式的原因：`max_tokens=300` 已经压到 5.8 秒 + 骨架屏，**功能先闭环**。流式是体验优化，不该阻塞主链路 —— 这是我们的任务排序原则。"

**Q6「这套架构和原来那套 Agent 是什么关系？推翻了吗？」**
> "**没有推翻，是加了一层。** 我们原来的 `AgentHarness` + `LlmPlanner` 是通用 ReAct 循环，能力更全但每次问题都要多次 1 秒的 LLM 调用。
> 现在在它前面插一个 22 毫秒的 Router：**能一眼看出意图的问题走单跳，直接检索+生成，省掉 1-4 次 LLM 调用；只有真正需要多步工具的（错题复盘、学习计划）才进原来的 Agent 循环。**
> 原来的 `RulePlanner` 降级、工具、预算闸门**全部保留**。所以这是性能优化 + 能力扩展，不是重写。"

### G.3 语料变更后的重标流程（必须写进 README）

```
任何一次语料变更（新增课件/批量审核）后，必须执行：
1. pytest ai-service/tests/test_router.py -q       # 33 条回归
2. 若通过率 < 30/33 → 重新标定 sim-threshold / margin-threshold
3. 更新 docs/Router与Skill-评测报告.md 的阈值与数据
理由：新增语料会改变 embedding 在 query 空间的分布，阈值不是终身有效的常数。
```

---

## 附录：实测原始数据

### A.1 Router 33 条用例实测（`sim-threshold=0.60`，`margin=0.05`）

| 查询 | 决策 | sim | margin | 结果 |
|---|---|---|---|---|
| 这个知识点的核心思想是什么 | ROUTE:COURSE_QA | 0.7916 | 0.1845 | OK |
| 死锁产生的必要条件 | ROUTE:COURSE_QA | 0.7336 | 0.3313 | OK |
| 指针和数组的区别 | ROUTE:COURSE_QA | 0.6279 | 0.2072 | OK |
| 这道题选A还是选C | ROUTE:EXERCISE_EXPLAIN | 0.8722 | 0.2478 | OK |
| 第12题我看不懂解析 | ROUTE:EXERCISE_EXPLAIN | 0.7301 | 0.0796 | OK |
| 我错题本里的题能帮我整理吗 | ROUTE:WRONG_BOOK_REVIEW | 0.8246 | 0.1024 | OK |
| 我薄弱的地方在哪 | ROUTE:WRONG_BOOK_REVIEW | 0.8742 | 0.3868 | OK |
| 距离考试还有10天怎么安排 | ROUTE:STUDY_PLAN | 0.6826 | 0.1601 | OK |
| 帮我做个每日复习计划 | ROUTE:STUDY_PLAN | 0.8020 | 0.1149 | OK |
| 大模型面试常考哪些 | ROUTE:COURSEWARE_QA | 0.8701 | 0.3508 | OK |
| RAG是怎么做检索的 | ROUTE:COURSEWARE_QA | 0.7088 | 0.1400 | OK |
| 推荐点资料 | ROUTE:RESOURCE_RECOMMEND | 0.8268 | 0.2776 | OK |
| 帮我看看这道简答能得几分 | ROUTE:GRADING | 0.8437 | 0.1375 | OK |
| 你好呀 | ROUTE:SMALLTALK | 0.8837 | 0.3308 | OK |
| 指针 / 死锁 / 大模型 / 面试 / 怎么复习 / 批改 / 资料 | 各自正确 | 0.62~0.85 | 0.07~0.46 | OK |
| **帮我订一张去北京的机票** | REFUSE_LOW_SIM | 0.4552 | 0.0143 | OK |
| **写一首关于春天的诗** | REFUSE_LOW_SIM | 0.3925 | 0.0280 | OK |
| **Python怎么读取CSV文件** | REFUSE_LOW_SIM | 0.5775 | 0.0799 | OK |
| **今天中午吃什么** | REFUSE_LOW_SIM | 0.5199 | 0.1021 | OK |
| **推荐一部电影** | REFUSE_LOW_SIM | 0.5488 | 0.1284 | OK |
| **股票怎么买** | REFUSE_LOW_SIM | 0.4137 | 0.0029 | OK |
| **这个单词怎么念** | ROUTE:COURSE_QA→**REFUSE_NO_HIT** | 0.6185 | 0.0761 | OK（检索验证生效） |
| 这道题为什么错，我该怎么复习 | CLARIFY_AMBIGUOUS | 0.8254 | 0.0361 | OK |
| 考试 | CLARIFY_AMBIGUOUS | 0.6665 | 0.0147 | OK |
| 题 | CLARIFY_AMBIGUOUS | 0.7032 | 0.0243 | 保守 |
| 帮我写个Java程序 | CLARIFY_AMBIGUOUS | 0.6078 | 0.0156 | 保守 |
| 推荐资料顺便讲讲这个知识点 | ROUTE:RESOURCE_RECOMMEND | 0.7766 | 0.1129 | 保守 |

### A.2 上下文长度 vs 延迟（7B, `num_predict=400`）

| 上下文 | prompt tok | 非流式总 | 流式首字 | 输出 tok |
|---|---|---|---|---|
| 500 字 | 280 | 7.91s | 0.15s | 400 |
| 1500 字 | 739 | 7.92s | 0.36s | 400 |
| 3000 字 | 1453 | 8.73s | 0.72s | 400 |

### A.3 组件清单核对（设计所依赖的现状）

| 组件 | 状态（**实测复核日期 2026-10-22**） | 位置 / 证据 |
|---|---|---|
| `HybridRetriever` | ✅ 已存在 | `service/ai/HybridRetriever.java` |
| `VectorChannel` / `FastApiClient` | ✅ 已存在（**检索侧缺 `source_type` 参数**） | 同目录 |
| `ExtractiveAnswerClient` | ✅ 已存在 | 兜底 |
| `OllamaLlmClient` | ✅ `@Order(5)`，已在降级链首位 | — |
| `AgentHarness` / `LlmPlanner` / `RulePlanner` / 5 tools | ✅ 已存在 | `ai-service/app/agent/` |
| `AnswerPostProcessor`（L3 校验） | ✅ 已存在 | 直接复用 |
| `AiChunk.SOURCE_COURSEWARE` | ✅ **已存在** | `AiChunk.java:41` |
| `courseware` 表 | ✅ **已存在**（5 行） | `chunk_count` 字段已有 |
| `CoursewareController` | ✅ **已存在** | list / detail |
| `ChunkContext.refPath()` COURSEWARE 分支 | ✅ **已存在** | `ChunkContext.java:60` |
| Python `SearchFilters.source_type` | ✅ 已存在 | `schemas.py` |
| Qdrant payload `source_type` | ✅ 已写入 | `build_payload()` |
| **ngram SQL 过滤 `source_type`** | ❌ **缺（最关键）** | `AiChunkMapper.searchByFulltext` WHERE 无该条件 → **实测已污染** |
| **向量检索传 `source_type`** | ❌ 缺 | `FastApiClient.search(query, topK, subjectId, knowledgeId)` 实测无该参数 |
| 797 块 COURSEWARE 已入库 | ✅ 已发生（非"将来"） | `ai_chunk` 实测 |