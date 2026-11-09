"""第2周：planner final 的「answer ⊆ material」核查（只读）。

背景：QA 发现新生成器 material 只给 120 字、answer 却用完整 body（最长 200 字），
导致 200/250 条答案比资料长。修复后应满足：
  1) answer 正文（去掉尾部 [1]。）是 material 的子串（归一化后）；
  2) answer 中位长度 ≤ material 中位长度；
  3) 不再出现「答案比资料长」。

用法：
  .venv-ai/Scripts/python.exe 项目规划/第2周/scripts/check_planner_alignment.py \
      --input training/data/sft_train_behavior.jsonl --tag current
输出：项目规划/第2周/scripts/planner_alignment_<tag>.json
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "training" / "data"


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s)


def split_material(user: str, title: str | None = None) -> str | None:
    """从 user 中取 [1] 的知识块正文（title：material 的 material 部分）。

    优先用 meta.title 精确定位前缀，避免标题自带「：」时按首个冒号误切。
    """
    if "【问题】" not in user:
        return None
    if title:
        head = f"[1] {title}："
        if head in user:
            return user.split(head, 1)[1].split("\n\n【问题】", 1)[0].strip()
    body = user.split("【问题】", 1)[0]
    # 取第一个 [1] 之后的内容（资料正文里可能自带 [1] 引用，不能用 rfind）
    idx = body.find("[1]")
    if idx < 0:
        return None
    block = body[idx + 3 :].strip()
    # 兜底：去掉 “标题：” 前缀（第一个中文/英文冒号）
    m = re.match(r"^[^：:]{1,80}[：:]\s*", block)
    if m:
        block = block[m.end() :]
    return block.strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=str(DATA / "sft_train_behavior.jsonl"))
    ap.add_argument("--tag", default="current")
    ap.add_argument("--out-dir", default=str(ROOT / "项目规划" / "第2周" / "scripts"))
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.input).read_text(encoding="utf-8").splitlines() if l.strip()]
    finals = []
    for i, r in enumerate(rows):
        if r.get("kind") != "planner":
            continue
        try:
            obj = json.loads(r["messages"][2]["content"])
        except json.JSONDecodeError:
            continue
        if obj.get("action") != "final":
            continue
        material = split_material(r["messages"][1]["content"], (r.get("meta") or {}).get("title")) or ""
        answer = str(obj.get("answer", ""))
        body = re.sub(r"\[\d+\]。?$", "", answer).strip()
        subset_exact = bool(body) and body in material
        subset_norm = bool(norm(body)) and norm(body) in norm(material)
        exact_equal = body.rstrip("。！？ \t\n") == material.rstrip("。！？ \t\n")
        finals.append(
            {
                "row_index": i,
                "qid": (r.get("meta") or {}).get("qid"),
                "chunk_id": (r.get("meta") or {}).get("chunk_id"),
                "material_chars": len(material),
                "answer_chars": len(body),
                "answer_longer": len(body) > len(material),
                "subset_exact": subset_exact,
                "subset_normalized": subset_norm,
                "exact_equal": exact_equal,
                "material": material,
                "answer_body": body,
            }
        )

    out = {
        "input": str(args.input),
        "tag": args.tag,
        "rows": len(rows),
        "planner_final": len(finals),
        "subset_exact": sum(1 for x in finals if x["subset_exact"]),
        "subset_normalized": sum(1 for x in finals if x["subset_normalized"]),
        "exact_equal": sum(1 for x in finals if x["exact_equal"]),
        "answer_longer": sum(1 for x in finals if x["answer_longer"]),
        "material_chars_median": median([x["material_chars"] for x in finals]) if finals else 0,
        "answer_chars_median": median([x["answer_chars"] for x in finals]) if finals else 0,
        "material_chars_min": min((x["material_chars"] for x in finals), default=0),
        "answer_chars_max": max((x["answer_chars"] for x in finals), default=0),
        "violations": [
            {
                "row_index": x["row_index"],
                "qid": x["qid"],
                "chunk_id": x["chunk_id"],
                "material_chars": x["material_chars"],
                "answer_chars": x["answer_chars"],
                "answer_head": x["answer_body"][:80],
            }
            for x in finals
            if not x["subset_normalized"]
        ][:20],
    }
    out_path = Path(args.out_dir) / f"planner_alignment_{args.tag}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: out[k] for k in (
        "planner_final", "subset_exact", "subset_normalized", "exact_equal", "answer_longer",
        "material_chars_median", "answer_chars_median", "material_chars_min", "answer_chars_max",
    )}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
