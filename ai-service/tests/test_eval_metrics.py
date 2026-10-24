"""离线评估的指标/判定边界测试（不连 Java、不跑真实检索）。"""

from __future__ import annotations

from eval.agent_eval import is_subsequence, judge
from eval.retrieval_eval import first_hit_rank


def test_first_hit_rank_basic() -> None:
    hits = [{"chunkId": 10}, {"chunkId": 20}, {"chunkId": 30}]
    assert first_hit_rank(hits, [30]) == 3
    assert first_hit_rank(hits, [99]) == 0
    assert first_hit_rank(hits, [10, 30]) == 1
    assert first_hit_rank([], [1]) == 0


def test_is_subsequence_allows_extra_tools() -> None:
    assert is_subsequence(["a", "b"], ["a", "x", "b"])
    assert not is_subsequence(["b", "a"], ["a", "b"])
    assert is_subsequence([], ["a"])
    assert not is_subsequence(["a"], [])


def _result(tools, ok=True, answer="答案 计划", steps=2):
    events = []
    for tool in tools:
        events.append({"event": "tool_call", "data": {"tool": tool}})
        events.append({"event": "tool_result", "data": {"tool": tool, "ok": ok}})
    return {"events": events, "answer": answer, "step_count": steps}


def test_judge_pass() -> None:
    task = {
        "expected_tools": ["get_study_plan"],
        "forbidden_tools": ["search_knowledge"],
        "required_answer_tokens": ["计划"],
        "max_steps": 3,
    }
    assert judge(task, _result(["get_study_plan"])) == []


def test_judge_forbidden_tool() -> None:
    task = {
        "expected_tools": ["get_study_plan"],
        "forbidden_tools": ["search_knowledge"],
        "required_answer_tokens": [],
        "max_steps": 3,
    }
    reasons = judge(task, _result(["get_study_plan", "search_knowledge"]))
    assert any("禁止工具" in reason for reason in reasons)


def test_judge_missing_token_and_steps() -> None:
    task = {
        "expected_tools": ["get_study_plan"],
        "forbidden_tools": [],
        "required_answer_tokens": ["计划"],
        "max_steps": 1,
    }
    reasons = judge(task, _result(["get_study_plan"], answer="没有", steps=3))
    assert any("答案缺少" in reason for reason in reasons)
    assert any("步数超限" in reason for reason in reasons)


def test_judge_tool_failure() -> None:
    task = {
        "expected_tools": ["get_study_plan"],
        "forbidden_tools": [],
        "required_answer_tokens": [],
        "max_steps": 3,
    }
    reasons = judge(task, _result(["get_study_plan"], ok=False))
    assert any("工具调用失败" in reason for reason in reasons)
