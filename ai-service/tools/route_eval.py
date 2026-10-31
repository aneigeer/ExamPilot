"""路由准确率与延迟评测（A1 契约的验收口径）。

## 为什么单独一个脚本

``tests/test_route.py`` 用桩跑契约形状，**跑不出准确率**——准确率取决于真模型
（bge-small-zh-v1.5）与原型句的相似度分布。所以评测必须在一个**带模型**的进程里
跑真实判定器，并把 csv 里的期望 skill 与判定结果对齐。

## 指标口径

* **准确率**：``decide()`` 返回的 ``skill`` 与 csv 的 ``expected_skill`` 完全相等；
  csv 里允许出现 ``REFUSE``（域外拒答）——这是路由器自身的判定，不做
  ``/v1/route`` 的折叠映射（折叠是**传输层**为了迁就 Java 白名单，评测要看真值）。
* **延迟**：单次 ``decide()`` 的墙钟耗时，报 P50 / P95 / max。
  P95 ≤ 50ms 是 A1 的硬指标（Java 侧路由读超时 300ms，15 倍余量）。
* **混淆对**：只打印错判明细，便于定位"COURSE_QA ↔ COURSEWARE_QA"这类近义混淆。

## 用法

    python ai-service/tools/route_eval.py                  # 默认 eval/router_cases.csv
    python ai-service/tools/route_eval.py --csv x.csv
    python ai-service/tools/route_eval.py --verbose        # 打印每条判定
"""

from __future__ import annotations

import argparse
import csv
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.router.router import get_router  # noqa: E402

DEFAULT_CSV = ROOT / "eval" / "router_cases.csv"


def load_cases(path: Path) -> List[Tuple[str, str, str]]:
    """读用例：``[(question, expected_skill, note)]``（容忍 BOM）。"""
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        return [(str(row["question"]).strip(),
                 str(row["expected_skill"]).strip().upper(),
                 (row.get("note") or "").strip())
                for row in reader if str(row.get("question") or "").strip()]


def percentile(values: List[float], ratio: float) -> float:
    """简单百分位（最近秩法，够用且不引依赖）。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(ratio * (len(ordered) - 1)))))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser(description="路由准确率 / 延迟评测")
    parser.add_argument("--csv", default=str(DEFAULT_CSV), help="用例 csv 路径")
    parser.add_argument("--verbose", action="store_true", help="逐条打印判定")
    args = parser.parse_args()

    cases = load_cases(Path(args.csv))
    if not cases:
        print(f"用例为空：{args.csv}")
        return 1

    intent_router = get_router()
    intent_router.decide("预热一句，触发原型句向量与模型加载")  # 排除首调用开销

    correct = 0
    latencies: List[float] = []
    confusion: Dict[str, int] = {}
    mistakes: List[str] = []

    for question, expected, note in cases:
        started = time.perf_counter()
        decision = intent_router.decide(question)
        latencies.append((time.perf_counter() - started) * 1000)
        actual = decision.skill.value
        ok = actual == expected
        correct += 1 if ok else 0
        if not ok:
            key = f"{expected} → {actual}"
            confusion[key] = confusion.get(key, 0) + 1
            mistakes.append(f"    ✗ [{expected} → {actual}] {question[:32]}（{note}）")
        if args.verbose:
            print(f"  {'✓' if ok else '✗'} {actual:20s} sim={decision.sim:.3f} "
                  f"margin={decision.margin:.3f} {question[:30]}")

    total = len(cases)
    accuracy = correct / total
    print(f"\n用例：{total} 条（{Path(args.csv).name}）")
    print(f"准确率：{correct}/{total} = {accuracy:.1%}")
    print(f"延迟：P50={percentile(latencies, 0.50):.1f}ms  "
          f"P95={percentile(latencies, 0.95):.1f}ms  max={max(latencies):.1f}ms")
    if confusion:
        print("错判分布：")
        for pair, count in sorted(confusion.items(), key=lambda item: -item[1]):
            print(f"    {pair}  × {count}")
        print("错判明细：")
        print("\n".join(mistakes))
    else:
        print("无错判。")
    return 0 if accuracy >= 0.8 else 2


if __name__ == "__main__":
    sys.exit(main())
