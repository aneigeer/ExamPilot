"""第2周 P0-2：F2（planner final 用错知识块）受影响样本核查。

只读 `training/data/sft_train_behavior.jsonl`，导出 158 条 `kind=planner & action=final`，
做关键词/截断/引用三项自动检查，并四分类；同时尝试从 train/val 反查 qid（planner 本身无 meta）。

用法：
  .venv-ai/Scripts/python.exe 项目规划/第2周/scripts/check_f2_affected.py
输出：
  项目规划/第2周/evidence/f2_affected_samples.jsonl
  项目规划/第2周/scripts/f2_stats.json
"""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data"
OUT_DIR = ROOT / "项目规划" / "第2周"
EVIDENCE = OUT_DIR / "evidence"

CJK = re.compile(r"[\u4e00-\u9fa5]")
# 虚词/题干套话，避免「下列/正确/一项」这类词造成假命中
STOP = set(
    "下列正确一项以下关于什么哪个哪些是否可属于不是的有在和与或就都而及等这那些为对错"
    "请根据上述资料题干问题作答说明依据"
)


def norm_text(s: str) -> str:
    return re.sub(r"[^\u4e00-\u9fa5A-Za-z0-9]+", "", s)


def keywords(stem: str) -> set[str]:
    """3–4 字中文 n-gram 关键词（保留最长的，去掉被更长串包含的），用于检查题干实词是否出现在 answer。"""
    s = norm_text(stem)
    out: set[str] = set()
    for n in (4, 3):
        for i in range(len(s) - n + 1):
            g = s[i : i + n]
            if not all(CJK.match(ch) for ch in g):
                continue
            if all(ch in STOP for ch in g):
                continue
            out.add(g)
    return {g for g in out if not any(g != h and g in h for h in out)}


def split_answer(ans: str) -> tuple[str, str]:
    """拆出正文与构建期追加的 [n]。后缀。"""
    m = re.search(r"(\[\d+\]。?)\s*$", ans)
    if m:
        return ans[: m.start()], m.group(1)
    return ans, ""


def parse_chunk(ans: str) -> tuple[str, int | None, str]:
    title = ""
    page = None
    m = re.search(r"《([^》]{1,80})》", ans)
    if m:
        title = m.group(1)
    p = re.search(r"第\s*(\d+)\s*页", ans[:60])
    if p:
        page = int(p.group(1))
    body = ans
    first = re.split(r"(?<=[。！？；])", body, maxsplit=1)[0].strip()
    return title, page, first[:200]


def category_of(hits: int, kw_total: int, body: str) -> tuple[str, str]:
    """返回 (自动分类, 判定依据)。"""
    truncated = body.rstrip().endswith(("…", "..."))
    if kw_total < 5:
        return "无法自动判定", "题干过短/关键词少于5个，无法可靠判定相关性"
    if hits == 0:
        return "明显无关", "题干3–4字实词n-gram无一出现在answer正文"
    if truncated:
        return "疑似相关但被截断", "命中题干关键词，但answer正文以省略号截断"
    if hits / kw_total >= 0.3:
        return "可能相关", "命中题干关键词且未被截断"
    return "无法自动判定", "仅少量弱命中，自动判据不足以定性"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=str(DATA / "sft_train_behavior.jsonl"))
    ap.add_argument("--tag", default="current")
    args = ap.parse_args()
    behavior_path = Path(args.input)
    behavior = [json.loads(line) for line in behavior_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    # qid 反查优先用同目录的 train/val（归档版与当前版各自成对）
    qdir = behavior_path.parent
    q_train = qdir / "sft_train.jsonl" if (qdir / "sft_train.jsonl").exists() else DATA / "sft_train.jsonl"
    q_val = qdir / "sft_val.jsonl" if (qdir / "sft_val.jsonl").exists() else DATA / "sft_val.jsonl"
    train = [json.loads(line) for line in q_train.read_text(encoding="utf-8").splitlines() if line.strip()]
    val = [json.loads(line) for line in q_val.read_text(encoding="utf-8").splitlines() if line.strip()]

    # qid 反查索引：train/val 的 user 文本 vs meta.qid
    qid_rows = []
    for r in train + val:
        q = (r.get("meta") or {}).get("qid")
        if q:
            qid_rows.append((norm_text(r["messages"][1]["content"]), q))

    finals = []
    for idx, r in enumerate(behavior):
        if r.get("kind") != "planner":
            continue
        try:
            obj = json.loads(r["messages"][2]["content"])
        except json.JSONDecodeError:
            continue
        if obj.get("action") != "final":
            continue
        stem = r["messages"][1]["content"]
        answer = str(obj.get("answer", ""))
        body, cite_tail = split_answer(answer)
        kws = keywords(stem)
        hits = sorted(g for g in kws if g in body)
        cat, reason = category_of(len(hits), len(kws), body)

        prefix = norm_text(stem)[:40]
        meta = r.get("meta") or {}
        meta_qid = meta.get("qid")
        if meta_qid:
            qid_recovered, qid_status = int(meta_qid), "meta"
        else:
            qids = {q for u, q in qid_rows if prefix and prefix in u}
            if len(qids) == 1:
                qid_recovered, qid_status = next(iter(qids)), "unique"
            elif len(qids) > 1:
                qid_recovered, qid_status = None, f"ambiguous({len(qids)})"
            else:
                qid_recovered, qid_status = None, "not_found"

        title, page, first = parse_chunk(body)
        meta_chunk = meta.get("chunk_id")
        if meta.get("title"):
            title = str(meta["title"])[:80]
        finals.append(
            {
                "row_index": idx,
                "kind": "planner",
                "meta_qid": int(meta_qid) if meta_qid else None,
                "qid_recovered": qid_recovered,
                "qid_recovery": qid_status,
                "stem": stem,
                "answer_full": answer,
                "answer_body": body,
                "cite_tail": cite_tail,
                "thought": obj.get("thought"),
                "chunk_id": int(meta_chunk) if meta_chunk else None,
                "chunk_title": title or None,
                "chunk_page": page,
                "chunk_first_sentence": first,
                "signals": {
                    "keyword_total": len(kws),
                    "keyword_hits": len(hits),
                    "hit_terms": hits[:10],
                    "body_ends_ellipsis": body.rstrip().endswith(("…", "...")),
                    "raw_ends_ellipsis": answer.rstrip().endswith(("…", "...")),
                    "has_citation": bool(re.search(r"\[\d+\]", answer)),
                    "answer_chars": len(answer),
                },
                "category": cat,
                "category_reason": reason,
            }
        )

    EVIDENCE.mkdir(parents=True, exist_ok=True)
    jsonl_name = "f2_affected_samples.jsonl" if args.tag == "baseline" else f"f2_affected_samples_{args.tag}.jsonl"
    EVIDENCE.joinpath(jsonl_name).write_text(
        "\n".join(json.dumps(x, ensure_ascii=False) for x in finals) + "\n", encoding="utf-8"
    )

    cats = Counter(x["category"] for x in finals)
    by_hit = Counter(x["signals"]["keyword_hits"] for x in finals)
    examples = {}
    for c in ("明显无关", "疑似相关但被截断", "无法自动判定", "可能相关"):
        examples[c] = [
            {
                "row_index": x["row_index"],
                "qid_recovered": x["qid_recovered"],
                "stem": x["stem"][:120],
                "answer": x["answer_full"][:200],
            }
            for x in finals
            if x["category"] == c
        ][:3]

    # 人工复核候选：从「明显无关」按等距抽 20 条，兼顾不同题干长度
    unrelated = [x for x in finals if x["category"] == "明显无关"]
    step = max(1, len(unrelated) // 20)
    manual = unrelated[::step][:20]

    stats = {
        "source": str(behavior_path),
        "tag": args.tag,
        "planner_total": sum(1 for r in behavior if r.get("kind") == "planner"),
        "final_total": len(finals),
        "categories": dict(cats),
        "signals": {
            "body_ends_ellipsis": sum(1 for x in finals if x["signals"]["body_ends_ellipsis"]),
            "raw_ends_ellipsis": sum(1 for x in finals if x["signals"]["raw_ends_ellipsis"]),
            "has_citation": sum(1 for x in finals if x["signals"]["has_citation"]),
            "answer_len_unique": sorted({x["signals"]["answer_chars"] for x in finals}),
            "keyword_hit_dist": dict(sorted(by_hit.items())),
        },
        "qid_recovery": dict(Counter(x["qid_recovery"] for x in finals)),
        "undetermined": [
            {
                "row_index": x["row_index"],
                "qid_recovered": x["qid_recovered"],
                "stem": x["stem"][:120],
                "answer": x["answer_full"][:160],
                "reason": x["category_reason"],
            }
            for x in finals
            if x["category"] == "无法自动判定"
        ],
        "examples": examples,
        "manual_review_20": [
            {
                "row_index": x["row_index"],
                "qid_recovered": x["qid_recovered"],
                "stem": x["stem"][:160],
                "answer": x["answer_full"][:200],
                "chunk_title": x["chunk_title"],
            }
            for x in manual
        ],
    }
    stats_path = OUT_DIR / "scripts" / ("f2_stats.json" if args.tag == "baseline" else f"f2_stats_{args.tag}.json")
    stats_path.parent.mkdir(parents=True, exist_ok=True)
    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: stats[k] for k in ("planner_total", "final_total", "categories", "signals", "qid_recovery")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
