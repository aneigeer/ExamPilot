"""SSE 侧 traceId 回落回归（QA TR-12 的**流式分支**）。

``/v1/agent/run`` 与 ``/v1/agent/stream`` 共用 ``app.routers.agent._context``，
所以"只带 ``X-Trace-Id`` 头、body 无 traceId"的修复对两者同时生效。
但"共用一处构造"是**实现细节**，不是契约保证 —— 一旦有人给流式入口单独拼
``AgentContext``，静默回退到空串的问题会在 SSE 上再来一次。

因此这里用**真路由 + 真 Harness**（只把 Java 桥换成桩）跑一遍流式，直接断
SSE 的 ``meta`` 文本，确保 ``meta.traceId`` == 入站头 == 响应头。
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

import pytest

from app.agent.harness import AgentHarness
from app.config import get_settings
from app.observability.trace import TRACE_ID_HEADER
from app.routers import agent as agent_router

#: 测试用内部令牌（与 Java ``app.ai.internal-token`` 同值的模拟）
_TOKEN = "s3cret"

#: 契约里的样例 traceId（16 位小写 hex）
_HEADER_TRACE = "fedcba9876543210"

_AGENT_BODY: Dict[str, Any] = {"question": "我的薄弱知识点有哪些", "userId": 3}


class _StubBridge:
    """桩桥：只让 Harness 跑完一个 final，不触网。"""

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        return '{"thought":"直接回答","action":"final","answer":"你的薄弱点是函数"}'

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        return []

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        return {}

    async def full_answer(
        self,
        question: str,
        subject_id: Optional[int] = None,
        draft_answer: str = "",
        contexts: Optional[List[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        return {
            "answer": draft_answer or "你的薄弱点是函数",
            "generator": "stub",
            "degraded": False,
            "refused": False,
            "sources": [],
        }


def _meta_of(sse_text: str) -> Dict[str, Any]:
    """从 SSE 文本里取出 ``event: meta`` 的 data（单行 JSON）。"""
    for block in sse_text.split("\n\n"):
        if "event: meta" not in block:
            continue
        for line in block.splitlines():
            if line.startswith("data: "):
                return json.loads(line[len("data: "):])
    raise AssertionError(f"响应中没有 meta 事件：{sse_text[:200]!r}")


def _post_stream(client: Any, monkeypatch: pytest.MonkeyPatch,
                 body: Dict[str, Any], trace_header: str) -> Any:
    """以桩桥跑一次流式请求（真路由 + 真 Harness，仅替换 Java 桥）。"""
    monkeypatch.setattr(agent_router, "_internal_token", lambda: _TOKEN)
    monkeypatch.setattr(
        agent_router,
        "_harness",
        lambda max_steps: AgentHarness(get_settings().agent, _StubBridge()),
    )
    return client.post(
        "/v1/agent/stream",
        json=body,
        headers={"X-Internal-Token": _TOKEN, TRACE_ID_HEADER: trace_header},
    )


def test_stream_meta_trace_id_falls_back_to_header(
    client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    """只带 ``X-Trace-Id`` 头时，流式 ``meta.traceId`` 必须等于头值（不能是空串）。"""
    resp = _post_stream(client, monkeypatch, _AGENT_BODY, _HEADER_TRACE)

    assert resp.status_code == 200, resp.text
    assert resp.headers.get(TRACE_ID_HEADER) == _HEADER_TRACE, "响应头应回写入站 traceId"
    meta = _meta_of(resp.text)
    assert meta["traceId"] == _HEADER_TRACE, (
        f"流式 meta.traceId 应回落到入站头，实际 {meta.get('traceId')!r}"
    )


def test_stream_meta_trace_id_prefers_header_when_body_differs(
    client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    """头与 body 冲突时**以头为准**（一条链路只能有一个 traceId）。

    为什么流式也要单独锁这条：只测"body 为空"的话，实现被改成
    ``request.trace_id or get_trace_id()``（body 优先）时用例仍然会绿 ——
    但那时 ``meta.traceId`` 会偏离响应头，链路在 meta 这一环断开。
    """
    resp = _post_stream(
        client,
        monkeypatch,
        {**_AGENT_BODY, "traceId": "aaaaaaaaaaaaaaaa"},
        "0123456789abcdef",
    )

    assert resp.status_code == 200, resp.text
    assert resp.headers.get(TRACE_ID_HEADER) == "0123456789abcdef"
    assert _meta_of(resp.text)["traceId"] == "0123456789abcdef", (
        "头与 body 冲突时应取头值（契约 §1 第 5 项：以头为准，body 仅兼容）"
    )
