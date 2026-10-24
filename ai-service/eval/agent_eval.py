"""Agent 侧离线评估：合成任务集 + 规则判定。

判定规则（与数据集字段一一对应）：
* 期望工具序列按序出现（允许额外只读工具）；
* 实际工具 ∩ forbidden_tools 必须为空；
* 所有工具调用成功；
* 最终答案包含所有 required_answer_tokens；
* step_count ≤ max_steps。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional, Sequence

from app.agent.harness import AgentHarness, REFUSAL_TEXT
from app.agent.tools import AgentContext
from app.clients.java_bridge import JavaBridge
from app.config import get_settings

from .report import write_report

EVAL_DIR = Path(__file__).resolve().parent
TASKS = EVAL_DIR / "agent_tasks.jsonl"


def load_tasks(limit: Optional[int] = None) -> List[Dict[str, Any]]:
    tasks: List[Dict[str, Any]] = []
    with TASKS.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if line:
                tasks.append(json.loads(line))
    return tasks[:limit] if limit else tasks


def is_subsequence(expected: Sequence[str], actual: Sequence[str]) -> bool:
    index = 0
    for tool in actual:
        if index < len(expected) and tool == expected[index]:
            index += 1
    return index == len(expected)


def judge(task: Dict[str, Any], result: Dict[str, Any]) -> List[str]:
    reasons: List[str] = []
    events = result.get("events") or []
    actual_tools = [
        str(item["data"].get("tool"))
        for item in events
        if item.get("event") == "tool_call"
    ]
    tool_results = [
        item.get("data") or {}
        for item in events
        if item.get("event") == "tool_result"
    ]

    if not is_subsequence(task.get("expected_tools") or [], actual_tools):
        reasons.append("工具序列不符")
    forbidden = set(actual_tools) & set(task.get("forbidden_tools") or [])
    if forbidden:
        reasons.append("调用了禁止工具:" + ",".join(sorted(forbidden)))
    if any(not item.get("ok") for item in tool_results):
        reasons.append("存在工具调用失败")
    answer = str(result.get("answer") or "")
    missing = [token for token in (task.get("required_answer_tokens") or []) if token not in answer]
    if missing:
        reasons.append("答案缺少:" + ",".join(missing))
    if int(result.get("step_count") or 0) > int(task.get("max_steps") or 0):
        reasons.append("步数超限")
    return reasons


def percentile(values: List[int], p: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * p))))
    return ordered[index]


async def run(
    limit: Optional[int] = None,
    progress: Optional[Callable[[int], Awaitable[None]]] = None,
) -> Dict[str, Any]:
    settings = get_settings()
    bridge = JavaBridge(settings.agent)
    harness = AgentHarness(settings.agent, bridge)
    tasks = load_tasks(limit)

    rows: List[Dict[str, Any]] = []
    passed = 0
    latencies: List[int] = []
    steps: List[int] = []
    tool_total = 0
    tool_ok = 0
    failures: Dict[str, int] = {}

    for index, task in enumerate(tasks, start=1):
        if progress and (index == 1 or index % 10 == 0):
            await progress(min(99, int(index / max(1, len(tasks)) * 100)))
        profile = task.get("profile") or {}
        ctx = AgentContext(
            question=str(task.get("goal") or ""),
            user_id=profile.get("userId"),
            subject_id=profile.get("subjectId"),
            weak_knowledge_ids=[int(x) for x in (profile.get("weakKnowledgeIds") or [])],
            trace_id=task.get("id", ""),
        )
        started = time.perf_counter()
        try:
            result = await harness.collect(ctx)
        except Exception as exc:  # noqa: BLE001
            rows.append(
                {
                    "id": task.get("id"),
                    "goal": task.get("goal"),
                    "tools": "-",
                    "result": "ERROR",
                    "reason": f"{type(exc).__name__}: {exc}"[:100],
                    "steps": 0,
                    "ms": int((time.perf_counter() - started) * 1000),
                    "_fail": True,
                }
            )
            failures["执行异常"] = failures.get("执行异常", 0) + 1
            continue
        elapsed = int((time.perf_counter() - started) * 1000)
        latencies.append(elapsed)
        reasons = judge(task, result)
        if not reasons:
            passed += 1
        for reason in reasons:
            key = reason.split(":")[0]
            failures[key] = failures.get(key, 0) + 1

        events = result.get("events") or []
        actual_tools = [
            str(item["data"].get("tool")) for item in events if item.get("event") == "tool_call"
        ]
        tool_results = [item.get("data") or {} for item in events if item.get("event") == "tool_result"]
        tool_total += len(tool_results)
        tool_ok += sum(1 for item in tool_results if item.get("ok"))
        steps.append(int(result.get("step_count") or 0))

        rows.append(
            {
                "id": task.get("id"),
                "goal": task.get("goal"),
                "tools": " → ".join(actual_tools) or "-",
                "result": "PASS" if not reasons else "FAIL",
                "reason": "；".join(reasons),
                "steps": result.get("step_count"),
                "ms": elapsed,
                "_fail": bool(reasons),
            }
        )

    metrics = {
        "任务数": len(tasks),
        "通过率": f"{(passed / len(tasks) * 100):.1f}%" if tasks else "-",
        "平均步数": f"{(sum(steps) / len(steps)):.2f}" if steps else "-",
        "P95 延迟": f"{percentile(latencies, 0.95)} ms" if latencies else "-",
        "工具成功率": f"{(tool_ok / tool_total * 100):.1f}%" if tool_total else "-",
        "主要失败原因": "；".join(f"{k}×{v}" for k, v in list(failures.items())[:4]) or "无",
    }

    report = write_report(
        "agent_eval",
        "Agent 侧评估报告（合成任务集）",
        metrics,
        (
            ("id", "任务"),
            ("goal", "目标"),
            ("tools", "实际工具链"),
            ("result", "结果"),
            ("reason", "失败原因"),
            ("steps", "步数"),
            ("ms", "耗时 ms"),
        ),
        rows,
        note=(
            "判定为规则判定：工具序列 + 禁止工具 + 工具成功 + 必需内容 + 步数上限。\n"
            "目标：通过率 ≥ 0.8。规则规划器只保证降级可用，不等价于 LLM 规划。\n"
            f"拒答文本：{REFUSAL_TEXT}"
        ),
    )

    return {
        "layer": "AGENT",
        "total": len(tasks),
        "passed": passed,
        "metrics": metrics,
        "detail": rows,
        "report_path": report,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Agent 侧离线评估")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()
    result = asyncio.run(run(args.limit))
    print(json.dumps(result["metrics"], ensure_ascii=False, indent=2))
    print("报告：", result["report_path"])


if __name__ == "__main__":
    main()
