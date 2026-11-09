"""校验 `training/output/data_pins.txt` 与磁盘文件是否一致（只读）。

用途：数据冻结后，任何人（评测/QA/训练）在跑之前先跑这个，避免用错版本的数据。
退出码 0 = 全部一致；1 = 有不一致（打印差异）。

用法：
    .venv-ai/Scripts/python.exe training/verify_pins.py
    .venv-ai/Scripts/python.exe training/verify_pins.py --pins training/output/data_pins.txt --data training/data
"""

from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINE = re.compile(r"^(\S+)\s+(\d+) 条(?:\s+\d+ B)?\s+sha256=([0-9a-f]{64})\s*$")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pins", default=str(ROOT / "training" / "output" / "data_pins.txt"))
    ap.add_argument("--data", default=str(ROOT / "training" / "data"))
    args = ap.parse_args()

    pins = Path(args.pins).read_text(encoding="utf-8")
    data = Path(args.data)
    bad = 0
    for raw in pins.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue                                   # 历史值以注释形式保留，跳过
        m = LINE.match(raw.strip())
        if not m:
            print(f"[FAIL] 无法解析 pins 行：{raw!r}")
            bad += 1
            continue
        name, want_rows, want_sha = m.group(1), int(m.group(2)), m.group(3)
        path = data / name
        if not path.exists():
            print(f"[FAIL] {name}: 文件不存在")
            bad += 1
            continue
        blob = path.read_bytes()
        rows = sum(1 for line in blob.decode("utf-8").splitlines() if line.strip())
        sha = hashlib.sha256(blob).hexdigest()
        ok = rows == want_rows and sha == want_sha
        print(f"[{'OK' if ok else 'FAIL'}] {name}: {rows}/{want_rows} 条  {len(blob)} B  sha={sha[:16]}"
              + ("" if ok else f"（pins 期望 {want_sha[:16]}）"))
        bad += 0 if ok else 1

    print("全部一致" if bad == 0 else f"有 {bad} 处不一致")
    raise SystemExit(0 if bad == 0 else 1)


if __name__ == "__main__":
    main()
