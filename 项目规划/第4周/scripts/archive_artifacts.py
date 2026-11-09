"""为一次训练 run 生成产物清单（`artifacts_manifest.json`），用于归档与验收取证。

只做文件级取证：**不加载模型、不 import torch、不占 GPU**（版本号走 importlib.metadata）。
默认不哈希基座权重（6 GB 起、耗时且无必要），只记录基座路径 + 索引文件 sha + 各分片大小；
需要完整哈希时加 `--hash-base`。

用法：
    # 最小用法（run 目录 + 默认基座/数据）
    .venv-ai/Scripts/python.exe 项目规划/第4周/scripts/archive_artifacts.py \
        --run training/output/qwen2.5-3b-exam-lora-v1

    # 显式指定基座、数据、训练日志、输出位置
    .venv-ai/Scripts/python.exe 项目规划/第4周/scripts/archive_artifacts.py \
        --run training/output/qwen2.5-3b-exam-lora-v1 \
        --base C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master \
        --data training/data/sft_train_behavior.jsonl \
        --log 项目规划/第4周/evidence/train_formal_v1.log \
        --out training/output/qwen2.5-3b-exam-lora-v1/artifacts_manifest.json

输出：
1. `artifacts_manifest.json`（默认写进 run 目录）；
2. 标准输出一段可直接贴进报告的 Markdown 表格。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from importlib import metadata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BASE_3B = "C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master"
CHUNK = 8 * 1024 * 1024

#: run 目录里必须出现的产物（缺失只告警，不中断——训练中被查也能出报告）
EXPECTED = ("adapter_model.safetensors", "adapter_config.json", "trainer_state.json", "train_metrics.json")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            block = fh.read(CHUNK)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def stat_entry(path: Path, rel: str) -> dict:
    st = path.stat()
    return {
        "path": rel,
        "bytes": st.st_size,
        "mtime": datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).astimezone().isoformat(timespec="seconds"),
        "sha256": sha256_of(path),
    }


def pkg_version(name: str) -> str:
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return "not-installed"


def git_commit() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(ROOT),
                             capture_output=True, text=True, timeout=15)
        return out.stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unavailable"


def base_info(base: Path, full_hash: bool) -> dict:
    """基座信息：默认只记路径 + 索引 sha + 分片大小，避免每次都读 6 GB。"""
    info: dict = {"path": str(base), "exists": base.is_dir()}
    if not base.is_dir():
        return info
    index = base / "model.safetensors.index.json"
    cfg = base / "config.json"
    if cfg.is_file():
        c = json.loads(cfg.read_text(encoding="utf-8"))
        info["arch"] = c.get("architectures")
        info["hidden_size"] = c.get("hidden_size")
        info["num_hidden_layers"] = c.get("num_hidden_layers")
        info["vocab_size"] = c.get("vocab_size")
        info["config_sha256"] = sha256_of(cfg)
    if index.is_file():
        idx = json.loads(index.read_text(encoding="utf-8"))
        info["params_total_size_bytes"] = idx.get("metadata", {}).get("total_size")
        info["index_sha256"] = sha256_of(index)
        shards = sorted({*idx["weight_map"].values()})
        info["shards"] = []
        for name in shards:
            p = base / name
            entry = {"name": name, "bytes": p.stat().st_size if p.is_file() else None}
            if full_hash and p.is_file():
                entry["sha256"] = sha256_of(p)
            info["shards"].append(entry)
    return info


def fmt_bytes(n: int | None) -> str:
    """人类可读体积（1024 进制）：B / KB / MB / GB。"""
    if n is None:
        return "-"
    if n < 1024:
        return f"{n} B"
    for unit, div in (("KB", 1024), ("MB", 1024 ** 2), ("GB", 1024 ** 3)):
        if n < div * 1024:
            return f"{n / div:.2f} {unit}"
    return f"{n / 1024 ** 3:.2f} GB"


def main() -> None:
    ap = argparse.ArgumentParser(description="训练产物归档清单（只读，不占 GPU）")
    ap.add_argument("--run", required=True, help="run 输出目录，如 training/output/qwen2.5-3b-exam-lora-v1")
    ap.add_argument("--base", default=DEFAULT_BASE_3B, help="基座目录")
    ap.add_argument("--data", default=str(ROOT / "training" / "data" / "sft_train_behavior.jsonl"))
    ap.add_argument("--script", default=str(ROOT / "training" / "train_qlora.py"))
    ap.add_argument("--log", action="append", default=[], help="要一并归档的日志文件，可重复")
    ap.add_argument("--out", default="", help="manifest 输出路径，默认 <run>/artifacts_manifest.json")
    ap.add_argument("--hash-base", action="store_true", help="连基座分片一起算 sha256（慢，约 6 GB 读盘）")
    args = ap.parse_args()

    run = Path(args.run)
    if not run.is_dir():
        raise SystemExit(f"run 目录不存在：{run}")

    files = []
    for p in sorted(run.rglob("*")):
        if p.is_file():
            files.append(stat_entry(p, str(p.relative_to(run)).replace("\\", "/")))

    missing = [n for n in EXPECTED if not any(f["path"].endswith(n) for f in files)]

    extras = []
    for item in [Path(args.data), Path(args.script), *(Path(x) for x in args.log)]:
        if item.is_file():
            try:
                rel = str(item.resolve().relative_to(ROOT)).replace("\\", "/")
            except ValueError:
                rel = str(item)
            extras.append(stat_entry(item, rel))

    manifest = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "host": os.environ.get("COMPUTERNAME", "unknown"),
        "git_commit": git_commit(),
        "run_dir": str(run),
        "missing_expected": missing,
        "env": {
            "python": sys.version.split()[0],
            "torch": pkg_version("torch"),
            "transformers": pkg_version("transformers"),
            "peft": pkg_version("peft"),
            "trl": pkg_version("trl"),
            "datasets": pkg_version("datasets"),
            "bitsandbytes": pkg_version("bitsandbytes"),
        },
        "base": base_info(Path(args.base), args.hash_base),
        "files": files,
        "extras": extras,
        "totals": {
            "run_files": len(files),
            "run_bytes": sum(f["bytes"] for f in files),
        },
    }

    out = Path(args.out) if args.out else run / "artifacts_manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---- 可直接贴进报告的 Markdown ----
    print(f"\n### 产物清单（{run}）\n")
    print("| 文件 | 字节 | 大小 | sha256(16) | mtime |")
    print("|---|---:|---:|---|---|")
    for f in files:
        print(f"| `{f['path']}` | {f['bytes']:,} | {fmt_bytes(f['bytes'])} | `{f['sha256'][:16]}` | {f['mtime']} |")
    if extras:
        print("\n**外部引用（数据/脚本/日志）**\n")
        print("| 文件 | 字节 | sha256(16) | mtime |")
        print("|---|---:|---|---|")
        for f in extras:
            print(f"| `{f['path']}` | {f['bytes']:,} | `{f['sha256'][:16]}` | {f['mtime']} |")

    b = manifest["base"]
    print("\n**基座 / 环境**\n")
    print(f"- 基座：`{b.get('path')}`（arch={b.get('arch')}，hidden={b.get('hidden_size')}，"
          f"layers={b.get('num_hidden_layers')}，vocab={b.get('vocab_size')}）")
    print(f"- 基座权重总量：{fmt_bytes(b.get('params_total_size_bytes'))}，"
          f"分片 {len(b.get('shards') or [])} 个，index sha `{(b.get('index_sha256') or '-')[:16]}`")
    print(f"- git commit：`{manifest['git_commit'][:12]}`；python {manifest['env']['python']}，"
          f"torch {manifest['env']['torch']}，transformers {manifest['env']['transformers']}，"
          f"peft {manifest['env']['peft']}，trl {manifest['env']['trl']}")
    if missing:
        print(f"\n> ⚠️ 缺失预期产物：{', '.join(missing)}（run 可能仍在训练或未正常收尾）")

    print(f"\n[manifest] {out}", flush=True)


if __name__ == "__main__":
    main()
