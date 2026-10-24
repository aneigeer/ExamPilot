"""Agent Harness / 工具截断 / 规则规划器测试。"""

from __future__ import annotations

import asyncio
from typing import Any, Dict, List

import pytest

from app.agent.harness import AgentHarness
from app.agent.planner import RulePlanner, parse_decision
from app.agent.tools import AgentContext, ToolRegistry, truncate
from app.clients.java_bridge import JavaBridgeError
from app.config import AgentSettings


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
