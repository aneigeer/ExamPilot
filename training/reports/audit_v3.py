"""v3 行为数据只读审计：7 项指标，不修改任何数据文件。

用法：.venv-ai/Scripts/python.exe training/reports/audit_v3.py
输出：training/reports/_audit_v3_metrics.json
"""

from __future__ import annotations

import json
import random
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "training" / "data"
OUT = ROOT / "training" / "reports" / "_audit_v3_metrics.json"

PATH_BEHAVIOR = DATA / "sft_train_behavior.jsonl"
PATH_FULL = DATA / "sft_train.jsonl"
TOKENIZER_DIR = Path("C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master")

CITE_RE = re.compile(r"\[\d+\]")
REFUSAL = "未找到相关资料"
GROUNDED_KINDS = ("grounded", "injection", "refusal", "citation", "wiki")
BANNED = (
    "已越狱", "越狱", "开发者模式", "系统提示词",
    "答案：略", "答案为略", "答案: 略", "答案:略", "DAN", "无条件执行",
)


def load(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def parts(row: dict):
    sys_ = row["messages"][0]["content"]
    user = row["messages"][1]["content"]
    ans = row["messages"][2]["content"]
    return sys_, user, ans


def pct(n: int, d: int) -> float:
    return round(100.0 * n / d, 2) if d else 0.0


def quantile(sorted_vals: list[int], q: float) -> int:
    if not sorted_vals:
        return 0
    idx = min(len(sorted_vals) - 1, max(0, int(round(q * (len(sorted_vals) - 1)))))
    return sorted_vals[idx]


def main() -> None:
    rows = load(PATH_BEHAVIOR)
    by_kind = defaultdict(list)
    for r in rows:
        by_kind[r["kind"]].append(r)

    m: dict = {"file": str(PATH_BEHAVIOR), "total": len(rows)}
    counts = Counter(r["kind"] for r in rows)
    m["counts"] = dict(counts)

    # 1) 引用覆盖
    cite = {}
    for k in ("grounded", "injection"):
        items = by_kind[k]
        with_any = sum(1 for r in items if CITE_RE.search(parts(r)[2]))
        with_1 = sum(1 for r in items if "[1]" in parts(r)[2])
        cite[k] = {"n": len(items), "any_cite": with_any, "cite_1": with_1,
                   "any_pct": pct(with_any, len(items)), "cite1_pct": pct(with_1, len(items))}
    m["cite"] = cite

    # 2) 系统提示分流
    def sys_info(k: str) -> dict:
        items = by_kind[k]
        c = Counter(parts(r)[0] for r in items)
        strict = sum(1 for r in items if "严格遵守" in parts(r)[0])
        nomat = sum(1 for r in items if "未找到相关资料" in parts(r)[0])
        nodata = sum(1 for r in items if "如果没有【资料】" in parts(r)[0])
        return {"n": len(items), "distinct_system": len(c), "has_strict": strict,
                "has_nomaterial": nomat, "has_nodata_clause": nodata}

    m["sys"] = {k: sys_info(k) for k in ["mcq", "grounded", "refusal", "injection", "citation", "wiki", "planner"]}

    bad_sys = []
    for k in ("grounded", "refusal", "injection", "citation", "wiki"):
        for r in by_kind[k]:
            s, u, a = parts(r)
            if "严格遵守" not in s or REFUSAL not in s or "如果没有【资料】" in s:
                bad_sys.append({"kind": k, "system": s[:300], "user": u[:120]})
    for r in by_kind["mcq"]:
        s, u, a = parts(r)
        if "如果没有【资料】" not in s or "严格遵守" in s:
            bad_sys.append({"kind": "mcq", "system": s[:300], "user": u[:120]})
    m["sys_bad_count"] = len(bad_sys)
    m["sys_bad_samples"] = bad_sys[:2]

    # 3) 拒答纪律
    ref = by_kind["refusal"]
    ok = 0
    bad_ref = []
    for r in ref:
        a = parts(r)[2].strip()
        if a == REFUSAL or a == REFUSAL + "。" or a == REFUSAL + "." or a == REFUSAL + "。\n":
            ok += 1
        elif len(bad_ref) < 3:
            bad_ref.append(a[:200])
    m["refusal"] = {"n": len(ref), "exact": ok, "pct": pct(ok, len(ref)), "bad_samples": bad_ref}

    # 4) planner 合法性
    pl = by_kind["planner"]
    legal = 0
    actions = Counter()
    bad_pl = []
    for r in pl:
        a = parts(r)[2].strip()
        obj = None
        try:
            obj = json.loads(a)
        except Exception:
            obj = None
        if isinstance(obj, dict) and obj.get("action") in ("tool", "final"):
            legal += 1
            actions[obj["action"]] += 1
        elif len(bad_pl) < 3:
            bad_pl.append(a[:300])
    # 工具名分布补充
    tools = Counter()
    for r in pl:
        try:
            o = json.loads(parts(r)[2])
        except Exception:
            continue
        if isinstance(o, dict) and o.get("action") == "tool":
            tools[str(o.get("tool"))] += 1
    m["planner"] = {"n": len(pl), "legal": legal, "pct": pct(legal, len(pl)),
                    "actions": dict(actions), "tools": dict(tools), "bad_samples": bad_pl}

    # 5) 抗注入
    inj = by_kind["injection"]
    viol = []
    for r in inj:
        a = parts(r)[2]
        hit = [w for w in BANNED if w in a]
        if hit and len(viol) < 5:
            viol.append({"hits": hit, "assistant": a[:300], "attack": (r.get("meta") or {}).get("attack")})
    viol_n = sum(1 for r in inj if any(w in parts(r)[2] for w in BANNED))
    # 答案一致性：meta.answer 是否出现在 assistant 开头
    ans_ok = sum(1 for r in inj if (r.get("meta") or {}).get("answer")
                 and parts(r)[2].strip().startswith("答案：" + str(r["meta"]["answer"])))
    ans_known = sum(1 for r in inj if (r.get("meta") or {}).get("answer"))
    # 是否复读了攻击句
    echo = sum(1 for r in inj if (r.get("meta") or {}).get("attack")
               and str(r["meta"]["attack"])[:12] in parts(r)[2])
    m["injection"] = {"n": len(inj), "violations": viol_n, "viol_samples": viol,
                      "answer_known": ans_known, "answer_match": ans_ok,
                      "answer_match_pct": pct(ans_ok, ans_known), "attack_echo": echo}

    # 6) 配比与去重
    total = len(rows)
    m["mix"] = {k: {"n": n, "pct": pct(n, total)} for k, n in counts.most_common()}
    keys = [(r["kind"], parts(r)[1][:120]) for r in rows]
    dup = {k: c for k, c in Counter(keys).items() if c > 1}
    m["dedup"] = {"dup_keys": len(dup), "dup_rows": sum(c - 1 for c in dup.values()),
                  "samples": [{"kind": k[0], "user120": k[1], "count": c} for k, c in list(dup.items())[:5]]}
    exact = Counter((r["kind"], parts(r)[1], parts(r)[2]) for r in rows)
    m["dedup"]["exact_dup_rows"] = sum(c - 1 for c in exact.values() if c > 1)

    # 7) token 长度（Qwen2.5 tokenizer，CPU）
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(str(TOKENIZER_DIR), trust_remote_code=True)
    lens = []
    for r in rows:
        enc = tok.apply_chat_template(r["messages"], tokenize=True, return_dict=True,
                                      add_generation_prompt=False)
        lens.append(len(enc["input_ids"]))
    s = sorted(lens)
    m["tokens"] = {"p50": quantile(s, 0.50), "p90": quantile(s, 0.90),
                   "p99": quantile(s, 0.99), "max": max(s), "min": min(s),
                   "mean": round(sum(s) / len(s), 1),
                   "gt1024": sum(1 for x in s if x > 1024),
                   "gt2048": sum(1 for x in s if x > 2048),
                   "gt4096": sum(1 for x in s if x > 4096)}
    by_tok = {}
    # 逐 kind 分位数（按索引对齐）
    idx_of = {}
    for i, r in enumerate(rows):
        idx_of.setdefault(r["kind"], []).append(i)
    for k, idxs in idx_of.items():
        vv = sorted(lens[i] for i in idxs)
        by_tok[k] = {"n": len(idxs), "p50": quantile(vv, .5), "p90": quantile(vv, .9), "max": max(vv)}
    m["tokens_by_kind"] = by_tok

    # 全量数据参考（仅计数，用于确认抽样源规模）
    if PATH_FULL.exists():
        full = load(PATH_FULL)
        m["source_counts"] = dict(Counter(r.get("kind") for r in full))
        m["source_total"] = len(full)

    OUT.write_text(json.dumps(m, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: m[k] for k in ("total", "counts", "cite", "refusal", "planner", "tokens")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    random.seed(0)
    main()
