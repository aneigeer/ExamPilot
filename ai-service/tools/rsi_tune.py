"""RSI（评测驱动的自我改进闭环）—— Router 原型句自动扩充。

## 为什么是"这个"而不是"让模型改自己"

真正的 RSI（递归自我改进）指模型改进自身权重/架构，本轮**不做也做不到**：
7B 本地模型没有训练回路，语料也不足以微调。这里实现的是它**可工程化的那一层**：

```
度量（评测集） → 定位失败 → 生成候选（失败问题本身作为新原型句）
              → 回归闸门（必须严格变好且不退化）→ 落盘 → 下一轮生效
```

**三个硬约束**（否则就是自欺欺人）：

1. **只扩充原型句，不动阈值** —— 阈值一动，拒答边界就漂，域外问题会被放进来说胡话
2. **必须过回归闸门** —— 新增原型句后，**所有原来对的用例不能变错**，且域外必须仍然全部拒答
3. **候选可追溯** —— 每条学到的原型句都记录"来自哪条失败用例"，写进 `rsi_run` 表

## 用法

    # 只看现状（不改任何东西）
    python ai-service/tools/rsi_tune.py --report

    # 跑一轮自改进（有改进才落盘）
    python ai-service/tools/rsi_tune.py --run

    # 回滚到出厂原型句（删除学习结果）
    python ai-service/tools/rsi_tune.py --reset

产物：`ai-service/app/router/prototypes_learned.json`
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

# 允许以 `python ai-service/tools/rsi_tune.py` 方式直接运行
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.router import router as router_mod  # noqa: E402
from app.router.prototypes import PROTOTYPES  # noqa: E402
from app.router.router import IntentRouter  # noqa: E402
from app.router.schemas import SkillName  # noqa: E402

CASES = ROOT / "eval" / "router_cases.csv"
LEARNED = ROOT / "app" / "router" / "prototypes_learned.json"

#: 事实类 skill：路由到这些却无资料 = 最严重（会编答案）
_DANGEROUS = {
    SkillName.COURSE_QA,
    SkillName.COURSEWARE_QA,
    SkillName.EXERCISE_EXPLAIN,
}


def load_cases() -> List[Tuple[str, SkillName, str]]:
    """读取评测集（question / expected_skill / note）。"""
    rows: List[Tuple[str, SkillName, str]] = []
    with CASES.open(encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            rows.append((row["question"].strip(), SkillName(row["expected_skill"].strip()),
                         row.get("note", "")))
    return rows


def split_cases(cases) -> Tuple[List, List]:
    """稳定切分训练集/留出集（按问题哈希，重复运行结果不变）。

    **为什么必须留出**：如果拿全部用例失败样本去补原型句，命中的是"记住测试题"，
    不是"泛化变好"。留出集里的样本**绝不参与学习**，只用来证明改进没有副作用。
    """
    train, holdout = [], []
    for item in cases:
        digest = hashlib.md5(item[0].encode("utf-8")).digest()[0]
        (holdout if digest % 4 == 0 else train).append(item)
    return train, holdout


def evaluate(r: IntentRouter, cases) -> Dict[str, object]:
    """跑一遍评测，返回指标与逐条明细。"""
    hit = 0
    dangerous = 0          # 域外被判成事实类：会编答案，最严重
    refuse_ok = 0
    refuse_total = 0
    details: List[Dict[str, object]] = []
    started = time.perf_counter()

    for question, expected, note in cases:
        decision = r.decide(question)
        ok = decision.skill is expected
        hit += int(ok)
        if expected is SkillName.REFUSE:
            refuse_total += 1
            refuse_ok += int(decision.skill is SkillName.REFUSE)
        if expected is SkillName.REFUSE and decision.skill in _DANGEROUS:
            dangerous += 1
        details.append({
            "question": question, "expected": expected.value,
            "actual": decision.skill.value, "sim": round(decision.sim, 4),
            "margin": round(decision.margin, 4), "ok": ok, "note": note,
        })

    cost = time.perf_counter() - started
    return {
        "total": len(cases), "hit": hit,
        "accuracy": hit / len(cases) if cases else 0.0,
        "refuse_ok": refuse_ok, "refuse_total": refuse_total,
        "dangerous": dangerous,
        "avg_ms": round(cost / max(len(cases), 1) * 1000, 1),
        "details": details,
    }


def _failed_by_skill(metrics) -> Dict[SkillName, List[str]]:
    """把失败用例按"本该去哪个 skill"归类，作为候选原型句。"""
    out: Dict[SkillName, List[str]] = {}
    for row in metrics["details"]:
        if not row["ok"] and row["expected"] != SkillName.REFUSE.value:
            out.setdefault(SkillName(row["expected"]), []).append(row["question"])
    return out


def _apply_candidates(learned: Dict[str, List[str]]) -> None:
    """把学到的原型句注入运行中的 PROTOTYPES（进程内生效）。"""
    for skill_name, sentences in learned.items():
        skill = SkillName(skill_name)
        existing = PROTOTYPES.setdefault(skill, [])
        for s in sentences:
            if s not in existing:
                existing.append(s)


def run(apply: bool) -> int:
    cases = load_cases()
    train, holdout = split_cases(cases)
    print(f"评测集：{len(cases)} 条（训练 {len(train)} / 留出 {len(holdout)}）"
          f"　学习结果：{LEARNED.name}"
          f"{'（已加载）' if LEARNED.exists() else '（无）'}")

    # 基线：先应用历史学习结果
    learned: Dict[str, List[str]] = {}
    if LEARNED.exists():
        learned = json.loads(LEARNED.read_text(encoding="utf-8"))
        _apply_candidates(learned)

    r = IntentRouter()
    base = evaluate(r, train)
    base_h = evaluate(IntentRouter(), holdout)
    print(f"\n【改进前】命中 {base['hit']}/{base['total']} = {base['accuracy']*100:.1f}%"
          f" | 域外拒答 {base['refuse_ok']}/{base['refuse_total']}"
          f" | 危险误判 {base['dangerous']} | 平均 {base['avg_ms']}ms")

    failed = _failed_by_skill(base)  # 只用**训练集**的失败做学习
    if not failed:
        print("\n没有可学习的失败样本，本轮结束。")
        return 0

    print("\n失败样本（按应去 skill 归类）：")
    for skill, items in failed.items():
        print(f"  {skill.value:18s} {len(items)} 条：{' / '.join(items[:3])}")

    # 候选：把失败问题本身作为该 skill 的新原型句（最保守的增量学习）
    candidates = {k.value: list(v) for k, v in failed.items()}
    trial = {k: list(v) for k, v in learned.items()}
    for skill, items in candidates.items():
        trial.setdefault(skill, []).extend([s for s in items if s not in trial.get(skill, [])])

    # 回归闸门：在干净的原型句上重新注入 trial，再评一次
    for skill in list(PROTOTYPES):
        base_list = [s for s in PROTOTYPES[skill] if s not in _all_learned(learned)]
        PROTOTYPES[skill] = base_list
    _apply_candidates(trial)
    after = evaluate(IntentRouter(), train)
    after_h = evaluate(IntentRouter(), holdout)

    print(f"\n【改进后】训练 {after['hit']}/{after['total']} = {after['accuracy']*100:.1f}%"
          f" | 留出 {after_h['hit']}/{after_h['total']} = {after_h['accuracy']*100:.1f}%"
          f" | 域外拒答 {after['refuse_ok']}/{after['refuse_total']}"
          f" | 危险误判 {after['dangerous']} | 平均 {after['avg_ms']}ms")
    print(f"         留出集改进前 {base_h['hit']}/{base_h['total']} → "
          f"改进后 {after_h['hit']}/{after_h['total']}（该集不参与学习）")

    # 闸门判定：严格变好 + 域外不退化 + 无危险误判增加
    better = (
        after["hit"] > base["hit"]                      # 训练集必须严格变好
        and after_h["hit"] >= base_h["hit"]              # 留出集不许退化（泛化底线）
        and after["refuse_ok"] >= base["refuse_ok"]      # 域外拒答不许退化
        and after_h["refuse_ok"] >= base_h["refuse_ok"]
        and after["dangerous"] <= base["dangerous"]      # 不许新增危险误判
        and after_h["dangerous"] <= base_h["dangerous"]
    )
    regressed = [
        row["question"] for row, old in zip(after["details"], base["details"])
        if old["ok"] and not row["ok"]
    ]
    if regressed:
        print(f"\n⚠ 闸门拒绝：有 {len(regressed)} 条原本正确的用例被改错 → {regressed[:3]}")
        better = False

    if not better:
        print("\n闸门结论：未严格变好，**不落盘**（保持现状）。")
        return 0

    print(f"\n✅ 闸门通过：命中 {base['hit']} → {after['hit']}（+{after['hit']-base['hit']}），"
          f"域外拒答未退化")
    if apply:
        LEARNED.write_text(json.dumps(trial, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"已写入 {LEARNED.relative_to(ROOT)}（{sum(len(v) for v in trial.values())} 条学习原型）")
        _record_run(base, after, trial)
    else:
        print("（未加 --run，本次只预演不落盘）")
    return 0


def _all_learned(learned: Dict[str, List[str]]) -> set:
    return {s for items in learned.values() for s in items}


def _record_run(base, after, learned) -> None:
    """把这一轮改进写进数据库，便于论文/答辩取证（表不存在则跳过）。"""
    try:
        import pymysql
        conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="123456",
                               database="exam_prep", charset="utf8mb4")
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO rsi_run (stage, metric_before, metric_after, learned_count, detail, created_at) "
                "VALUES (%s, %s, %s, %s, %s, NOW())",
                ("ROUTER_PROTOTYPE", json.dumps({"hit": base["hit"], "acc": round(base["accuracy"], 4)}),
                 json.dumps({"hit": after["hit"], "acc": round(after["accuracy"], 4)}),
                 sum(len(v) for v in learned.values()),
                 json.dumps(learned, ensure_ascii=False)[:2000]))
            conn.commit()
        conn.close()
        print("已记录 rsi_run（可查历史改进曲线）")
    except Exception as exc:  # noqa: BLE001 - 记录失败不影响改进本身
        print(f"（rsi_run 未记录：{type(exc).__name__}: {str(exc)[:60]}）")


def report() -> int:
    cases = load_cases()
    learned = json.loads(LEARNED.read_text(encoding="utf-8")) if LEARNED.exists() else {}
    _apply_candidates(learned)
    train, holdout = split_cases(cases)
    m = evaluate(IntentRouter(), train)
    mh = evaluate(IntentRouter(), holdout)
    print(f"当前状态：训练 {m['hit']}/{m['total']} = {m['accuracy']*100:.1f}%"
          f" | 留出 {mh['hit']}/{mh['total']} = {mh['accuracy']*100:.1f}%"
          f" | 域外拒答 {m['refuse_ok']}/{m['refuse_total']}"
          f" | 危险误判 {m['dangerous']} | 平均 {m['avg_ms']}ms"
          f" | 学习原型 {sum(len(v) for v in learned.values())} 条")
    print("\n失败明细：")
    for row in m["details"]:
        if not row["ok"]:
            print(f"  {row['question'][:28]:30s} 期望 {row['expected']:16s} 实际 {row['actual']:16s}"
                  f" sim={row['sim']:.3f} margin={row['margin']:.3f}")
    return 0


def reset() -> int:
    if LEARNED.exists():
        LEARNED.unlink()
        print("已删除学习结果，恢复出厂原型句")
    else:
        print("没有学习结果可删")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="RSI：评测驱动的 Router 原型句自改进")
    ap.add_argument("--report", action="store_true", help="只看当前指标与失败明细")
    ap.add_argument("--run", action="store_true", help="跑一轮改进并落盘（默认只预演）")
    ap.add_argument("--reset", action="store_true", help="删除学习结果")
    args = ap.parse_args()
    if args.reset:
        return reset()
    if args.report:
        return report()
    return run(apply=args.run)


if __name__ == "__main__":
    sys.exit(main())
