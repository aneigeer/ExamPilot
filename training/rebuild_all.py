"""一条命令重建全部训练/评测数据（唯一入口，参数与种子写死）。

为什么需要它：2026-11-04 验收发现「冻结产物靠脚本复现不出来」——原因是脚本有多处改写，
不同人手里拿着不同 vintage 的副本，跑出来的数自然不同（例如 chunks 查询漏 `source_type`
会让 planner final 静默变成 0 条，总量凭空少 300 条）。这里把顺序、参数、种子全部固化。

顺序（与 03 报告一致）：
    1. build_dataset.py         → sft_train.jsonl / sft_val.jsonl
    2. mix_behavior.py          → sft_train_behavior.jsonl
    3. compress_for_training.py → 原地压缩 sft_train / sft_train_behavior 到 640 tokens
    4. build_planner_eval.py    → eval_planner.jsonl
    5. build_eval.py            → eval_holdout.jsonl
    6. 打印 5 个文件的条数/sha256；`--write-pins` 时才写 pins

用法：
    # 验证用（写临时目录，不动冻结数据）
    python training/rebuild_all.py --out "$TEMP/rebuild_a"

    # 正式重建（会覆盖 training/data，必须显式加 --apply）
    python training/rebuild_all.py --apply --write-pins

硬约束：
- 默认 **不写** `training/data/` 与 pins；只有 `--apply`（覆盖数据）+ `--write-pins`（覆盖 pins）才写。
- 所有随机种子在脚本里写死（build=42 / mix=7 / planner_eval=99 / eval=2026）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRAIN = ROOT / "training"
DATA = TRAIN / "data"
EVIDENCE = ROOT / "项目规划" / "第2周" / "evidence"
PY = sys.executable

#: 写死的参数（改这里等于改冻结口径，必须同步 03 报告）
MAX_TOKENS = 640
PLANNER_EVAL_N = 50
HOLDOUT_PER_KIND = 50


def sh(cmd: list[str], step: str) -> float:
    """跑一步并计时；失败直接中止（不吞异常）。"""
    t0 = time.time()
    print(f"\n=== [{step}] {' '.join(str(c) for c in cmd[1:])}", flush=True)
    proc = subprocess.run(cmd, cwd=str(ROOT))
    dt = time.time() - t0
    if proc.returncode != 0:
        raise SystemExit(f"[{step}] 失败（exit={proc.returncode}），已中止")
    print(f"--- [{step}] 完成，耗时 {dt:.1f}s", flush=True)
    return dt


def sha_and_rows(path: Path) -> tuple[str, int, int]:
    blob = path.read_bytes()
    rows = sum(1 for line in blob.decode("utf-8").splitlines() if line.strip())
    return hashlib.sha256(blob).hexdigest(), rows, len(blob)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="", help="输出目录；默认 training/data，但需 --apply 才允许写")
    ap.add_argument("--apply", action="store_true", help="允许写入 training/data（否则必须显式给 --out）")
    ap.add_argument("--pins", default=str(TRAIN / "output" / "data_pins.txt"))
    ap.add_argument("--write-pins", action="store_true", help="重建后写 pins（会覆盖现有文件，旧值另存注释）")
    ap.add_argument("--evidence", default=str(EVIDENCE))
    args = ap.parse_args()

    out = Path(args.out) if args.out else DATA
    if out.resolve() == DATA.resolve() and not args.apply:
        raise SystemExit("拒绝写入 training/data：请加 --apply，或用 --out 指向临时目录")
    out.mkdir(parents=True, exist_ok=True)
    ev = Path(args.evidence)
    ev.mkdir(parents=True, exist_ok=True)

    timings: dict[str, float] = {}
    t_all = time.time()

    timings["build_dataset"] = sh([PY, str(TRAIN / "build_dataset.py"), "--out", str(out)], "1/5 build_dataset")
    timings["mix_behavior"] = sh([PY, str(TRAIN / "mix_behavior.py"),
                                 "--src", str(out / "sft_train.jsonl"),
                                 "--out", str(out / "sft_train_behavior.jsonl")], "2/5 mix_behavior")

    for name, tag in (("sft_train.jsonl", "train"), ("sft_train_behavior.jsonl", "behavior")):
        tmp = out / f".{tag}.640.jsonl"
        timings[f"compress_{tag}"] = sh([PY, str(TRAIN / "compress_for_training.py"),
                                        "--max-tokens", str(MAX_TOKENS),
                                        "--in", str(out / name),
                                        "--out", str(tmp),
                                        "--report", str(ev / f"compress_{tag}_{MAX_TOKENS}.json")],
                                       f"3/5 compress {tag}")
        os.replace(tmp, out / name)          # 原地替换（先写临时文件，避免半截文件）

    timings["build_planner_eval"] = sh([PY, str(TRAIN / "build_planner_eval.py"),
                                        "--n", str(PLANNER_EVAL_N), "--data-dir", str(out)], "4/5 build_planner_eval")
    timings["build_eval"] = sh([PY, str(TRAIN / "build_eval.py"),
                                "--per-kind", str(HOLDOUT_PER_KIND), "--data-dir", str(out)], "5/5 build_eval")

    files = ["sft_train.jsonl", "sft_train_behavior.jsonl", "sft_val.jsonl", "eval_holdout.jsonl", "eval_planner.jsonl"]
    result = {}
    print("\n=== 产物清单", flush=True)
    for name in files:
        p = out / name
        sha, rows, size = sha_and_rows(p)
        result[name] = {"sha256": sha, "rows": rows, "bytes": size}
        print(f"{name:26s} {rows:5d} 条  {size:9d} B  {sha[:16]}", flush=True)

    elapsed = time.time() - t_all
    report = {"out": str(out), "max_tokens": MAX_TOKENS, "elapsed_sec": round(elapsed, 1),
              "timings": {k: round(v, 1) for k, v in timings.items()}, "files": result}
    (ev / f"rebuild_all_{out.name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[总耗时] {elapsed:.1f}s → 明细 {ev / f'rebuild_all_{out.name}.json'}", flush=True)

    if args.write_pins:
        pins = Path(args.pins)
        if pins.exists():
            hist = ROOT / "backup" / time.strftime("%Y-%m-%d") / f"{pins.stem}.prev-{time.strftime('%H%M%S')}.txt"
            hist.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(pins, hist)
            print(f"[pins] 旧文件已备份 {hist}", flush=True)
        lines = [f"# 数据冻结 {time.strftime('%Y-%m-%d %H:%M')}（rebuild_all.py 重建）",
                 f"# 入口：python training/rebuild_all.py --apply --write-pins（max_tokens={MAX_TOKENS}，种子写死）"]
        for name in files:
            r = result[name]
            lines.append(f"{name:26s} {r['rows']:5d} 条  {r['bytes']:9d} B  sha256={r['sha256']}")
        pins.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"[pins] 已写入 {pins}", flush=True)
    else:
        print("[pins] 未写（加 --write-pins 才写）", flush=True)


if __name__ == "__main__":
    main()
