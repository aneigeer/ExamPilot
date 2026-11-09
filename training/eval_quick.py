"""快速对比：微调后的合并权重（transformers 4bit） vs Ollama 基线。

用途：本机 Ollama 0.40 无法导入 safetensors（MLX 不支持 Qwen2 架构），
且拿不到 llama-quantize 二进制，先在 transformers 侧验证「微调是否有效」。
评分函数与 eval_compare.py 完全一致，保证口径统一。

用法：
    python training/eval_quick.py --n 30
"""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

from eval_compare import ask, score

ROOT = Path(__file__).resolve().parents[1]
DATA = Path(__file__).resolve().parent / "data"
REPORTS = Path(__file__).resolve().parent / "reports"
MERGED = Path("D:/models/qwen2.5-7b-exam-merged")


def pick_samples(path: Path, n: int) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = [r for r in rows if r["kind"] in ("mcq", "grounded", "refusal", "injection", "citation", "wiki")]
    # 按 kind 轮转取样，避免某一类刷屏
    buckets: dict[str, list[dict]] = {}
    for r in rows:
        buckets.setdefault(r["kind"], []).append(r)
    out, i = [], 0
    while len(out) < n and any(buckets.values()):
        for kind in list(buckets):
            if buckets[kind] and len(out) < n:
                out.append(buckets[kind].pop(0))
        i += 1
        if i > n * 3:
            break
    return out


@torch.inference_mode()
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=30)
    parser.add_argument("--max-new-tokens", type=int, default=220)
    parser.add_argument("--base-model", default="qwen2.5-7b-instruct-baseline")
    args = parser.parse_args()

    samples = pick_samples(DATA / "sft_val.jsonl", args.n)
    print(f"[quick] 样本 {len(samples)} 条：{ {k: sum(1 for s in samples if s['kind']==k) for k in {s['kind'] for s in samples} } }", flush=True)

    tokenizer = AutoTokenizer.from_pretrained(str(MERGED), trust_remote_code=True)
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                             bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.bfloat16)
    model = AutoModelForCausalLM.from_pretrained(
        str(MERGED), quantization_config=bnb, device_map={"": 0}, dtype=torch.bfloat16
    )
    model.eval()

    rows_tuned, lat_tuned = [], []
    for i, sample in enumerate(samples, 1):
        prompt = tokenizer.apply_chat_template(sample["messages"][:2], tokenize=False, add_generation_prompt=True)
        inputs = tokenizer(prompt, return_tensors="pt").to("cuda")
        started = time.perf_counter()
        out = model.generate(
            **inputs, max_new_tokens=args.max_new_tokens, do_sample=False,
            pad_token_id=tokenizer.eos_token_id,
        )
        cost = time.perf_counter() - started
        text = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
        rows_tuned.append(score(sample, text))
        lat_tuned.append(cost)
        print(f"[tuned] {i}/{len(samples)} {cost:.1f}s", flush=True)

    del model
    torch.cuda.empty_cache()

    rows_base, lat_base = [], []
    for i, sample in enumerate(samples, 1):
        try:
            text, cost = ask(args.base_model, sample["messages"][:2])
        except Exception as exc:
            text, cost = f"<error: {exc}>", 0.0
        rows_base.append(score(sample, text))
        lat_base.append(cost)
        print(f"[base] {i}/{len(samples)} {cost:.1f}s", flush=True)

    def summary(rows, lats):
        by = {}
        for r in rows:
            b = by.setdefault(r["kind"], {"n": 0, "pass": 0})
            b["n"] += 1
            b["pass"] += 1 if r["pass"] else 0
        for b in by.values():
            b["rate"] = round(b["pass"] / b["n"], 4) if b["n"] else 0.0
        lats = sorted(lats)
        return {
            "by_kind": by,
            "overall_rate": round(sum(b["pass"] for b in by.values()) / max(1, len(rows)), 4),
            "latency_avg_s": round(sum(lats) / max(1, len(lats)), 2),
            "latency_p95_s": round(lats[min(len(lats) - 1, int(len(lats) * 0.95))], 2) if lats else 0.0,
            "n": len(rows),
        }

    report = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "n": len(samples),
        "base": {"model": f"{args.base_model} (ollama Q4_K_M)", "summary": summary(rows_base, lat_base), "rows": rows_base},
        "tuned": {"model": "qwen2.5-7b-exam-merged (transformers 4bit nf4)", "summary": summary(rows_tuned, lat_tuned), "rows": rows_tuned},
        "caveat": "两侧推理栈不同（Ollama Q4_K_M vs transformers nf4），量化误差方向不固定；结论用于判断行为是否改善，不作绝对性能对比。",
    }
    REPORTS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    (REPORTS / f"quick_compare_{stamp}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    b, t = report["base"]["summary"], report["tuned"]["summary"]
    lines = ["# 快速对比（微调 vs 基线）", "", f"- 时间：{report['generated_at']}",
             f"- 样本：{len(samples)} 条（同集、贪心解码）", f"- 说明：{report['caveat']}", "",
             "| 维度 | 基线 | 微调 | 变化 |", "|---|---|---|---|"]
    for kind in sorted(set(b["by_kind"]) | set(t["by_kind"])):
        bb = b["by_kind"].get(kind, {"pass": 0, "n": 0, "rate": 0})
        tt = t["by_kind"].get(kind, {"pass": 0, "n": 0, "rate": 0})
        lines.append(f"| {kind} | {bb['rate']*100:.0f}% ({bb['pass']}/{bb['n']}) | {tt['rate']*100:.0f}% ({tt['pass']}/{tt['n']}) | {(tt['rate']-bb['rate'])*100:+.0f}pp |")
    lines += [f"| **总体** | **{b['overall_rate']*100:.0f}%** | **{t['overall_rate']*100:.0f}%** | **{(t['overall_rate']-b['overall_rate'])*100:+.0f}pp** |"]
    text = "\n".join(lines)
    (REPORTS / f"quick_compare_{stamp}.md").write_text(text, encoding="utf-8")
    print("\n" + text)


if __name__ == "__main__":
    main()
