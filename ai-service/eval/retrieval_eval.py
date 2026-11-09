"""检索侧离线评估：Golden Set + Recall@K / MRR + 拒答准确率。

评估对象是 Java 内部检索工具（与生产 Agent 同一条 HybridRetriever 链路）。
``applyGate=false`` 看纯召回；``applyGate=true`` 另统计拒答行为。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from pathlib import Path
from typing import Any, Awaitable, Callable, Dict, List, Optional

from app.clients.java_bridge import JavaBridge, JavaBridgeError
from app.config import AgentSettings, get_settings

from .report import write_report

EVAL_DIR = Path(__file__).resolve().parent
GOLDEN_SET = EVAL_DIR / "golden_set.jsonl"
TOP_K = 10


def load_cases(limit: Optional[int] = None) -> List[Dict[str, Any]]:
    cases: List[Dict[str, Any]] = []
    with GOLDEN_SET.open("r", encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases[:limit] if limit else cases


async def search(bridge: JavaBridge, case: Dict[str, Any], apply_gate: bool) -> Dict[str, Any]:
    payload = {
        "question": case["question"],
        "subjectId": case.get("subject_id"),
        "topK": TOP_K,
        "applyGate": apply_gate,
    }
    return await bridge.tool_post("/internal/agent/tools/search", payload)


def first_hit_rank(hits: List[Dict[str, Any]], expected: List[int]) -> int:
    expected_set = set(expected)
    for index, hit in enumerate(hits, start=1):
        if int(hit.get("chunkId")) in expected_set:
            return index
    return 0


async def run(
    limit: Optional[int] = None,
    progress: Optional[Callable[[int], Awaitable[None]]] = None,
) -> Dict[str, Any]:
    settings = get_settings()
    bridge = JavaBridge(settings.agent)
    cases = load_cases(limit)

    rows: List[Dict[str, Any]] = []
    recall_counts = {1: 0, 3: 0, 5: 0, 10: 0}
    reciprocal = 0.0
    answerable = 0
    refusal_cases = 0
    refusal_hits = 0
    false_rejects = 0
    latencies: List[int] = []

    for index, case in enumerate(cases, start=1):
        if progress and (index == 1 or index % 10 == 0):
            await progress(min(99, int(index / max(1, len(cases)) * 100)))
        started = time.perf_counter()
        try:
            data = await search(bridge, case, apply_gate=False)
            elapsed = int((time.perf_counter() - started) * 1000)
            latencies.append(elapsed)
            hits = data.get("contexts") or []
            if case.get("expect_refusal"):
                refusal_cases += 1
                gated = await search(bridge, case, apply_gate=True)
                refused = bool(gated.get("refused"))
                refusal_hits += 1 if refused else 0
                rows.append(
                    {
                        "id": case["id"],
                        "question": case["question"],
                        "rank": "-",
                        "recall": "拒答" if refused else "未拒答",
                        "mode": gated.get("mode", ""),
                        "_fail": not refused,
                    }
                )
                continue

            answerable += 1
            expected = [int(x) for x in case.get("expected_chunk_ids") or []]
            rank = first_hit_rank(hits, expected)
            if rank:
                reciprocal += 1.0 / rank
            for k in recall_counts:
                if rank and rank <= k:
                    recall_counts[k] += 1
            # 开闸门统计误拒
            gated = await search(bridge, case, apply_gate=True)
            if gated.get("refused"):
                false_rejects += 1
            rows.append(
                {
                    "id": case["id"],
                    "question": case["question"],
                    "rank": rank or "MISS",
                    "recall": "HIT" if rank else "MISS",
                    "mode": data.get("mode", ""),
                    "gate_refused": bool(gated.get("refused")),
                    "_fail": rank == 0,
                }
            )
        except JavaBridgeError as exc:
            rows.append(
                {
                    "id": case["id"],
                    "question": case["question"],
                    "rank": "ERROR",
                    "recall": str(exc)[:80],
                    "mode": "",
                    "_fail": True,
                }
            )

    metrics = {
        "用例数": len(cases),
        "Recall@1": f"{(recall_counts[1] / answerable * 100):.1f}%" if answerable else "-",
        "Recall@3": f"{(recall_counts[3] / answerable * 100):.1f}%" if answerable else "-",
        "Recall@5": f"{(recall_counts[5] / answerable * 100):.1f}%" if answerable else "-",
        "Recall@10": f"{(recall_counts[10] / answerable * 100):.1f}%" if answerable else "-",
        "MRR": f"{(reciprocal / answerable):.3f}" if answerable else "-",
        "拒答准确率": f"{(refusal_hits / refusal_cases * 100):.1f}%" if refusal_cases else "-",
        "误拒率": f"{(false_rejects / answerable * 100):.1f}%" if answerable else "-",
        "平均延迟": f"{sum(latencies) / len(latencies):.0f} ms" if latencies else "-",
    }

    report = write_report(
        "retrieval_eval",
        "检索侧评估报告（Golden Set）",
        metrics,
        (
            ("id", "用例"),
            ("question", "问题"),
            ("rank", "首个命中排名"),
            ("recall", "结果"),
            ("mode", "召回模式"),
        ),
        rows,
        note=(
            "标注流程：先人工读 chunk 正文，再写学生口吻问题，最后填 expected_chunk_ids；"
            "脚本不参与决定标答。\n"
            f"目标：Recall@5 ≥ 0.8，MRR ≥ 0.7。纯召回用 applyGate=false，"
            "拒答/误拒另用 applyGate=true 统计。"
        ),
    )

    return {"layer": "RETRIEVAL", "total": len(cases), "passed": sum(1 for r in rows if not r.get("_fail")),
            "metrics": metrics, "detail": rows, "report_path": report}


def main() -> None:
    parser = argparse.ArgumentParser(description="检索侧离线评估")
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()
    result = asyncio.run(run(args.limit))
    print(json.dumps(result["metrics"], ensure_ascii=False, indent=2))
    print("报告：", result["report_path"])


if __name__ == "__main__":
    main()
