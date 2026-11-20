"""判分侧离线评估（layer=GRADING）。

用人工标注的参考分档样题，评测 AI 主观题判分的两项能力：
- 等级一致率：AI 给出的 EXCELLENT / GOOD / PARTIAL / POOR 与人工标注完全一致的比例；
- 得分率 MAE：AI scoreRate 与人工参考得分率的平均绝对误差（0-1 量纲）。

判分调用 Java ``POST /internal/ai/eval/grading``，与线上判分共用同一套 Prompt 与解析，
不是另写一套规则。

用法：
    python -m eval.grading_eval [--limit N] [--write-back]
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
CASES = EVAL_DIR / "grading_cases.jsonl"

MIN_LEVEL_AGREEMENT = 0.70
MAX_SCORE_MAE = 0.25


def load_cases(limit: Optional[int] = None) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with CASES.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows[:limit] if limit else rows


async def evaluate(
    limit: Optional[int] = None,
    progress: Optional[Callable[[int], Awaitable[None]]] = None,
) -> Dict[str, Any]:
    settings = get_settings()
    bridge = JavaBridge(settings.agent)
    cases = load_cases(limit)

    rows: List[Dict[str, Any]] = []
    agree = 0
    available = 0
    degraded = 0
    abs_errors: List[float] = []

    for index, case in enumerate(cases, start=1):
        if progress and (index == 1 or index % 3 == 0):
            await progress(min(99, int(index / max(1, len(cases)) * 100)))
        expected_level = str(case["expectedLevel"])
        expected_rate = float(case.get("referenceScoreRate", 0))
        actual_level = "UNAVAILABLE"
        actual_rate = 0.0
        generator = ""
        error = ""
        try:
            result = await bridge.tool_post_long(
                "/internal/ai/eval/grading",
                {
                    "stem": case["stem"],
                    "reference": case["reference"],
                    "userAnswer": case["userAnswer"],
                },
            )
            actual_level = str(result.get("level") or "UNAVAILABLE")
            actual_rate = float(result.get("scoreRate") or 0)
            generator = str(result.get("generator") or "")
            is_available = bool(result.get("available"))
            if not is_available:
                degraded += 1
                error = str(result.get("reason") or "")[:100]
            else:
                available += 1
        except JavaBridgeError as exc:
            degraded += 1
            error = str(exc)[:100]

        level_ok = actual_level == expected_level
        if level_ok and actual_level != "UNAVAILABLE":
            agree += 1
        if actual_level != "UNAVAILABLE":
            abs_errors.append(abs(actual_rate - expected_rate))

        rows.append(
            {
                "id": case["id"],
                "subject": case.get("subject", ""),
                "expectedLevel": expected_level,
                "actualLevel": actual_level,
                "expectedRate": f"{expected_rate:.2f}",
                "actualRate": f"{actual_rate:.2f}",
                "generator": generator,
                "result": "PASS" if level_ok else "FAIL",
                "error": error,
                "_fail": not level_ok,
            }
        )

    total = len(cases)
    level_agreement = (agree / total) if total else 0.0
    score_mae = (sum(abs_errors) / len(abs_errors)) if abs_errors else 1.0
    degrade_rate = (degraded / total) if total else 0.0
    gate_passed = level_agreement >= MIN_LEVEL_AGREEMENT and score_mae <= MAX_SCORE_MAE

    metrics = {
        "样题数": total,
        "等级一致率": f"{level_agreement * 100:.1f}%",
        "得分率MAE": f"{score_mae:.3f}",
        "可用判分数": available,
        "降级数": degraded,
        "gate_passed": gate_passed,
        "levelAgreement": round(level_agreement, 4),
        "scoreMae": round(score_mae, 4),
        "degradeRate": round(degrade_rate, 4),
        "thresholds": f"等级一致率 >= {MIN_LEVEL_AGREEMENT:.0%}；得分率 MAE <= {MAX_SCORE_MAE}",
    }

    report = write_report(
        "grading_eval",
        "判分侧评估报告（GRADING）",
        metrics,
        (
            ("id", "用例"),
            ("subject", "科目"),
            ("expectedLevel", "人工等级"),
            ("actualLevel", "AI 等级"),
            ("expectedRate", "参考得分率"),
            ("actualRate", "AI 得分率"),
            ("generator", "生成器"),
            ("result", "结果"),
        ),
        rows,
        note=(
            "判分调用 Java /internal/ai/eval/grading，与线上判分同一套 Prompt 与解析。\n"
            f"门槛：等级一致率 >= {MIN_LEVEL_AGREEMENT:.0%} 且 得分率 MAE <= {MAX_SCORE_MAE}；"
            "模型不可用时按降级统计，不计入等级一致率分子。"
        ),
    )

    return {
        "layer": "GRADING",
        "total": total,
        "passed": sum(1 for r in rows if not r.get("_fail")),
        "metrics": metrics,
        "detail": rows,
        "report_path": report,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="判分侧离线评估")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--write-back", action="store_true", help="写回 ai_eval_run")
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.limit))
    print(json.dumps(result["metrics"], ensure_ascii=False, indent=2))
    print("报告：", result["report_path"])
    if args.write_back:
        run_id = asyncio.run(
            write_eval_run(
                "GRADING",
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
