#!/usr/bin/env python
"""第6周 G1/G2 复跑：完整保存输出，用最终判分器重算。

- G2：holdout injection 50 条，base vs tuned
- G1：eval_refusal_hard 30 条，base vs tuned，按 scene 分开
- 优化：按模型整组跑（避免每样本切模型）；每条落盘；支持断点续跑
- 输出：项目规划/第6周/evidence/rerun_g1_g2_20261107.json
"""
from __future__ import annotations

import json
import math
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(r"D:\大学生多考试备考知识库与题库系统")
EVID = ROOT / "项目规划" / "第6周" / "evidence"
sys.path.insert(0, str(ROOT / "training"))
from eval_v2 import injection_ok, refused_ok  # noqa: E402

OLLAMA = "http://127.0.0.1:11434/api/chat"
MODELS = {
    "base": "qwen2.5-3b-instruct-q8_0-baseline",
    "tuned": "exam-prep-3b-v1-q8",
}
OPTIONS = {"temperature": 0, "top_p": 1, "num_predict": 400, "num_ctx": 4096, "seed": 7}


def read_jsonl(path: Path):
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def chat(model: str, messages, retries: int = 1) -> str:
    payload = {
        "model": model,
        "messages": messages,
        "stream": False,
        "keep_alive": "10m",
        "options": OPTIONS,
    }
    last_err = None
    for _ in range(retries + 1):
        try:
            req = urllib.request.Request(
                OLLAMA,
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=240) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            return data["message"]["content"]
        except Exception as exc:  # noqa: BLE001
            last_err = exc
            time.sleep(1.0)
    return f"__ERROR__: {last_err}"


def mcnemar_exact(b: int, c: int) -> float:
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def score_fn_for(group: str):
    if group == "injection":
        return lambda out, meta: injection_ok(out)
    return lambda out, meta: refused_ok(out, meta)


def progress_path(group: str, label: str) -> Path:
    return EVID / f".rerun_progress_{group}_{label}.jsonl"


def load_progress(group: str, label: str) -> dict:
    path = progress_path(group, label)
    if not path.exists():
        return {}
    done = {}
    for line in path.open(encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        done[rec["idx"]] = rec
    return done


def append_progress(group: str, label: str, rec: dict) -> None:
    path = progress_path(group, label)
    with path.open("a", encoding="utf-8") as fp:
        fp.write(json.dumps(rec, ensure_ascii=False) + "\n")


def run_group(name: str, samples, score_fn) -> dict:
    records = []
    for label, model in MODELS.items():
        done = load_progress(name, label)
        batch_size = 8
        for start in range(0, len(samples), batch_size):
            for idx in range(start, min(start + batch_size, len(samples))):
                if idx in done:
                    continue
                sample = samples[idx]
                messages = [m for m in sample["messages"] if m["role"] in ("system", "user")]
                meta = sample.get("meta") or {}
                out = chat(model, messages)
                passed, reason = score_fn(out, meta)
                rec = {
                    "idx": idx,
                    "kind": sample.get("kind"),
                    "qid": meta.get("qid"),
                    "scene": meta.get("scene"),
                    "question": messages[-1]["content"][:1200],
                    "model_label": label,
                    "model": model,
                    "pass": bool(passed),
                    "reason": reason,
                    "output": out,
                }
                append_progress(name, label, rec)
                done[idx] = rec
                print(
                    f"[{name}] {label} {idx + 1}/{len(samples)} qid={meta.get('qid')} "
                    f"pass={passed} reason={reason}",
                    flush=True,
                )

    # assemble
    by_idx = {}
    for label in MODELS:
        done = load_progress(name, label)
        for idx, rec in done.items():
            by_idx.setdefault(idx, {"idx": idx, "kind": rec["kind"], "qid": rec["qid"],
                                    "scene": rec["scene"], "question": rec["question"],
                                    "models": {}})
            by_idx[idx]["models"][label] = {
                "model": rec["model"],
                "pass": rec["pass"],
                "reason": rec["reason"],
                "output": rec["output"],
            }
    records = [by_idx[i] for i in sorted(by_idx) if all(m in by_idx[i]["models"] for m in MODELS)]

    base = [r["models"]["base"]["pass"] for r in records]
    tuned = [r["models"]["tuned"]["pass"] for r in records]
    b = sum(1 for x, y in zip(base, tuned) if x and not y)
    c = sum(1 for x, y in zip(base, tuned) if not x and y)
    summary = {
        "n": len(records),
        "base_pass": sum(base),
        "tuned_pass": sum(tuned),
        "base_rate": round(sum(base) / len(records), 4) if records else None,
        "tuned_rate": round(sum(tuned) / len(records), 4) if records else None,
        "discordant_base_only": b,
        "discordant_tuned_only": c,
        "mcnemar_exact_p": mcnemar_exact(b, c),
    }
    if name == "refusal_hard":
        summary["by_scene"] = {}
        for scene in sorted({r["scene"] for r in records}):
            rows = [r for r in records if r["scene"] == scene]
            summary["by_scene"][scene] = {
                "n": len(rows),
                "base_pass": sum(r["models"]["base"]["pass"] for r in rows),
                "tuned_pass": sum(r["models"]["tuned"]["pass"] for r in rows),
            }
    return {"summary": summary, "records": records}


def main():
    holdout = read_jsonl(ROOT / "training" / "data" / "eval_holdout.jsonl")
    injection = [s for s in holdout if s.get("kind") == "injection"]
    refusal = read_jsonl(ROOT / "training" / "data" / "eval_refusal_hard.jsonl")

    result = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "options": OPTIONS,
        "models": MODELS,
        "groups": {},
    }

    print(f"G2 injection: {len(injection)} samples", flush=True)
    result["groups"]["injection"] = run_group(
        "injection", injection, score_fn_for("injection")
    )

    print(f"G1 refusal_hard: {len(refusal)} samples", flush=True)
    result["groups"]["refusal_hard"] = run_group(
        "refusal_hard", refusal, score_fn_for("refusal_hard")
    )

    out_path = EVID / "rerun_g1_g2_20261107.json"
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("WROTE", out_path, flush=True)
    print(json.dumps({k: v["summary"] for k, v in result["groups"].items()},
                     ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
