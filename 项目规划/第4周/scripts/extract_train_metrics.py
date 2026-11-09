"""第4周：从训练日志与 run 目录抽取指标（只读）。

用法：
  .venv-ai/Scripts/python.exe 项目规划/第4周/scripts/extract_train_metrics.py \
      --log 项目规划/第4周/evidence/train_formal_v1.log \
      --run-dir training/output/qwen2.5-3b-exam-lora-v1

输出（默认写到 run-dir 下，也可 --out-dir）：
  train_metrics_summary.json
  train_metrics_summary.md
"""

from __future__ import annotations

import argparse
import ast
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ADAPTER_FOUR = ("adapter_model.safetensors", "adapter_config.json", "tokenizer.json", "chat_template.jinja")
EXTRA_FILES = ("trainer_state.json", "optimizer.pt", "train_metrics.json")
CORE_FILES = ADAPTER_FOUR + EXTRA_FILES


def parse_elapsed(text: str) -> int:
    parts = [int(x) for x in text.split(":")]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    return parts[0]


def parse_log(path: Path) -> dict:
    raw = path.read_text(encoding="utf-8", errors="replace").replace("\r", "\n")
    lines = raw.split("\n")

    model = ""
    total_steps = None
    metrics: list[dict] = []
    mem: list[dict] = []
    last_step = 0
    last_elapsed = 0
    step_sec_samples: list[float] = []

    for line in lines:
        if not model:
            m = re.search(r"\[train\] model=(.+)", line)
            if m:
                model = m.group(1).strip()

        # tqdm: 5/135 [08:12<1:41:03, 48.51s/it]
        for pm in re.finditer(r"(\d+)/(\d+) \[(\d+:\d+(?::\d+)?)<[^,]*,\s*([\d.]+)s/it\]", line):
            step, total = int(pm.group(1)), int(pm.group(2))
            total_steps = total
            if step >= last_step:
                last_step = step
                last_elapsed = parse_elapsed(pm.group(3))
            step_sec_samples.append(float(pm.group(4)))

        # [mem] step=5 peak_alloc=3.58G peak_reserved=3.72G
        for mm in re.finditer(r"\[mem\] step=(\d+) peak_alloc=([\d.]+)G peak_reserved=([\d.]+)G", line):
            mem.append({"step": int(mm.group(1)), "peak_alloc_gb": float(mm.group(2)), "peak_reserved_gb": float(mm.group(3))})

        # 指标 dict（值可能带引号）
        for dm in re.finditer(r"\{'loss':.*?\}", line):
            try:
                obj = ast.literal_eval(dm.group(0))
            except (ValueError, SyntaxError):
                continue
            row = {"step": last_step}
            for k, v in obj.items():
                try:
                    row[k] = float(v)
                except (TypeError, ValueError):
                    row[k] = v
            metrics.append(row)

    # 去重（同一 step 重复打印时保留最后一条）
    dedup: dict[int, dict] = {}
    for m in metrics:
        dedup[int(m["step"])] = m
    metrics = [dedup[k] for k in sorted(dedup)]

    # 每 5 步（配置 logging_steps=5）；若日志按 1 步打印，这里也保留全部
    every5 = [m for m in metrics if int(m["step"]) % 5 == 0] or metrics

    avg_step_sec = last_elapsed / last_step if last_step else (sum(step_sec_samples) / len(step_sec_samples) if step_sec_samples else 0)
    return {
        "log": str(path),
        "model": model,
        "total_steps": total_steps,
        "last_step": last_step,
        "last_elapsed_sec": last_elapsed,
        "step_avg_sec": round(avg_step_sec, 2),
        "metrics": metrics,
        "metrics_every5": every5,
        "mem": mem,
        "peak_alloc_gb": max((m["peak_alloc_gb"] for m in mem), default=None),
        "peak_reserved_gb": max((m["peak_reserved_gb"] for m in mem), default=None),
    }


def scan_checkpoints(run_dir: Path) -> list[dict]:
    out = []
    for d in sorted(run_dir.glob("checkpoint-*"), key=lambda p: int(re.sub(r"\D", "", p.name) or 0)):
        files = {f: (d / f).exists() for f in CORE_FILES}
        state = None
        state_ok = False
        if files["trainer_state.json"]:
            try:
                state = json.loads((d / "trainer_state.json").read_text(encoding="utf-8"))
                state_ok = True
            except Exception:  # noqa: BLE001
                state_ok = False
        sizes = {f: (d / f).stat().st_size for f in CORE_FILES if (d / f).exists()}
        out.append(
            {
                "dir": str(d.resolve()),
                "name": d.name,
                "global_step": (state or {}).get("global_step", int(re.sub(r"\D", "", d.name) or 0)),
                "files": files,
                "adapter_four_present": all(files[f] for f in ADAPTER_FOUR),
                "trainer_state_parseable": state_ok,
                "optimizer_present": files["optimizer.pt"],
                "sizes": sizes,
                "likely_incomplete": (not all(files[f] for f in ADAPTER_FOUR)) or (not state_ok) or sizes.get("optimizer.pt", 0) < 1_000_000,
            }
        )
    return out


def to_markdown(data: dict) -> str:
    rows = data["metrics_every5"]
    lines = [
        f"# 训练指标摘要（{Path(data['log']).name}）",
        "",
        f"- 模型：`{data['model'] or '未识别'}`",
        f"- 计划步数 / 日志末步：{data['total_steps']} / {data['last_step']}",
        f"- 步均耗时：{data['step_avg_sec']} s/step（按日志 elapsed / step）",
        f"- 峰值显存：alloc {data['peak_alloc_gb']} G / reserved {data['peak_reserved_gb']} G",
        "",
        "| step | loss | grad_norm | lr | token_acc | num_tokens | epoch |",
        "|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for m in rows:
        lines.append(
            "| {step} | {loss} | {grad} | {lr} | {acc} | {tok} | {ep} |".format(
                step=int(m.get("step", 0)),
                loss=m.get("loss", ""),
                grad=m.get("grad_norm", ""),
                lr=m.get("learning_rate", ""),
                acc=m.get("mean_token_accuracy", ""),
                tok=m.get("num_tokens", ""),
                ep=m.get("epoch", ""),
            )
        )
    lines += ["", "## Checkpoints", ""]
    if not data["checkpoints"]:
        lines.append("（暂无 checkpoint）")
    else:
        lines += ["| dir | global_step | adapter 四件 | trainer_state | optimizer | 可疑残件 |", "|---|---:|---|---|---|---|"]
        for c in data["checkpoints"]:
            lines.append(
                f"| {c['name']} | {c['global_step']} | {c['adapter_four_present']} | {c['trainer_state_parseable']} | {c['optimizer_present']} | {c['likely_incomplete']} |"
            )
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True)
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--out-dir", default="")
    args = ap.parse_args()

    log = Path(args.log)
    run_dir = Path(args.run_dir)
    data = parse_log(log)
    data["run_dir"] = str(run_dir.resolve())
    data["checkpoints"] = scan_checkpoints(run_dir) if run_dir.exists() else []

    out_dir = Path(args.out_dir) if args.out_dir else run_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "train_metrics_summary.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    md = to_markdown(data)
    (out_dir / "train_metrics_summary.md").write_text(md, encoding="utf-8")

    print(json.dumps({k: data[k] for k in (
        "model", "total_steps", "last_step", "step_avg_sec", "peak_alloc_gb", "peak_reserved_gb",
        "checkpoints",
    )}, ensure_ascii=False, indent=2))
    print(f"[out] {out_dir / 'train_metrics_summary.json'}")
    print(f"[out] {out_dir / 'train_metrics_summary.md'}")


if __name__ == "__main__":
    main()
