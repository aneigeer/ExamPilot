"""登记第4-5 周全链路产物（adapter → merged → GGUF → Ollama），产出 JSON + Markdown 表。

只读：不训练、不推理、不 `ollama create`；不 import torch（版本走 importlib.metadata，GGUF 用 gguf 的 mmap 读取）。
大文件按 8 MB 分块算 sha256，中间结果缓存到 `evidence/.sha_cache.json`（按 path+size+mtime 命中），
所以中断后重跑只补没算过的文件。

用法：
    .venv-ai/Scripts/python.exe 项目规划/第5周/scripts/build_registry.py
    .venv-ai/Scripts/python.exe 项目规划/第5周/scripts/build_registry.py --no-cache --skip-sha merged
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime
from importlib import metadata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT_DIR = Path(__file__).resolve().parents[1] / "evidence"
CACHE = OUT_DIR / ".sha_cache.json"
CHUNK = 8 * 1024 * 1024
PROGRESS_EVERY = 512 * 1024 * 1024          # 每 512 MB 打一次进度

RUN = ROOT / "training" / "output" / "qwen2.5-3b-exam-lora-v1"
BASE = Path("C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master")
BASE_7B = Path("C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master")
MERGED = Path("C:/models/exam-3b-v1-merged")
GGUF_F16 = Path("C:/models/exam-3b-v1-f16.gguf")
GGUF_Q4 = Path("C:/models/exam-3b-v1-q4_0.gguf")
GGUF_BASE_F16 = Path("C:/models/qwen2.5-3b-instruct-f16.gguf")
GGUF_BASE_Q4 = Path("C:/models/qwen2.5-3b-instruct-q4_0.gguf")
GGUF_Q8 = Path("C:/models/exam-3b-v1-q8_0.gguf")
GGUF_BASE_Q8 = Path("C:/models/qwen2.5-3b-instruct-q8_0.gguf")
MF_TUNED = ROOT / "training" / "Modelfile.exam-prep-3b-v1"
MF_BASE = ROOT / "training" / "Modelfile.baseline-3b-q4_0"
MF_TUNED_F16 = ROOT / "training" / "Modelfile.exam-prep-3b-v1-f16"
MF_TUNED_Q8 = ROOT / "training" / "Modelfile.exam-prep-3b-v1-q8"
MF_BASE_Q8 = ROOT / "training" / "Modelfile.baseline-3b-q8_0"


# ------------------------------------------------------------------ 工具

def pkg_version(name: str) -> str:
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return "not-installed"


def sha256_file(path: Path, use_cache: bool, cache: dict) -> tuple[str, float, bool]:
    """分块 sha256；返回 (sha, 耗时秒, 是否命中缓存)。"""
    st = path.stat()
    key = str(path.resolve())
    if use_cache and key in cache:
        rec = cache[key]
        if rec.get("size") == st.st_size and int(rec.get("mtime", 0)) == int(st.st_mtime):
            return rec["sha256"], float(rec.get("secs", 0.0)), True

    t0 = time.time()
    h = hashlib.sha256()
    done = 0
    with path.open("rb") as fh:
        while True:
            block = fh.read(CHUNK)
            if not block:
                break
            h.update(block)
            done += len(block)
            if done % PROGRESS_EVERY < CHUNK:
                print(f"    … {path.name}: {done / 1024 ** 3:.1f} GB", flush=True)
    digest = h.hexdigest()
    cache[key] = {"sha256": digest, "size": st.st_size, "mtime": int(st.st_mtime),
                  "secs": round(time.time() - t0, 2),
                  "computed_at": datetime.now().isoformat(timespec="seconds")}
    return digest, time.time() - t0, False


def file_entry(path: Path, use_cache: bool, cache: dict) -> dict:
    st = path.stat()
    sha, secs, hit = sha256_file(path, use_cache, cache)
    return {
        "name": path.name,
        "path": str(path).replace("\\", "/"),
        "bytes": st.st_size,
        "sha256": sha,
        "mtime": datetime.fromtimestamp(st.st_mtime).astimezone().isoformat(timespec="seconds"),
        "sha_seconds": round(secs, 2),
        "sha_cached": hit,
        "sha_note": f"{'缓存命中' if hit else '本次计算'}；分块 8 MB 流式读取" + ("（>1GB，耗时见 sha_seconds）" if st.st_size > 1024 ** 3 else ""),
    }


def safetensors_summary(path: Path) -> dict:
    """只读前 8 字节头长 + JSON 头，统计张量数与参数量（不加载权重）。"""
    with path.open("rb") as fh:
        raw = fh.read(8)
        n = int.from_bytes(raw, "little")
        header = json.loads(fh.read(n).decode("utf-8"))
    header.pop("__metadata__", None)
    params = 0
    dtypes = Counter()
    for info in header.values():
        numel = 1
        for d in info["shape"]:
            numel *= int(d)
        params += numel
        dtypes[info["dtype"]] += 1
    return {"tensors": len(header), "params": params, "dtypes": dict(dtypes)}


def gguf_summary(path: Path) -> dict:
    """用 gguf 的 mmap 读元数据与张量表，不加载权重到内存。"""
    from gguf import GGUFReader
    r = GGUFReader(str(path))
    ts = r.tensors
    params = 0
    types = Counter()
    for t in ts:
        numel = 1
        for d in t.shape:
            numel *= int(d)
        params += numel
        types[t.tensor_type.name] += 1
    return {
        "tensors": len(ts),
        "params": params,
        "quant_types": dict(types),
        "has_output_norm": any(t.name == "output_norm.weight" for t in ts),
        "arch": (r.fields.get("general.architecture").contents() if "general.architecture" in r.fields else None),
        "first_tensor": ts[0].name,
        "last_tensor": ts[-1].name,
    }


def ollama(cmd: list[str]) -> str:
    try:
        out = subprocess.run(["ollama", *cmd], capture_output=True, text=True, timeout=60)
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def ollama_models() -> dict[str, dict]:
    """解析 `ollama list`，返回 {name: {id, size, modified}}。"""
    rows = {}
    for line in ollama(["list"]).splitlines()[1:]:
        parts = re.split(r"\s{2,}", line.strip())
        if len(parts) >= 4:
            rows[parts[0]] = {"id": parts[1], "size": parts[2], "modified": " ".join(parts[3:])}
    return rows


def modelfile_from(path: Path) -> str | None:
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        s = line.strip()
        if s.upper().startswith("FROM "):
            return s[5:].strip()
    return None


def blob_of(model: str) -> str | None:
    text = ollama(["show", "--modelfile", model])
    m = re.search(r"sha256-([0-9a-f]{64})", text)
    return m.group(1) if m else None


def bytes_h(n: int | None) -> str:
    if n is None:
        return "-"
    if n < 1024:
        return f"{n} B"
    for unit, div in (("KB", 1024), ("MB", 1024 ** 2), ("GB", 1024 ** 3)):
        if n < div * 1024:
            return f"{n / div:.2f} {unit}"
    return f"{n / 1024 ** 3:.2f} GB"


# ------------------------------------------------------------------ 主流程

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DIR / "artifacts_registry.json"))
    ap.add_argument("--md", default=str(OUT_DIR / "artifacts_registry.md"))
    ap.add_argument("--no-cache", action="store_true", help="忽略 sha 缓存，全部重算")
    ap.add_argument("--skip-sha", nargs="*", default=[], help="跳过这些 kind 的 sha（如 merged gguf_f16）")
    args = ap.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cache = {}
    if CACHE.is_file() and not args.no_cache:
        try:
            cache = json.loads(CACHE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            cache = {}
    use_cache = not args.no_cache

    def fake_sha(path: Path) -> dict:
        st = path.stat()
        return {"name": path.name, "path": str(path).replace("\\", "/"), "bytes": st.st_size,
                "sha256": None, "mtime": datetime.fromtimestamp(st.st_mtime).astimezone().isoformat(timespec="seconds"),
                "sha_seconds": 0, "sha_cached": False, "sha_note": "skipped by --skip-sha"}

    def entry(path: Path, kind: str) -> dict:
        return fake_sha(path) if kind in args.skip_sha else file_entry(path, use_cache, cache)

    artifacts: list[dict] = []
    t_start = time.time()

    # 1) adapter（顶层 + 两个 checkpoint）
    adapter_meta = safetensors_summary(RUN / "adapter_model.safetensors")
    cfg = json.loads((RUN / "adapter_config.json").read_text(encoding="utf-8"))
    metrics = json.loads((RUN / "train_metrics.json").read_text(encoding="utf-8")) if (RUN / "train_metrics.json").is_file() else {}
    for rel, note in (("adapter_model.safetensors", "最终 adapter"),
                      ("checkpoint-130/adapter_model.safetensors", "checkpoint-130"),
                      ("checkpoint-135/adapter_model.safetensors", "checkpoint-135")):
        p = RUN / rel
        if not p.is_file():
            continue
        e = entry(p, "adapter")
        e.update({
            "kind": "adapter",
            "note": note,
            "provenance": {
                "run": str(RUN).replace("\\", "/"),
                "global_step": metrics.get("steps"),
                "status": metrics.get("status"),
                "base_model": cfg.get("base_model_name_or_path"),
                "lora": {"r": cfg.get("r"), "alpha": cfg.get("lora_alpha"),
                         "dropout": cfg.get("lora_dropout"), "target_modules": cfg.get("target_modules")},
                "data_sha_expected": "1fb1510e0a36",
            },
            "structure": adapter_meta,
        })
        artifacts.append(e)

    # 2) merged
    if MERGED.is_dir():
        idx = json.loads((MERGED / "model.safetensors.index.json").read_text(encoding="utf-8"))
        shard_names = sorted(set(idx["weight_map"].values()))
        shards = [entry(MERGED / n, "merged") for n in shard_names]
        total = safetensors_summary(MERGED / shard_names[0])
        if len(shard_names) > 1:
            extra = safetensors_summary(MERGED / shard_names[1])
            total = {"tensors": total["tensors"] + extra["tensors"],
                     "params": total["params"] + extra["params"],
                     "dtypes": {k: total["dtypes"].get(k, 0) + extra["dtypes"].get(k, 0)
                                for k in set(total["dtypes"]) | set(extra["dtypes"])}}
        artifacts.append({
            "kind": "merged",
            "path": str(MERGED).replace("\\", "/"),
            "bytes": sum(s["bytes"] for s in shards),
            "sha256": None,
            "sha_note": "按分片分别记录（见 files），合并目录本身不产生单一 sha",
            "mtime": max(s["mtime"] for s in shards),
            "files": shards + [entry(MERGED / "model.safetensors.index.json", "merged")],
            "provenance": {
                "base_model": str(BASE).replace("\\", "/"),
                "adapter": str(RUN / "adapter_model.safetensors").replace("\\", "/"),
                "tool": "training/merge_lora.py",
                "merge_dtype": "bfloat16 (CPU)",
                "shards": len(shard_names),
            },
            "structure": total,
        })

    # 3) GGUF：tuned f16 / tuned q4_0 / baseline f16 / baseline q4_0
    tool_sha = {}
    for t in ("tools/llama/conv_b6000.py", "tools/quantize_gguf.py"):
        p = ROOT / t
        if p.is_file():
            tool_sha[t] = sha256_file(p, use_cache, cache)[0]

    for kind, path, prov in (
        ("gguf_f16", GGUF_F16, {"source_merged": str(MERGED).replace("\\", "/"),
                                "tool": "tools/llama/conv_b6000.py", "outtype": "f16"}),
        ("gguf_q4_0", GGUF_Q4, {"source_gguf": str(GGUF_F16).replace("\\", "/"),
                                "tool": "tools/quantize_gguf.py", "qtype": "q4_0"}),
        ("gguf_baseline_f16", GGUF_BASE_F16, {"source_model": str(BASE).replace("\\", "/"),
                                              "tool": "tools/llama/conv_b6000.py", "outtype": "f16"}),
        ("gguf_baseline", GGUF_BASE_Q4, {"source_gguf": str(GGUF_BASE_F16).replace("\\", "/"),
                                         "tool": "tools/quantize_gguf.py", "qtype": "q4_0"}),
        ("gguf_q8_0", GGUF_Q8, {"source_gguf": str(GGUF_F16).replace("\\", "/"),
                                "tool": "tools/quantize_gguf.py", "qtype": "q8_0"}),
        ("gguf_baseline_q8_0", GGUF_BASE_Q8, {"source_gguf": str(GGUF_BASE_F16).replace("\\", "/"),
                                              "tool": "tools/quantize_gguf.py", "qtype": "q8_0"}),
    ):
        if not path.is_file():
            continue
        e = entry(path, kind)
        prov = dict(prov)
        prov["tool_sha256"] = tool_sha.get(prov["tool"], "")[:16]
        src = Path(prov.get("source_gguf", ""))
        if src.is_file() and e["bytes"]:
            prov["ratio_vs_source"] = round(src.stat().st_size / e["bytes"], 2)   # f16 / 本档
            prov["source_bytes"] = src.stat().st_size
        e.update({"kind": kind, "provenance": prov, "structure": gguf_summary(path)})
        artifacts.append(e)

    # 4) Modelfile
    for kind_path, model_hint in (
        (MF_TUNED, "exam-prep-3b-v1"),
        (MF_BASE, "qwen2.5-3b-instruct-q4_0-baseline"),
        (MF_TUNED_F16, "exam-prep-3b-v1-f16"),
        (MF_TUNED_Q8, "exam-prep-3b-v1-q8"),
        (MF_BASE_Q8, "qwen2.5-3b-instruct-q8_0-baseline"),
    ):
        if not kind_path.is_file():
            continue
        e = entry(kind_path, "modelfile")
        src = modelfile_from(kind_path)
        e.update({"kind": "modelfile", "provenance": {"from": src, "ollama_model": model_hint,
                                                      "template": "由 GGUF 自带 chat_template 提供（未写 TEMPLATE）"}})
        artifacts.append(e)

    # 5) Ollama 模型
    om = ollama_models()
    for name, hint, mf in (
        ("exam-prep-3b-v1:latest", "tuned-q4_0", MF_TUNED),
        ("qwen2.5-3b-instruct-q4_0-baseline:latest", "baseline-q4_0", MF_BASE),
        ("exam-prep-3b-v1-f16:latest", "tuned-f16", MF_TUNED_F16),
        ("exam-prep-3b-v1-q8:latest", "tuned-q8_0", MF_TUNED_Q8),
        ("qwen2.5-3b-instruct-q8_0-baseline:latest", "baseline-q8_0", MF_BASE_Q8),
    ):
        if name not in om:
            continue
        blob = blob_of(name)
        blob_bytes = None
        if blob:
            bp = Path.home() / ".ollama" / "models" / "blobs" / f"sha256-{blob}"
            if bp.is_file():
                blob_bytes = bp.stat().st_size
        artifacts.append({
            "kind": "ollama_model",
            "path": name,
            "ollama_digest": om[name]["id"],
            "blob": f"sha256-{blob}" if blob else None,
            "bytes": blob_bytes,
            "sha256": blob,
            "sha_note": "ollama blob sha256（`ollama show --modelfile` 的 FROM 指向的 blob）",
            "size_reported": om[name]["size"],
            "mtime": om[name]["modified"],
            "provenance": {"role": hint,
                           "from_gguf": modelfile_from(mf) if mf.is_file() else None,
                           "modelfile": str(mf).replace("\\", "/") if mf.is_file() else None,
                           "created_by": "ollama create -f <Modelfile>"},
        })

    # ---- 一致性检查 ----
    by_kind: dict[str, list[dict]] = {}
    for a in artifacts:
        by_kind.setdefault(a["kind"], []).append(a)

    def struct_of(kind: str):
        xs = [a for a in by_kind.get(kind, []) if a.get("structure")]
        return xs[0]["structure"] if xs else None

    merged_s, f16_s, q4_s, bq4_s = struct_of("merged"), struct_of("gguf_f16"), struct_of("gguf_q4_0"), struct_of("gguf_baseline")
    checks = []

    def add(name: str, ok: bool | None, detail: str) -> None:
        checks.append({"check": name, "ok": ok, "detail": detail})

    if merged_s and f16_s:
        add("merged.params == gguf_f16.params", merged_s["params"] == f16_s["params"],
            f"merged {merged_s['params']:,} vs f16 {f16_s['params']:,}")
        add("gguf_f16 含 output_norm.weight", f16_s["has_output_norm"], f"tensors={f16_s['tensors']}")
    if f16_s and q4_s:
        add("gguf_f16.tensors == gguf_q4_0.tensors", f16_s["tensors"] == q4_s["tensors"],
            f"{f16_s['tensors']} vs {q4_s['tensors']}；量化后 params 应相等：{f16_s['params'] == q4_s['params']}")
        add("gguf_q4_0 含 output_norm.weight", q4_s["has_output_norm"], f"quant_types={q4_s['quant_types']}")
    if merged_s and f16_s:
        add("merged.tensors == gguf_f16.tensors", merged_s["tensors"] == f16_s["tensors"],
            f"{merged_s['tensors']} vs {f16_s['tensors']}（3B 为 tie_word_embeddings=True：36 层×12 + token_embd + output_norm = 434，无独立 output.weight；7B 未绑定故为 339 = 28×12+3）")
    if q4_s and bq4_s:
        add("tuned/baseline 张量数一致", q4_s["tensors"] == bq4_s["tensors"],
            f"tuned {q4_s['tensors']} vs baseline {bq4_s['tensors']}")

    q8_s, bq8_s = struct_of("gguf_q8_0"), struct_of("gguf_baseline_q8_0")
    if f16_s and q8_s:
        add("gguf_q8_0.tensors == gguf_f16.tensors", q8_s["tensors"] == f16_s["tensors"],
            f"{q8_s['tensors']} vs {f16_s['tensors']}")
        add("gguf_q8_0.params == gguf_f16.params", q8_s["params"] == f16_s["params"],
            f"{q8_s['params']:,} vs {f16_s['params']:,}")
        add("gguf_q8_0 含 output_norm.weight", q8_s["has_output_norm"], f"quant_types={q8_s['quant_types']}")
    if q8_s and bq8_s:
        add("q8_0 tuned/baseline 张量数一致", q8_s["tensors"] == bq8_s["tensors"],
            f"tuned {q8_s['tensors']} vs baseline {bq8_s['tensors']}")

    # gguf sha 与 ollama blob 的关系（ollama 会重打包，通常不等，仅记录）
    for a in by_kind.get("ollama_model", []):
        role = a["provenance"]["role"]
        from_gguf = a["provenance"].get("from_gguf") or ""
        src = Path(from_gguf) if from_gguf else GGUF_Q4
        srcs = [x for x in artifacts if x.get("path") == str(src).replace("\\", "/")] if src else []
        if srcs and a.get("sha256"):
            same = srcs[0]["sha256"] == a["sha256"]
            add(f"ollama[{role}].blob 与 {src.name} 同 sha（仅记录）", None,
                f"{'相同：ollama 直接复用原 GGUF blob' if same else '不同：ollama 重打包过 GGUF，元数据可能被改写'}")

    registry = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "total_seconds": round(time.time() - t_start, 1),
        "env": {
            "python": sys.version.split()[0],
            "torch": pkg_version("torch"),
            "transformers": pkg_version("transformers"),
            "peft": pkg_version("peft"),
            "trl": pkg_version("trl"),
            "gguf": pkg_version("gguf"),
            "datasets": pkg_version("datasets"),
            "bitsandbytes": pkg_version("bitsandbytes"),
            "safetensors": pkg_version("safetensors"),
            "ollama": (ollama(["--version"]).replace("ollama version is ", "").strip() or "unknown"),
        },
        "artifacts": artifacts,
        "checks": checks,
        "totals": {
            "count": len(artifacts),
            "bytes": sum(a["bytes"] or 0 for a in artifacts),
            "by_kind": {k: len(v) for k, v in by_kind.items()},
        },
    }
    out = Path(args.out)
    out.write_text(json.dumps(registry, ensure_ascii=False, indent=2), encoding="utf-8")
    CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---- Markdown ----
    lines = [f"# 产物登记表（{registry['generated_at']}）", "",
             f"共 **{registry['totals']['count']}** 个产物，合计 **{bytes_h(registry['totals']['bytes'])}**。", "",
             "| kind | 名称/路径 | 大小 | sha256(16) | mtime | 来源 |",
             "|---|---|---:|---|---|---|"]
    for a in artifacts:
        name = a.get("path") or a.get("ollama_digest")
        sha = (a.get("sha256") or "-")[:16]
        prov = a.get("provenance") or {}
        if a["kind"] == "ollama_model":
            src = f"{prov.get('role')}；blob {str(a.get('blob'))[:20]}…"
        else:
            src = ", ".join(f"{k}={str(v)[:46]}" for k, v in prov.items() if v)
        cost = ""
        if (a.get("bytes") or 0) > 1024 ** 3 and a.get("sha_seconds"):
            cost = f"（sha {a['sha_seconds']:.1f}s）"
        lines.append(f"| {a['kind']} | `{name}` | {bytes_h(a.get('bytes'))}{cost} | `{sha}` | {a.get('mtime', '-')} | {src} |")
        for f in a.get("files") or []:          # merged 目录：分片逐个列，保证 sha 可见
            sub_cost = f"（sha {f['sha_seconds']:.1f}s）" if f.get("sha_seconds") else ""
            lines.append(f"| {a['kind']}/file | `{f['name']}` | {bytes_h(f['bytes'])}{sub_cost} | `{(f.get('sha256') or '-')[:16]}` | {f['mtime']} | 见所属 {a['kind']} |")
    # 量化档对照（f16 / q8_0 / q4_0）
    def size_of(kind: str) -> int | None:
        xs = [a for a in artifacts if a["kind"] == kind]
        return xs[0].get("bytes") if xs else None

    tiers = (("f16", "gguf_f16", "gguf_baseline_f16"),
             ("q8_0", "gguf_q8_0", "gguf_baseline_q8_0"),
             ("q4_0", "gguf_q4_0", "gguf_baseline"))
    f16_tuned = size_of("gguf_f16")
    lines += ["", "## 量化档对照（3B，微调 vs 基线）", "",
              "| 档位 | 微调体积 | 基线体积 | f16 / 该档 ratio |", "|---|---:|---:|---:|"]
    for tier, kt, kb in tiers:
        st, sb = size_of(kt), size_of(kb)
        ratio = "-" if (not f16_tuned or not st) else f"{f16_tuned / st:.2f}"
        lines.append(f"| {tier} | {bytes_h(st)} | {bytes_h(sb)} | {ratio} |")

    lines += ["", "## 一致性检查", "", "| 检查 | 结果 | 说明 |", "|---|---|---|"]
    for c in checks:
        flag = "✅" if c["ok"] else ("❌" if c["ok"] is False else "ℹ️")
        lines.append(f"| {c['check']} | {flag} | {c['detail']} |")
    lines += ["", "## 环境", ""]
    for k, v in registry["env"].items():
        lines.append(f"- {k}: `{v}`")
    Path(args.md).write_text("\n".join(lines) + "\n", encoding="utf-8")

    print("\n".join(lines))
    print(f"\n[json] {out}\n[md]   {args.md}\n[耗时] {registry['total_seconds']} s")
    bad = [c for c in checks if c["ok"] is False]
    raise SystemExit(1 if bad else 0)


if __name__ == "__main__":
    main()
