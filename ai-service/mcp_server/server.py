"""只读 MCP Server 的协议核心（MCP ``2024-11-05`` / JSON-RPC 2.0 over stdio）。

**为什么单独一个进程与包**：MCP 客户端（Claude Desktop 等）通过 stdin/stdout
与子进程通信；把它塞进正在跑 HTTP 的 FastAPI 进程里，既有"stdout 被 uvicorn
日志污染"的现实风险，也让"只读工具"拿到了主服务的全部能力。独立进程 +
写死的三工具白名单，是这道边界最便宜也最可靠的实现。

**设计约束（全部是安全条款，不是风格偏好）**

1. **只读**：仅 ``search_knowledge`` / ``get_question`` / ``get_wrong_book``
   三个工具，没有任何写操作，也没有"调任意 Java 路径"的通用工具。
2. **答案永不出库**：``get_question`` 固定 ``includeAnswer=false``，
   调用方传什么都不会改变（不信任模型传入的任何开关）。
3. **身份不接受模型指定**：``get_wrong_book`` 的 ``userId`` 只从**服务端配置**
   （``MCP_USER_ID``）读取；模型传 ``userId`` 一律忽略，防止越权读他人错题。
4. **令牌 fail-closed**：``AI_INTERNAL_TOKEN`` 为空时进程直接退出（见 ``__main__``）。

``handle_request`` 是**纯函数**（无非本地状态的读写，除 bridge 外不碰全局），
stdio 循环只负责读写一行 JSON —— 这样协议逻辑可以在测试里毫秒级验证。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional

from app.clients.java_bridge import JavaBridge, JavaBridgeError

logger = logging.getLogger(__name__)

#: MCP 协议版本（客户端握手时回显该值；不认识更新版本的客户端会降级）
PROTOCOL_VERSION = "2024-11-05"

#: 服务标识（出现在 initialize 响应里，便于客户端区分多个 MCP server）
SERVER_INFO: Dict[str, str] = {"name": "exam-prep-mcp", "version": "1.0.0"}

# --------------------------------------------------------------------------------------
# JSON-RPC 2.0 错误码
# --------------------------------------------------------------------------------------

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
#: 工具不可用（Java 侧不通 / 未授权 / 依赖缺失）——MCP 约定用 -32000 段
TOOL_UNAVAILABLE = -32000

#: ``search_knowledge`` 的 topK 上限（与 Agent 工具口径一致）
SEARCH_TOP_K_MAX = 10
#: ``get_wrong_book`` 的 limit 上限（防模型一次拉走整个错题本）
WRONG_BOOK_LIMIT_MAX = 20


class InvalidParams(Exception):
    """工具参数非法（映射 JSON-RPC -32602）。"""


class ToolUnavailable(Exception):
    """工具不可用：Java 不通、未授权、身份未配置等（映射 JSON-RPC -32000）。"""


# --------------------------------------------------------------------------------------
# 工具白名单（写死，不做任何动态注册）
# --------------------------------------------------------------------------------------

TOOLS: List[Dict[str, Any]] = [
    {
        "name": "search_knowledge",
        "description": (
            "在备考知识库中检索与问题最相关的资料片段（只读）。"
            "返回命中片段的标题/正文摘要/来源引用，不含答案。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "检索问句，非空，建议不超过 200 字"},
                "topK": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": SEARCH_TOP_K_MAX,
                    "default": 5,
                    "description": "返回条数上限",
                },
            },
            "required": ["query"],
            "additionalProperties": False,
        },
    },
    {
        "name": "get_question",
        "description": (
            "按 ID 读取一道题目的题干与选项（**永远不含答案与解析**，只读）。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "questionId": {"type": "integer", "minimum": 1, "description": "题目 ID"},
            },
            "required": ["questionId"],
            "additionalProperties": False,
        },
    },
    {
        "name": "get_wrong_book",
        "description": (
            "读取当前用户的错题本（只读）。用户身份由服务端配置决定，调用方无需也不能指定。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": WRONG_BOOK_LIMIT_MAX,
                    "default": 10,
                    "description": "返回条数上限",
                },
            },
            "required": [],
            "additionalProperties": False,
        },
    },
]

#: 工具名 → 规格（用于 O(1) 判定"是否在白名单内"）
_TOOL_INDEX: Dict[str, Dict[str, Any]] = {tool["name"]: tool for tool in TOOLS}


# --------------------------------------------------------------------------------------
# 参数校验小工具（显式、不静默纠正）
# --------------------------------------------------------------------------------------


def _require_non_empty_str(arguments: Dict[str, Any], key: str, max_len: int = 200) -> str:
    """取必填非空字符串参数。

    Raises:
        InvalidParams: 缺失 / 非字符串 / 空白 / 超长。
    """
    value = arguments.get(key)
    if not isinstance(value, str) or not value.strip():
        raise InvalidParams(f"参数 {key} 必须是非空字符串")
    text = value.strip()
    if len(text) > max_len:
        raise InvalidParams(f"参数 {key} 过长（上限 {max_len} 字）")
    return text


def _optional_int(arguments: Dict[str, Any], key: str, default: int,
                  low: int, high: int) -> int:
    """取可选整型参数，**越界报错而不是静默 clamp**。

    为什么与 Agent 内部工具不同：那边模型输出不可控、必须容错；
    这边是外部 MCP 客户端显式调用，"悄悄改小 topK 却不说"比报错更难排查。

    Raises:
        InvalidParams: 非整数 / 越界。
    """
    raw = arguments.get(key, default)
    if isinstance(raw, bool) or not isinstance(raw, (int, str)):
        raise InvalidParams(f"参数 {key} 必须是整数")
    try:
        number = int(raw)
    except (TypeError, ValueError) as exc:
        raise InvalidParams(f"参数 {key} 必须是整数（收到 {raw!r}）") from exc
    if number < low or number > high:
        raise InvalidParams(f"参数 {key} 必须在 {low}..{high} 之间（当前 {number}）")
    return number


# --------------------------------------------------------------------------------------
# JSON-RPC 信封
# --------------------------------------------------------------------------------------


def _error(request_id: Any, code: int, message: str) -> Dict[str, Any]:
    """构造 JSON-RPC 错误响应（``id`` 原样回显，包括 ``null``）。"""
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def _result(request_id: Any, result: Dict[str, Any]) -> Dict[str, Any]:
    """构造 JSON-RPC 成功响应。"""
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def _text_content(data: Any) -> Dict[str, Any]:
    """把工具结果包成 MCP ``content`` 数组（纯文本，JSON 序列化，中文不转义）。"""
    text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    return {"content": [{"type": "text", "text": text}]}


# --------------------------------------------------------------------------------------
# 方法实现
# --------------------------------------------------------------------------------------


async def _call_search_knowledge(arguments: Dict[str, Any], bridge: Any) -> Any:
    """``POST /internal/agent/tools/search``（只检索，不生成）。"""
    query = _require_non_empty_str(arguments, "query")
    top_k = _optional_int(arguments, "topK", 5, 1, SEARCH_TOP_K_MAX)
    data = await bridge.tool_post(
        "/internal/agent/tools/search",
        {
            "question": query,
            "subjectId": None,
            "examCategoryId": None,
            "topK": top_k,
            # 让 Java 侧走拒答闸门：MCP 场景同样不该把"没资料"包装成"有结论"
            "applyGate": True,
        },
    )
    return data if data is not None else {}


async def _call_get_question(arguments: Dict[str, Any], bridge: Any) -> Any:
    """``GET /internal/agent/tools/question/{id}?includeAnswer=false``（答案永不出库）。"""
    question_id = _optional_int(arguments, "questionId", 0, 1, 2_147_483_647)
    data = await bridge.tool_get(
        f"/internal/agent/tools/question/{question_id}",
        # ★ 固定 false：不接受调用方传入，也不读 arguments 里的任何同名键
        {"includeAnswer": False},
    )
    if not data:
        raise ToolUnavailable(f"题目不存在或不可读：questionId={question_id}")
    return data


async def _call_get_wrong_book(arguments: Dict[str, Any], bridge: Any,
                               user_id: Optional[int]) -> Any:
    """``GET /internal/agent/tools/wrong-book``（userId 只认服务端配置）。"""
    if user_id is None:
        raise ToolUnavailable(
            "未配置 MCP_USER_ID：错题本查询需要服务端指定用户，不接受调用方传入"
        )
    limit = _optional_int(arguments, "limit", 10, 1, WRONG_BOOK_LIMIT_MAX)
    data = await bridge.tool_get(
        "/internal/agent/tools/wrong-book",
        {"userId": int(user_id), "limit": limit},
    )
    return data if data is not None else []


async def _dispatch_tool(name: str, arguments: Dict[str, Any], bridge: Any,
                         user_id: Optional[int]) -> Any:
    """按白名单分发工具调用。

    Raises:
        InvalidParams: 参数非法 / 未知工具名（未知工具按 -32601 单独处理，见调用方）。
        ToolUnavailable: 工具不可用。
    """
    if name == "search_knowledge":
        return await _call_search_knowledge(arguments, bridge)
    if name == "get_question":
        return await _call_get_question(arguments, bridge)
    if name == "get_wrong_book":
        return await _call_get_wrong_book(arguments, bridge, user_id)
    raise InvalidParams(f"未知工具：{name}")  # pragma: no cover - 调用方已先查白名单


# --------------------------------------------------------------------------------------
# 协议入口
# --------------------------------------------------------------------------------------


async def handle_request(payload: Any, bridge: Any,
                         user_id: Optional[int] = None) -> Optional[Dict[str, Any]]:
    """处理一条 JSON-RPC 请求，返回响应 dict（通知消息返回 ``None``）。

    Args:
        payload: 已 ``json.loads`` 的请求体；非法结构返回 ``-32600``。
        bridge: Java 内部接口客户端（:class:`JavaBridge` 或测试桩）。
        user_id: 服务端配置的当前用户 ID（``MCP_USER_ID``），**不接受模型传入**。

    Returns:
        JSON-RPC 响应；``payload`` 没有 ``id`` 时视为通知，返回 ``None``。
    """
    if not isinstance(payload, dict):
        return _error(None, INVALID_REQUEST, "请求必须是 JSON 对象")

    request_id = payload.get("id")
    is_notification = "id" not in payload

    if payload.get("jsonrpc") != "2.0":
        if is_notification:
            return None
        return _error(request_id, INVALID_REQUEST, "jsonrpc 必须为 \"2.0\"")

    method = payload.get("method")
    if not isinstance(method, str):
        if is_notification:
            return None
        return _error(request_id, INVALID_REQUEST, "缺少 method")

    params = payload.get("params") or {}
    if params is not None and not isinstance(params, dict):
        return _error(request_id, INVALID_PARAMS, "params 必须是对象")

    # ---- initialize：握手，回协议版本与能力
    if method == "initialize":
        return _result(request_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": dict(SERVER_INFO),
        })

    # ---- tools/list：工具白名单（永远是这 3 个，不含写操作）
    if method == "tools/list":
        return _result(request_id, {"tools": [dict(tool) for tool in TOOLS]})

    # ---- tools/call
    if method == "tools/call":
        name = params.get("name")
        if not isinstance(name, str) or not name:
            return _error(request_id, INVALID_PARAMS, "tools/call 缺少 name")
        if name not in _TOOL_INDEX:
            return _error(request_id, METHOD_NOT_FOUND, f"未知工具：{name}")
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return _error(request_id, INVALID_PARAMS, "arguments 必须是对象")
        try:
            data = await _dispatch_tool(name, arguments, bridge, user_id)
        except InvalidParams as exc:
            return _error(request_id, INVALID_PARAMS, str(exc))
        except JavaBridgeError as exc:
            logger.warning("[MCP] 工具 %s 调用 Java 失败：%s", name, exc)
            return _error(request_id, TOOL_UNAVAILABLE, f"工具不可用：{exc}")
        except ToolUnavailable as exc:
            return _error(request_id, TOOL_UNAVAILABLE, str(exc))
        except Exception as exc:  # noqa: BLE001 - 兜底：绝不把堆栈吐给客户端
            logger.exception("[MCP] 工具 %s 未预期异常", name)
            return _error(request_id, TOOL_UNAVAILABLE, f"工具执行异常：{type(exc).__name__}")
        return _result(request_id, _text_content(data))

    # ---- 通知（客户端发来的 initialized 等）：不该有任何响应
    if is_notification:
        return None

    return _error(request_id, METHOD_NOT_FOUND, f"未知方法：{method}")


def parse_line(line: str) -> Any:
    """把一行文本解析成 JSON；失败返回 :class:`_ParseFailure` 占位对象。

    Args:
        line: 一行输入文本（已去掉换行）。

    Returns:
        解析后的对象，或标记解析失败的 :class:`_ParseFailure`。
    """
    try:
        return json.loads(line)
    except ValueError:
        return _ParseFailure(line[:200])


class _ParseFailure:
    """``json.loads`` 失败的占位对象（避免用 None 表示两种含义）。"""

    def __init__(self, raw: str) -> None:
        self.raw = raw
