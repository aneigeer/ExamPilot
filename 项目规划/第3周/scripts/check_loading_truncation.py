"""第3周：核验训练加载阶段是否会把 assistant 尾部 `[1]。` 二次截断（CPU，只读）。

训练路径（TRL 1.17.1 SFTTrainer，dataset_text_field="text"）：
  text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
  batch = tokenizer(text, add_special_tokens=False, truncation=True, max_length=args.max_length)
本脚本对比：
  build 侧：apply_chat_template(tokenize=True) 的 input_ids 长度（压缩阶段口径）
  train 侧：tokenizer(text, add_special_tokens=False) 的 input_ids 长度（SFTTrainer 口径）
并检查 3 条最接近 640 的样本在 640 截断后 `[1]。` 是否完整。

用法：
  .venv-ai/Scripts/python.exe 项目规划/第3周/scripts/check_loading_truncation.py
输出：项目规划/第3周/data/loading_truncation_check.json
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data"
TOKENIZER = "C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"


def load(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def clean_tail(text: str) -> str:
    """去掉 chat template 的 <|im_end|> 及之后的内容，返回 assistant 正文尾部。"""
    idx = text.rfind("<|im_end|>")
    return text[:idx] if idx >= 0 else text


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--subset", default=str(ROOT / "项目规划" / "第3周" / "data" / "short_run_200.jsonl"))
    ap.add_argument("--full", default=str(DATA / "sft_train_behavior.jsonl"))
    ap.add_argument("--max-length", type=int, default=640)
    ap.add_argument("--out", default=str(ROOT / "项目规划" / "第3周" / "data" / "loading_truncation_check.json"))
    args = ap.parse_args()

    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(TOKENIZER, trust_remote_code=True)

    rows = load(Path(args.subset))
    details = []
    mismatches = 0
    for i, r in enumerate(rows):
        text = tok.apply_chat_template(r["messages"], tokenize=False, add_generation_prompt=False)
        build_ids = tok.apply_chat_template(r["messages"], tokenize=True, return_dict=True, add_generation_prompt=False)["input_ids"]
        train_ids = tok(text, add_special_tokens=False)["input_ids"]
        if len(build_ids) != len(train_ids):
            mismatches += 1
        details.append({"i": i, "build_len": len(build_ids), "train_len": len(train_ids), "text": text})

    details.sort(key=lambda x: x["train_len"], reverse=True)
    boundary = []
    for d in details[:3]:
        r = rows[d["i"]]
        text = d["text"]
        ids = tok(text, add_special_tokens=False)["input_ids"]
        truncated = ids[: args.max_length]
        decoded_full = clean_tail(text)
        decoded_trunc = clean_tail(tok.decode(truncated, skip_special_tokens=False))
        boundary.append(
            {
                "i": d["i"],
                "kind": r["kind"],
                "build_len": d["build_len"],
                "train_len": d["train_len"],
                "margin_to_max": args.max_length - d["train_len"],
                "truncated_len": len(truncated),
                "truncated_is_same": truncated == ids,
                "full_ends_with_citation": decoded_full.rstrip().endswith("[1]。"),
                "truncated_ends_with_citation": decoded_trunc.rstrip().endswith("[1]。"),
                "full_tail_40": decoded_full[-40:],
                "truncated_tail_40": decoded_trunc[-40:],
            }
        )

    # 全量：训练口径下是否有 >640 的样本
    full = load(Path(args.full))
    full_lens = []
    for r in full:
        text = tok.apply_chat_template(r["messages"], tokenize=False, add_generation_prompt=False)
        full_lens.append(len(tok(text, add_special_tokens=False)["input_ids"]))

    out = {
        "subset": str(args.subset),
        "full": str(args.full),
        "tokenizer": TOKENIZER,
        "max_length": args.max_length,
        "trl_version": __import__("trl").__version__,
        "subset_n": len(rows),
        "build_vs_train_len_mismatch": mismatches,
        "subset_max_build": max(d["build_len"] for d in details),
        "subset_max_train": max(d["train_len"] for d in details),
        "subset_over_max": sum(1 for d in details if d["train_len"] > args.max_length),
        "full_n": len(full),
        "full_max_train": max(full_lens),
        "full_over_max": sum(1 for x in full_lens if x > args.max_length),
        "full_at_max": sum(1 for x in full_lens if x == args.max_length),
        "boundary": boundary,
        "conclusion": "no_second_truncation" if max(full_lens) <= args.max_length else "risk",
    }
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: out[k] for k in (
        "trl_version", "subset_n", "build_vs_train_len_mismatch", "subset_max_build", "subset_max_train",
        "subset_over_max", "full_n", "full_max_train", "full_over_max", "full_at_max", "conclusion",
    )}, ensure_ascii=False, indent=2))
    for b in boundary:
        print(json.dumps(b, ensure_ascii=False))


if __name__ == "__main__":
    main()
