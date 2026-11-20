"""拒答侧离线评估（layer=REFUSAL）。

固定检验两个硬门槛：
- 域外拒答率 >= 0.90：与备考无关的问题必须拒答；
- 域内误拒率 <= 0.10：备考相关问题不能被闸门误伤。

判据取自 Java ``POST /internal/agent/tools/search`` 的 ``refused`` 字段，
与线上问答共用同一条闸门链路（不是另写一套规则）。

用法：
    python -m eval.refusal_eval [--limit N] [--write-back]
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
CASES = EVAL_DIR / "refusal_cases.jsonl"

REFUSAL_MIN = 0.90
FALSE_REJECT_MAX = 0.10


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
    out_total = out_refused = 0
    in_total = in_refused = 0

    for index, case in enumerate(cases, start=1):
        if progress and (index == 1 or index % 5 == 0):
            await progress(min(99, int(index / max(1, len(cases)) * 100)))
        expect_refusal = bool(case.get("expect_refusal"))
        refused = False
        mode = ""
        error = ""
        try:
            data = await bridge.tool_post(
                "/internal/agent/tools/search",
                {
                    "question": case["question"],
                    "topK": 5,
                    "applyGate": True,
                },
            )
            refused = bool(data.get("refused"))
            mode = str(data.get("mode") or "")
        except JavaBridgeError as exc:
            error = str(exc)[:100]

        if expect_refusal:
            out_total += 1
            out_refused += 1 if refused else 0
            ok = refused
        else:
            in_total += 1
            in_refused += 1 if refused else 0
            ok = not refused

        rows.append(
            {
                "id": case["id"],
                "domain": case.get("domain", ""),
                "question": case["question"],
                "expect": "拒答" if expect_refusal else "作答",
                "actual": "拒答" if refused else "作答",
                "mode": mode,
                "result": "PASS" if ok else "FAIL",
                "error": error,
                "_fail": (not ok) or bool(error),
            }
        )

    refusal_rate = (out_refused / out_total) if out_total else 0.0
    false_reject_rate = (in_refused / in_total) if in_total else 0.0
    gate_passed = refusal_rate >= REFUSAL_MIN and false_reject_rate <= FALSE_REJECT_MAX

    metrics = {
        "用例数": len(cases),
        "域外拒答率": f"{refusal_rate * 100:.1f}%",
        "域内误拒率": f"{false_reject_rate * 100:.1f}%",
        "域外拒答数": f"{out_refused}/{out_total}",
        "域内误拒数": f"{in_refused}/{in_total}",
        "gate_passed": gate_passed,
        "refusalRate": round(refusal_rate, 4),
        "falseRejectRate": round(false_reject_rate, 4),
        "thresholds": f"域外拒答率 >= {REFUSAL_MIN:.0%}；域内误拒率 <= {FALSE_REJECT_MAX:.0%}",
    }

    report = write_report(
        "refusal_eval",
        "拒答侧评估报告（REFUSAL）",
        metrics,
        (
            ("id", "用例"),
            ("domain", "域"),
            ("question", "问题"),
            ("expect", "期望"),
            ("actual", "实际"),
            ("mode", "召回模式"),
            ("result", "结果"),
        ),
        rows,
        note=(
            "判据来自 /internal/agent/tools/search 的 refused 字段，与线上问答同一条闸门链路。\n"
            f"硬门槛：域外拒答率 >= {REFUSAL_MIN:.0%} 且 域内误拒率 <= {FALSE_REJECT_MAX:.0%}；"
            "任一不满足 gate_passed=false。"
        ),
    )

    return {
        "layer": "REFUSAL",
        "total": len(cases),
        "passed": sum(1 for r in rows if not r.get("_fail")),
        "metrics": metrics,
        "detail": rows,
        "report_path": report,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="拒答侧离线评估")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--write-back", action="store_true", help="写回 ai_eval_run")
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.limit))
    print(json.dumps(result["metrics"], ensure_ascii=False, indent=2))
    print("报告：", result["report_path"])
    if args.write_back:
        run_id = asyncio.run(
            write_eval_run(
                "REFUSAL",
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
