"""按行为优先的配比，从全量 SFT 数据里抽训练集。

第一版 1,600 条里「照抄解析」类（mcq+grounded）占 60%，行为类（refusal+injection）只占 25%，
结果评测上行为指标没有稳定提升。本脚本把配比倒过来：行为类占 65%。

用法：python training/mix_behavior.py --out training/data/sft_train_behavior.jsonl
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"

#: 行为优先的配额（合计约 2,400）
QUOTA = {
    "refusal": 600,     # 无资料必拒答
    "injection": 600,   # 资料内注入不执行
    "grounded": 450,    # 有资料必标 [1]
    "planner_tool": 250,   # planner 的工具调用（保留 JSON 协议 + 何时该检索）
    "planner_final": 250,  # planner 的 final 收尾（F2 修复后由 COURSEWARE 资料合成）
    "mcq": 200,         # 题解能力（SYSTEM_BARE，不带引用）
    "citation": 44,     # 全量
    "wiki": 46,         # 全量
}


def _action(row: dict) -> str:
    try:
        return str(json.loads(row["messages"][2]["content"]).get("action") or "")
    except (KeyError, IndexError, json.JSONDecodeError):
        return ""



def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default=str(DATA / "sft_train.jsonl"))
    parser.add_argument("--out", default=str(DATA / "sft_train_behavior.jsonl"))
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    random.seed(args.seed)
    rows = [json.loads(line) for line in Path(args.src).read_text(encoding="utf-8").splitlines() if line.strip()]
    buckets: dict[str, list[dict]] = {}
    for row in rows:
        kind = row.get("kind", "unknown")
        if kind == "planner":                      # planner 按 tool / final 分桶配额
            kind = "planner_final" if _action(row) == "final" else "planner_tool"
        buckets.setdefault(kind, []).append(row)

    picked: list[dict] = []
    for kind, quota in QUOTA.items():
        pool = buckets.get(kind, [])
        take = min(quota, len(pool))
        picked.extend(random.sample(pool, take))
        print(f"{kind:10s} 取 {take:4d} / 可用 {len(pool)}")

    # 按 user 归一化指纹去重（同题不同 id 会重复采样）
    import re as _re
    def _fp(row):
        return _re.sub(r"[^0-9A-Za-z一-鿿]", "", row["messages"][1]["content"])[:120]
    uniq, seen = [], set()
    for row in picked:
        key = _fp(row)
        if key in seen:
            continue
        seen.add(key)
        uniq.append(row)
    print(f"去重：{len(picked)} -> {len(uniq)}（丢弃 {len(picked)-len(uniq)} 条同题重复）")
    picked = uniq

    random.shuffle(picked)
    out = Path(args.out)
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in picked), encoding="utf-8")
    print(f"\n总 {len(picked)} 条 -> {out}")


if __name__ == "__main__":
    main()
