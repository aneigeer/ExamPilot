"""Agent Harness / 工具截断 / 规则规划器测试。"""

from __future__ import annotations

import asyncio
from typing import Any, Dict, List

import pytest

from app.agent.context_manager import build_context
from app.agent.harness import AgentHarness
from app.agent.planner import PlannerError, RulePlanner, parse_decision
from app.agent.tools import AgentContext, ToolRegistry, truncate
from app.clients.java_bridge import JavaBridgeError
from app.config import AgentSettings
from app.routers.agent import _context
from app.schemas import AgentRequest, AgentTurn


class FakeBridge:
    """可编程的 Java 桥桩：不启动后端，全部返回固定数据。"""

    def __init__(self, *, llm_ok: bool = False, tool_ok: bool = True) -> None:
        self.llm_ok = llm_ok
        self.tool_ok = tool_ok
        self.tools_called: List[str] = []

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        if not self.llm_ok:
            raise JavaBridgeError("no key")
        if "search_knowledge" in system_prompt and "工具" in system_prompt:
            return '{"thought":"先检索","action":"tool","tool":"search_knowledge","arguments":{"query":"int 字节"}}'
        return '{"thought":"回答","action":"final","answer":"int 占 4 字节 [1]"}'

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        self.tools_called.append(path)
        if not self.tool_ok:
            raise JavaBridgeError("tool down")
        return {
            "contexts": [
                {
                    "chunkId": 1,
                    "title": "C语言int",
                    "content": "x" * 800,
                    "refType": "QUESTION",
                    "refId": 1,
                    "score": 0.9,
                }
            ],
            "bestScore": 0.9,
            "mode": "FULLTEXT_AND",
            "refused": False,
        }

    async def tool_get(self, path: str, params: Dict[str, Any] | None = None) -> Any:
        self.tools_called.append(path)
        if not self.tool_ok:
            raise JavaBridgeError("tool down")
        return [{"knowledgeId": 5, "name": "函数", "mastery": 0.2, "weakness": 0.8}]

    async def full_answer(
        self,
        question: str,
        subject_id: int | None = None,
        draft_answer: str = "",
        contexts: List[Dict[str, Any]] | None = None,
    ) -> Dict[str, Any]:
        return {
            "answer": draft_answer or "int 占 4 个字节 [1]",
            "generator": "extractive",
            "degraded": True,
            "refused": False,
            "sources": [{"chunkId": 1}],
        }


def _run(harness: AgentHarness, ctx: AgentContext) -> List[Dict[str, Any]]:
    async def collect() -> List[Dict[str, Any]]:
        return [item async for item in harness.run(ctx)]

    return asyncio.run(collect())


def test_router_context_carries_session_history_and_summary() -> None:
    """回归：Java 已透传 sessionId/历史/摘要，Python 入口必须原样注入 AgentContext。"""
    request = AgentRequest(
        question="那题为什么选 B",
        userId=3,
        sessionId="s-abc",
        history=[AgentTurn(question="上一问", answer="上一答")],
        memorySummary="更早的滚动摘要",
    )
    ctx = _context(request)
    assert ctx.session_id == "s-abc"
    assert ctx.user_id == 3
    assert ctx.history == [{"question": "上一问", "answer": "上一答"}]
    assert ctx.memory_summary == "更早的滚动摘要"


def test_context_manager_renders_history_and_summary() -> None:
    bundle = build_context(
        question="继续",
        history=[{"question": "q1", "answer": "a1"}],
        memory_summary="早前摘要",
        weak_knowledge_ids=[],
        observations=[],
    )
    assert "【会话记忆】" in bundle.text
    assert "q1" in bundle.text and "a1" in bundle.text
    assert "早前摘要" in bundle.text


def test_meta_reports_history_and_memory() -> None:
    ctx = AgentContext(
        question="继续",
        session_id="s-abc",
        history=[{"question": "q1", "answer": "a1"}],
        memory_summary="摘要",
    )
    events = _run(AgentHarness(AgentSettings(), FakeBridge()), ctx)
    meta = events[0]["data"]
    assert meta["sessionId"] == "s-abc"
    assert meta["historyTurns"] == 1
    assert meta["memoryChars"] == len("摘要")


def test_rule_planner_routes_intents() -> None:
    planner = RulePlanner()
    weak = planner.plan(AgentContext(question="我的薄弱知识点有哪些"), [])
    assert weak.action == "tool" and weak.tool == "get_weak_knowledge"

    plan = planner.plan(AgentContext(question="今天的学习计划"), [])
    assert plan.tool == "get_study_plan"

    search = planner.plan(AgentContext(question="C语言 int 占几个字节"), [])
    assert search.tool == "search_knowledge"


def test_parse_decision_tolerates_code_block() -> None:
    raw = '```json\n{"thought":"t","action":"final","answer":"a"}\n```'
    decision = parse_decision(raw)
    assert decision.action == "final" and decision.answer == "a"


def test_parse_decision_accepts_tool_name_as_action() -> None:
    """回归：本地 7B 常输出 action=get_question（工具名当 action）。"""
    raw = '{"thought":"先看题目","action":"get_question","arguments":{"questionId":3}}'
    decision = parse_decision(raw)
    assert decision.action == "tool"
    assert decision.tool == "get_question"
    assert decision.arguments == {"questionId": 3}


def test_parse_decision_tolerates_raw_control_characters() -> None:
    """回归：7B 会把换行原样写进 JSON 字符串，strict 模式会直接解析失败。"""
    raw = '{"thought":"先查\n资料","action":"tool","tool":"search_knowledge","arguments":{"query":"x"}}'
    decision = parse_decision(raw)
    assert decision.action == "tool" and decision.tool == "search_knowledge"


def test_parse_decision_rejects_unknown_action() -> None:
    with pytest.raises(PlannerError):
        parse_decision('{"thought":"t","action":"do_something"}')


def test_truncate_rule() -> None:
    assert truncate("x" * 400).endswith("…[truncated]")
    assert len(truncate("x" * 400)) == 300 + len("…[truncated]")
    assert truncate("short") == "short"


def test_harness_emits_event_sequence_with_llm_unavailable() -> None:
    bridge = FakeBridge(llm_ok=False)
    harness = AgentHarness(AgentSettings(), bridge)
    events = _run(harness, AgentContext(question="C语言int占几个字节"))
    names = [e["event"] for e in events]
    assert names[0] == "meta"
    assert "tool_call" in names and "tool_result" in names
    assert names[-2:] == ["final", "done"]
    final = events[-2]["data"]
    assert final["step_count"] >= 1
    assert final["tool_calls"] == 1
    # final 契约字段必须齐全
    for key in ("answer", "generator", "degraded", "refused", "step_count", "tool_calls"):
        assert key in final


def test_harness_uses_llm_planner_when_available() -> None:
    bridge = FakeBridge(llm_ok=True)
    harness = AgentHarness(AgentSettings(), bridge)
    events = _run(harness, AgentContext(question="C语言int占几个字节"))
    thoughts = [e["data"]["text"] for e in events if e["event"] == "thought"]
    assert any("先检索" in text for text in thoughts)
    assert any(e["event"] == "tool_call" for e in events)


def test_tool_failure_does_not_break_loop() -> None:
    bridge = FakeBridge(llm_ok=False, tool_ok=False)
    harness = AgentHarness(AgentSettings(), bridge)
    events = _run(harness, AgentContext(question="C语言int占几个字节"))
    tool_results = [e["data"] for e in events if e["event"] == "tool_result"]
    assert tool_results and tool_results[0]["ok"] is False
    assert events[-2]["event"] == "final"


def test_profile_only_weak_tool_returns_synthetic() -> None:
    bridge = FakeBridge()
    registry = ToolRegistry(bridge)  # type: ignore[arg-type]
    ctx = AgentContext(question="薄弱点", user_id=None, weak_knowledge_ids=[5, 8])
    result = asyncio.run(registry.execute("get_weak_knowledge", {}, ctx))
    assert result.ok is True
    assert len(result.data) == 2


class PlanBridge(FakeBridge):
    """计划 / 错题工具返回结构化数据。"""

    async def tool_get(self, path: str, params: Dict[str, Any] | None = None) -> Any:
        if "study-plan" in path:
            return {
                "plannedCount": 20,
                "doneCount": 5,
                "completionPercent": 25,
                "items": [{"knowledgeName": "函数", "targetCount": 10, "reason": "薄弱"}],
            }
        if "wrong-book" in path:
            return [{"questionId": 1, "stem": "int 占几个字节", "wrongCount": 2}]
        return []


def test_structured_plan_answer_uses_tool_data() -> None:
    harness = AgentHarness(AgentSettings(), PlanBridge())
    events = _run(harness, AgentContext(question="今天的学习计划", user_id=3))
    final = events[-2]["data"]
    assert "计划" in final["answer"]
    assert final["generator"] == "agent-rule"


def test_structured_wrong_book_answer_uses_tool_data() -> None:
    harness = AgentHarness(AgentSettings(), PlanBridge())
    events = _run(harness, AgentContext(question="帮我复盘最近的错题", user_id=3))
    final = events[-2]["data"]
    assert "错题" in final["answer"]
    assert "int 占几个字节" in final["answer"]


# --------------------------------------------------------------------------------------
# 端到端证据：记忆链路（HTTP → _context → build_context → planner prompt → meta）
# --------------------------------------------------------------------------------------

def test_agent_run_e2e_keeps_memory_in_prompt_and_meta(
    client: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    """走真实 /v1/agent/run：带 session_id + 2 轮 history + memory_summary。

    断言三件事（缺一即说明记忆在某一段被丢弃）：
    1. 响应 meta 事件里 historyTurns=2、memoryChars=len(memory_summary)；
    2. 真正的**规划 prompt** 里出现【会话记忆】块，且两轮问答与摘要都在里面；
    3. 记忆不改变决策：规划仍能落到 final。
    """
    from app.agent.planner import JavaBridge as _PlannerBridge

    captured: Dict[str, Any] = {}

    async def _fake_llm_complete(self: Any, system: str, prompt: str) -> str:
        captured["system"] = system
        captured["prompt"] = prompt
        return '{"thought":"结合记忆可直接回答","action":"final","answer":"选 B，因为…"}'

    async def _fake_full_answer(self: Any, question: str, subject_id: Any,
                                draft: str, contexts: List[str]) -> Dict[str, Any]:
        captured["draft"] = draft
        return {"answer": draft or "选 B，因为…", "generator": "stub",
                "degraded": False, "refused": False, "sources": []}

    monkeypatch.setattr(_PlannerBridge, "llm_complete", _fake_llm_complete)
    monkeypatch.setattr(_PlannerBridge, "full_answer", _fake_full_answer)

    summary = "用户此前问过进程调度，答错优先级调度"
    response = client.post("/v1/agent/run", json={
        "question": "那题为什么选 B",
        "userId": 3,
        "sessionId": "s-e2e",
        "memorySummary": summary,
        "history": [
            {"question": "第一轮问题", "answer": "第一轮回答"},
            {"question": "第二轮问题", "answer": "第二轮回答"},
        ],
    })
    assert response.status_code == 200, response.text
    body = response.json()

    meta = next(e["data"] for e in body["events"] if e["event"] == "meta")
    assert meta["sessionId"] == "s-e2e"
    assert meta["historyTurns"] == 2
    assert meta["memoryChars"] == len(summary)

    prompt = captured.get("prompt") or ""
    assert "【会话记忆】" in prompt
    for fragment in ("第一轮问题", "第二轮回答", summary):
        assert fragment in prompt, f"记忆片段未进入规划 prompt：{fragment}"

    assert body["answer"].startswith("选 B")
    # 证据留痕：把注入上下文的前 200 字打进测试输出，便于人工核对
    print("\n[planner prompt 前 200 字]", prompt[:200].replace("\n", " / "))


# --------------------------------------------------------------------------------------
# V1 鉴权：/v1/agent/* 内部令牌（X-Internal-Token）
# --------------------------------------------------------------------------------------

def _enable_token(monkeypatch: pytest.MonkeyPatch, token: str) -> None:
    """让依赖读到指定令牌（不改 frozen dataclass，直接换读取函数）。"""
    from app.routers import agent as agent_router

    monkeypatch.setattr(agent_router, "_internal_token", lambda: token)


_AGENT_BODY: Dict[str, Any] = {
    "question": "我的薄弱知识点有哪些",
    "userId": 3,
    "sessionId": "s-auth",
}


def test_agent_requires_token_when_configured(client: Any,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    """配了令牌：不带 X-Internal-Token → 401（run 与 stream 都要拦）。"""
    _enable_token(monkeypatch, "s3cret")
    for path in ("/v1/agent/run", "/v1/agent/stream"):
        resp = client.post(path, json=_AGENT_BODY)
        assert resp.status_code == 401, f"{path} 应 401，实际 {resp.status_code}"
        body = resp.json()
        # 不泄露"是缺了还是错了"，统一 UNAUTHORIZED；且仍走统一错误体
        assert body["message"] == "UNAUTHORIZED"
        assert "code" in body and "degraded" in body


def test_agent_rejects_wrong_token(client: Any,
                                   monkeypatch: pytest.MonkeyPatch) -> None:
    """配了令牌：带错令牌 → 401。"""
    _enable_token(monkeypatch, "s3cret")
    resp = client.post("/v1/agent/run", json=_AGENT_BODY,
                       headers={"X-Internal-Token": "wrong"})
    assert resp.status_code == 401
    assert resp.json()["message"] == "UNAUTHORIZED"


def test_agent_accepts_correct_token(client: Any,
                                     monkeypatch: pytest.MonkeyPatch) -> None:
    """配了令牌且带对 → 正常执行（run 返回事件数组，stream 返回 200 SSE）。"""
    from app.agent.planner import JavaBridge as _PlannerBridge

    _enable_token(monkeypatch, "s3cret")

    async def _fake_llm_complete(self: Any, system: str, prompt: str) -> str:
        return '{"thought":"直接回答","action":"final","answer":"你的薄弱点是进程调度"}'

    async def _fake_full_answer(self: Any, question: str, subject_id: Any,
                                draft: str, contexts: List[str]) -> Dict[str, Any]:
        return {"answer": draft or "你的薄弱点是进程调度", "generator": "stub",
                "degraded": False, "refused": False, "sources": []}

    monkeypatch.setattr(_PlannerBridge, "llm_complete", _fake_llm_complete)
    monkeypatch.setattr(_PlannerBridge, "full_answer", _fake_full_answer)

    headers = {"X-Internal-Token": "s3cret"}
    run_resp = client.post("/v1/agent/run", json=_AGENT_BODY, headers=headers)
    assert run_resp.status_code == 200, run_resp.text
    assert run_resp.json()["answer"]

    stream_resp = client.post("/v1/agent/stream", json=_AGENT_BODY, headers=headers)
    assert stream_resp.status_code == 200
    assert stream_resp.headers["content-type"].startswith("text/event-stream")
    assert "event: meta" in stream_resp.text or "meta" in stream_resp.text


def test_agent_allows_when_token_unconfigured(client: Any,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    """未配置令牌（本地开发/单测常态）：放行，但依赖要能正常跑通。"""
    from app.routers.agent import require_internal_token

    _enable_token(monkeypatch, "")
    require_internal_token(x_internal_token="")  # 不抛即通过
    assert client.post("/v1/agent/run", json=_AGENT_BODY).status_code != 401
