"""只读 MCP Server 测试（MCP ``2024-11-05`` / JSON-RPC 2.0）。

覆盖：``initialize`` 握手、``tools/list`` 三工具、``tools/call`` 正常路径、
未知工具、参数越界、未配令牌启动失败、非法 JSON、通知无响应。

全部离线：``MockBridge`` 记录调用参数并返回固定数据，不连 Java、不起进程。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, Dict, List, Optional

import pytest

from mcp_server import runner
from mcp_server.server import (
    INVALID_PARAMS,
    METHOD_NOT_FOUND,
    PARSE_ERROR,
    PROTOCOL_VERSION,
    SERVER_INFO,
    TOOLS,
    TOOL_UNAVAILABLE,
    handle_request,
    parse_line,
)
from mcp_server.server import _ParseFailure


class MockBridge:
    """Java 内部接口桩：记录每次调用，返回固定数据。"""

    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.calls: List[Dict[str, Any]] = []

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        self.calls.append({"method": "POST", "path": path, "payload": payload})
        if self.fail:
            from app.clients.java_bridge import JavaBridgeError

            raise JavaBridgeError("Java 内部接口返回 503：down")
        return {"contexts": [{"chunkId": 7, "title": "C语言int", "content": "int 占 4 字节"}],
                "bestScore": 0.91, "mode": "FULLTEXT_AND", "refused": False}

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        self.calls.append({"method": "GET", "path": path, "params": dict(params or {})})
        if self.fail:
            from app.clients.java_bridge import JavaBridgeError

            raise JavaBridgeError("Java 内部接口返回 401")
        if "question/" in path:
            return {"questionId": 7, "stem": "int 占几个字节", "options": ["2", "4"]}
        return [{"questionId": 1, "stem": "int 占几个字节", "wrongCount": 2}]


def _call(payload: Dict[str, Any], bridge: MockBridge, user_id: Optional[int] = None) -> Any:
    """同步跑一次 ``handle_request``。"""
    return asyncio.run(handle_request(payload, bridge, user_id))


def _rpc(request_id: Any, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    body: Dict[str, Any] = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        body["params"] = params
    return body


# --------------------------------------------------------------------------------------
# 协议层
# --------------------------------------------------------------------------------------


def test_initialize_handshake() -> None:
    """``initialize`` 必须回协议版本、tools 能力与服务标识。"""
    response = _call(_rpc(1, "initialize", {"protocolVersion": PROTOCOL_VERSION,
                                            "clientInfo": {"name": "t", "version": "0"}}),
                     MockBridge())
    assert response["jsonrpc"] == "2.0"
    assert response["id"] == 1
    result = response["result"]
    assert result["protocolVersion"] == PROTOCOL_VERSION == "2024-11-05"
    assert result["capabilities"] == {"tools": {}}
    assert result["serverInfo"] == SERVER_INFO
    assert result["serverInfo"]["name"] == "exam-prep-mcp"


def test_tools_list_has_exactly_three_readonly_tools() -> None:
    """``tools/list`` 只有三个只读工具，且都带 inputSchema。"""
    response = _call(_rpc("abc", "tools/list"), MockBridge())
    tools = response["result"]["tools"]
    assert [tool["name"] for tool in tools] == [
        "search_knowledge", "get_question", "get_wrong_book",
    ]
    assert len(tools) == 3 == len(TOOLS)
    for tool in tools:
        assert tool["inputSchema"]["type"] == "object"
        assert tool["description"]


def test_response_is_valid_jsonrpc_with_echoed_id() -> None:
    """响应必须是合法 JSON-RPC：``jsonrpc`` + ``id`` 原样回显（字符串 id 也是）。"""
    response = _call(_rpc("req-42", "tools/list"), MockBridge())
    assert set(response.keys()) == {"jsonrpc", "id", "result"}
    assert response["jsonrpc"] == "2.0"
    assert response["id"] == "req-42"
    # 可被标准库无损序列化（stdio 只写一行 JSON）
    assert json.loads(json.dumps(response))["id"] == "req-42"


def test_unknown_method_returns_method_not_found() -> None:
    """未知方法 → -32601（不是 500，也不是静默忽略）。"""
    response = _call(_rpc(2, "resources/list"), MockBridge())
    assert response["error"]["code"] == METHOD_NOT_FOUND
    assert response["id"] == 2


def test_notification_has_no_response() -> None:
    """通知（无 ``id``）不得有响应，否则客户端会把响应错配到别的方法上。"""
    assert _call({"jsonrpc": "2.0", "method": "notifications/initialized"},
                 MockBridge()) is None
    assert _call({"jsonrpc": "2.0", "method": "whatever"}, MockBridge()) is None


def test_bad_jsonrpc_version_is_invalid_request() -> None:
    """``jsonrpc`` 不是 "2.0" → -32600。"""
    response = _call({"jsonrpc": "1.0", "id": 3, "method": "initialize"}, MockBridge())
    assert response["error"]["code"] == -32600


def test_parse_error_placeholder() -> None:
    """解析失败用独立占位对象（避免 None 承担两种语义）。"""
    assert isinstance(parse_line("{not json"), _ParseFailure)
    assert isinstance(parse_line("[1,2]"), list)
    assert PARSE_ERROR == -32700


# --------------------------------------------------------------------------------------
# tools/call
# --------------------------------------------------------------------------------------


def test_search_knowledge_calls_java_search_endpoint() -> None:
    """``search_knowledge`` → ``POST /internal/agent/tools/search``，topK 生效。"""
    bridge = MockBridge()
    response = _call(_rpc(4, "tools/call", {
        "name": "search_knowledge",
        "arguments": {"query": "int 占几个字节", "topK": 3},
    }), bridge)

    assert response["result"]["content"][0]["type"] == "text"
    assert "int" in response["result"]["content"][0]["text"]
    call = bridge.calls[0]
    assert call["path"] == "/internal/agent/tools/search"
    assert call["payload"]["question"] == "int 占几个字节"
    assert call["payload"]["topK"] == 3
    assert call["payload"]["applyGate"] is True


def test_get_question_never_requests_answer() -> None:
    """``get_question`` 固定 ``includeAnswer=false``：调用方塞什么都不改变。"""
    bridge = MockBridge()
    response = _call(_rpc(5, "tools/call", {
        "name": "get_question",
        "arguments": {"questionId": 7, "includeAnswer": True, "withAnswer": True},
    }), bridge)

    assert "stem" in response["result"]["content"][0]["text"]
    call = bridge.calls[0]
    assert call["path"] == "/internal/agent/tools/question/7"
    assert call["params"] == {"includeAnswer": False}, f"答案被请求了：{call['params']}"


def test_get_wrong_book_uses_server_side_user_id() -> None:
    """``get_wrong_book`` 的 userId 只认服务端配置，模型传的 999 被忽略。"""
    bridge = MockBridge()
    response = _call(_rpc(6, "tools/call", {
        "name": "get_wrong_book",
        "arguments": {"limit": 5, "userId": 999},
    }), bridge, user_id=3)

    assert response["result"]["content"][0]["text"]
    call = bridge.calls[0]
    assert call["params"]["userId"] == 3, f"越权：{call['params']}"
    assert call["params"]["limit"] == 5


def test_get_wrong_book_without_server_user_id_is_unavailable() -> None:
    """未配置 ``MCP_USER_ID`` → -32000，而不是"猜一个 userId"。"""
    bridge = MockBridge()
    response = _call(_rpc(7, "tools/call", {
        "name": "get_wrong_book", "arguments": {"userId": 999},
    }), bridge, user_id=None)
    assert response["error"]["code"] == TOOL_UNAVAILABLE
    assert bridge.calls == [], "身份未确定却已发起查询"


@pytest.mark.parametrize(
    ("tool", "arguments"),
    [
        ("search_knowledge", {"query": ""}),                 # 空查询
        ("search_knowledge", {"query": "x", "topK": 11}),    # topK 超上限
        ("search_knowledge", {"query": "x", "topK": 0}),     # topK 低于下限
        ("search_knowledge", {"query": "x", "topK": "abc"}), # 非整数
        ("search_knowledge", {}),                            # 缺 query
        ("get_question", {"questionId": 0}),                 # id 非法
        ("get_question", {}),                                # 缺 questionId
        ("get_wrong_book", {"limit": 21}),                   # limit 超上限
    ],
)
def test_invalid_arguments_return_invalid_params(tool: str, arguments: Dict[str, Any]) -> None:
    """参数越界 → -32602（显式报错，不静默 clamp）。"""
    response = _call(_rpc(8, "tools/call", {"name": tool, "arguments": arguments}),
                     MockBridge(), user_id=3)
    assert response["error"]["code"] == INVALID_PARAMS, response


def test_unknown_tool_returns_method_not_found() -> None:
    """未知工具（含任何写操作 → -32601，白名单外一律不认）。"""
    for name in ("delete_question", "update_wrong_book", "run_sql"):
        response = _call(_rpc(9, "tools/call", {"name": name, "arguments": {}}), MockBridge())
        assert response["error"]["code"] == METHOD_NOT_FOUND, name


def test_java_failure_returns_tool_unavailable() -> None:
    """Java 不通/未授权 → -32000，且不把堆栈或令牌吐给客户端。"""
    response = _call(_rpc(10, "tools/call", {
        "name": "search_knowledge", "arguments": {"query": "x"},
    }), MockBridge(fail=True))
    assert response["error"]["code"] == TOOL_UNAVAILABLE
    assert "Traceback" not in json.dumps(response, ensure_ascii=False)


# --------------------------------------------------------------------------------------
# 启动安全（fail-closed）
# --------------------------------------------------------------------------------------


def test_startup_fails_without_internal_token(monkeypatch: pytest.MonkeyPatch,
                                             caplog: pytest.LogCaptureFixture) -> None:
    """``AI_INTERNAL_TOKEN`` 为空 → 启动即 SystemExit(2) + ERROR 日志。"""
    import logging

    from app.config import reset_settings

    monkeypatch.setenv("AI_INTERNAL_TOKEN", "")
    monkeypatch.setenv("AGENT_ALLOW_ANONYMOUS", "0")
    reset_settings()
    try:
        with caplog.at_level(logging.ERROR):
            with pytest.raises(SystemExit) as excinfo:
                runner.build_bridge()
        assert excinfo.value.code == 2
        assert any("AI_INTERNAL_TOKEN" in r.getMessage() and r.levelno >= logging.ERROR
                   for r in caplog.records), [r.getMessage() for r in caplog.records]
    finally:
        reset_settings()


def test_bridge_built_when_token_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    """配了令牌 → 正常构造桥，且携带该令牌。"""
    from app.clients.java_bridge import INTERNAL_TOKEN_HEADER
    from app.config import reset_settings

    monkeypatch.setenv("AI_INTERNAL_TOKEN", "mcp-test-token")
    reset_settings()
    try:
        bridge = runner.build_bridge()
        assert bridge._headers()[INTERNAL_TOKEN_HEADER] == "mcp-test-token"
    finally:
        reset_settings()


def test_resolve_user_id(monkeypatch: pytest.MonkeyPatch) -> None:
    """``MCP_USER_ID`` 解析：合法整数生效，缺失/非法返回 None（不猜）。"""
    monkeypatch.setenv("MCP_USER_ID", "3")
    assert runner.resolve_user_id() == 3
    monkeypatch.setenv("MCP_USER_ID", "abc")
    assert runner.resolve_user_id() is None
    monkeypatch.delenv("MCP_USER_ID", raising=False)
    assert runner.resolve_user_id() is None


# --------------------------------------------------------------------------------------
# stdio 循环（读写一行 JSON）
# --------------------------------------------------------------------------------------


def test_run_stdio_writes_one_json_line_per_request() -> None:
    """stdio 循环：每条请求输出**恰好一行** JSON；通知不输出。"""
    import io

    stdin = io.StringIO(
        json.dumps(_rpc(1, "initialize")) + "\n"
        + "\n"                                        # 空行忽略
        + "{not json}" + "\n"                         # 解析失败 → -32700
        + json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n"
        + json.dumps(_rpc(2, "tools/list")) + "\n"
    )
    stdout = io.StringIO()

    code = runner.run_stdio(stdin, stdout, MockBridge(), user_id=3)
    assert code == 0

    lines = [line for line in stdout.getvalue().splitlines() if line.strip()]
    assert len(lines) == 3, lines
    parsed = [json.loads(line) for line in lines]
    assert parsed[0]["id"] == 1 and "result" in parsed[0]
    assert parsed[1]["error"]["code"] == PARSE_ERROR and parsed[1]["id"] is None
    assert parsed[2]["id"] == 2 and len(parsed[2]["result"]["tools"]) == 3
