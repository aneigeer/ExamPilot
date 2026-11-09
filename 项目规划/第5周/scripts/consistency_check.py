"""推理一致性抽检：F16 微调 vs Q4_0 微调（以及可选的其他两两对比）。

职责
- 生成固定抽样清单（五类各 N 条，seed 固定），清单含完整 messages，跑的时候不再读数据文件；
- 串行调用 Ollama `/api/chat`（temperature=0），保存原始响应；
- 计算三层一致性：L1 逐字、L2 语义等价、L3 行为指标，并出 JSON + Markdown 报告。

用法
    # 1) 生成清单（只读 training/data，不写数据文件）
    python 项目规划/第5周/scripts/consistency_check.py --make-manifest ^
        --manifest 项目规划/第5周/evidence/sample20.json

    # 2) 干跑（不发请求，只校验清单与参数）
    python 项目规划/第5周/scripts/consistency_check.py ^
        --model-a exam-3b-v1-f16 --model-b exam-3b-v1-q4 \
        --manifest 项目规划/第5周/evidence/sample20.json ^
        --out-dir 项目规划/第5周/evidence/run_f16_vs_q4 --dry-run

    # 3) 正式跑（F16 导入完成后）
    python 项目规划/第5周/scripts/consistency_check.py ^
        --model-a exam-3b-v1-f16 --model-b exam-3b-v1-q4 ^
        --manifest 项目规划/第5周/evidence/sample20.json ^
        --out-dir 项目规划/第5周/evidence/run_f16_vs_q4 ^
        --file-a C:/models/exam-3b-v1-f16.gguf --file-b C:/models/exam-3b-v1-q4_0.gguf
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Tuple

import httpx

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "training"))

DATA = ROOT / "training" / "data"
DEFAULT_HOLDOUT = DATA / "eval_holdout.jsonl"
DEFAULT_PLANNER = DATA / "eval_planner.jsonl"
DEFAULT_ENDPOINT = "http://127.0.0.1:11434"

DECODE = {
    "temperature": 0,
    "top_p": 1,
    "num_predict": 200,
    "num_ctx": 4096,
    "seed": 7,
}


# ---------------------------------------------------------------- 工具
def load_jsonl(path: Path) -> List[Dict[str, Any]]:
    return [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def normalize(text: str) -> str:
    """去标点/空白 + NFKC，用于 L2 语义等价判定。"""
    flat = unicodedata.normalize("NFKC", text or "")
    return re.sub(r"[\s,，。、；;：:！!？?“”\"'（）()\[\]【】\-—_…·]+", "", flat)


def citations(text: str) -> List[int]:
    return [int(m) for m in re.findall(r"\[(\d{1,2})\]", text or "")]


def safe_name(name: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "_", name)


# ---------------------------------------------------------------- 生成清单
def build_manifest(holdout: Path, planner: Path, out: Path, per_kind: int, seed: int,
                   with_timestamp: bool = False) -> Dict[str, Any]:
    rows: List[Dict[str, Any]] = []
    for path in (holdout, planner):
        if path.exists():
            for r in load_jsonl(path):
                rows.append({**r, "_source": path.name})

    by_kind: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for i, r in enumerate(rows):
        by_kind[r.get("kind", "")].append({**r, "_idx": i})

    kinds = sorted(by_kind)
    rng = random.Random(seed)
    items: List[Dict[str, Any]] = []
    dist: Dict[str, int] = {}
    for kind in kinds:
        pool = sorted(by_kind[kind], key=lambda x: (str((x.get("meta") or {}).get("qid")), x["_idx"]))
        take = min(per_kind, len(pool))
        picked = rng.sample(pool, take)
        picked.sort(key=lambda x: (str((x.get("meta") or {}).get("qid")), x["_idx"]))
        dist[kind] = take
        for n, r in enumerate(picked):
            msgs = r["messages"]
            system = next((m["content"] for m in msgs if m["role"] == "system"), "")
            user = next((m["content"] for m in msgs if m["role"] == "user"), "")
            items.append({
                "id": f"{kind}-{n:02d}-{(r.get('meta') or {}).get('qid', 'na')}",
                "kind": kind,
                "qid": (r.get("meta") or {}).get("qid"),
                "chunk_id": (r.get("meta") or {}).get("chunk_id"),
                "source": r["_source"],
                "system": system,
                "user": user,
                "gold_assistant": msgs[-1]["content"],
                "meta": r.get("meta") or {},
            })

    manifest = {
        "seed": seed,
        "per_kind": per_kind,
        "dist": dist,
        "total": len(items),
        "sources": {
            holdout.name: {"rows": len(load_jsonl(holdout)), "sha256": sha256_file(holdout)} if holdout.exists() else {},
            planner.name: {"rows": len(load_jsonl(planner)), "sha256": sha256_file(planner)} if planner.exists() else {},
        },
        "decode": DECODE,
        "items": items,
    }
    if with_timestamp:                       # 默认不写时间戳，保证清单逐字节可复现
        manifest["created_at"] = datetime.now().isoformat(timespec="seconds")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


# ---------------------------------------------------------------- 调用
def chat(endpoint: str, model: str, system: str, user: str, timeout: float) -> Tuple[str, str]:
    """返回 (输出, 错误)。串行调用，失败由调用方重试。"""
    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": user})
    payload = {"model": model, "messages": messages, "stream": False, "options": DECODE}
    try:
        resp = httpx.post(f"{endpoint}/api/chat", json=payload, timeout=timeout)
        resp.raise_for_status()
        return resp.json().get("message", {}).get("content", ""), ""
    except Exception as exc:                             # 网络/超时/HTTP 错误
        return "", f"{type(exc).__name__}: {str(exc)[:160]}"


def run_side(endpoint: str, model: str, items: List[Dict[str, Any]], out_dir: Path,
             timeout: float, retries: int) -> Dict[str, Any]:
    side = out_dir / safe_name(model)
    side.mkdir(parents=True, exist_ok=True)
    outputs: Dict[str, str] = {}
    failures: List[Dict[str, Any]] = []
    for i, item in enumerate(items, 1):
        out, err = "", ""
        for attempt in range(retries + 1):
            out, err = chat(endpoint, model, item["system"], item["user"], timeout)
            if not err:
                break
            time.sleep(2 * (attempt + 1))
        outputs[item["id"]] = out
        (side / f"{safe_name(item['id'])}.txt").write_text(out, encoding="utf-8")
        if err:
            failures.append({"id": item["id"], "error": err})
        print(f"  [{model}] {i}/{len(items)} {item['id']} {'FAIL' if err else 'ok'}", flush=True)
    (out_dir / f"{safe_name(model)}.raw.jsonl").write_text(
        "\n".join(json.dumps({"id": k, "output": v}, ensure_ascii=False) for k, v in outputs.items()),
        encoding="utf-8",
    )
    return {"outputs": outputs, "failures": failures}


# ---------------------------------------------------------------- 三层判据
def l1_equal(a: str, b: str) -> bool:
    return a.strip() == b.strip()


def l2_equivalent(a: str, b: str, threshold: float = 0.9) -> bool:
    """语义等价：完全同文（去标点）或（引用序列一致 且 归一化相似度 ≥ threshold）。"""
    from difflib import SequenceMatcher

    na, nb = normalize(a), normalize(b)
    if na and na == nb:
        return True
    if citations(a) != citations(b):
        return False
    if not na or not nb:
        return False
    return SequenceMatcher(None, na, nb).ratio() >= threshold


def behaviour_metrics(items: List[Dict[str, Any]], outputs: Dict[str, str]) -> Dict[str, Dict[str, int]]:
    """复用 eval_v2 判分，输出「通过数/总数」而不是百分比（20 条样本太小，百分比会误导）。"""
    import eval_v2 as ev

    buckets: Dict[str, Dict[str, int]] = defaultdict(lambda: {"pass": 0, "n": 0})
    for item in items:
        sample = {
            "kind": item["kind"],
            "messages": [{"role": "system", "content": item["system"]},
                         {"role": "user", "content": item["user"]},
                         {"role": "assistant", "content": item["gold_assistant"]}],
            "meta": item["meta"],
        }
        out = outputs.get(item["id"], "")
        res = ev.score_sample(sample, out, "anchor")
        for key in ("mcq_acc", "cite_rate", "refusal_ok", "planner_ok", "injection_ok"):
            if key in res["checks"]:
                buckets[key]["n"] += 1
                buckets[key]["pass"] += int(bool(res["checks"][key]))
    return dict(buckets)


# ---------------------------------------------------------------- 报告
def build_report(manifest: Dict[str, Any], a_model: str, b_model: str,
                 side_a: Dict[str, Any], side_b: Dict[str, Any],
                 meta_info: Dict[str, Any]) -> Dict[str, Any]:
    items = manifest["items"]
    outs_a, outs_b = side_a["outputs"], side_b["outputs"]
    l1 = sum(1 for it in items if l1_equal(outs_a.get(it["id"], ""), outs_b.get(it["id"], "")))
    l2 = sum(1 for it in items if l2_equivalent(outs_a.get(it["id"], ""), outs_b.get(it["id"], "")))
    by_kind: Dict[str, Dict[str, int]] = defaultdict(lambda: {"n": 0, "l1": 0, "l2": 0})
    diffs: List[Dict[str, Any]] = []
    for it in items:
        oa, ob = outs_a.get(it["id"], ""), outs_b.get(it["id"], "")
        e1, e2 = l1_equal(oa, ob), l2_equivalent(oa, ob)
        by_kind[it["kind"]]["n"] += 1
        by_kind[it["kind"]]["l1"] += int(e1)
        by_kind[it["kind"]]["l2"] += int(e2)
        if not e2:
            diffs.append({"id": it["id"], "kind": it["kind"], "a": oa[:160], "b": ob[:160]})

    ba = behaviour_metrics(items, outs_a)
    bb = behaviour_metrics(items, outs_b)
    l3: Dict[str, Any] = {}
    for key in sorted(set(ba) | set(bb)):
        x, y = ba.get(key, {"pass": 0, "n": 0}), bb.get(key, {"pass": 0, "n": 0})
        l3[key] = {
            "a": f"{x['pass']}/{x['n']}", "b": f"{y['pass']}/{y['n']}",
            "delta_rows": y["pass"] - x["pass"],
            "delta_pp": round((y["pass"] - x["pass"]) / max(1, x["n"]) * 100, 1),
        }

    n = len(items)
    return {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "models": {"a": a_model, "b": b_model},
        "decode": manifest.get("decode") or DECODE,
        "manifest": {"total": n, "dist": manifest.get("dist"), "seed": manifest.get("seed")},
        "artifacts": meta_info,
        "L1_exact": {"matched": l1, "total": n, "rate": round(l1 / max(1, n) * 100, 1)},
        "L2_equivalent": {"matched": l2, "total": n, "rate": round(l2 / max(1, n) * 100, 1)},
        "L2_by_kind": {k: {"l1": f"{v['l1']}/{v['n']}", "l2": f"{v['l2']}/{v['n']}"} for k, v in sorted(by_kind.items())},
        "L3_behaviour": l3,
        "failures": {"a": side_a["failures"], "b": side_b["failures"]},
        "diffs_top": diffs[:10],
        "diff_total": len(diffs),
    }


def render_md(rep: Dict[str, Any]) -> str:
    lines = [
        "# 推理一致性抽检报告", "",
        f"- 时间：{rep['generated_at']}",
        f"- 模型 A：`{rep['models']['a']}` / 模型 B：`{rep['models']['b']}`",
        f"- 解码：{json.dumps(rep['decode'], ensure_ascii=False)}",
        f"- 样本：{rep['manifest']['total']} 条，分布 {rep['manifest']['dist']}，seed={rep['manifest']['seed']}",
        "",
    ]
    art = rep.get("artifacts") or {}
    if art.get("models"):
        lines += ["## 产物指纹（证明评测的就是交付的）", "", "| 侧 | 模型 | digest | size | 文件 sha256 |", "|---|---|---|---|---|"]
        for side, info in art["models"].items():
            lines.append(f"| {side} | `{info.get('name','')}` | `{str(info.get('digest',''))[:16]}` | "
                         f"{info.get('size', 0) / 1e9:.2f} GB | `{str(info.get('file_sha256',''))[:16]}` |")
        lines.append("")

    lines += [
        "## 三层一致性", "",
        "| 层级 | 判据 | 结果 | 阈值 | 结论 |",
        "|---|---|---|---|---|",
        f"| L1 | 逐字完全相同 | {rep['L1_exact']['matched']}/{rep['L1_exact']['total']}"
        f"（{rep['L1_exact']['rate']}%） | ≥30% | {'通过' if rep['L1_exact']['rate'] >= 30 else '未达'} |",
        f"| L2 | 去标点后相同，或引用序列一致且相似度≥0.9 | {rep['L2_equivalent']['matched']}/{rep['L2_equivalent']['total']}"
        f"（{rep['L2_equivalent']['rate']}%） | ≥90% | {'通过' if rep['L2_equivalent']['rate'] >= 90 else '未达'} |",
        "",
        "### L2 分 kind",
        "", "| kind | L1 | L2 |", "|---|---|---|",
    ]
    for kind, v in rep["L2_by_kind"].items():
        lines.append(f"| {kind} | {v['l1']} | {v['l2']} |")

    lines += ["", "### L3 行为指标（通过数/总数）", "",
              "| 指标 | A | B | 差值（行） | 差值（pp） | 判定（≤5pp） |",
              "|---|---|---|---|---|---|"]
    for key, v in rep["L3_behaviour"].items():
        ok = abs(v["delta_pp"]) <= 5
        lines.append(f"| {key} | {v['a']} | {v['b']} | {v['delta_rows']:+d} | {v['delta_pp']:+.1f} | "
                     f"{'通过' if ok else '未达'} |")

    lines += [
        "",
        "> ⚠ L3 在本清单上只有每类 4 条（步长 25pp），**5pp 阈值实际不可分辨**；"
        "本表只作方向性参考，权威结论以全量 250 条评测为准。",
        "",
        f"## 不一致样本（前 10 / 共 {rep['diff_total']}）", "",
    ]
    if not rep["diffs_top"]:
        lines.append("无：20 条全部语义等价。")
    else:
        lines += ["| id | kind | A（截断 160） | B（截断 160） |", "|---|---|---|---|"]
        for d in rep["diffs_top"]:
            a = d["a"].replace("|", "\\|").replace("\n", " ")
            b = d["b"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {d['id']} | {d['kind']} | {a} | {b} |")

    fa = len(rep["failures"]["a"]) + len(rep["failures"]["b"])
    lines += ["", f"## 调用失败：{fa} 条", ""]
    for side in ("a", "b"):
        for f in rep["failures"][side]:
            lines.append(f"- [{side}] {f['id']}: {f['error']}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 主流程
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--make-manifest", action="store_true", help="只生成抽样清单")
    ap.add_argument("--manifest", default=str(ROOT / "项目规划" / "第5周" / "evidence" / "sample20.json"))
    ap.add_argument("--holdout", default=str(DEFAULT_HOLDOUT))
    ap.add_argument("--planner", default=str(DEFAULT_PLANNER))
    ap.add_argument("--per-kind", type=int, default=4)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--with-timestamp", action="store_true", help="清单里写入生成时间（会破坏逐字节复现）")

    ap.add_argument("--model-a", default="")
    ap.add_argument("--model-b", default="")
    ap.add_argument("--out-dir", default=str(ROOT / "项目规划" / "第5周" / "evidence" / "run"))
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--retries", type=int, default=1)
    ap.add_argument("--file-a", default="", help="A 侧 GGUF 路径（用于记录 sha256）")
    ap.add_argument("--file-b", default="", help="B 侧 GGUF 路径（用于记录 sha256）")
    ap.add_argument("--dry-run", action="store_true", help="不发请求，只校验清单与参数")
    args = ap.parse_args()

    if args.make_manifest:
        m = build_manifest(Path(args.holdout), Path(args.planner), Path(args.manifest),
                           args.per_kind, args.seed, args.with_timestamp)
        print(json.dumps({"manifest": args.manifest, "total": m["total"], "dist": m["dist"],
                          "sources": m["sources"]}, ensure_ascii=False, indent=2))
        return

    if not args.model_a or not args.model_b:
        raise SystemExit("必须给 --model-a 与 --model-b（或使用 --make-manifest）")
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    items = manifest["items"]
    out_dir = Path(args.out_dir)

    if args.dry_run:
        print(f"[dry-run] manifest={args.manifest} items={len(items)} dist={manifest.get('dist')}")
        print(f"[dry-run] A={args.model_a} B={args.model_b} endpoint={args.endpoint}")
        print(f"[dry-run] 计划调用 {len(items) * 2} 次（串行），timeout={args.timeout}s, retries={args.retries}")
        print(f"[dry-run] 输出目录 {out_dir}（不会创建）")
        for side, f in (("A", args.file_a), ("B", args.file_b)):
            if f:
                p = Path(f)
                print(f"[dry-run] {side} 文件 {f} 存在={p.exists()} "
                      f"sha256={sha256_file(p)[:16] if p.exists() else '-'}")
        return

    out_dir.mkdir(parents=True, exist_ok=True)
    info: Dict[str, Any] = {"models": {}}

    # 指纹：优先本地 GGUF 文件，其次 /api/tags 里的 digest
    try:
        tags = httpx.get(f"{args.endpoint}/api/tags", timeout=10).json().get("models", [])
    except Exception:
        tags = []
    for side, name, fpath in (("a", args.model_a, args.file_a), ("b", args.model_b, args.file_b)):
        entry = next((t for t in tags if t.get("name") == name or t.get("model") == name), {})
        info["models"][side] = {
            "name": name,
            "digest": entry.get("digest", ""),
            "size": entry.get("size", 0),
            "file_sha256": sha256_file(Path(fpath)) if fpath and Path(fpath).exists() else "",
        }

    print(f"[run] A={args.model_a} B={args.model_b} items={len(items)}", flush=True)
    side_a = run_side(args.endpoint, args.model_a, items, out_dir, args.timeout, args.retries)
    side_b = run_side(args.endpoint, args.model_b, items, out_dir, args.timeout, args.retries)

    rep = build_report(manifest, args.model_a, args.model_b, side_a, side_b, info)
    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    jp = out_dir / f"consistency_report_{stamp}.json"
    mp = out_dir / f"consistency_report_{stamp}.md"
    jp.write_text(json.dumps(rep, ensure_ascii=False, indent=2), encoding="utf-8")
    mp.write_text(render_md(rep), encoding="utf-8")
    print(f"\nL1 {rep['L1_exact']['matched']}/{rep['L1_exact']['total']} | "
          f"L2 {rep['L2_equivalent']['matched']}/{rep['L2_equivalent']['total']} | "
          f"失败 {len(rep['failures']['a'])}+{len(rep['failures']['b'])}")
    print(f"[json] {jp}\n[md] {mp}")


if __name__ == "__main__":
    main()
