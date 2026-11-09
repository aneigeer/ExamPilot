"""单独构造 planner（JSON 协议）留出集，不动已冻结的 eval_holdout。

F2 修复后 planner final 改为「资料已在上下文」的合成样本，因此留出集也要换：
- 训练用过的 chunk（`meta.chunk_id`）一律排除，保证资料零重叠
- final 全部由 `build_planner_final()` 新生成器产出（不再复用 build_planner 的旧配块逻辑）
- 每条补 `meta.chunk_id` 与 `meta.question`，便于按 chunk/题干去重与溯源

用法：python training/build_planner_eval.py --n 50
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path

from build_dataset import (
    DEFAULT_YML,
    build_planner,
    build_planner_final,
    connect,
    fetch_all,
    load_dsn,
)
from build_eval import _stem_of

DATA = Path(__file__).resolve().parent / "data"


def load(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def question_of(user: str) -> str:
    """取 user 里【问题】之后的问句（planner final 样本用它做溯源键）。"""
    if "【问题】" in user:
        return user.split("【问题】", 1)[1].strip()
    return user.strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=50)
    ap.add_argument("--final-ratio", type=float, default=0.32, help="final 样本占比（默认 32%%，50 条=16 条）")
    ap.add_argument("--out", default="", help="默认 <data-dir>/eval_planner.jsonl")
    ap.add_argument("--data-dir", default=str(DATA), help="训练数据目录（读取 sft_train/sft_train_behavior 判重）")
    ap.add_argument("--seed", type=int, default=99)
    args = ap.parse_args()

    random.seed(args.seed)
    data_dir = Path(args.data_dir)
    out_path = Path(args.out) if args.out else data_dir / "eval_planner.jsonl"
    seen_qids: set[int] = set()
    train_chunk_ids: set[int] = set()
    train_stems: set[str] = set()
    for name in ("sft_train.jsonl", "sft_train_behavior.jsonl"):
        p = data_dir / name
        if not p.exists():
            continue
        for row in load(p):
            meta = row.get("meta") or {}
            qid = meta.get("qid")
            if qid:
                seen_qids.add(int(qid))
            cid = meta.get("chunk_id")
            if cid:
                train_chunk_ids.add(int(cid))
            st = _stem_of(row["messages"][1]["content"])
            if st:
                train_stems.add(st)
    print(f"训练已用题目 {len(seen_qids)} 个、chunk {len(train_chunk_ids)} 个")

    conn = connect(load_dsn(DEFAULT_YML))
    with conn.cursor() as cur:
        questions = fetch_all(cur, """
            SELECT id, stem, material, analysis, subject_id, type FROM question
            WHERE is_deleted=0 AND analysis<>'' AND stem<>'' ORDER BY id
        """)
        chunks = fetch_all(cur, """
            SELECT id, title, content, subject_id, source_type FROM ai_chunk
            WHERE content IS NOT NULL AND CHAR_LENGTH(content) BETWEEN 120 AND 1200
        """)
    conn.close()

    fresh_chunks = [c for c in chunks if int(c["id"]) not in train_chunk_ids]
    print(f"语料 {len(chunks)} 个，训练未使用 {len(fresh_chunks)} 个")

    fresh_q = [q for q in questions if int(q["id"]) not in seen_qids]
    random.shuffle(fresh_q)

    n_final = max(1, int(round(args.n * args.final_ratio)))
    n_tool = args.n - n_final

    # tool 样本：chunks 传空，避免再触发 final 生成
    tool = build_planner(fresh_q, [], n_tool * 6)
    final = build_planner_final(fresh_chunks, n_final * 3)

    picked_tool, seen = [], set()
    for s in tool:
        qid = s.meta.get("qid")
        if qid and int(qid) in seen_qids:
            continue
        stem = _stem_of(s.messages[1]["content"])
        if stem and stem in train_stems:
            continue
        key = s.messages[1]["content"][:120]
        if key in seen:
            continue
        seen.add(key)
        s.meta.update({"chunk_id": None, "question": question_of(s.messages[1]["content"])})
        picked_tool.append(s)
        if len(picked_tool) >= n_tool:
            break

    picked_final = []
    for s in final:
        cid = int(s.meta["chunk_id"])
        if cid in train_chunk_ids:
            continue
        s.meta["question"] = question_of(s.messages[1]["content"])
        picked_final.append(s)
        if len(picked_final) >= n_final:
            break

    out = picked_tool + picked_final
    random.shuffle(out)
    out_path.write_text(
        "\n".join(json.dumps(s.as_json(), ensure_ascii=False) for s in out), encoding="utf-8")

    acts = Counter(json.loads(s.messages[2]["content"])["action"] for s in out)
    tmpl = Counter(s.meta.get("template") for s in out if "template" in s.meta)
    print(f"planner 留出集 {len(out)} 条 -> {out_path}")
    print(f"  动作分布 {dict(acts)}；final 模板分布 {dict(tmpl)}")
    print(f"  训练 chunk 重叠：0（已排除 {len(train_chunk_ids)} 个）；final 缺资料 {sum(1 for s in out if '【资料】' not in s.messages[1]['content'] and json.loads(s.messages[2]['content'])['action']=='final')} 条")


if __name__ == "__main__":
    main()
