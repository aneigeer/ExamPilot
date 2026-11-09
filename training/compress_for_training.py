"""按 token 预算压缩训练样本：只删 user 里【资料】段的尾部句子，绝不动 assistant。

背景（`项目规划/第2周/02_长度分布与截断分析.md`）：训练按 max_length 右截断，
512 档 936 条被截、其中 504 条连 assistant 整段都没进模型。长度主要来自 user 的【资料】，
所以正确做法是「按句边界压资料」，而不是「截尾把答案切掉」。

规则：
1. 渲染口径与训练一致：apply_chat_template(tokenize=False, add_generation_prompt=False) → tokenizer(text, add_special_tokens=False)
2. 超预算时只在 user 的【资料】块内按句（。！？）从尾部删句，逐句重算 token，直到 ≤ 预算
3. 没有【资料】块、或删到只剩 1 条且仍超预算 → 丢弃并记录（qid / chunk_id / 原因）
4. assistant 一个字符都不改，因此「答案被截」「引用被截」恒为 0

用法：
    python training/compress_for_training.py --max-tokens 640 \
        --in training/data/sft_train_behavior.jsonl \
        --out training/data/sft_train_behavior.640.jsonl \
        --report 项目规划/第2周/evidence/compress_behavior_640.json
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
from pathlib import Path
from typing import Any, Dict, List, Tuple

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TOKENIZER = r"C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"

SENT_RE = re.compile(r"[^。！？]*[。！？]|[^。！？]+$")


def load_rows(path: Path) -> List[Dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def render_len(tok, messages: List[Dict[str, str]]) -> int:
    text = tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
    return len(tok(text, add_special_tokens=False)["input_ids"])


def split_material(user: str) -> Tuple[str, List[str], str] | None:
    """把 user 拆成 (前导, 资料行列表, 【问题】之后的部分)；没有【资料】块返回 None。"""
    if "【资料】" not in user or "【问题】" not in user:
        return None
    head, rest = user.split("【资料】", 1)
    material, tail = rest.split("【问题】", 1)
    lines = [ln for ln in material.strip("\n").split("\n") if ln.strip()]
    return head, lines, tail


def drop_tail_sentence(line: str) -> str | None:
    """删掉该行最后一个完整句；删不出内容返回 None（表示这一行该整行丢掉）。"""
    m = re.match(r"^(\[\d+\]\s*[^：:]{0,40}[：:])(.*)$", line)
    prefix, body = (m.group(1), m.group(2)) if m else ("", line)
    sents = [s for s in SENT_RE.findall(body) if s.strip()]
    if len(sents) <= 1:
        return None
    return prefix + "".join(sents[:-1])


def rebuild(head: str, lines: List[str], tail: str) -> str:
    return f"{head}【资料】\n" + "\n".join(lines) + f"\n\n【问题】{tail}"


def compress_row(tok, row: Dict[str, Any], max_tokens: int) -> Tuple[Dict[str, Any] | None, int, int, str]:
    """返回 (新行或 None(丢弃), 压缩前 token, 压缩后 token, 状态)。"""
    messages = row["messages"]
    before = render_len(tok, messages)
    if before <= max_tokens:
        return row, before, before, "ok"

    parts = split_material(messages[1]["content"])
    if parts is None:
        return None, before, before, "no_material"          # 无法压缩 → 丢弃
    head, lines, tail = parts

    while True:
        cur = rebuild(head, lines, tail)
        cand = [dict(messages[0]), {"role": "user", "content": cur}, dict(messages[2])]
        if len(messages) > 3:                       # 理论上没有，防御性处理
            cand += [dict(m) for m in messages[3:]]
        after = render_len(tok, cand)
        if after <= max_tokens:
            new_row = dict(row)
            new_row["messages"] = cand
            meta = dict(row.get("meta") or {})
            meta["compressed"] = {"before_tokens": before, "after_tokens": after}
            new_row["meta"] = meta
            return new_row, before, after, "compressed"

        # 从最后一行尾部删一句；该行删空则整行移除
        if not lines:
            return None, before, before, "material_empty"
        shrunk = drop_tail_sentence(lines[-1])
        if shrunk is None:
            lines.pop()
            if not lines:
                return None, before, before, "material_empty"
        else:
            lines[-1] = shrunk



def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-tokens", type=int, default=640)
    ap.add_argument("--in", dest="src", default=str(ROOT / "training" / "data" / "sft_train_behavior.jsonl"))
    ap.add_argument("--out", default="")
    ap.add_argument("--report", default="")
    ap.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    args = ap.parse_args()

    src = Path(args.src)
    out = Path(args.out) if args.out else src.with_suffix(f".{args.max_tokens}.jsonl")
    report = Path(args.report) if args.report else ROOT / "项目规划" / "第2周" / "evidence" / f"compress_{src.stem}_{args.max_tokens}.json"

    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.tokenizer, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    print(f"[load] tokenizer={args.tokenizer} vocab={len(tok)}", flush=True)

    rows = load_rows(src)
    print(f"[load] {len(rows)} 条，预算 {args.max_tokens} tokens", flush=True)

    kept: List[Dict[str, Any]] = []
    per_sample: List[Dict[str, Any]] = []
    dropped: List[Dict[str, Any]] = []
    before_tokens: List[int] = []
    after_tokens: List[int] = []

    for i, row in enumerate(rows):
        new_row, before, after, status = compress_row(tok, row, args.max_tokens)
        meta = row.get("meta") or {}
        if new_row is not None:
            kept.append(new_row)
            before_tokens.append(before)
            after_tokens.append(after)
            per_sample.append({
                "i": i, "kind": row.get("kind"), "qid": meta.get("qid"),
                "chunk_id": meta.get("chunk_id"), "before": before, "after": after,
                "action": status,
            })
        else:
            dropped.append({
                "i": i, "kind": row.get("kind"), "qid": meta.get("qid"),
                "chunk_id": meta.get("chunk_id"), "tokens": before, "reason": status,
            })
        if (i + 1) % 500 == 0:
            print(f"  {i+1}/{len(rows)} kept={len(kept)} dropped={len(dropped)}", flush=True)

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in kept), encoding="utf-8")

    compressed_n = sum(1 for p in per_sample if p["action"] == "compressed")
    summary = {
        "meta": {
            "src": str(src.relative_to(ROOT)) if src.is_relative_to(ROOT) else str(src),
            "out": str(out.relative_to(ROOT)) if out.is_relative_to(ROOT) else str(out),
            "max_tokens": args.max_tokens,
            "tokenizer": args.tokenizer,
            "render": "apply_chat_template(tokenize=False, add_generation_prompt=False) → tokenizer(text, add_special_tokens=False)",
        },
        "before_rows": len(rows),
        "after_rows": len(kept),
        "dropped_rows": len(dropped),
        "compressed_rows": compressed_n,
        "untouched_rows": len(kept) - compressed_n,
        "tokens": {
            "before_total": sum(before_tokens),
            "after_total": sum(after_tokens),
            "before_p50": int(statistics.median(before_tokens)) if before_tokens else 0,
            "after_p50": int(statistics.median(after_tokens)) if after_tokens else 0,
            "after_max": max(after_tokens) if after_tokens else 0,
            "assistant_tokens_touched": 0,
            "answer_truncated": 0,
            "citation_truncated": 0,
        },
        "dropped_by_reason": {},
        "dropped_samples": dropped,
        "per_sample": per_sample,
    }
    for d in dropped:
        summary["dropped_by_reason"][d["reason"]] = summary["dropped_by_reason"].get(d["reason"], 0) + 1

    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("per_sample", "dropped_samples")},
                     ensure_ascii=False, indent=2), flush=True)
    print(f"[done] {len(kept)} 条 -> {out}\n[report] {report}", flush=True)


if __name__ == "__main__":
    main()
