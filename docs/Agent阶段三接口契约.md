# Agent 阶段三接口契约（冻结）

适用范围：阶段三「可观测性与持续评估」+ 阶段二遗留 A1/A11/A8。本文件是三实现单元（Java / Python / 管理端）的**唯一口径来源**，实现与验收冲突时以本文件为准。日期 2026-10-31。

## 1. traceId 贯通契约

**载体**：头名 `X-Trace-Id`，值 `^[0-9a-f]{16}$`（16 位小写 hex）；生成规则与现状一致（Java `UUID.randomUUID()...substring(0,16)` / Python `uuid4().hex[:16]`）。
**优先级**：合法入站头 > 同线程已有 MDC/contextvar > 新生成；入站值不匹配正则时**丢弃并重新生成、不报错**（防日志注入）；traceId 不进 URL/query。
**唯一来源**：Java `TraceContext.HEADER`、Python `main.TRACE_ID_HEADER`，两处字符串必须相等（均已存在，勿新增第三个常量）。

| # | 传播点 | 载体 | 规则 |
|---|---|---|---|
| 1 | 客户端 → Java | 请求头 `X-Trace-Id` | 合法则复用；无/非法则生成 |
| 2 | Java → 客户端 | **响应头** `X-Trace-Id` | Filter 在 chain 之前 setHeader；401/403/500/SSE/异常路径一律携带 |
| 3 | Java 响应体 | `Result.traceId` | 取 `TraceContext.current()`，缺失才生成。**删除** `Result:33` 与 `AiAgentController:59` 各自 `randomUUID` 的写法 |
| 4 | Java 日志 | MDC key `traceId` | `application.yml` 的 `logging.pattern.console` 增 `[%X{traceId:-}]` |
| 5 | Java → Python | 头 `X-Trace-Id` + body `traceId` | 两者必须同值；Python 读取**以头为准**，body 字段保留兼容（`AgentStreamService:259-272` 补头） |
| 6 | Python 入/出 | `trace_id_middleware` | 现有逻辑（`main.py:186-205`）保留：透传/生成 + 回写响应头 + `X-Cost-Ms`；补写 contextvar 供日志与出站读取 |
| 7 | Python 日志 | contextvar + `logging.Filter` | 格式加 `[%(trace_id)s]`；request.state 在并发下不可用于日志，禁止用它取 trace |
| 8 | Python → Java | 头 `X-Trace-Id` | `JavaBridge._headers()` 增该头（`java_bridge.py:29-30` 当前只有 token）；缺失时`不带`，由 Java Filter 生成兜底 |
| 9 | 落库 | `ai_agent_run.trace_id` | 写 `run.traceId`，与 1/2 同值；列为 `VARCHAR(32)`，16 位足够 |
| 10 | SSE `meta` 事件 | `data.traceId` | 与 1/9 同值；`final`/`error` 事件不重复携带 |

**Filter 注册顺序与清理（防线程池串号）**
- 新增 `config/TraceIdFilter.java`（`OncePerRequestFilter`，**不加 `@Component`**，否则 Boot 会二次注册全局链）+ `config/TraceIdConfig.java` 以 `FilterRegistrationBean` 注册：`setOrder(Ordered.HIGHEST_PRECEDENCE)`（**必须早于 Spring Security 的 -100**，否则 401/403 无头）、`addUrlPatterns("/*")`、`setDispatcherTypes(DispatcherType.REQUEST)`（不含 ASYNC/ERROR，避免 SSE 收尾二次进入重复 setHeader）。
- `doFilterInternal`：解析 → `MDC.put("traceId", id)` → `response.setHeader("X-Trace-Id", id)`（**先设**，保证任何分支都带）→ `try{chain} finally{ MDC.remove("traceId"); MDC.clear(); }`。
- 线程池：`AgentStreamService.relay()` 跑在 `agentStreamExecutor`，**任务入口** `MDC.put("traceId", run.getTraceId())`、`finally` 中 `MDC.clear()`；心跳任务不写 MDC（若加日志则 set/clear 成对）。`TraceContext.current()` 在 MDC 为空时生成并回填，保证 `Result` 与同线程日志一致。`/internal/**` 虽走独立安全链，容器级 Filter 仍覆盖它 → Python 工具调用的 Java 日志可串上。

**SSE 场景**
- 同一会话**复用同一 traceId**：响应头、`meta.traceId`、`ai_agent_run.trace_id`、两侧日志 4 处同值；断连/超时**不重新生成**。断连收尾仍用该 traceId 写终态（`CANCELLED/client_disconnect` 或 `FAILED/timeout`），`relay` finally 里的 `runService.finish` 日志即"收尾点"。
- 心跳注释行 `: ping` 不带 traceId（避免放大流量）；跨端排查以 `meta` + DB 为准。

**五处一致（可复现验证）**：前置——后端 `java -jar ... > backend.log 2>&1`，AI 服务 `uvicorn ... > ai-service.log 2>&1`（IDE 启动则用其控制台日志替代同名文件）。
```bash
API=http://127.0.0.1:8080/api; JWT=<学生JWT>
TID=$(curl -sS -m 60 -D /tmp/h -o /tmp/sse -X POST $API/ai/agent/stream -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"question":"牛顿第二定律的内容是什么"}' \
  >/dev/null; tr -d '\r' </tmp/h | awk 'tolower($1)=="x-trace-id:"{print $2}')
echo "1 响应头:$TID"; echo "2 meta:$(grep -o '"traceId": *"[0-9a-f]\{16\}"' /tmp/sse | head -1)"
echo "3 DB:$(mysql -uroot -p"$DB_PASSWORD" exam_prep -N -e 'select trace_id from ai_agent_run order by id desc limit 1')"
echo "4 Java日志:$(grep -c "$TID" backend.log) 行"; echo "5 Python日志:$(grep -c "$TID" ai-service.log) 行"
```
通过标准：1/2/3 为同一个 16 位 hex，4、5 各 ≥1 行且包含该值。

## 2. 运行指标契约

`GET /admin/ai/metrics?days=7`，**只读**，AUDITOR | ADMIN（`/admin/**` 已被 `SecurityConfig` 统一约束，无需新增规则）。`days` 默认 7，整数，允许 `1..90`。

```json
{"code":200,"message":"成功","traceId":"0123456789abcdef","data":{
 "days":7,"timezone":"Asia/Shanghai","from":"2026-10-24 21:00:00","to":"2026-10-31 21:00:00","generatedAt":"2026-10-31 21:00:00",
 "total":128,"running":8,"terminal":120,
 "statusCounts":{"RUNNING":8,"SUCCESS":96,"REFUSED":6,"FAILED":16,"CANCELLED":2},
 "successRate":0.8000,"refusedRate":0.0500,"failureRate":0.1333,"cancelRate":0.0167,
 "degradedCount":12,"degradedDenominator":102,"degradedRate":0.1176,
 "latency":{"samples":118,"avgMs":2415,"p95Ms":6120,"maxMs":15320},
 "generators":[{"generator":"llm","count":80,"successRate":0.9000,"avgMs":2300,"p95Ms":6000}],
 "errorTop5":[{"error":"timeout","count":7}]}}
```

| 字段 | 类型 | 含义 / 分母口径（写死） |
|---|---|---|
| `days` / `timezone` / `generatedAt` | int / string / string | `days` 回显请求值；`timezone` 恒为 `Asia/Shanghai`；`generatedAt` 取本次 `SELECT NOW()` 的同一时刻 |
| `from`/`to` | string | `yyyy-MM-dd HH:mm:ss`，与 DB 同时区（Asia/Shanghai），不做时区换算；窗口 = `created_at >= from AND created_at <= to`，`to` 即上一条的 `NOW()`，`from = to - INTERVAL days DAY`，**所有子查询共用这组边界**（可复现的前提） |
| `total` | int | 窗口内全部行，**含 RUNNING** |
| `running` / `terminal` | int | `RUNNING` 行数 / `total - running` |
| `statusCounts` | object<string,int> | **恒定 5 键** RUNNING/SUCCESS/REFUSED/FAILED/CANCELLED，无数据补 0，不省略 |
| `successRate` | number\|null | `SUCCESS / terminal`；**分母不含 RUNNING**；4 位小数 HALF_UP |
| `refusedRate` / `failureRate` / `cancelRate` | number\|null | 分别 `REFUSED / terminal`、`FAILED / terminal`、`CANCELLED / terminal`（拒答不算成功也不算失败，单列） |
| `degradedCount` / `degradedDenominator` / `degradedRate` | int/int/number\|null | 分子分母**同为 `status IN ('SUCCESS','REFUSED')` 子集**（只有产生回答的轮次降级才有意义）；`degraded` 为 NULL 视为非降级（DDL 是 `NOT NULL DEFAULT 0`，实际不会出现） |
| `latency` | object | `samples` = `terminal` 且 `total_ms > 0` 的行数（`total_ms=0` 表示未执行到生成，如 `executor_rejected`，**剔除**并靠 `samples` 可核对）；`avgMs` = `ROUND(AVG(total_ms))`；`p95Ms` 用 **MySQL 8 窗口函数 + 最近秩法**：`ROW_NUMBER() OVER (ORDER BY total_ms)` 取 `rn = CEIL(0.95*n)`（n≥1 必有 1 行，n=0 → null）；`maxMs` = MAX |
| `generators` | array | `generator` 为 NULL 归入 `"unknown"`；按 `count desc, generator asc`；**最多 20 组**；组内 `successRate/avgMs/p95Ms` 分母 = 该组 `terminal` |
| `errorTop5` | array | 域 = `terminal` 且 `TRIM(error) <> ''`；按 `count desc, error asc`，取 5 |

**空数据与非法入参**
- 空窗口：HTTP **200** / `code=200`，`total=0,terminal=0,running=0`，`statusCounts` 5 键全 0，四个率与 `latency` 各值为 `null`、`samples=0`，`generators=[]`，`errorTop5=[]`。**不返回 404/500**；管理端显示空状态而非 0%。分母为 0 → 比率返回 `null`（不是 0，不是 NaN/Infinity）。**样本极小**（如 `terminal<20`）各率照常按公式输出，但接口必须同时给出 `terminal`/`statusCounts`，管理端**禁止只展示百分比**下结论（当前库量级为个位数～十位数，百分比会被单条记录放大）。
- `days` 非法（0、负数、>90）→ HTTP 400 + `code=400` + `message="days 必须在 1..90 之间（当前 N）"`。**刻意不静默 clamp**（与 `page/size` 的 clamp 不同：指标口径必须显式，静默纠正会让报告失真）。`days=abc` 由 `GlobalExceptionHandler:63` 已统一为 400/400，不用新增处理。数据库异常 → 500/500，不返回半成品（不做部分降级）。

## 3. fail-closed 与预算边界契约

**A1 fail-closed（`/v1/agent/run|stream`）**
```json
{"code":5231,"message":"内部接口未授权：X-Internal-Token 缺失或无效","degraded":false}
```
- 触发：`AI_INTERNAL_TOKEN` 为空 **且** `AGENT_ALLOW_ANONYMOUS` 未显式开启；或令牌不匹配（`secrets.compare_digest`，不区分"没带"与"带错"）。HTTP **401**，响应头照常带 `X-Trace-Id`。错误体**保持 Python 现有 3 字段** `{code,message,degraded}`，不加 `traceId` 字段（Java 侧 5 处按此解析，形状不动），traceId 由响应头承载。
- 新增 `ErrorCode.UNAUTHORIZED = 5231`；同时补声明 `AGENT_DISABLED = 5230`（`agent.py:94` 已在用但未声明，**值不变**）。实现改为 `raise AiServiceError(5231, ..., http_status=401, degraded=False)`，**不得再用 `StarletteHTTPException`**（会被现有 handler 覆盖成 5200 参数错误）。
- 放行：仅 `AGENT_ALLOW_ANONYMOUS=1`（`1`/`true` 视为真）；**作用域仅这两条路由**，`/v1/embed|search|ocr|llm|wiki|route|interview` 不受影响。日志：启动时 `logger.error` 一条（含"生产禁止"），运行期进程内 flag 只 WARNING 一次，防刷屏。
- Java 侧遇 401：**不重试**，`log.error("[Agent] Python 拒绝内部调用(401)：检查 AI_INTERNAL_TOKEN 两侧一致")`，仍回退 Java 问答（`degraded=true`）——该配置错误会在 `degradedRate` 与 `errorTop5` 上显形，这是它的可观测信号。

**A8 预算边界**（`AgentRequest`（`schemas.py:512`）只有 `max_steps`；本次**不新增** `maxToolCalls` 请求字段）
| 参数 | 来源 | 允许 | 未传 | 越界（0 / 负数 / 超上限 / 非整数） |
|---|---|---|---|---|
| `maxSteps` | 请求字段 `maxSteps`（可选） | 1..12 | `None` → 沿用 `AGENT_MAX_STEPS`（默认 6） | HTTP 400 + `code=5200`，文案由既有 handler 生成：`参数错误：body maxSteps 必须在 1..12 之间（当前 0）` |
| `maxToolCalls` | **仅配置级**，不开放请求覆盖 | `AGENT_MAX_TOOL_CALLS` 默认 4，上限 8 | — | 配置越界 → 启动时 clamp 到上限 + WARNING（运维操作，不返回 400） |

- **"未传" ≠ "传 0"**：未传 = `None` 用默认 6；`0` = 非法值直接 400。`agent.py:97` 的 `if max_steps_override:` 把 0 当未传**静默回退**，必须改成 `is not None` 再走范围校验；用 `field_validator` 抛 ValueError 复用既有 400/5200 handler。上限常量 `MAX_STEPS_LIMIT=12`。

**A11 + `/v1/health` 安全字段**
- `/internal/agent/tools/question/{id}` 增 `includeAnswer`（query，默认 `false`）；`false` 时必须**移除** answer/解析/正确答案字段，而非置空串。
- `/v1/health` 新增 `"security":{"internalTokenConfigured":true,"agentAuthRequired":true,"anonymousAllowed":false}`：`internalTokenConfigured` = `AI_INTERNAL_TOKEN` 非空；`anonymousAllowed` = `AGENT_ALLOW_ANONYMOUS` 为真；`agentAuthRequired` = `internalTokenConfigured && !anonymousAllowed`。**只暴露布尔**，任何情况不返回令牌值/长度/前缀；不参与 `all_ready` 与 `status` 判定；只增字段不改既有字段，Java 侧 `/ai/capability` 需回归"未知字段不报错"。

## 4. 文件归属表与验收断言

| 单元 | 允许改动（且仅这些） |
|---|---|
| **Java（T02）** | `common/TraceContext.java`(新)、`common/Result.java`、`config/TraceIdFilter.java`(新)、`config/TraceIdConfig.java`(新)、`controller/AiAgentController.java`、`controller/AiOpsController.java`、`controller/AgentInternalController.java`、`service/ai/agent/AgentStreamService.java`、`service/ai/agent/AgentMetricsService.java`(新)、`dto/ai/AgentMetricsVO.java`(新，含嵌套静态类)、`mapper/AiAgentRunMapper.java`(@Select 聚合)、`resources/application.yml`(仅 logging.pattern)、`src/test/java/.../agent/`(新测试) |
| **Python（T03）** | `app/routers/agent.py`、`app/routers/health.py`、`app/schemas.py`、`app/errors.py`、`app/config.py`、`app/main.py`、`app/clients/java_bridge.py`、`app/observability/trace.py`(新)、`ai-service/tests/**` |
| **管理端（T04）** | `admin-web/index.html`、`backend/src/main/resources/static/admin/index.html`（**逐字一致的同步副本**） |
| **禁止** | 三者互不越界；管理端不得新增 `<script src>`/构建链；**不做 DDL 变更**（`ai_agent_run` 已有 `trace_id/degraded/total_ms`），指标接口只读不建表 |

**Java 验收断言**：① `grep -n "UUID.randomUUID" common/Result.java controller/AiAgentController.java` 命中 0；② 任意接口（含不带 token 的 401）响应头 `X-Trace-Id` 匹配 `^[0-9a-f]{16}$`，且 `/ai/agent/runs` 的 `body.traceId` 与头**完全相等**；③ `days` 为 0/-1/91/abc → 400 + `code=400`，不传 → `days=7`；④ 用响应里的 `from/to` 作 SQL 边界复算 `count(*)`、`sum(status='SUCCESS')` 等，与 `total/statusCounts` **完全相等**；⑤ 空窗口 → 200、`total=0`、四率 `null`、`latency.samples=0`、`errorTop5=[]`；⑥ 单测固定样本断言口径（`total_ms=[10,20,30,40]` → `p95Ms=40`；含 1 条 RUNNING 时 `terminal` 不含它）。

**Python 验收断言**：① `unset AI_INTERNAL_TOKEN AGENT_ALLOW_ANONYMOUS` 启动后无令牌请求 `/v1/agent/run` → 401，body 恰 3 字段且 `code=5231`，头含 `X-Trace-Id`；② `AGENT_ALLOW_ANONYMOUS=1` → 非 401，启动日志 1 条含"生产禁止"的 ERROR，第二次请求不新增 ERROR；③ `maxSteps=0/-1/13` → 400 + `code=5200` 且 message 含 `maxSteps` 与 `1..12`；不传 `maxSteps` → harness 实际 `max_steps=6`；传 `maxSteps=3` → 实际 3；④ `/v1/health` 的 `security` 三键齐备且均为 `bool`，响应全文 `grep` 实际令牌值命中 0；⑤ 带 `X-Trace-Id: 0123456789abcdef` 调用 → 响应头同值、Python 日志同值、`JavaBridge` 出站头同值（`httpx.MockTransport` 断言）；⑥ 不带该头 → 输出匹配 `^[0-9a-f]{16}$`。

**管理端验收断言**：① AI 运行观测页新增「运行指标」区 + days 选择（1/7/30，默认 7），代码中请求路径含 `/admin/ai/metrics?days=`；② 展示 total、5 个 status 计数、成功率/拒答率/失败率、`avgMs/p95Ms/samples`、`degradedRate`、generator 表、errorTop5 表；③ 分母为 0 的比率显示 `-`（不是 `0%`），空窗口显示"该窗口无运行记录"；④ 页面写出三行口径文案（成功率分母=终态；P95=nearest-rank；degraded 分母=SUCCESS+REFUSED）；⑤ `<script src` 计数与改造前一致，且 `diff admin-web/index.html backend/src/main/resources/static/admin/index.html` 为空；⑥ 接口 401/403 时不白屏，沿用现有 `api()` 错误提示。

## 5. 未决 / 未验证（实施时需回填结论）

1. `degraded` 在 DDL 为 `NOT NULL DEFAULT 0`，契约按"NULL 视为 0"兜底；若后续改可空需回看本节。`error` 含动态内容（如 `fallback_failed: <原因>`），`errorTop5` 按原文分组会把同类拆成多行——**已知限制**，本次不归一化。
2. Python 日志靠 `logging.Filter` 注入 trace；uvicorn 自带 access log 是否被覆盖**未验证**，故以业务 logger 行为准。CORS：管理端同源托管，无需 expose `X-Trace-Id`；将来跨域需在 `SecurityConfig` 加 `exposedHeaders`（本次不做）。
