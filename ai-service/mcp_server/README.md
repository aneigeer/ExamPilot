# 只读 MCP Server（`mcp_server`）

把「备考知识库」以 **MCP（Model Context Protocol）** 工具的形式开放给外部 Agent 客户端
（Claude Desktop 等）。**只读、三工具、独立进程**，不接入主 FastAPI 服务。

- 协议：MCP `2024-11-05`，JSON-RPC 2.0，**stdio 传输**（每行一个 JSON 报文）
- 进程：`python -m mcp_server`（stdout 只输出 JSON-RPC；日志全部走 stderr）
- 依赖：标准库 + 已有 `httpx`（**不新增依赖**）
- 代码：[`server.py`](./server.py)（协议/工具，纯函数 `handle_request`）、[`runner.py`](./runner.py)（stdio 循环与启动校验）

---

## 1. 启动

```powershell
cd D:\大学生多考试备考知识库与题库系统\ai-service
..\.venv-ai\Scripts\Activate.ps1
python -m mcp_server
```

启动前置条件（缺一不可）：

| 环境变量 | 必填 | 说明 |
|---|---|---|
| `AI_INTERNAL_TOKEN` | ✅ | 与 Java `app.ai.internal-token` 同值；**为空则进程直接退出（退出码 2）并打 ERROR** |
| `JAVA_BASE_URL` | 建议 | 默认 `http://127.0.0.1:8080/api`（含 context-path） |
| `MCP_USER_ID` | 建议 | 错题本工具的用户身份；未配置时 `get_wrong_book` 返回 `-32000` |

> 令牌 fail-closed 是刻意的：只读工具同样能读到"某个学生的错题本"，
> 没有凭证就不该起进程。宁可启动失败（立刻可见），也不要"起了但每次都 401"。

---

## 2. 工具清单（写死白名单，无写操作）

| 工具 | 参数 | 调用的 Java 内部接口 |
|---|---|---|
| `search_knowledge` | `query`（非空，≤200 字）、`topK`（1..10，默认 5） | `POST /internal/agent/tools/search` |
| `get_question` | `questionId`（≥1） | `GET /internal/agent/tools/question/{id}?includeAnswer=false` |
| `get_wrong_book` | `limit`（1..20，默认 10） | `GET /internal/agent/tools/wrong-book?userId=<服务端配置>&limit=` |

所有调用携带 `X-Internal-Token`；当前上下文的 traceId 存在时附 `X-Trace-Id`
（缺失则不带，由 Java `TraceIdFilter` 生成兜底）。

### 安全边界（三条不可协商）

1. **只读**：没有写操作，也没有"调任意内部路径"的通用工具。未知工具名返回 `-32601`。
2. **答案永不出库**：`get_question` 的 `includeAnswer` **固定 false**；
   调用方在 `arguments` 里传 `includeAnswer: true` / `withAnswer: true` 一律被忽略。
3. **身份不接受模型指定**：`get_wrong_book` 的 `userId` 只从 `MCP_USER_ID` 读取；
   模型传 `userId: 999` 会被忽略（防越权读他人错题）。

参数越界**显式报错**（`-32602`）而不是静默 clamp —— 外部客户端能看到自己传错了。

---

## 3. 客户端配置示例（Claude Desktop）

```json
{
  "mcpServers": {
    "exam-prep": {
      "command": "D:\\大学生多考试备考知识库与题库系统\\.venv-ai\\Scripts\\python.exe",
      "args": ["-m", "mcp_server"],
      "cwd": "D:\\大学生多考试备考知识库与题库系统\\ai-service",
      "env": {
        "AI_INTERNAL_TOKEN": "<与 Java app.ai.internal-token 同值>",
        "JAVA_BASE_URL": "http://127.0.0.1:8080/api",
        "MCP_USER_ID": "3"
      }
    }
  }
}
```

> 令牌请从 `ai-service/.env` 复制，**不要**写进任何提交到 Git 的文件。

---

## 4. 一次真实交互长什么样

请求（stdin，一行）：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"claude-desktop","version":"0.7"}}}
```

响应（stdout，一行）：

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"exam-prep-mcp","version":"1.0.0"}}}
```

`tools/list`：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

`tools/call`：

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"int 占几个字节","topK":3}}}
```

成功响应：`{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"contexts\":[...]}"}]}}`

错误码：`-32700` 解析失败 / `-32600` 非法请求 / `-32601` 未知方法或工具 /
`-32602` 参数非法 / `-32000` 工具不可用（Java 不通、未授权、身份未配置）。

---

## 5. 已知限制

1. **stdio 单连接**：一个进程服务一个客户端，不做多路复用（MCP stdio 的常规做法）。
2. **不做流式**：工具结果一次性返回；检索结果由 Java 侧截断，不会拖爆上下文。
3. **`tools/call` 未实现 MCP 的 `isError` 语义**：失败走 JSON-RPC error，
   便于客户端区分"协议坏了"与"工具跑了但没查到"。
4. **未做重连/熔断**：Java 不通时每次都会重试一次（本机场景，代价可接受）；
   跨机部署应在外层加熔断。
