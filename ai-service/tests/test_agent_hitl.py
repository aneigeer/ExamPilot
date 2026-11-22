"""工具风险分级、高风险写操作 HITL 审批挂起与恢复放行测试。"""

from __future__ import annotations

import asyncio

from app.agent import harness as harness_mod
from app.agent import planner, tools
from app.agent.events import EVENT_APPROVAL_REQUIRED, EVENT_DONE, EVENT_FINAL
from app.agent.harness import AgentHarness
from app.agent.planner import Decision
from app.agent.tools import AgentContext, ToolRegistry, ToolResult, requires_approval, risk_of
from app.config import AgentSettings


def test_all_builtin_tools_are_read_only():
    for spec in tools.TOOL_SPECS:
        assert spec.get("risk_level", "READ") == "READ"
        assert spec.get("requires_approval", False) is False


def test_high_risk_tool_is_blocked_before_execution():
    registry = ToolRegistry(bridge=None)  # type: ignore[arg-type]
    tools.TOOL_SPECS.append({
        "name": "danger_write",
        "description": "测试用写操作",
        "risk_level": "WRITE_HIGH",
        "requires_approval": True,
        "arguments": {},
    })
    try:
        assert risk_of("danger_write") == "WRITE_HIGH"
        assert requires_approval("danger_write") is True
        result = asyncio.run(registry.execute("danger_write", {}, AgentContext(question="q")))
        assert result.ok is False
        assert "人工审批" in result.summary
    finally:
        tools.TOOL_SPECS.pop()


def test_harness_suspends_on_high_risk_tool_and_resumes_when_approved():
    spec = {
        "name": "danger_write",
        "description": "测试用高风险写操作",
        "risk_level": "WRITE_HIGH",
        "requires_approval": True,
        "arguments": {"targetId": "int"},
    }
    tools.TOOL_SPECS.append(spec)
    old_names = harness_mod.TOOL_NAMES
    harness_mod.TOOL_NAMES = set(old_names) | {"danger_write"}
    try:
        class _PlannerStub:
            def __init__(self) -> None:
                self.calls = 0

            async def plan(self, ctx, observations, policy):
                self.calls += 1
                if not observations:
                    return Decision(action="tool", thought="准备执行写操作", tool="danger_write",
                                    arguments={"targetId": 99})
                return Decision(action="final", thought="已完成", answer="写操作已完成")

        class _BridgeStub:
            async def full_answer(self, *args, **kwargs):
                return {"answer": kwargs.get("draft") or "写操作已完成", "generator": "stub",
                        "degraded": False, "refused": False, "sources": []}

        h = AgentHarness(AgentSettings(enabled=True, max_steps=4, max_tool_calls=2), _BridgeStub())  # type: ignore[arg-type]
        h.llm_planner = _PlannerStub()  # type: ignore[assignment]

        executed = []

        async def _fake_exec(name, args, ctx):
            approved = set(ctx.approved_tools or ())
            if requires_approval(name) and name not in approved:
                raise AssertionError("未经审批不应调用执行器")
            executed.append((name, args))
            return ToolResult(ok=True, summary="已执行", data={"ok": True})

        h.tools.execute = _fake_exec  # type: ignore[method-assign]

        # 1) 首次未审批：应发出 approval_required + done(waitingApproval=True)，不发 final、不执行工具
        paused = asyncio.run(h.collect(AgentContext(question="帮我清理错题", trace_id="abcd1234abcd1234")))
        assert paused["waiting_approval"] is True
        assert paused["approval"] is not None
        assert paused["approval"]["tool"] == "danger_write"
        assert paused["approval"]["riskLevel"] == "WRITE_HIGH"
        assert paused["approval"]["argKeys"] == ["targetId"]
        assert len(paused["approval"]["approvalToken"]) >= 16
        assert executed == []
        event_names = [e["event"] for e in paused["events"]]
        assert EVENT_APPROVAL_REQUIRED in event_names
        assert EVENT_FINAL not in event_names
        assert event_names[-1] == EVENT_DONE

        # 2) 人工审批后恢复（带 approved_tools=["danger_write"]）：工具放行并产出 final
        resumed = asyncio.run(h.collect(AgentContext(
            question="帮我清理错题",
            trace_id="abcd1234abcd1234",
            approved_tools=["danger_write"],
        )))
        assert resumed["waiting_approval"] is False
        assert len(executed) == 1
        assert executed[0][0] == "danger_write"
        assert resumed["answer"] == "写操作已完成"
    finally:
        harness_mod.TOOL_NAMES = old_names
        tools.TOOL_SPECS.pop()
