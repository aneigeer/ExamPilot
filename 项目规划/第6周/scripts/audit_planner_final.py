"""planner final 抽检：schema + 内容相关性（对应第 2 周记录的 F2 缺陷）。

对象：`training/data/eval_planner.jsonl` 中 gold 为 `action=final` 的 16 条。
对每个模型跑一遍，判定：
  1. schema：合法 JSON、字段齐全、类型正确、action=final、answer 非空；
  2. 相关性：answer 是否真的与该题资料/题干相关（三条信号任一满足）；
  3. F2 指纹：answer 与「其它题的资料」相似度显著高于「本题资料」→ 判定为取自无关 chunk。

用法：
    python 项目规划/第6周/scripts/audit_planner_final.py --dry-run
    python 项目规划/第6周/scripts/audit_planner_final.py ^
        --models tuned=exam-prep-3b-v1-q8 ^
        --out-dir 项目规划/第6周/evidence/planner_final

阈值依据（用 16 条 gold 校准）：
- gold 的「答案 vs 本题资料」3-gram 包含率：min 0.974 / p50 0.986；
- 「答案 vs 随机其他题资料」：p50 0.000 / max 0.079；
- 因此 material_containment ≥ 0.30 既远离噪声（0.08）又给改写留足余量（gold 0.97）。
- 题干 2-gram 覆盖率不作为主判据（gold 的 min=0，题干多为指令句），只作备选。
- gold_sim（与 gold 答案的相似度）≥ 0.45 作第二备选，覆盖「正确但改写较大」的情况。
"""

from __future__ import annotations

import argparse
import json
import re
import time
from datetime import datetime
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Dict, List, Tuple

import httpx

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data" / "eval_planner.jsonl"
DEFAULT_MODELS = ["tuned=exam-prep-3b-v1-q8"]
DECODE = {"temperature": 0, "top_p": 1, "num_predict": 400, "num_ctx": 4096, "seed": 7}

MATERIAL_CONTAINMENT = 0.30     # 主判据：答案 3-gram 落在本题资料中的比例
GOLD_SIM = 0.45                 # 备选：与 gold 答案的字符相似度
QUESTION_COVERAGE = 0.15        # 仅作参考，不参与判定（见下）
CRITERION_VERSION = f"material>={MATERIAL_CONTAINMENT}|gold_sim>={GOLD_SIM}"
ENTITY_NOTE = "q_cov / entity_coverage 仅作参考：final 是资料复述，天然不复述题干用词"
F2_MARGIN = 0.30                # F2 指纹：其他资料比本题高出的绝对值

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


def parse_materials(user: str) -> Dict[int, str]:
    m = re.search(r"【资料】(.*?)【问题】", user, re.S)
    body = m.group(1) if m else user
    marks = [(int(x.group(1)), x.start()) for x in re.finditer(r"\[(\d+)\]", body)]
    out: Dict[int, str] = {}
    for i, (num, pos) in enumerate(marks):
        end = marks[i + 1][1] if i + 1 < len(marks) else len(body)
        out[num] = body[pos + len(f"[{num}]"):end].strip()
    return out


def parse_answer_json(text: str) -> Tuple[Dict[str, Any] | None, str]:
    """剥 ```json 围栏后解析；返回 (obj, 错误原因)。"""
    s = (text or "").strip()
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\s*", "", s)
        s = re.sub(r"```\s*$", "", s).strip()
    try:
        obj = json.loads(s)
    except Exception:
        return None, "非合法 JSON"
    if not isinstance(obj, dict):
        return None, "JSON 非对象"
    return obj, ""


def schema_check(obj: Dict[str, Any] | None, err: str) -> Dict[str, Any]:
    if obj is None:
        return {"ok": False, "reason": err}
    problems = []
    if not isinstance(obj.get("thought"), str):
        problems.append("thought 缺失或非字符串")
    if obj.get("action") != "final":
        problems.append(f"action={obj.get('action')!r}（期望 final）")
    ans = obj.get("answer")
    if not isinstance(ans, str) or not ans.strip():
        problems.append("answer 缺失/非字符串/空")
    return {"ok": not problems, "reason": "；".join(problems) or "ok"}


def judge(obj: Dict[str, Any] | None, question: str, material: str,
          gold: str, others: List[str]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"schema_ok": False, "reason": ""}
    sch = schema_check(obj, "" if obj is not None else "非合法 JSON")
    out.update(schema_ok=sch["ok"], reason=sch["reason"])
    if not sch["ok"] or obj is None:
        return out

    ans = obj.get("answer", "")
    out["material_containment"] = round(containment(ans, material, 3), 3)
    out["gold_sim"] = round(SequenceMatcher(None, normalize(ans), normalize(gold)).ratio(), 3)
    out["question_coverage"] = round(containment(question, ans, 2), 3) if question else 0.0

    # 硬判据只有两条：答案必须能在本题资料里找到，或与 gold 足够接近。
    # q_cov 不参与判定——final 形态是资料复述，题干多为指令句，gold 的 q_cov 中位仅 0.068。
    out["relevant"] = (
        out["material_containment"] >= MATERIAL_CONTAINMENT
        or out["gold_sim"] >= GOLD_SIM
    )

    # 题干关键实体（数字 / 引号内专名）覆盖率，仅作参考
    ents = (re.findall(r"\d{2,4}", question or "")
            + re.findall(r"[「“]([^」”]{2,20})[」”]", question or ""))
    if ents:
        out["entity_coverage"] = round(sum(1 for e in ents if normalize(e) in normalize(ans)) / len(ents), 3)
    else:
        out["entity_coverage"] = 0.0

    # F2 指纹：与其他题资料的相似度
    cross = [containment(ans, o, 3) for o in others if o]
    out["cross_material_max"] = round(max(cross), 3) if cross else 0.0
    out["f2_signature"] = (
        out["cross_material_max"] >= MATERIAL_CONTAINMENT
        and out["cross_material_max"] - out["material_containment"] >= F2_MARGIN
    )
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


def rejudge(report_path: Path, data_path: Path) -> None:
    """离线重判：复用报告里的原始输出，按当前判据重算，避免 summary 与 records 打架。"""
    rep = json.loads(report_path.read_text(encoding="utf-8"))
    rows = [json.loads(l) for l in data_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    finals = [r for r in rows
              if (lambda o: isinstance(o, dict) and o.get("action") == "final")(
                  _safe_json(r["messages"][-1]["content"]))]
    by_chunk = {}
    for r in finals:
        cid = (r.get("meta") or {}).get("chunk_id")
        user = next(m["content"] for m in r["messages"] if m["role"] == "user")
        by_chunk[cid] = {"question": (r.get("meta") or {}).get("question", ""),
                         "material": "\n".join(parse_materials(user).values()),
                         "gold": json.loads(r["messages"][-1]["content"]).get("answer", "")}

    out = {"rejudged_at": datetime.now().isoformat(timespec="seconds"),
           "source_report": str(report_path),
           "criterion_version": CRITERION_VERSION,
           "thresholds": {"material_containment": MATERIAL_CONTAINMENT, "gold_sim": GOLD_SIM},
           "models": {}}
    for name, side in rep.get("models", {}).items():
        records = []
        for rec in side.get("records", []):
            cid = rec.get("chunk_id")
            ref = by_chunk.get(cid)
            if not ref:
                continue
            obj, _ = parse_answer_json(rec.get("output", ""))
            others = [v["material"] for k, v in by_chunk.items() if k != cid]
            verdict = judge(obj, ref["question"], ref["material"], ref["gold"], others)
            records.append({"chunk_id": cid, "output": rec.get("output", ""), "parsed": obj, **verdict})
        n = len(records)
        schema_ok = sum(1 for x in records if x["schema_ok"])
        relevant = sum(1 for x in records if x.get("relevant"))
        out["models"][name] = {"records": records, "summary": {
            "n": n, "schema_pass": schema_ok, "schema_rate": round(schema_ok / max(1, n) * 100, 1),
            "relevant_pass": relevant, "relevant_rate": round(relevant / max(1, n) * 100, 1),
            "f2_signature": sum(1 for x in records if x.get("f2_signature")),
            "irrelevant": sum(1 for x in records if x["schema_ok"] and not x.get("relevant"))}}

    stem = report_path.with_suffix("")
    jp = Path(str(stem) + ".rejudged.json")
    mp = Path(str(stem) + ".rejudged.md")
    jp.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# planner final 抽检（离线重判）", "",
             f"- 源报告：`{report_path}`", f"- 时间：{out['rejudged_at']}",
             f"- 判据版本：`{CRITERION_VERSION}`", "",
             "| 模型 | schema | 相关 | F2 指纹 | 无关 |", "|---|---|---|---|---|"]
    for name, s in out["models"].items():
        m = s["summary"]
        lines.append(f"| {name} | {m['schema_pass']}/{m['n']}（{m['schema_rate']}%） | "
                     f"{m['relevant_pass']}/{m['n']}（{m['relevant_rate']}%） | {m['f2_signature']} | {m['irrelevant']} |")
    mp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    for name, s in out["models"].items():
        m = s["summary"]
        print(f"[{name}] schema {m['schema_pass']}/{m['n']} 相关 {m['relevant_pass']}/{m['n']} "
              f"无关 {m['irrelevant']} F2 {m['f2_signature']}")
    print(f"[json] {jp}\n[md] {mp}")


def _safe_json(text: str):
    try:
        return json.loads(text)
    except Exception:
        return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=DEFAULT_MODELS, help="name=ollama_model")
    ap.add_argument("--data", default=str(DATA))
    ap.add_argument("--out-dir", default=str(ROOT / "项目规划" / "第6周" / "evidence" / "planner_final"))
    ap.add_argument("--endpoint", default="http://127.0.0.1:11434")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--retries", type=int, default=1)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rejudge", default="", help="用已有报告的原始输出离线重判（不发请求），写出 xxx.rejudged.json/md")
    args = ap.parse_args()

    if args.rejudge:
        rejudge(Path(args.rejudge), Path(args.data))
        return

    rows = [json.loads(l) for l in Path(args.data).read_text(encoding="utf-8").splitlines() if l.strip()]
    finals = []
    for r in rows:
        try:
            obj = json.loads(r["messages"][-1]["content"])
        except Exception:
            continue
        if isinstance(obj, dict) and obj.get("action") == "final":
            finals.append(r)

    pairs = []
    for spec in args.models:
        name, _, model = spec.partition("=")
        pairs.append((name or model, model or name))

    if args.dry_run:
        print(f"[dry-run] final 样本 {len(finals)} 条；模型 {len(pairs)} 个 → 计划调用 {len(finals) * len(pairs)} 次")
        print(f"[dry-run] 串行、timeout={args.timeout}s、retries={args.retries}、decode={DECODE}")
        print(f"[dry-run] 硬判据 material≥{MATERIAL_CONTAINMENT} 或 gold_sim≥{GOLD_SIM}；"
              f"q_cov/entity 仅参考（{ENTITY_NOTE}）")
        print(f"[dry-run] 输出目录 {args.out_dir}（不会创建）")
        for name, model in pairs:
            print(f"[dry-run]   {name} -> {model}")
        return

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    materials = [ "\n".join(parse_materials(next(m["content"] for m in r["messages"] if m["role"] == "user")).values())
                  for r in finals ]

    result: Dict[str, Any] = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "data": args.data,
        "n_final": len(finals),
        "decode": DECODE,
        "criterion_version": CRITERION_VERSION,
        "thresholds": {"material_containment": MATERIAL_CONTAINMENT, "gold_sim": GOLD_SIM,
                       "question_coverage": QUESTION_COVERAGE, "f2_margin": F2_MARGIN},
        "models": {},
    }

    for name, model in pairs:
        print(f"[run] {name} = {model}", flush=True)
        side_dir = out_dir / re.sub(r"[^0-9A-Za-z._-]+", "_", name)
        side_dir.mkdir(parents=True, exist_ok=True)
        records, failures = [], []
        for i, r in enumerate(finals, 1):
            user = next(m["content"] for m in r["messages"] if m["role"] == "user")
            system = next((m["content"] for m in r["messages"] if m["role"] == "system"), "")
            gold_obj = json.loads(r["messages"][-1]["content"])
            mats = parse_materials(user)
            other_mats = [m for j, m in enumerate(materials) if j != finals.index(r)]
            out, err = "", ""
            for attempt in range(args.retries + 1):
                out, err = chat(args.endpoint, model, system, user, args.timeout)
                if not err:
                    break
                time.sleep(2 * (attempt + 1))
            obj, perr = parse_answer_json(out) if not err else (None, "调用失败")
            verdict = judge(obj, (r.get("meta") or {}).get("question", ""),
                            "\n".join(mats.values()), gold_obj.get("answer", ""), other_mats)
            rec = {"chunk_id": (r.get("meta") or {}).get("chunk_id"),
                   "question": (r.get("meta") or {}).get("question"),
                   "output": out, "parsed": obj, "error": err, **verdict}
            records.append(rec)
            (side_dir / f"chunk_{rec['chunk_id']}.txt").write_text(out, encoding="utf-8")
            if err:
                failures.append({"chunk_id": rec["chunk_id"], "error": err})
            print(f"  [{name}] {i}/{len(finals)} chunk={rec['chunk_id']} "
                  f"{'FAIL' if err else ('ok' if verdict['schema_ok'] else 'SCHEMA')}", flush=True)

        n = len(records)
        schema_ok = sum(1 for x in records if x["schema_ok"])
        relevant = sum(1 for x in records if x.get("relevant"))
        f2 = [x for x in records if x.get("f2_signature")]
        irrelevant = [x for x in records if x["schema_ok"] and not x.get("relevant")]
        result["models"][name] = {
            "model": model, "records": records, "failures": failures,
            "summary": {"n": n, "answered": n - len(failures),
                        "schema_pass": schema_ok, "schema_rate": round(schema_ok / max(1, n) * 100, 1),
                        "relevant_pass": relevant, "relevant_rate": round(relevant / max(1, n) * 100, 1),
                        "f2_signature": len(f2), "irrelevant": len(irrelevant)},
        }

    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    jp = out_dir / f"planner_final_audit_{stamp}.json"
    mp = out_dir / f"planner_final_audit_{stamp}.md"
    jp.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# planner final 抽检报告", "",
             f"- 时间：{result['generated_at']}",
             f"- 样本：`{args.data}` 中 final {len(finals)} 条",
             f"- 解码：{json.dumps(DECODE, ensure_ascii=False)}", "",
             "## 汇总", "",
             "| 模型 | schema 通过 | 相关性通过 | F2 指纹 | 无关样本 | 调用失败 |",
             "|---|---|---|---|---|---|"]
    for name, s in result["models"].items():
        m = s["summary"]
        lines.append(f"| {name} | {m['schema_pass']}/{m['n']}（{m['schema_rate']}%） | "
                     f"{m['relevant_pass']}/{m['n']}（{m['relevant_rate']}%） | {m['f2_signature']} | "
                     f"{m['irrelevant']} | {len(s['failures'])} |")
    for name, s in result["models"].items():
        bad = [x for x in s["records"] if not x.get("relevant") or x.get("f2_signature")]
        lines += ["", f"## {name}：内容与题干无关 / F2 指纹（{len(bad)} 条）", ""]
        if not bad:
            lines.append("无。")
            continue
        lines += ["| chunk_id | schema | 本题资料 | 其他资料最高 | gold 相似 | 题干覆盖(参考) | 实体覆盖(参考) | 答案前 60 字 |",
                  "|---|---|---|---|---|---|---|---|"]
        for x in bad:
            ans = (x.get("parsed") or {}).get("answer", "") if x.get("parsed") else x.get("output", "")[:60]
            ans = str(ans).replace("|", "\\|").replace("\n", " ")[:60]
            lines.append(f"| {x['chunk_id']} | {'Y' if x['schema_ok'] else 'N'} | "
                         f"{x.get('material_containment', 0):.2f} | {x.get('cross_material_max', 0):.2f} | "
                         f"{x.get('gold_sim', 0):.2f} | {x.get('question_coverage', 0):.2f} | "
                         f"{x.get('entity_coverage', 0):.2f} | {ans} |")
    lines += ["", "> 相关性 = 「本题资料 3-gram 包含率 ≥0.30」或「与 gold 相似度 ≥0.45」，二者满足其一即通过；"
              "题干/实体覆盖仅作参考（final 是资料复述，天然不复述题干用词）。", ""]
    mp.write_text("\n".join(lines), encoding="utf-8")
    for name, s in result["models"].items():
        m = s["summary"]
        print(f"[{name}] schema {m['schema_pass']}/{m['n']} 相关 {m['relevant_pass']}/{m['n']} "
              f"F2 {m['f2_signature']} 无关 {m['irrelevant']}")
    print(f"[json] {jp}\n[md] {mp}")


if __name__ == "__main__":
    main()
