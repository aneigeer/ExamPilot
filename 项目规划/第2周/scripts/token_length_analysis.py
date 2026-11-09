"""统计训练样本的 token 长度与截断影响（CPU only，不加载模型权重）。

口径与训练完全一致：
    text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
    ids  = tokenizer(text, add_special_tokens=False).input_ids
（`training/train_qlora.py:render` 就是这么渲染的，SFTTrainer 再按 `max_length` 右截断）

用法：
    .venv-ai/Scripts/python.exe 项目规划/第2周/scripts/token_length_analysis.py \
        --data training/data/sft_train_behavior.jsonl \
        --out 项目规划/第2周/evidence/length_stats.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, List

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_TOKENIZER = r"C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"

USER_MARK = "<|im_start|>user"
ASST_MARK = "<|im_start|>assistant"


def load_rows(path: Path) -> List[Dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def percentile(values: List[int], q: float) -> int:
    """nearest-rank 分位，避免 numpy 依赖。"""
    if not values:
        return 0
    data = sorted(values)
    idx = min(len(data) - 1, max(0, int(round(q * (len(data) - 1)))))
    return data[idx]


def dist(values: List[int]) -> Dict[str, Any]:
    if not values:
        return {"n": 0}
    return {
        "n": len(values),
        "min": min(values),
        "p50": percentile(values, 0.50),
        "p90": percentile(values, 0.90),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
        "mean": round(statistics.fmean(values), 1),
        "over_512": sum(1 for v in values if v > 512),
        "over_640": sum(1 for v in values if v > 640),
        "over_768": sum(1 for v in values if v > 768),
    }


def analyse(tok, row: Dict[str, Any], limits: List[int]) -> Dict[str, Any]:
    msgs = row["messages"]
    text = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=False)
    enc = tok(text, add_special_tokens=False, return_offsets_mapping=True)
    ids, offs = enc["input_ids"], enc["offset_mapping"]
    n = len(ids)

    user_start = text.find(USER_MARK)
    asst_mark = text.rfind(ASST_MARK)
    asst_start = asst_mark + len(ASST_MARK) + 1 if asst_mark >= 0 else len(text)   # 跳过换行
    user_content = msgs[1]["content"] if len(msgs) > 1 else ""
    assistant = msgs[-1]["content"] if msgs else ""

    out: Dict[str, Any] = {
        "kind": row.get("kind", ""),
        "qid": (row.get("meta") or {}).get("qid"),
        "n_tokens": n,
        "len_user_chars": len(user_content),
        "len_assistant_chars": len(assistant),
        "has_citation": "[" in assistant and "]" in assistant,
        "assistant_tokens": len(tok(text[asst_start:], add_special_tokens=False)["input_ids"]),
        "limits": {},
    }

    # 引用位置（最后一个 [n] 在 assistant 内容里的字符下标）
    last_cite_end = -1
    for i in range(len(assistant) - 1):
        if assistant[i] == "[":
            j = assistant.find("]", i + 1)
            if 0 < j - i <= 4 and assistant[i + 1:j].isdigit():
                last_cite_end = j + 1
    cite_pos_in_text = asst_start + 1 + last_cite_end if last_cite_end > 0 else -1

    # 答案标记「答案：」的位置
    ans_mark = assistant.find("答案")
    ans_mark_pos = asst_start + 1 + ans_mark if ans_mark >= 0 else -1

    # 压缩资料后的最小长度：丢掉 【资料】 段，只留 【问题】
    q_pos = user_content.find("【问题】")
    min_tokens = n
    if q_pos > 0 and "【资料】" in user_content[:q_pos]:
        slim = [
            {"role": "system", "content": msgs[0]["content"]},
            {"role": "user", "content": user_content[q_pos:]},
            {"role": "assistant", "content": assistant},
        ]
        slim_text = tok.apply_chat_template(slim, tokenize=False, add_generation_prompt=False)
        min_tokens = len(tok(slim_text, add_special_tokens=False)["input_ids"])
    out["min_tokens_without_material"] = min_tokens

    for L in limits:
        info: Dict[str, Any] = {"truncated": n > L}
        if n > L:
            cut_char = offs[L - 1][1]
            info["cut_char"] = cut_char
            info["cut_in"] = "assistant" if cut_char > asst_start else "user_or_system"
            info["answer_fully_lost"] = cut_char <= asst_start
            info["answer_tail_cut"] = cut_char > asst_start
            info["answer_marker_lost"] = ans_mark_pos >= 0 and cut_char <= ans_mark_pos
            info["citation_lost"] = cite_pos_in_text >= 0 and cut_char <= cite_pos_in_text
            info["fits_if_material_dropped"] = min_tokens <= L
            kept_asst_chars = max(0, cut_char - asst_start - 1)
            info["assistant_kept_ratio"] = round(
                kept_asst_chars / max(1, len(assistant)), 3
            )
        out["limits"][str(L)] = info
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(ROOT / "training" / "data" / "sft_train_behavior.jsonl"))
    ap.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    ap.add_argument("--out", default=str(ROOT / "项目规划" / "第2周" / "evidence" / "length_stats.json"))
    ap.add_argument("--limits", default="512,640,768")
    args = ap.parse_args()

    limits = [int(x) for x in args.limits.split(",")]
    data_path = Path(args.data)
    rows = load_rows(data_path)
    sha = hashlib.sha256(data_path.read_bytes()).hexdigest()

    from transformers import AutoTokenizer

    started = time.time()
    tok = AutoTokenizer.from_pretrained(args.tokenizer, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    print(f"[load] tokenizer={args.tokenizer} vocab={len(tok)}", flush=True)
    print(f"[load] {len(rows)} 条，limits={limits}", flush=True)

    analysed: List[Dict[str, Any]] = []
    for i, row in enumerate(rows):
        try:
            analysed.append(analyse(tok, row, limits))
        except Exception as exc:                      # 单条异常不影响全量统计
            print(f"[warn] 第 {i} 条失败: {exc}", flush=True)
        if (i + 1) % 500 == 0:
            print(f"  {i + 1}/{len(rows)}", flush=True)

    elapsed = round(time.time() - started, 1)
    lengths = [a["n_tokens"] for a in analysed]
    by_kind: Dict[str, List[int]] = defaultdict(list)
    for a in analysed:
        by_kind[a["kind"]].append(a["n_tokens"])

    assistant_tokens = [a["assistant_tokens"] for a in analysed]
    result: Dict[str, Any] = {
        "meta": {
            "data": str(data_path),
            "sha256": sha,
            "rows": len(analysed),
            "tokenizer": args.tokenizer,
            "vocab": len(tok),
            "render": "apply_chat_template(tokenize=False, add_generation_prompt=False) → tokenizer(text, add_special_tokens=False)",
            "elapsed_sec": elapsed,
        },
        "overall": dist(lengths),
        "assistant_tokens": dist(assistant_tokens),
        "total_tokens_untruncated": sum(lengths),
        "by_kind": {k: dist(v) for k, v in sorted(by_kind.items())},
        "limits": {},
    }

    for L in limits:
        key = str(L)
        over = [a for a in analysed if a["n_tokens"] > L]
        rec = {
            "truncated": len(over),
            "truncated_pct": round(len(over) / max(1, len(analysed)) * 100, 2),
            "by_kind": {},
            "answer_tail_cut": sum(1 for a in over if a["limits"][key].get("answer_tail_cut")),
            "answer_fully_lost": sum(1 for a in over if a["limits"][key].get("answer_fully_lost")),
            "answer_marker_lost": sum(1 for a in over if a["limits"][key].get("answer_marker_lost")),
            "citation_lost": sum(1 for a in over if a["limits"][key].get("citation_lost")),
            "fits_if_material_dropped": sum(1 for a in over if a["limits"][key].get("fits_if_material_dropped")),
            "median_over_by": round(statistics.fmean([a["n_tokens"] for a in over]), 1) if over else 0,
            "effective_token_total": sum(min(a["n_tokens"], L) for a in analysed),
            "unsavable_by_kind": dict(Counter(
                a["kind"] for a in over if not a["limits"][key].get("fits_if_material_dropped")
            ).most_common()),
            "median_assistant_kept_ratio": round(
                statistics.fmean([a["limits"][key]["assistant_kept_ratio"] for a in over]), 3
            ) if over else 0,
        }
        for k in sorted(by_kind):
            rec["by_kind"][k] = {
                "n": len(by_kind[k]),
                "over": sum(1 for a in analysed if a["kind"] == k and a["n_tokens"] > L),
            }
        # 样例：优先给「答案整段丢失」和「答案尾部被截且引用丢失」
        samples: List[Dict[str, Any]] = []
        for a in over:
            if len(samples) >= 12:
                break
            samples.append({
                "qid": a["qid"],
                "kind": a["kind"],
                "n_tokens": a["n_tokens"],
                "assistant_tokens": a["assistant_tokens"],
                "min_tokens_without_material": a["min_tokens_without_material"],
                "cut_in": a["limits"][key]["cut_in"],
                "answer_fully_lost": a["limits"][key]["answer_fully_lost"],
                "citation_lost": a["limits"][key]["citation_lost"],
                "assistant_kept_ratio": a["limits"][key]["assistant_kept_ratio"],
            })
        rec["samples"] = samples
        result["limits"][key] = rec

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n[总分布] {result['overall']}", flush=True)
    for k, d in result["by_kind"].items():
        print(f"  {k:10s} n={d['n']:5d} p50={d['p50']:5d} p90={d['p90']:5d} p95={d['p95']:5d} p99={d['p99']:5d} max={d['max']:5d}", flush=True)
    for L in limits:
        r = result["limits"][str(L)]
        print(f"\n[截断 @{L}] {r['truncated']} 条（{r['truncated_pct']}%）"
              f" 答案整段丢 {r['answer_fully_lost']} / 尾部截 {r['answer_tail_cut']}"
              f" / 引用丢 {r['citation_lost']} / 丢资料可救 {r['fits_if_material_dropped']}", flush=True)
    print(f"\n[json] {out_path}  耗时 {elapsed}s", flush=True)


if __name__ == "__main__":
    main()
