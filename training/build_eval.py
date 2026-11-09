"""构造「与训练集零重叠」的留出评测集。

背景：sft_val.jsonl 与训练集同源，去污染后 grounded 只剩 1 条、injection 为 0，
无法评测引用与抗注入行为。本脚本从题库里挑**训练没出现过的 qid**，用与训练相同的
构造函数生成四类样本，保证评测独立。

用法：python training/build_eval.py --per-kind 50
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path

from build_dataset import (
    DEFAULT_YML,
    build_grounded,
    build_mcq,
    build_refusal,
    connect,
    fetch_all,
    load_dsn,
)

DATA = Path(__file__).resolve().parent / "data"


def _stem_of(user_text: str) -> str:
    """从 user 文本里抽题干：mcq 取【题目】段，其余取【问题】之后。"""
    import re as _re
    if "【题目】" in user_text:
        body = user_text.split("【题目】", 1)[1]
        body = body.split("【选项】", 1)[0]
    elif "【问题】" in user_text:
        body = user_text.split("【问题】", 1)[1]
        body = body.split("\n请", 1)[0]
    else:
        body = user_text
    return _norm(body)


def _stem_dup(stem: str, seen_stems: list[str], index: dict[str, set[int]]) -> bool:
    """题干级判重：完全相同、互相包含、或相似 ≥0.9 且都短于 40 字视为同一题。"""
    from difflib import SequenceMatcher
    if not stem:
        return False
    if stem in seen_stems:
        return True
    for seed in seen_stems:
        if len(stem) >= 12 and (stem in seed or seed in stem):
            return True
    grams = _grams(stem)
    hits: dict[int, int] = {}
    for gram in grams:
        for idx in index.get(gram, ()):
            hits[idx] = hits.get(idx, 0) + 1
    for idx, shared in hits.items():
        if shared < max(3, int(len(grams) * 0.4)):
            continue
        if SequenceMatcher(None, stem, seen_stems[idx]).ratio() >= 0.9:
            return True
    return False


def _grams(text: str, k: int = 6) -> set[str]:
    return {text[i:i + k] for i in range(max(1, len(text) - k + 1))}


def _build_index(texts: list[str], k: int = 6) -> dict[str, set[int]]:
    """6-gram 倒排索引：不依赖前缀，避免「题目四/题目二」这类前缀分叉漏判。"""
    index: dict[str, set[int]] = {}
    for idx, text in enumerate(texts):
        for gram in _grams(text, k):
            index.setdefault(gram, set()).add(idx)
    return index


def _near_dup(candidate: str, index: dict[str, set[int]], texts: list[str], threshold: float = 0.9) -> bool:
    """先按共享 6-gram 数筛候选，再算全文相似度。"""
    from difflib import SequenceMatcher
    grams = _grams(candidate)
    if not grams:
        return False
    hits: dict[int, int] = {}
    for gram in grams:
        for idx in index.get(gram, ()):
            hits[idx] = hits.get(idx, 0) + 1
    need = max(3, int(len(grams) * 0.4))
    for idx, shared in hits.items():
        if shared < need:
            continue
        if SequenceMatcher(None, candidate, texts[idx]).ratio() >= threshold:
            return True
    return False


def _norm(text: str) -> str:
    """归一化文本指纹：去空白与标点，只留中文/字母/数字，避免同题不同排版漏判。"""
    import re as _re
    keep = _re.sub(r"[^0-9A-Za-z一-鿿]", "", text or "")
    return keep


def load(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--per-kind", type=int, default=50)
    parser.add_argument("--out", default="", help="默认 <data-dir>/eval_holdout.jsonl")
    parser.add_argument("--data-dir", default=str(DATA), help="训练数据目录（读取 sft_train/sft_train_behavior 判重）")
    parser.add_argument("--seed", type=int, default=2026)
    args = parser.parse_args()

    random.seed(args.seed)
    seen_qids: set[int] = set()
    seen_fp: set[str] = set()
    seen_texts: list[str] = []
    seen_stems_from_samples: list[str] = []
    data_dir = Path(args.data_dir)
    out_path = Path(args.out) if args.out else data_dir / "eval_holdout.jsonl"
    for name in ("sft_train.jsonl", "sft_train_behavior.jsonl"):
        path = data_dir / name
        if not path.exists():
            continue
        for row in load(path):
            qid = row.get("meta", {}).get("qid")
            if qid:
                seen_qids.add(int(qid))
            _t = _norm(row["messages"][1]["content"])
            if _t:
                seen_texts.append(_t)
            _st = _stem_of(row["messages"][1]["content"])
            if _st:
                seen_stems_from_samples.append(_st)
    print(f"训练已用题目 {len(seen_qids)} 个")

    conn = connect(load_dsn(DEFAULT_YML))
    with conn.cursor() as cur:
        questions = fetch_all(cur, """
            SELECT id, stem, material, analysis, subject_id, type
            FROM question
            WHERE is_deleted = 0 AND analysis IS NOT NULL AND analysis <> '' AND stem <> ''
            ORDER BY id
        """)
        options = fetch_all(cur, "SELECT question_id, option_key, content, is_answer FROM question_option ORDER BY question_id, sort")
        chunks = fetch_all(cur, "SELECT id, title, content, subject_id FROM ai_chunk WHERE content IS NOT NULL")
    conn.close()

    opt_map: dict[int, list[dict]] = {}
    for o in options:
        opt_map.setdefault(int(o["question_id"]), []).append(o)

    seen_stems = [_norm(q["stem"]) for q in questions if int(q["id"]) in seen_qids]
    seen_stems.extend(seen_stems_from_samples)   # planner 样本 user 就是题干，必须一并纳入
    seen_stem_index = _build_index(seen_stems)
    print(f"训练题干：{len(seen_stems)} 条")

    seen_index = _build_index(seen_texts)
    print(f"训练文本索引：{len(seen_texts)} 条、{len(seen_index)} 个 6-gram")

    fresh = [q for q in questions if int(q["id"]) not in seen_qids]
    random.shuffle(fresh)
    print(f"题库可用 {len(questions)}，未参与训练 {len(fresh)}")

    n = args.per_kind
    pool = fresh[: n * 40]
    samples = []
    samples += build_mcq(pool, opt_map, n * 8)
    samples += build_grounded(pool, opt_map, n * 8, with_attack=False)
    samples += build_grounded(pool, opt_map, n * 8, with_attack=True)
    samples += build_refusal(fresh[: n * 30], chunks, n * 8)

    # 去重 + 只保留与训练集 qid 不重叠的样本
    out, seen = [], set()
    per_kind_count: dict[str, int] = {}
    dropped_qid = dropped_text = 0
    for s in samples:
        qid = s.meta.get("qid")
        if qid and int(qid) in seen_qids:
            dropped_qid += 1
            continue
        cand = _norm(s.messages[1]["content"])
        stem = _stem_of(s.messages[1]["content"])
        if (cand in seen_fp or _near_dup(cand, seen_index, seen_texts)
                or _stem_dup(stem, seen_stems, seen_stem_index)):
            dropped_text += 1
            continue
        if per_kind_count.get(s.kind, 0) >= n:
            continue
        key = s.kind + "|" + s.messages[1]["content"][:120]
        if key in seen:
            continue
        seen.add(key)
        per_kind_count[s.kind] = per_kind_count.get(s.kind, 0) + 1
        out.append(s)

    path = out_path
    path.write_text("\n".join(json.dumps(s.as_json(), ensure_ascii=False) for s in out), encoding="utf-8")
    dist = Counter(s.kind for s in out)
    print(f"留出集 {len(out)} 条 -> {path}")
    print("分布:", dict(dist))
    print(f"丢弃：qid 命中 {dropped_qid} 条、文本/题干重复 {dropped_text} 条")


if __name__ == "__main__":
    main()
