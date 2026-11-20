"""错题学习计划离线评估（layer=STUDY_PLAN）。

三项指标：
- JSON 合规率：计划必须具备 diagnosis / weakPoints / dailySchedule / tips 等结构；
- 错题 ID 零幻觉率：计划里出现的 questionIds 必须全部来自该用户真实错题白名单（硬门槛 100%）；
- 降级率：模型不可用或解析失败时降级为规则引擎的比例（可接受但不希望过高）。

调用 Java ``POST /internal/ai/eval/study-plan``，与线上 ``POST /api/me/study-plan/ai``
共用同一个 AiStudyPlanService。

用法：
    python -m eval.study_plan_eval [--limit N] [--write-back]
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional

from app.clients.java_bridge import JavaBridge, JavaBridgeError
from app.config import get_settings

from .report import write_report
from .writeback import write_eval_run

EVAL_DIR = Path(__file__).resolve().parent
CASES = EVAL_DIR / "study_plan_cases.jsonl"

MIN_JSON_COMPLIANCE = 0.95
MAX_DEGRADE_RATE = 0.50


def load_cases(limit: Optional[int] = None) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with CASES.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows[:limit] if limit else rows


def plan_question_ids(plan: Dict[str, Any]) -> List[int]:
    ids: List[int] = []
    for day in plan.get("dailySchedule") or []:
        for task in day.get("tasks") or []:
            for qid in task.get("questionIds") or []:
                if isinstance(qid, int):
                    ids.append(qid)
                elif isinstance(qid, str) and qid.isdigit():
                    ids.append(int(qid))
    return ids


def is_compliant(plan: Any) -> bool:
    if not isinstance(plan, dict):
        return False
    if not isinstance(plan.get("diagnosis"), str):
        return False
    if not isinstance(plan.get("weakPoints"), list):
        return False
    if not isinstance(plan.get("tips"), list):
        return False
    schedule = plan.get("dailySchedule")
    if not isinstance(schedule, list) or not schedule:
        return False
    for day in schedule:
        if not isinstance(day, dict):
            return False
        if not isinstance(day.get("day"), int) or not isinstance(day.get("title"), str):
            return False
        tasks = day.get("tasks")
        if not isinstance(tasks, list):
            return False
        for task in tasks:
            if not isinstance(task, dict):
                return False
            if not isinstance(task.get("type"), str) or not isinstance(task.get("title"), str):
                return False
            if not isinstance(task.get("targetCount"), int):
                return False
            if not isinstance(task.get("questionIds"), list):
                return False
    return True


async def evaluate(
    limit: Optional[int] = None,
    progress: Optional[Callable[[int], Awaitable[None]]] = None,
) -> Dict[str, Any]:
    settings = get_settings()
    bridge = JavaBridge(settings.agent)
    cases = load_cases(limit)

    rows: List[Dict[str, Any]] = []
    compliant_count = 0
    zero_hallucination_count = 0
    degraded_count = 0
    total_ids = 0
    hallucinated_ids = 0

    for index, case in enumerate(cases, start=1):
        if progress:
            await progress(min(99, int(index / max(1, len(cases)) * 100)))
        plan: Any = None
        real_ids: List[int] = []
        generator = ""
        degraded = False
        error = ""
        try:
            data = await bridge.tool_post_long(
                "/internal/ai/eval/study-plan",
                {
                    "userId": case["userId"],
                    "days": case.get("days", 7),
                    "dailyTarget": case.get("dailyTarget", 20),
                },
            )
            plan = data.get("plan")
            real_ids = [int(x) for x in (data.get("realWrongIds") or [])]
            generator = str((plan or {}).get("generator") or "")
            degraded = bool((plan or {}).get("degraded"))
        except JavaBridgeError as exc:
            error = str(exc)[:100]
            degraded = True

        compliant = is_compliant(plan)
        ids = plan_question_ids(plan) if isinstance(plan, dict) else []
        hallucinated = [qid for qid in ids if qid not in set(real_ids)]
        zero_hallucination = len(hallucinated) == 0

        if compliant:
            compliant_count += 1
        if zero_hallucination:
            zero_hallucination_count += 1
        if degraded:
            degraded_count += 1
        total_ids += len(ids)
        hallucinated_ids += len(hallucinated)

        rows.append(
            {
                "id": case["id"],
                "userId": case["userId"],
                "generator": generator,
                "degraded": "是" if degraded else "否",
                "jsonCompliant": "是" if compliant else "否",
                "planQuestionIds": len(ids),
                "hallucinated": len(hallucinated),
                "realWrongIds": len(real_ids),
                "result": "PASS" if (compliant and zero_hallucination) else "FAIL",
                "error": error,
                "_fail": not (compliant and zero_hallucination),
            }
        )

    total = len(cases)
    json_compliance = (compliant_count / total) if total else 0.0
    zero_hallucination_rate = (zero_hallucination_count / total) if total else 0.0
    degrade_rate = (degraded_count / total) if total else 0.0
    gate_passed = (
        json_compliance >= MIN_JSON_COMPLIANCE
        and zero_hallucination_rate >= 1.0
        and degrade_rate <= MAX_DEGRADE_RATE
    )

    metrics = {
        "用例数": total,
        "JSON合规率": f"{json_compliance * 100:.1f}%",
        "错题ID零幻觉率": f"{zero_hallucination_rate * 100:.1f}%",
        "降级率": f"{degrade_rate * 100:.1f}%",
        "计划题号总数": total_ids,
        "幻觉题号数": hallucinated_ids,
        "gate_passed": gate_passed,
        "jsonCompliance": round(json_compliance, 4),
        "idZeroHallucinationRate": round(zero_hallucination_rate, 4),
        "degradeRate": round(degrade_rate, 4),
        "thresholds": (
            f"JSON 合规率 >= {MIN_JSON_COMPLIANCE:.0%}；"
            "错题 ID 零幻觉率 = 100%；"
            f"降级率 <= {MAX_DEGRADE_RATE:.0%}"
        ),
    }

    report = write_report(
        "study_plan_eval",
        "错题学习计划评估报告（STUDY_PLAN）",
        metrics,
        (
            ("id", "用例"),
            ("userId", "用户"),
            ("generator", "生成器"),
            ("degraded", "降级"),
            ("jsonCompliant", "JSON 合规"),
            ("planQuestionIds", "计划题号数"),
            ("hallucinated", "幻觉题号"),
            ("realWrongIds", "真实错题数"),
            ("result", "结果"),
        ),
        rows,
        note=(
            "计划由线上 AiStudyPlanService 生成，真实错题白名单来自 wrong_book（mastered=0）。\n"
            "硬门槛：错题 ID 零幻觉率必须 100%；任一题号不在白名单即 gate_passed=false。"
        ),
    )

    return {
        "layer": "STUDY_PLAN",
        "total": total,
        "passed": sum(1 for r in rows if not r.get("_fail")),
        "metrics": metrics,
        "detail": rows,
        "report_path": report,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="错题学习计划离线评估")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--write-back", action="store_true", help="写回 ai_eval_run")
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.limit))
    print(json.dumps(result["metrics"], ensure_ascii=False, indent=2))
    print("报告：", result["report_path"])
    if args.write_back:
        run_id = asyncio.run(
            write_eval_run(
                "STUDY_PLAN",
                result["total"],
                result["passed"],
                result["metrics"],
                result["detail"],
                result["report_path"],
            )
        )
        print("已写回 ai_eval_run.id =", run_id)


if __name__ == "__main__":
    main()
