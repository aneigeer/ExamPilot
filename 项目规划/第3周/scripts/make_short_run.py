"""第3周：从冻结 behavior 集分层抽 200 条短跑子集（只读源数据）。

要求：kind 比例与全集一致、seed=2026、同一命令两次结果逐字节一致。
输出：
  项目规划/第3周/data/short_run_200.jsonl
  项目规划/第3周/data/short_run_200.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "training" / "data" / "sft_train_behavior.jsonl"
OUT_DIR = ROOT / "项目规划" / "第3周" / "data"
TOKENIZER = "C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def allocate(total: int, counts: Counter, n: int) -> dict[str, int]:
    """最大余数法分配配额，保证严格等于 n。"""
    kinds = list(counts)
    exact = {k: counts[k] * n / total for k in kinds}
    quota = {k: int(exact[k]) for k in kinds}
    remain = n - sum(quota.values())
    order = sorted(kinds, key=lambda k: (exact[k] - quota[k], counts[k], k), reverse=True)
    for k in order[:remain]:
        quota[k] += 1
    return quota


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(SRC))
    ap.add_argument("--out-dir", default=str(OUT_DIR))
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--tokenizer", default=TOKENIZER)
    args = ap.parse_args()

    raw_lines = [l for l in Path(args.src).read_text(encoding="utf-8").splitlines() if l.strip()]
    rows = [json.loads(l) for l in raw_lines]
    counts = Counter(r["kind"] for r in rows)
    total = len(rows)
    quota = allocate(total, counts, args.n)

    rng = random.Random(args.seed)
    picked: list[int] = []
    for kind in sorted(counts):
        idxs = [i for i, r in enumerate(rows) if r["kind"] == kind]
        picked.extend(rng.sample(idxs, quota[kind]))
    picked.sort()  # 按源文件顺序输出，保证逐字节可复现

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_text = "\n".join(raw_lines[i] for i in picked) + "\n"
    out_path = out_dir / f"short_run_{args.n}.jsonl"
    out_path.write_bytes(out_text.encode("utf-8"))  # 二进制写入，避免 Windows 换行转换

    # 用训练同款 tokenizer 复算长度（apply_chat_template tokenize=True 不额外加 special tokens）
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(args.tokenizer, trust_remote_code=True)
    lens = []
    for i in picked:
        enc = tok.apply_chat_template(rows[i]["messages"], tokenize=True, return_dict=True, add_generation_prompt=False)
        lens.append(len(enc["input_ids"]))

    picked_rows = [rows[i] for i in picked]
    meta = {
        "source": str(args.src),
        "source_rows": total,
        "source_sha256": sha256_bytes(Path(args.src).read_bytes()),
        "seed": args.seed,
        "n": args.n,
        "kind_counts": dict(Counter(r["kind"] for r in picked_rows)),
        "kind_ratio": {k: round(v / args.n, 4) for k, v in Counter(r["kind"] for r in picked_rows).items()},
        "source_kind_counts": dict(counts),
        "total_tokens": int(sum(lens)),
        "max_tokens": int(max(lens)),
        "min_tokens": int(min(lens)),
        "p50_tokens": int(sorted(lens)[len(lens) // 2]),
        "n_at_640": int(sum(1 for x in lens if x == 640)),
        "n_over_640": int(sum(1 for x in lens if x > 640)),
        "output_sha256": sha256_bytes(out_text.encode("utf-8")),
        "tokenizer": args.tokenizer,
        "tokenize_method": "apply_chat_template(tokenize=True, add_generation_prompt=False) → len(input_ids)",
        "command": f".venv-ai/Scripts/python.exe 项目规划/第3周/scripts/make_short_run.py --n {args.n} --seed {args.seed}",
    }
    (out_dir / f"short_run_{args.n}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: meta[k] for k in (
        "source_rows", "n", "kind_counts", "total_tokens", "max_tokens", "p50_tokens",
        "n_at_640", "n_over_640", "output_sha256",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
