"""Subagent 受控实验（``AGENT_SUBAGENT_ENABLED``）测试。

覆盖四条红线（缺一条这个实验就不该上线）：

1. **拆解解析**：正常 / 缺字段 / 非 JSON / 空数组 / 超上限 / 重复 —— 都按契约收敛或抛
   :class:`SubagentError`，绝不"猜一个子问题出来"；
2. **预算共享**：3 个子问题合计工具调用 **不超过全局 4 次**（不是每个各 4 次）；
3. **失败回退**：拆解失败时最终仍产出 ``final``（HTTP 200 语义）且带 ``degraded`` /
   ``degradedReason``，单 Agent 的工具链照常执行；
4. **关闭态等价**：``AGENT_SUBAGENT_ENABLED=false`` 时事件序列与基线逐条一致，
   不出现任何 ``[subagent]`` 文本，且**不发生**拆解调用。
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Dict, List, Optional

import pytest

from app.agent.events import EVENT_DONE, EVENT_FINAL, EVENT_META, EVENT_THOUGHT, EVENT_TOOL_CALL
from app.agent.harness import AgentHarness
from app.agent.subagent import (
    StepBudget,
    SubagentError,
    ToolBudget,
    parse_sub_questions,
)
from app.agent.tools import AgentContext
from app.clients.java_bridge import JavaBridgeError
from app.config import (
    SUBAGENT_MAX_QUESTIONS_LIMIT,
    AgentSettings,
    get_settings,
    log_security_posture,
    reset_settings,
)

# --------------------------------------------------------------------------------------
# 桩：可编程 Java 桥
# --------------------------------------------------------------------------------------

_TOOL_DECISION = json.dumps(
    {"thought": "先检索", "action": "tool", "tool": "search_knowledge",
     "arguments": {"query": "C语言 int", "topK": 5}},
    ensure_ascii=False,
)

_FINAL_DECISION = json.dumps(
    {"thought": "信息足够", "action": "final", "answer": "int 通常占 4 字节 [1]"},
    ensure_ascii=False,
)


def _tool_decisions(count: int, *, thought: str = "先检索") -> List[str]:
    """生成 ``count`` 条**互不相同**的工具决策（模拟真实的多次工具调用）。

    harness 对"相同 tool + 相同 arguments"做去重（重复不扣预算），
    若脚本复用同一参数，第 2 步起会被跳过，就测不出"预算是否被真实消耗"。
    """
    return [
        json.dumps(
            {"thought": thought, "action": "tool", "tool": "search_knowledge",
             "arguments": {"query": f"C语言 int 第{index}次", "topK": 5}},
            ensure_ascii=False,
        )
        for index in range(1, count + 1)
    ]


class ScriptedBridge:
    """可控桩桥：拆解 / 规划 / 工具 / 定稿四个入口都可编程。

    Attributes:
        sub_questions: 拆解返回的子问题（``None`` 表示不做拆解响应）。
        decompose_error: 拆解时抛出的异常（模拟 Java LLM 代理不可用）。
        plan_script: 规划调用按序返回的原始 JSON 文本；用尽后返回 final。
        tool_ok: 工具调用是否成功。
        llm_calls: 记录每次 LLM 调用的类型（``decompose`` / ``plan``）。
    """

    def __init__(
        self,
        *,
        sub_questions: Optional[List[str]] = None,
        decompose_error: Optional[Exception] = None,
        plan_script: Optional[List[str]] = None,
        tool_ok: bool = True,
    ) -> None:
        self.sub_questions = sub_questions
        self.decompose_error = decompose_error
        self.plan_script = list(plan_script or [])
        self.tool_ok = tool_ok
        self.llm_calls: List[str] = []

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        if "任务拆解器" in system_prompt:
            self.llm_calls.append("decompose")
            if self.decompose_error is not None:
                raise self.decompose_error
            return json.dumps(
                {"sub_questions": self.sub_questions or []}, ensure_ascii=False
            )
        self.llm_calls.append("plan")
        if self.plan_script:
            return self.plan_script.pop(0)
        return _FINAL_DECISION

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        if not self.tool_ok:
            raise JavaBridgeError("tool down")
        return {
            "contexts": [
                {"chunkId": 1, "title": "C语言int", "content": "int 占 4 字节",
                 "refType": "QUESTION", "refId": 1, "score": 0.9}
            ],
            "bestScore": 0.9,
            "mode": "FULLTEXT_AND",
        }

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if not self.tool_ok:
            raise JavaBridgeError("tool down")
        return [{"knowledgeId": 5, "name": "函数", "mastery": 0.2, "weakness": 0.8}]

    async def full_answer(
        self,
        question: str,
        subject_id: Optional[int] = None,
        draft_answer: str = "",
        contexts: Optional[List[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        return {
            "answer": draft_answer or "int 通常占 4 字节 [1]",
            "generator": "stub",
            "degraded": False,
            "refused": False,
            "sources": [{"chunkId": 1}],
        }


def _run(harness: AgentHarness, ctx: AgentContext) -> List[Dict[str, Any]]:
    async def collect() -> List[Dict[str, Any]]:
        return [item async for item in harness.run(ctx)]

    return asyncio.run(collect())


def _names(events: List[Dict[str, Any]]) -> List[str]:
    return [str(item["event"]) for item in events]


def _thoughts(events: List[Dict[str, Any]]) -> List[str]:
    return [
        str(item["data"].get("text") or "")
        for item in events
        if item["event"] == EVENT_THOUGHT
    ]


def _subagent_settings(**overrides: Any) -> AgentSettings:
    base: Dict[str, Any] = {"subagent_enabled": True, "subagent_max_questions": 3}
    base.update(overrides)
    return AgentSettings(**base)


# --------------------------------------------------------------------------------------
# 1. 拆解 JSON 解析
# --------------------------------------------------------------------------------------

def test_parse_sub_questions_normal() -> None:
    raw = '{"sub_questions":["我有哪些错题","今天该复习什么"]}'
    assert parse_sub_questions(raw, 3) == ["我有哪些错题", "今天该复习什么"]


def test_parse_sub_questions_tolerates_code_block_and_alias_key() -> None:
    """容忍代码块包裹与同义 key（本地 7B 常见偏差），但语义必须明确。"""
    raw = '```json\n{"subQuestions": ["问题A"]}\n```'
    assert parse_sub_questions(raw, 3) == ["问题A"]


def test_parse_sub_questions_missing_field_raises() -> None:
    """缺 ``sub_questions`` 字段 → 抛错（交给上层回退），不猜。"""
    with pytest.raises(SubagentError) as exc:
        parse_sub_questions('{"thought":"我拆好了"}', 3)
    assert "sub_questions" in str(exc.value)


def test_parse_sub_questions_not_json_raises() -> None:
    with pytest.raises(SubagentError) as exc:
        parse_sub_questions("我觉得你可以先看错题本", 3)
    assert "JSON" in str(exc.value)


def test_parse_sub_questions_empty_output_raises() -> None:
    with pytest.raises(SubagentError):
        parse_sub_questions("   ", 3)


def test_parse_sub_questions_empty_list_raises() -> None:
    """空数组 = 拆解失败（不是"0 个子问题也能跑"）。"""
    with pytest.raises(SubagentError) as exc:
        parse_sub_questions('{"sub_questions":[]}', 3)
    assert "为空" in str(exc.value)


def test_parse_sub_questions_wrong_type_raises() -> None:
    with pytest.raises(SubagentError):
        parse_sub_questions('{"sub_questions":"一个字符串"}', 3)


def test_parse_sub_questions_clamped_and_deduped() -> None:
    raw = json.dumps(
        {"sub_questions": ["A", "A", "  ", "B", "C", "D"]}, ensure_ascii=False
    )
    # 上限 3：去重去空后取前 3 条
    assert parse_sub_questions(raw, 3) == ["A", "B", "C"]
    assert parse_sub_questions(raw, 1) == ["A"]


def test_parse_sub_questions_accepts_object_items() -> None:
    raw = '{"sub_questions":[{"question":"子问题一"},{"question":"子问题二"}]}'
    assert parse_sub_questions(raw, 3) == ["子问题一", "子问题二"]


# --------------------------------------------------------------------------------------
# 2. 预算共享（全局 4 次，不是每个子问题各 4 次）
# --------------------------------------------------------------------------------------

def test_tool_budget_is_shared_across_sub_questions() -> None:
    """3 个子问题 + 全局预算 4 → 总工具调用恰为 4（不是每个子问题各 4 次）。"""
    bridge = ScriptedBridge(
        sub_questions=["子问题一", "子问题二", "子问题三"],
        plan_script=_tool_decisions(20),  # 永远想调工具（每次参数不同，真实扣预算）
    )
    harness = AgentHarness(_subagent_settings(max_tool_calls=4, max_steps=6), bridge)
    events = _run(harness, AgentContext(question="帮我全面复习", user_id=3))

    tool_calls = [e for e in events if e["event"] == EVENT_TOOL_CALL]
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)

    assert len(tool_calls) == 4, f"工具调用应全局共享 4 次，实际 {len(tool_calls)}"
    assert final["tool_calls"] == 4
    # 预算耗尽时必须显式告知（要么跳过剩余子问题，要么单步内提示已达上限）
    assert any(
        "跳过剩余" in text or "已达上限" in text for text in _thoughts(events)
    ), _thoughts(events)


def test_step_budget_is_shared_across_sub_questions() -> None:
    """3 个子问题合计步数不超过会话 ``max_steps``（不是每个各 6 步 = 18 步）。"""
    bridge = ScriptedBridge(
        sub_questions=["子问题一", "子问题二", "子问题三"],
        plan_script=[_TOOL_DECISION] * 30,
    )
    harness = AgentHarness(_subagent_settings(max_tool_calls=4, max_steps=6), bridge)
    events = _run(harness, AgentContext(question="帮我全面复习", user_id=3))
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["step_count"] <= 6, f"步数应全局共享 6 步，实际 {final['step_count']}"


def test_step_budget_allocation_is_fair() -> None:
    """3 个子问题分 6 步：每个都能分到 2 步，而不是先来先占。"""
    budget = StepBudget(limit=6)
    assert budget.allocate(3) == 2
    budget.record(2)
    assert budget.allocate(2) == 2
    budget.record(2)
    assert budget.allocate(1) == 2
    assert StepBudget(limit=0).allocate(3) >= 1, "allocate 至少给 1 步，否则子问题无法启动"


def test_tool_budget_unit_semantics() -> None:
    budget = ToolBudget(limit=2)
    assert budget.remaining == 2 and not budget.exhausted
    assert budget.try_consume() is True
    assert budget.try_consume() is True
    assert budget.try_consume() is False
    assert budget.exhausted and budget.remaining == 0


def test_sub_question_count_is_clamped_to_hard_limit() -> None:
    """即使配置被改成 99，最多也只拆 3 个子问题（硬上限）。"""
    bridge = ScriptedBridge(
        sub_questions=["s1", "s2", "s3", "s4", "s5"],
        plan_script=[_TOOL_DECISION] * 20,
    )
    harness = AgentHarness(
        _subagent_settings(max_tool_calls=2, subagent_max_questions=99), bridge
    )
    events = _run(harness, AgentContext(question="帮我全面复习", user_id=3))
    routing = [t for t in _thoughts(events) if "子问题 " in t and "/" in t]
    assert len(routing) <= SUBAGENT_MAX_QUESTIONS_LIMIT, routing


def test_subagent_success_marks_meta_and_thoughts() -> None:
    """成功路径：meta.subagent=true，子步骤 thought 带 ``[subagent]`` 前缀。"""
    bridge = ScriptedBridge(sub_questions=["子问题一", "子问题二"], plan_script=[])
    harness = AgentHarness(_subagent_settings(), bridge)
    events = _run(harness, AgentContext(question="帮我全面复习", user_id=3))

    meta = events[0]["data"]
    assert meta["subagent"] is True
    assert meta["subagentMaxQuestions"] == 3
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["subagent"] is True
    assert final["subQuestions"] == ["子问题一", "子问题二"]
    assert any(t.startswith("[subagent]") for t in _thoughts(events))
    # 事件类型集合不新增（沿用 thought/tool_*，Java 侧零改动）
    assert set(_names(events)) <= {
        EVENT_META, "thought", "tool_call", "tool_result", "budget_truncated",
        EVENT_FINAL, EVENT_DONE,
    }


# --------------------------------------------------------------------------------------
# 3. 失败回退（最终仍产出 final + degraded 标记，绝不抛异常）
# --------------------------------------------------------------------------------------

def test_decompose_failure_degrades_to_single_agent() -> None:
    bridge = ScriptedBridge(
        decompose_error=JavaBridgeError("no key"),
        plan_script=[_TOOL_DECISION],  # 回退后的单 Agent 会正常调工具
    )
    harness = AgentHarness(_subagent_settings(), bridge)
    events = _run(harness, AgentContext(question="我的薄弱知识点有哪些", user_id=3))

    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["degraded"] is True, "回退路径必须显式标记 degraded"
    assert "拆解调用失败" in str(final.get("degradedReason") or "")
    assert final["subagent"] is False
    # 回退后单 Agent 照常执行工具（不是空答案）
    assert any(e["event"] == EVENT_TOOL_CALL for e in events)
    assert events[-1]["event"] == EVENT_DONE
    assert any("回退单 Agent" in t for t in _thoughts(events))


def test_empty_sub_questions_degrades() -> None:
    bridge = ScriptedBridge(sub_questions=[])
    harness = AgentHarness(_subagent_settings(), bridge)
    events = _run(harness, AgentContext(question="我的错题有哪些", user_id=3))
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["degraded"] is True
    assert "拆解结果为空" in str(final.get("degradedReason") or "")


def test_unexpected_exception_degrades_instead_of_raising() -> None:
    """任何意外（不是 SubagentError）也必须回退，不能把异常漏到 SSE 出口。"""

    class BoomBridge(ScriptedBridge):
        async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
            if "任务拆解器" in system_prompt:
                raise RuntimeError("unexpected")
            return await super().llm_complete(system_prompt, user_prompt)

    harness = AgentHarness(_subagent_settings(), BoomBridge(plan_script=[_TOOL_DECISION]))
    events = _run(harness, AgentContext(question="我的错题有哪些", user_id=3))
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["degraded"] is True
    assert "RuntimeError" in str(final.get("degradedReason") or "")


def test_degrade_restores_full_tool_budget() -> None:
    """回退路径重新给满预算：实验失败不该让用户少拿工具调用。"""
    bridge = ScriptedBridge(
        decompose_error=JavaBridgeError("no key"),
        plan_script=_tool_decisions(20),
    )
    harness = AgentHarness(_subagent_settings(max_tool_calls=4, max_steps=6), bridge)
    events = _run(harness, AgentContext(question="帮我全面复习", user_id=3))
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert final["tool_calls"] == 4, final
    assert len([e for e in events if e["event"] == EVENT_TOOL_CALL]) == 4


# --------------------------------------------------------------------------------------
# 4. 关闭态：事件序列与基线一致，且不发生拆解调用
# --------------------------------------------------------------------------------------

_BASELINE_SEQUENCE = [
    "meta", "thought", "tool_call", "tool_result", "thought", "final", "done",
]

#: 规则规划器路径的基线序列（多一条"已切换规则规划器"的 thought）
_RULE_PLANNER_SEQUENCE = [
    "meta", "thought", "thought", "tool_call", "tool_result", "thought", "final", "done",
]


def test_disabled_mode_matches_baseline_sequence() -> None:
    """默认关闭：事件序列逐条等于基线，且没有一次拆解调用。"""
    bridge = ScriptedBridge(plan_script=[_TOOL_DECISION])
    harness = AgentHarness(AgentSettings(), bridge)  # 默认 subagent_enabled=False
    events = _run(harness, AgentContext(question="C语言 int 占几个字节"))

    assert _names(events) == _BASELINE_SEQUENCE, _names(events)
    assert events[0]["data"]["subagent"] is False
    assert not any("subagent" in t for t in _thoughts(events)), _thoughts(events)
    assert "decompose" not in bridge.llm_calls, bridge.llm_calls


def test_disabled_mode_rule_planner_sequence_unchanged() -> None:
    """LLM 不可用（规则规划器）时，关闭态的序列也必须与阶段三基线一致。"""
    bridge = ScriptedBridge()

    async def _no_plan(system_prompt: str, user_prompt: str) -> str:
        raise JavaBridgeError("no plan")

    bridge.llm_complete = _no_plan  # type: ignore[assignment]
    harness = AgentHarness(AgentSettings(), bridge)
    events = _run(harness, AgentContext(question="我的薄弱知识点有哪些", user_id=3))

    assert _names(events) == _RULE_PLANNER_SEQUENCE, _names(events)
    final = next(e["data"] for e in events if e["event"] == EVENT_FINAL)
    assert "degradedReason" not in final, "关闭态不得新增任何字段/事件"
    assert "subagent" not in final


# --------------------------------------------------------------------------------------
# 5. 配置：默认关、硬上限 3、启动日志覆盖
# --------------------------------------------------------------------------------------

def test_config_defaults_disabled(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AGENT_SUBAGENT_ENABLED", raising=False)
    monkeypatch.delenv("AGENT_SUBAGENT_MAX_QUESTIONS", raising=False)
    reset_settings()
    try:
        agent = get_settings().agent
        assert agent.subagent_enabled is False
        assert agent.subagent_max_questions == 3
    finally:
        reset_settings()


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("true", True), ("1", True), ("false", False), ("0", False), ("", False)],
)
def test_config_enabled_flag(monkeypatch: pytest.MonkeyPatch, raw: str,
                             expected: bool) -> None:
    monkeypatch.setenv("AGENT_SUBAGENT_ENABLED", raw)
    reset_settings()
    try:
        assert get_settings().agent.subagent_enabled is expected
    finally:
        reset_settings()


@pytest.mark.parametrize(("raw", "expected"), [("9", 3), ("99", 3), ("0", 1), ("-2", 1), ("2", 2)])
def test_config_questions_clamped(monkeypatch: pytest.MonkeyPatch, raw: str,
                                  expected: int) -> None:
    monkeypatch.setenv("AGENT_SUBAGENT_MAX_QUESTIONS", raw)
    reset_settings()
    try:
        assert get_settings().agent.subagent_max_questions == expected
    finally:
        reset_settings()


def test_startup_log_reports_subagent_posture(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """启动日志必须能看出"到底开没开"（开=WARNING，关=INFO）。"""
    monkeypatch.setenv("AGENT_SUBAGENT_ENABLED", "true")
    reset_settings()
    try:
        with caplog.at_level(logging.INFO):
            settings = get_settings()
            log_security_posture(settings)
        joined = " ".join(record.getMessage() for record in caplog.records)
        assert "Subagent" in joined, joined
        assert "AGENT_SUBAGENT_ENABLED=true" in joined, joined
        assert settings.agent.subagent_enabled is True
    finally:
        monkeypatch.delenv("AGENT_SUBAGENT_ENABLED", raising=False)
        reset_settings()


# --------------------------------------------------------------------------------------
# 6. HTTP 层证据：实验路径失败时 /v1/agent/run 仍 200 且带降级标记
# --------------------------------------------------------------------------------------

def test_http_run_degrades_returns_200(client: Any,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    """端到端（真路由 + 真 schema）：开实验但 LLM 代理不可用 → 200 + degradedReason。"""
    from app import config as config_module
    from app.agent.planner import JavaBridge as PlannerBridge
    from app.routers import agent as agent_router

    monkeypatch.setenv("AGENT_SUBAGENT_ENABLED", "true")
    config_module.reset_settings()
    monkeypatch.setattr(agent_router, "_internal_token", lambda: "s3cret")

    async def _llm_down(self: Any, system: str, prompt: str) -> str:
        raise JavaBridgeError("proxy down")

    async def _final(self: Any, question: str, subject_id: Any = None,
                     draft_answer: str = "", contexts: Any = None) -> Dict[str, Any]:
        return {"answer": "你的薄弱点是函数", "generator": "stub",
                "degraded": False, "refused": False, "sources": []}

    monkeypatch.setattr(PlannerBridge, "llm_complete", _llm_down)
    monkeypatch.setattr(PlannerBridge, "full_answer", _final)

    resp = client.post(
        "/v1/agent/run",
        json={"question": "我的薄弱知识点有哪些", "userId": 3},
        headers={"X-Internal-Token": "s3cret"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    meta = next(e["data"] for e in body["events"] if e["event"] == "meta")
    final = next(e["data"] for e in body["events"] if e["event"] == "final")
    assert meta["subagent"] is True, "meta 必须标记本次开了实验"
    assert final["degraded"] is True
    assert "拆解调用失败" in final["degradedReason"]
    assert final["answer"], "降级后仍必须有答案（不能因为实验失败返回空）"
