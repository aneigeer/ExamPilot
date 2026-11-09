"""第1周 P0：训练数据数量对账（只读，不改任何数据文件）。

对 training/data 下三个训练/验证文件逐项统计：
  - 总行数 / 有效 JSON 行 / 非法行
  - 各 kind 条数与占比
  - 完全重复（归一化 user 全文）/ 题干重复
  - 空 assistant / 缺 system / 缺 meta.qid
  - 长度分布（Qwen tokenizer，可退回字符数）
  - sha256 与 training/output/data_pins.txt 对齐
并输出 system prompt 三分类（无资料版 / 资料版 / planner 版）及 1,691 分母校验。

用法：
  .venv-ai/Scripts/python.exe 项目规划/第1周/scripts/count_train_data.py
  .venv-ai/Scripts/python.exe 项目规划/第1周/scripts/count_train_data.py --no-tokenizer
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data"
PINS = ROOT / "training" / "output" / "data_pins.txt"
TOKENIZER_DIR = Path("C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master")

FILES = ("sft_train.jsonl", "sft_train_behavior.jsonl", "sft_val.jsonl")
PUNCT = set(" ,，。、；;：:！!？?“”\"'（）()[]【】-—_…·\n\t\r")


def norm(text: str) -> str:
    """NFKC + 去空白与常见标点，用于重复判定。"""
    s = unicodedata.normalize("NFKC", text)
    return "".join(ch for ch in s if not ch.isspace() and ch not in PUNCT)


def stem(text: str) -> str:
    """取题干：mcq 取【题目】到【选项】之间；其余取【问题】之后；都没有则整段。"""
    if "【题目】" in text:
        q = text.split("【题目】", 1)[1].split("【选项】", 1)[0]
    elif "【问题】" in text:
        q = text.rsplit("【问题】", 1)[1]
    else:
        q = text
    for suffix in ("请作答并说明依据。", "请依据资料说明。", "请依据资料作答。", "请依据资料作答"):
        q = q.replace(suffix, "")
    return norm(q)


def quantile(sorted_vals: list[int], q: float) -> int:
    if not sorted_vals:
        return 0
    i = min(len(sorted_vals) - 1, max(0, int(round(q * (len(sorted_vals) - 1)))))
    return sorted_vals[i]


def read_pins(path: Path) -> dict[str, dict]:
    pins: dict[str, dict] = {}
    if not path.exists():
        return pins
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^(\S+\.jsonl)\s+(\d+)\s*条(?:\s+\d+\s*B)?\s+sha256=([0-9a-f]{64})", line.strip())
        if m:
            pins[m.group(1)] = {"count": int(m.group(2)), "sha256": m.group(3)}
    return pins


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_rows(path: Path) -> tuple[list[dict], int]:
    rows, bad = [], 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            bad += 1
    return rows, bad


def system_kind(system: str) -> str:
    if "如果没有【资料】" in system and "严格遵守" not in system:
        return "bare"          # mcq 无资料版
    if "严格遵守" in system and "未找到相关资料" in system and "每次只输出一个 JSON" in system:
        return "planner"       # Agent 规划版
    if "严格遵守" in system and "未找到相关资料" in system:
        return "strict"        # 资料约束版
    return "unknown"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=str(DATA))
    ap.add_argument("--pins", default=str(PINS))
    ap.add_argument("--out", default=str(ROOT / "项目规划" / "第1周" / "02A_训练数据数量对账.json"))
    ap.add_argument("--tokenizer", default=str(TOKENIZER_DIR))
    ap.add_argument("--no-tokenizer", action="store_true")
    args = ap.parse_args()

    data_dir = Path(args.data_dir)
    pins = read_pins(Path(args.pins))

    tok = None
    tok_error = ""
    if not args.no_tokenizer:
        try:
            from transformers import AutoTokenizer

            tok = AutoTokenizer.from_pretrained(args.tokenizer, trust_remote_code=True)
        except Exception as exc:  # noqa: BLE001
            tok_error = f"{type(exc).__name__}: {exc}"

    out: dict = {
        "generated_at": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "data_dir": str(data_dir),
        "pins_file": str(args.pins),
        "length_unit": "qwen_tokens" if tok else "chars",
        "tokenizer_error": tok_error,
        "files": {},
        "system_split": {},
        "kinds_total_check": {},
    }

    for name in FILES:
        path = data_dir / name
        rows, bad = load_rows(path)
        n = len(rows)

        kinds = Counter(r.get("kind", "<none>") for r in rows)
        kind_pct = {k: round(100 * v / n, 2) if n else 0.0 for k, v in kinds.items()}

        users = [norm(r["messages"][1]["content"]) for r in rows if len(r.get("messages", [])) >= 2]
        stems = [stem(r["messages"][1]["content"]) for r in rows if len(r.get("messages", [])) >= 2]
        user_c = Counter(users)
        stem_c = Counter(stems)

        lens: list[int] = []
        if tok:
            for r in rows:
                enc = tok.apply_chat_template(r["messages"], tokenize=True, return_dict=True)
                lens.append(len(enc["input_ids"]))
        else:
            lens = [sum(len(str(m.get("content", ""))) for m in r.get("messages", [])) for r in rows]
        lens_sorted = sorted(lens)

        actual_sha = sha256(path)
        pin = pins.get(name, {})

        out["files"][name] = {
            "rows": n,
            "valid_rows": n,
            "invalid_rows": bad,
            "kinds": dict(kinds.most_common()),
            "kind_pct": kind_pct,
            "dup_user_full": {
                "rows": sum(c - 1 for c in user_c.values() if c > 1),
                "groups": sum(1 for c in user_c.values() if c > 1),
            },
            "dup_stem": {
                "rows": sum(c - 1 for c in stem_c.values() if c > 1),
                "groups": sum(1 for c in stem_c.values() if c > 1),
                "note": "题干相同但资料/选项不同的题也计入",
            },
            "empty_assistant": sum(
                1 for r in rows if not r.get("messages") or not str(r["messages"][-1].get("content", "")).strip()
            ),
            "missing_system": sum(
                1 for r in rows if not r.get("messages") or r["messages"][0].get("role") != "system"
            ),
            "missing_meta_qid": sum(1 for r in rows if not (r.get("meta") or {}).get("qid")),
            "empty_gold": sum(
                1 for r in rows if (r.get("meta") or {}).get("answer_text", "") == "" and r.get("kind") in ("mcq", "grounded", "injection")
            ),
            "length": {
                "unit": "qwen_tokens" if tok else "chars",
                "p50": quantile(lens_sorted, 0.50),
                "p90": quantile(lens_sorted, 0.90),
                "p99": quantile(lens_sorted, 0.99),
                "max": max(lens_sorted) if lens_sorted else 0,
                "min": min(lens_sorted) if lens_sorted else 0,
                "mean": round(sum(lens) / len(lens), 1) if lens else 0.0,
            },
            "sha256": actual_sha,
            "pinned_sha256": pin.get("sha256", ""),
            "sha_match": actual_sha == pin.get("sha256", ""),
            "pinned_count": pin.get("count"),
            "count_match": pin.get("count") == n,
        }

        # system prompt 分流（只对 behavior 统计，写入顶层）
        if name == "sft_train_behavior.jsonl":
            split = Counter(system_kind(r["messages"][0]["content"]) for r in rows)
            non_planner = n - split.get("planner", 0)
            out["system_split"] = {
                "total": n,
                "bare_mcq": split.get("bare", 0),
                "strict_answer": split.get("strict", 0),
                "planner": split.get("planner", 0),
                "unknown": split.get("unknown", 0),
                "non_planner_denominator": non_planner,
                "bare_plus_strict": split.get("bare", 0) + split.get("strict", 0),
                "checks": {
                    "bare_plus_strict_equals_non_planner": split.get("bare", 0) + split.get("strict", 0) == non_planner,
                    "bare_plus_strict_plus_planner_equals_total": (
                        split.get("bare", 0) + split.get("strict", 0) + split.get("planner", 0) == n
                    ),
                    "mcq_count_equals_bare": kinds.get("mcq", 0) == split.get("bare", 0),
                    "strict_kinds_are_5": sorted(
                        k for k in kinds if k in ("grounded", "refusal", "injection", "citation", "wiki")
                    ),
                    "strict_sum_from_kinds": sum(
                        kinds.get(k, 0) for k in ("grounded", "refusal", "injection", "citation", "wiki")
                    ),
                },
            }

    # 各文件 kind 合计校验
    for name, info in out["files"].items():
        out["kinds_total_check"][name] = {
            "sum_kinds": sum(info["kinds"].values()),
            "rows": info["rows"],
            "match": sum(info["kinds"].values()) == info["rows"],
        }

    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {
        "files": {k: {"rows": v["rows"], "kinds": v["kinds"], "sha_match": v["sha_match"]} for k, v in out["files"].items()},
        "system_split": out["system_split"],
        "length_unit": out["length_unit"],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
