"""引用三级审计：引用存在 → 引用相关 → 引用支持。

对象：`training/data/eval_holdout.jsonl` 中 kind=grounded 的 50 条（每题 1 条资料 + 1 个问题）。
对每个模型跑一遍（默认两个：微调 / 基线），逐条判定三层，输出 json + md。

用法（先干跑，确认调用量；正式跑由 team-lead 执行）：
    python 项目规划/第6周/scripts/audit_citation.py --dry-run
    python 项目规划/第6周/scripts/audit_citation.py ^
        --models tuned=exam-prep-3b-v1-q8 base=qwen2.5-3b-instruct-q8_0-baseline ^
        --out-dir 项目规划/第6周/evidence/citation

判据（阈值均以 gold 校准，见文末「阈值依据」）：
- L1 存在：答案含 `[n]`，且 n ∈ 资料编号集合
- L2 相关：把被引用的 chunk 拼起来，3-gram 包含率 ≥ 0.20
- L3 支持：结论句与 chunk 的最长公共子串 ≥ 20 字，或结论句 3-gram 包含率 ≥ 0.35
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import Counter
from datetime import datetime
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Dict, List, Tuple

import httpx

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data" / "eval_holdout.jsonl"
DEFAULT_MODELS = ["tuned=exam-prep-3b-v1-q8", "base=qwen2.5-3b-instruct-q8_0-baseline"]
DECODE = {"temperature": 0, "top_p": 1, "num_predict": 400, "num_ctx": 4096, "seed": 7}

# ---- 阈值（见文末说明）
RELATED_CONTAINMENT = 0.20      # L2：3-gram 包含率
SUPPORT_LCS = 20                # L3：最长公共子串（字）
SUPPORT_CONTAINMENT = 0.35      # L3 备选：结论句 3-gram 包含率
VERBATIM_COPY = 0.95            # 「逐字复读」判定：答案 3-gram 有 ≥95% 来自资料
ARTIFACT_PAT = re.compile(r"\d{1,3}根据|··+|\.{3,}|万吨亿吨")   # 语料转写噪声/残缺数字

PUNCT = r"[\s，。、；：！？（）()\[\]【】\-—_…·\"'“”]+"


def normalize(text: str) -> str:
    return re.sub(PUNCT, "", text or "")


def grams(text: str, k: int) -> set:
    t = normalize(text)
    return {t[i:i + k] for i in range(max(0, len(t) - k + 1))}


def containment(part: str, whole: str, k: int = 3) -> float:
    gp = grams(part, k)
    if not gp:
        return 0.0
    return len(gp & grams(whole, k)) / len(gp)


def lcs_len(a: str, b: str) -> int:
    return SequenceMatcher(None, normalize(a), normalize(b)).find_longest_match(
        0, len(normalize(a)), 0, len(normalize(b))
    ).size


def parse_materials(user: str) -> Dict[int, str]:
    """把 user 里 `[1] ... [2] ...` 的资料块拆出来（到【问题】为止）。"""
    m = re.search(r"【资料】(.*?)【问题】", user, re.S)
    body = m.group(1) if m else user
    marks = [(int(x.group(1)), x.start()) for x in re.finditer(r"\[(\d+)\]", body)]
    out: Dict[int, str] = {}
    for i, (num, pos) in enumerate(marks):
        end = marks[i + 1][1] if i + 1 < len(marks) else len(body)
        out[num] = body[pos + len(f"[{num}]"):end].strip()
    return out


def conclusion_of(answer: str) -> str:
    """取结论句：优先「依据：」之后，其次「答案：」之后，最后整段。"""
    if "依据：" in answer:
        return answer.split("依据：", 1)[1]
    if "答案：" in answer:
        return answer.split("答案：", 1)[1]
    return answer


def judge(answer: str, materials: Dict[int, str], meta_answer_text: str = "") -> Dict[str, Any]:
    cited = [int(x) for x in re.findall(r"\[(\d+)\]", answer)]
    valid = sorted({n for n in cited if n in materials})
    out: Dict[str, Any] = {"citations": cited, "valid_citations": valid}

    # L1 存在
    out["exists"] = bool(cited) and bool(valid) and len(valid) == len(set(cited))
    if cited and not valid:
        out["note"] = "引用编号越界"

    # L2 相关
    chunk = "\n".join(materials[n] for n in valid) if valid else ""
    out["related_containment"] = round(containment(answer, chunk, 3), 3) if chunk else 0.0
    out["related"] = bool(chunk) and out["related_containment"] >= RELATED_CONTAINMENT

    # L3 支持（近似）
    concl = conclusion_of(answer)
    out["support_lcs"] = lcs_len(concl, chunk) if chunk else 0
    out["support_containment"] = round(containment(concl, chunk, 3), 3) if chunk else 0.0
    out["supported"] = bool(chunk) and (
        out["support_lcs"] >= SUPPORT_LCS or out["support_containment"] >= SUPPORT_CONTAINMENT
    )

    # 复读标记：答案几乎全部来自资料 → L2/L3 天然成立，判据失去区分力
    out["verbatim_copy"] = out["related_containment"] >= VERBATIM_COPY
    out["strict_supported"] = bool(out["supported"]) and not out["verbatim_copy"]

    # 结论项证据：选项原文（≥4 字）是否出现在被引资料里（弱信号，gold 基线 8/50）
    ans_text = normalize((meta_answer_text or ""))
    out["answer_item_in_chunk"] = bool(ans_text) and len(ans_text) >= 4 and ans_text in normalize(chunk)

    # 语料噪声：残缺数字/转写噪声（数据缺陷，不是模型问题）
    out["artifact"] = bool(ARTIFACT_PAT.search(answer or ""))
    return out


def chat(endpoint: str, model: str, system: str, user: str, timeout: float) -> Tuple[str, str]:
    msgs = []
    if system:
        msgs.append({"role": "system", "content": system})
    msgs.append({"role": "user", "content": user})
    try:
        r = httpx.post(f"{endpoint}/api/chat",
                       json={"model": model, "messages": msgs, "stream": False, "options": DECODE},
                       timeout=timeout)
        r.raise_for_status()
        return r.json().get("message", {}).get("content", ""), ""
    except Exception as exc:
        return "", f"{type(exc).__name__}: {str(exc)[:160]}"


def load_grounded(path: Path, limit: int = 0) -> List[Dict[str, Any]]:
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    g = [r for r in rows if r.get("kind") == "grounded"]
    return g[:limit] if limit else g


def run_model(endpoint: str, name: str, model: str, items: List[Dict[str, Any]],
              out_dir: Path, timeout: float, retries: int) -> Dict[str, Any]:
    side = out_dir / re.sub(r"[^0-9A-Za-z._-]+", "_", name)
    side.mkdir(parents=True, exist_ok=True)
    records, failures = [], []
    for i, row in enumerate(items, 1):
        user = next(m["content"] for m in row["messages"] if m["role"] == "user")
        system = next((m["content"] for m in row["messages"] if m["role"] == "system"), "")
        ans, err = "", ""
        for attempt in range(retries + 1):
            ans, err = chat(endpoint, model, system, user, timeout)
            if not err:
                break
            time.sleep(2 * (attempt + 1))
        mats = parse_materials(user)
        verdict = judge(ans, mats, (row.get("meta") or {}).get("answer_text", ""))
        qid = (row.get("meta") or {}).get("qid")
        records.append({"qid": qid, "answer": ans, "gold": row["messages"][-1]["content"],
                        "material_preview": {k: v[:100] for k, v in mats.items()},
                        **verdict, "error": err})
        (side / f"qid_{qid}.txt").write_text(ans, encoding="utf-8")
        if err:
            failures.append({"qid": qid, "error": err})
        print(f"  [{name}] {i}/{len(items)} qid={qid} {'FAIL' if err else 'ok'}", flush=True)
    (out_dir / f"{side.name}.jsonl").write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in records), encoding="utf-8")
    return {"name": name, "model": model, "records": records, "failures": failures}


def summarize(records: List[Dict[str, Any]]) -> Dict[str, Any]:
    n = len(records)
    ok = [r for r in records if not r["error"]]
    m = len(ok)
    cnt = {
        "exists": sum(r["exists"] for r in ok),
        "related": sum(r["related"] for r in ok),
        "supported": sum(r["supported"] for r in ok),
    }
    # 假引用：有引用但（不相关 或 不支持）
    fake = [r for r in ok if r["exists"] and (not r["related"] or not r["supported"])]
    extra = {
        "strict_supported_qid": len({r.get("qid") for r in ok if r.get("strict_supported")}),
        "verbatim": sum(1 for r in ok if r.get("verbatim_copy")),
        "strict_supported": sum(1 for r in ok if r.get("strict_supported")),
        "answer_item_hits": sum(1 for r in ok if r.get("answer_item_in_chunk")),
        "artifact": sum(1 for r in ok if r.get("artifact")),
    }
    return {"n": n, "answered": m, "counts": cnt,
            "rates": {k: round(v / max(1, m) * 100, 1) for k, v in cnt.items()},
            "fake_refs": len(fake),
            "extra": extra,
            "failures": n - m}


def render_md(result: Dict[str, Any]) -> str:
    lines = ["# 引用三级审计报告", "",
             f"- 时间：{result['generated_at']}",
             f"- 数据：`{result['data']}`（grounded {result['n']} 条）",
             f"- 解码：{json.dumps(DECODE, ensure_ascii=False)}", "",
             "## 3×2 计数矩阵（存在 / 相关 / 支持）", "",
             "| 模型 | 回答数 | 引用存在 | 引用相关 | 引用支持 | 逐字复读 | 严格支持(非复读) | 选项原文命中 | 语料残缺 | 疑似假引用 | 调用失败 |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, s in result["models"].items():
        r = s["summary"]
        e = r.get("extra", {})
        lines.append(f"| {name} | {r['answered']}/{r['n']} | {r['counts']['exists']}（{r['rates']['exists']}%） | "
                     f"{r['counts']['related']}（{r['rates']['related']}%） | "
                     f"{r['counts']['supported']}（{r['rates']['supported']}%） | "
                     f"{e.get('verbatim', 0)} | {e.get('strict_supported', 0)} | "
                     f"{e.get('answer_item_hits', 0)} | {e.get('artifact', 0)} | {r['fake_refs']} | {r['failures']} |")
    lines += ["", "> 「支持」为**近似判据**（LCS≥20 或结论句包含率≥0.35）；「逐字复读」= 答案 ≥95% 的 3-gram 来自资料。",
              "> 本评测集 grounded 的 gold 本身是抽取式（gold 与资料的 3-gram 包含率中位 0.955），"
              "因此**照抄资料的模型在 L2/L3 上必然满分**——判据能抓「乱引」（base 的存在→相关→支持衰减即为例证），"
              "抓不到「抄错/抄残」。请结合「严格支持」「选项原文命中」「语料残缺」三列与人工抽读判读。",
              "> 注：`strict_supported` 是按**行**计；同一 qid 可能有多行（mcq/grounded 共用题），"
              "json 的 `extra.strict_supported_qid` 是**按 qid 去重**后的独立支持数，两者不要混用。",
              "> **结论口径**：若「逐字复读率」高（>50%）而「严格支持」低，则「假引用 0」是**判据抓不到**，不是真空；"
              "若复读率低且严格支持接近引用支持，才可写「未发现假引用」。", ""]

    for name, s in result["models"].items():
        lines += [f"## {name} 明细", "",
                  "| qid | 引用 | 存在 | 相关(3gram) | 支持(LCS) | 答案前 60 字 |", "|---|---|---|---|---|---|"]
        for r in s["records"]:
            a = r["answer"].replace("|", "\\|").replace("\n", " ")[:60]
            cites = ",".join(str(x) for x in r["citations"]) or "-"
            if r["error"]:
                lines.append(f"| {r['qid']} | - | - | - | - | 调用失败：{r['error'][:40]} |")
                continue
            lines.append(f"| {r['qid']} | {cites} | {'Y' if r['exists'] else 'N'} | "
                         f"{r['related_containment']:.2f} | {r['support_lcs']} | {a} |")
        fakes = [r for r in s["records"] if not r["error"] and r["exists"] and (not r["related"] or not r["supported"])]
        lines += ["", f"### 疑似假引用（{len(fakes)} 条）", ""]
        if not fakes:
            lines.append("无。")
        else:
            lines += ["| qid | 引用 | 相关 | 支持 | 答案 |", "|---|---|---|---|---|"]
            for r in fakes:
                lines.append(f"| {r['qid']} | {','.join(map(str, r['valid_citations'])) or '-'} | "
                             f"{'Y' if r['related'] else 'N'} | {'Y' if r['supported'] else 'N'} | "
                             f"{r['answer'].replace('|','\\|').replace(chr(10),' ')[:80]} |")
        lines.append("")
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=DEFAULT_MODELS,
                    help="name=ollama_model，可多个，如 tuned=exam-prep-3b-v1-q8")
    ap.add_argument("--data", default=str(DATA))
    ap.add_argument("--out-dir", default=str(ROOT / "项目规划" / "第6周" / "evidence" / "citation"))
    ap.add_argument("--endpoint", default="http://127.0.0.1:11434")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--retries", type=int, default=1)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 条（调试用）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    items = load_grounded(Path(args.data), args.limit)
    pairs = []
    for spec in args.models:
        name, _, model = spec.partition("=")
        pairs.append((name or model, model or name))

    if args.dry_run:
        print(f"[dry-run] grounded {len(items)} 条；模型 {len(pairs)} 个 → 计划调用 {len(items) * len(pairs)} 次")
        print(f"[dry-run] 串行、timeout={args.timeout}s、retries={args.retries}、decode={DECODE}")
        print(f"[dry-run] 输出目录 {args.out_dir}（不会创建）")
        for name, model in pairs:
            print(f"[dry-run]   {name} -> {model}")
        return

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    result: Dict[str, Any] = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "data": args.data,
        "n": len(items),
        "decode": DECODE,
        "thresholds": {"related_containment": RELATED_CONTAINMENT,
                       "support_lcs": SUPPORT_LCS,
                       "support_containment": SUPPORT_CONTAINMENT},
        "models": {},
    }
    for name, model in pairs:
        print(f"[run] {name} = {model}", flush=True)
        side = run_model(args.endpoint, name, model, items, out_dir, args.timeout, args.retries)
        side["summary"] = summarize(side["records"])
        result["models"][name] = side

    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    jp = out_dir / f"citation_audit_{stamp}.json"
    mp = out_dir / f"citation_audit_{stamp}.md"
    jp.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    mp.write_text(render_md(result), encoding="utf-8")
    for name, s in result["models"].items():
        r = s["summary"]
        print(f"[{name}] 存在 {r['counts']['exists']}/{r['answered']} 相关 {r['counts']['related']} "
              f"支持 {r['counts']['supported']} 假引用 {r['fake_refs']}")
    print(f"[json] {jp}\n[md] {mp}")


if __name__ == "__main__":
    main()
