"""重跑评测集构建并输出逐阶段日志（只读复用 training 里的构造函数，不改原脚本默认行为）。

目的：解释项目规划里「题库 3,044 → 排除训练后 1,222 题可选 → 丢弃 1,236 条同题变体 → 200 条」
这个看起来矛盾的漏斗——**题** 与 **样本** 是不是同一个计量单位。

用法（仓库根目录）：
    .venv-ai/Scripts/python.exe "项目规划/第1周/scripts/rebuild_eval_with_trace.py" \
        --target holdout --out C:/Users/15241/AppData/Local/Temp/holdout_trace.jsonl \
        --trace-json C:/Users/15241/AppData/Local/Temp/holdout_trace.json

输出与 training/data/eval_holdout.jsonl 逐字节一致（除非数据库或训练集变了）。
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TRAINING = ROOT / "training"
sys.path.insert(0, str(TRAINING))

from build_dataset import (  # noqa: E402
    DEFAULT_YML,
    build_grounded,
    build_mcq,
    build_planner,
    build_refusal,
    connect,
    fetch_all,
    load_dsn,
)
from build_eval import _build_index, _near_dup, _norm, _stem_dup, _stem_of  # noqa: E402

DATA = TRAINING / "data"


def load(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def section(title: str) -> None:
    print(f"\n=== {title} ===", flush=True)


def load_train_refs() -> dict:
    """训练侧的 qid / 全文指纹 / 题干，与 build_eval.py 完全同口径。"""
    seen_qids: set[int] = set()
    seen_fp: set[str] = set()
    seen_texts: list[str] = []
    seen_stems_from_samples: list[str] = []
    per_file: dict[str, int] = {}
    for name in ("sft_train.jsonl", "sft_train_behavior.jsonl"):
        path = DATA / name
        if not path.exists():
            continue
        rows = load(path)
        per_file[name] = len(rows)
        for row in rows:
            qid = row.get("meta", {}).get("qid")
            if qid:
                seen_qids.add(int(qid))
            t = _norm(row["messages"][1]["content"])
            if t:
                seen_texts.append(t)
            st = _stem_of(row["messages"][1]["content"])
            if st:
                seen_stems_from_samples.append(st)
    return {
        "per_file": per_file,
        "qids": seen_qids,
        "fp": seen_fp,
        "texts": seen_texts,
        "stems_from_samples": seen_stems_from_samples,
    }


def fetch_db() -> dict:
    conn = connect(load_dsn(DEFAULT_YML))
    with conn.cursor() as cur:
        all_q = fetch_all(cur, "SELECT id FROM question WHERE is_deleted = 0 ORDER BY id")
        questions = fetch_all(cur, """
            SELECT id, stem, material, analysis, subject_id, type
            FROM question
            WHERE is_deleted = 0 AND analysis IS NOT NULL AND analysis <> '' AND stem <> ''
            ORDER BY id
        """)
        options = fetch_all(
            cur,
            "SELECT question_id, option_key, content, is_answer FROM question_option ORDER BY question_id, sort",
        )
        chunks = fetch_all(cur, "SELECT id, title, content, subject_id FROM ai_chunk WHERE content IS NOT NULL")
    conn.close()
    return {"all_q": all_q, "questions": questions, "options": options, "chunks": chunks}


def run_holdout(args, refs, db) -> tuple[list, dict]:
    trace: dict = {"target": "holdout", "seed": args.seed, "per_kind": args.per_kind}

    questions = db["questions"]
    opt_map: dict[int, list[dict]] = {}
    for o in db["options"]:
        opt_map.setdefault(int(o["question_id"]), []).append(o)

    seen_qids = refs["qids"]
    seen_texts = refs["texts"]
    seen_stems = [_norm(q["stem"]) for q in questions if int(q["id"]) in seen_qids]
    seen_stems.extend(refs["stems_from_samples"])
    seen_stem_index = _build_index(seen_stems)
    seen_index = _build_index(seen_texts)

    fresh = [q for q in questions if int(q["id"]) not in seen_qids]
    random.shuffle(fresh)

    section("阶段 1/5 题源")
    print(f"题库总题数（is_deleted=0）      : {len(db['all_q'])}")
    print(f"有效题数（有 stem+analysis）     : {len(questions)}")
    print(f"无效题数（缺 stem/analysis）     : {len(db['all_q']) - len(questions)}")
    print(f"训练已用 qid                    : {len(seen_qids)}")
    print(f"未参与训练的题目                : {len(fresh)}")
    print(f"训练全文指纹                    : {len(seen_texts)} 条")
    print(f"训练题干（题库+样本）           : {len(seen_stems)} 条")

    n = args.per_kind
    pool = fresh[: n * 40]

    section("阶段 2/5 生成候选样本（同一道题可生成多类多条）")
    mcq_samples = build_mcq(pool, opt_map, n * 8)
    grounded = build_grounded(pool, opt_map, n * 8, with_attack=False)
    injection = build_grounded(pool, opt_map, n * 8, with_attack=True)
    refusal = build_refusal(fresh[: n * 30], db["chunks"], n * 8)
    samples = mcq_samples + grounded + injection + refusal
    cand_kind = Counter(s.kind for s in samples)
    print(f"参与构造的题目池                : {len(pool)} 题（fresh[:{n * 40}]）")
    print(f"  mcq      来自 {len(pool)} 题 → 生成 {len(mcq_samples)} 条")
    print(f"  grounded 来自 {len(pool)} 题 → 生成 {len(grounded)} 条")
    print(f"  injection 同上 → 生成 {len(injection)} 条")
    print(f"  refusal  来自 {len(fresh[: n * 30])} 题 → 生成 {len(refusal)} 条")
    print(f"候选样本合计                    : {len(samples)} 条 {dict(cand_kind)}")

    section("阶段 3/5 三级判重（逐条计数）")
    out: list = []
    seen_keys: set[str] = set()
    per_kind_count: dict[str, int] = {}
    dropped = Counter()
    drop_kind = Counter()
    for s in samples:
        qid = s.meta.get("qid")
        if qid and int(qid) in seen_qids:
            dropped["qid"] += 1
            drop_kind[(s.kind, "qid")] += 1
            continue
        cand = _norm(s.messages[1]["content"])
        stem = _stem_of(s.messages[1]["content"])
        if cand in refs["fp"]:
            dropped["text_exact"] += 1
            drop_kind[(s.kind, "text_exact")] += 1
            continue
        if _near_dup(cand, seen_index, seen_texts):
            dropped["text_near"] += 1
            drop_kind[(s.kind, "text_near")] += 1
            continue
        if _stem_dup(stem, seen_stems, seen_stem_index):
            dropped["stem"] += 1
            drop_kind[(s.kind, "stem")] += 1
            continue
        if per_kind_count.get(s.kind, 0) >= n:
            dropped["cap"] += 1
            drop_kind[(s.kind, "cap")] += 1
            continue
        key = s.kind + "|" + s.messages[1]["content"][:120]
        if key in seen_keys:
            dropped["key_dup"] += 1
            drop_kind[(s.kind, "key_dup")] += 1
            continue
        seen_keys.add(key)
        per_kind_count[s.kind] = per_kind_count.get(s.kind, 0) + 1
        out.append(s)

    print(f"qid 命中训练集                  : {dropped['qid']} 条")
    print(f"全文归一化完全相同              : {dropped['text_exact']} 条")
    print(f"全文近似（6-gram + ratio≥0.9）  : {dropped['text_near']} 条")
    print(f"题干级重复（含互相包含/相似）   : {dropped['stem']} 条")
    print(f"每类配额已满丢弃                : {dropped['cap']} 条")
    print(f"120 字前缀重复                  : {dropped['key_dup']} 条")
    print(f"去重后保留                      : {len(out)} 条")

    section("阶段 4/5 各 kind 漏斗")
    kept_kind = Counter(s.kind for s in out)
    header = f"{'kind':10s}{'候选':>7s}{'qid':>6s}{'原文':>6s}{'近似':>6s}{'题干':>6s}{'配额':>6s}{'前缀':>6s}{'保留':>6s}"
    print(header)
    print("-" * len(header))
    for kind in sorted(cand_kind):
        print(f"{kind:10s}{cand_kind[kind]:7d}"
              f"{drop_kind[(kind, 'qid')]:6d}{drop_kind[(kind, 'text_exact')]:6d}"
              f"{drop_kind[(kind, 'text_near')]:6d}{drop_kind[(kind, 'stem')]:6d}"
              f"{drop_kind[(kind, 'cap')]:6d}{drop_kind[(kind, 'key_dup')]:6d}{kept_kind[kind]:6d}")

    section("阶段 5/5 产出")
    dist = Counter(s.kind for s in out)
    print(f"最终样本                        : {len(out)} 条 {dict(dist)}")

    trace.update({
        "all_questions": len(db["all_q"]),
        "valid_questions": len(questions),
        "train_used_qids": len(seen_qids),
        "fresh_questions": len(fresh),
        "train_texts": len(seen_texts),
        "train_stems": len(seen_stems),
        "pool_questions": len(pool),
        "candidates": dict(cand_kind),
        "candidates_total": len(samples),
        "dropped": dict(dropped),
        "dropped_total": sum(dropped.values()),
        "final": dict(dist),
        "final_total": len(out),
    })
    return out, trace


def run_planner(args, refs, db) -> tuple[list, dict]:
    trace: dict = {"target": "planner", "seed": args.seed, "n": args.n}
    questions = db["questions"]
    seen_qids = refs["qids"]
    train_stems = set(refs["stems_from_samples"])

    fresh = [q for q in questions if int(q["id"]) not in seen_qids]
    random.shuffle(fresh)

    section("阶段 1/4 题源")
    print(f"题库总题数（is_deleted=0）      : {len(db['all_q'])}")
    print(f"有效题数                        : {len(questions)}")
    print(f"训练已用 qid                    : {len(seen_qids)}")
    print(f"未参与训练的题目                : {len(fresh)}")
    print(f"训练题干集合                    : {len(train_stems)} 条")

    section("阶段 2/4 生成候选")
    samples = build_planner(fresh, db["chunks"], args.n * 12)
    print(f"build_planner(fresh, chunks, {args.n}*12) → 候选 {len(samples)} 条")

    section("阶段 3/4 判重")
    out: list = []
    seen_keys: set[str] = set()
    dropped = Counter()
    for s in samples:
        qid = s.meta.get("qid")
        if qid and int(qid) in seen_qids:
            dropped["qid"] += 1
            continue
        key = s.messages[1]["content"][:120]
        if key in seen_keys:
            dropped["key_dup"] += 1
            continue
        if _stem_of(s.messages[1]["content"]) in train_stems:
            dropped["stem"] += 1
            continue
        seen_keys.add(key)
        out.append(s)
        if len(out) >= args.n:
            break
    print(f"qid 命中                        : {dropped['qid']} 条")
    print(f"120 字前缀重复                  : {dropped['key_dup']} 条")
    print(f"题干命中训练集                  : {dropped['stem']} 条")
    print(f"最终                            : {len(out)} 条")

    section("阶段 4/4 产出")
    print(f"planner 留出集                  : {len(out)} 条")

    trace.update({
        "all_questions": len(db["all_q"]),
        "valid_questions": len(questions),
        "train_used_qids": len(seen_qids),
        "fresh_questions": len(fresh),
        "train_stems": len(train_stems),
        "candidates_total": len(samples),
        "dropped": dict(dropped),
        "dropped_total": sum(dropped.values()),
        "final_total": len(out),
    })
    return out, trace


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=("holdout", "planner"), default="holdout")
    ap.add_argument("--per-kind", type=int, default=50, help="holdout 每类目标条数")
    ap.add_argument("--n", type=int, default=50, help="planner 目标条数")
    ap.add_argument("--seed", type=int, default=None, help="默认 holdout=2026 / planner=99")
    ap.add_argument("--out", required=True)
    ap.add_argument("--trace-json", default="")
    ap.add_argument("--compare", action="store_true", help="与冻结集逐字节比对 sha256")
    args = ap.parse_args()
    if args.seed is None:
        args.seed = 2026 if args.target == "holdout" else 99
    random.seed(args.seed)

    section("阶段 0/5 读取训练集基准")
    refs = load_train_refs()
    print(f"训练样本文件                    : {refs['per_file']}")

    db = fetch_db()
    if args.target == "holdout":
        out, trace = run_holdout(args, refs, db)
    else:
        out, trace = run_planner(args, refs, db)

    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(s.as_json(), ensure_ascii=False) for s in out), encoding="utf-8")
    print(f"\n写出 {len(out)} 条 → {path}")

    if args.compare:
        import hashlib

        frozen = DATA / ("eval_holdout.jsonl" if args.target == "holdout" else "eval_planner.jsonl")
        rebuilt_sha = hashlib.sha256(path.read_bytes()).hexdigest()
        frozen_sha = hashlib.sha256(frozen.read_bytes()).hexdigest() if frozen.exists() else ""
        print(f"\n[比对] 重建 {rebuilt_sha}")
        print(f"[比对] 冻结 {frozen_sha}")
        print(f"[比对] {'逐字节一致 ✓' if rebuilt_sha == frozen_sha else '不一致 ✗'}")
        trace["compare"] = {"frozen": str(frozen), "frozen_sha256": frozen_sha,
                            "rebuilt_sha256": rebuilt_sha, "identical": rebuilt_sha == frozen_sha}

    if args.trace_json:
        Path(args.trace_json).write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"阶段数据 → {args.trace_json}")


if __name__ == "__main__":
    main()
