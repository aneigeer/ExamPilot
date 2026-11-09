"""第6周：配对统计检验（McNemar + 差值置信区间）。

输入：`eval_v2` 的逐样本结果 JSON（base / tuned 两份 rows 按下标一一对应）。
输出：`stat_test.json` + `stat_test.md`，五个指标各一份配对列联表、p 值、95% CI。

为什么必须是**配对**检验
------------------------
base 与 tuned 跑的是**同一批 250 条样本**，每条样本两侧各有一次判定。
把它们当成两个独立样本做比例检验会低估精度（丢掉"同题同难度"的配对信息），
所以统一用 McNemar（配对二分类）+ 配对差值的 Newcombe 区间。

判据与口径
----------
| 指标 | 取值字段 | kind | n |
|---|---|---|---|
| 单选准确率 | `checks.mcq_acc` | mcq + grounded | 100 |
| 引用率 | `checks.cite_rate` | grounded | 50 |
| 拒答合规率 | `checks.refusal_ok` | refusal | 50 |
| planner JSON 合法率 | `checks.planner_ok` | planner | 50 |
| 抗注入通过率 | `checks.injection_ok` | injection | 50 |

用法
----
    python 项目规划/第6周/scripts/stat_test.py \
        --input 项目规划/第5周/evidence/eval_q8_aligned/eval_v2_20261106_2310.json \
        --out-dir 项目规划/第6周/evidence
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from statistics import NormalDist
from typing import Any, Iterable

Z = NormalDist().inv_cdf(0.975)  # 1.959964

#: 指标定义：(显示名, 判定字段, 参与 kind, 说明)
METRICS: tuple[tuple[str, str, tuple[str, ...], str], ...] = (
    ("单选准确率", "mcq_acc", ("mcq", "grounded"), "答案字母命中；mcq 50 + grounded 50"),
    ("引用率", "cite_rate", ("grounded",), "回答含 [n] 标记；仅 grounded"),
    ("拒答合规率", "refusal_ok", ("refusal",), "以「未找到相关资料」开头且未给答案"),
    ("planner JSON 合法率", "planner_ok", ("planner",), "合法 JSON 且 action ∈ {tool, final}"),
    ("抗注入通过率", "injection_ok", ("injection",), "未执行注入指令、未泄露系统提示"),
)

EXACT_LIMIT = 25  # b+c < 25 用精确二项，否则卡方（连续性校正）


@dataclass
class Paired:
    """配对列联表 + 检验结果。"""

    name: str
    n: int
    a: int  # 都对
    b: int  # 仅基线对
    c: int  # 仅微调对
    d: int  # 都错
    note: str

    @property
    def base_ok(self) -> int:
        return self.a + self.b

    @property
    def tuned_ok(self) -> int:
        return self.a + self.c

    @property
    def base_rate(self) -> float:
        return self.base_ok / self.n if self.n else 0.0

    @property
    def tuned_rate(self) -> float:
        return self.tuned_ok / self.n if self.n else 0.0

    @property
    def delta_pp(self) -> float:
        return (self.tuned_rate - self.base_rate) * 100


def wilson(k: int, n: int, z: float = Z) -> tuple[float, float]:
    """Wilson score 区间（单比例）。"""
    if n == 0:
        return 0.0, 0.0
    p = k / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


def newcombe_paired(a: int, b: int, c: int, d: int, z: float = Z) -> tuple[float, float]:
    """配对比例差的 Newcombe 混合得分区间（method 10）。

    同时利用两侧的 Wilson 区间与配对结构，比「独立两样本」的正态近似更贴合配对设计。
    """
    n = a + b + c + d
    if n == 0:
        return 0.0, 0.0
    p1 = (a + b) / n      # base 成功率
    p2 = (a + c) / n      # tuned 成功率
    l1, u1 = wilson(a + b, n, z)
    l2, u2 = wilson(a + c, n, z)
    delta = p2 - p1
    lower = delta - math.sqrt((p2 - l2) ** 2 + (u1 - p1) ** 2)
    upper = delta + math.sqrt((u2 - p2) ** 2 + (p1 - l1) ** 2)
    return max(-1.0, lower), min(1.0, upper)


def mcnemar_exact(b: int, c: int) -> float:
    """精确 McNemar：在 b+c 次不一致中，b 与 c 应各占一半。双侧 p。"""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def mcnemar_chi2(b: int, c: int) -> float:
    """带连续性校正的 McNemar 卡方（df=1），p 用 erfc 直接算。"""
    if b + c == 0:
        return 1.0
    chi2 = (abs(b - c) - 1) ** 2 / (b + c)
    chi2 = max(0.0, chi2)
    return math.erfc(math.sqrt(chi2 / 2))


def mcnemar(b: int, c: int) -> tuple[float, str]:
    if b + c < EXACT_LIMIT:
        return mcnemar_exact(b, c), "exact-binomial"
    return mcnemar_chi2(b, c), "chi2-cc"


def resolution(n: int) -> dict[str, Any]:
    """样本量分辨率：±1 条样本等于多少 pp，几个样本才算「可分辨」。"""
    per_sample = 100.0 / n if n else 0.0
    # 取 3 条样本作为"可分辨"门槛：单条噪声在多类指标上常见，连续 3 条同向才构成信号
    return {
        "n": n,
        "pp_per_sample": round(per_sample, 2),
        "indistinguishable_below_pp": round(per_sample * 3, 1),
        "half_width_at_50pct_pp": round((wilson(n // 2, n)[1] - wilson(n // 2, n)[0]) / 2 * 100, 1),
    }


def build_rows(base_rows: list[dict[str, Any]], tuned_rows: list[dict[str, Any]], field: str,
               kinds: Iterable[str]) -> list[tuple[bool, bool]]:
    pairs: list[tuple[bool, bool]] = []
    for left, right in zip(base_rows, tuned_rows):
        if left.get("kind") != right.get("kind"):
            raise SystemExit("base/tuned 行序不一致（kind 不同），无法配对，请检查评测输出")
        if left.get("kind") not in kinds:
            continue
        pairs.append((bool((left.get("checks") or {}).get(field)), bool((right.get("checks") or {}).get(field))))
    return pairs


def analyse(name: str, field: str, kinds: tuple[str, ...], note: str,
            base_rows: list[dict[str, Any]], tuned_rows: list[dict[str, Any]]) -> Paired:
    pairs = build_rows(base_rows, tuned_rows, field, kinds)
    a = sum(1 for x, y in pairs if x and y)
    b = sum(1 for x, y in pairs if x and not y)
    c = sum(1 for x, y in pairs if not x and y)
    d = sum(1 for x, y in pairs if not x and not y)
    return Paired(name=name, n=len(pairs), a=a, b=b, c=c, d=d, note=note)


def render_md(results: list[Paired], meta: dict[str, Any]) -> str:
    lines = [
        "# 第6周 · 配对统计检验结果",
        "",
        f"- 输入：`{meta['input']}`",
        f"- 基线：`{meta['base_model']}`　微调：`{meta['tuned_model']}`",
        f"- 样本：同一批 {meta['n']} 条（配对设计，逐样本对齐）",
        f"- 检验：McNemar（b+c<{EXACT_LIMIT} 用精确二项，否则卡方+连续性校正）；"
        "差值 CI 用配对 Newcombe 区间（95%）",
        "",
        "## 1. 总表",
        "",
        "| 指标 | n | 基线 | 微调 | 差(pp) | b(仅基线对) | c(仅微调对) | p 值 | 95% CI(pp) | 判定 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for item in results:
        p, method = mcnemar(item.b, item.c)
        low, high = newcombe_paired(item.a, item.b, item.c, item.d)
        verdict = judge(item, p, low, high)
        lines.append(
            f"| {item.name} | {item.n} | {item.base_ok}/{item.n} = {item.base_rate * 100:.0f}% "
            f"| {item.tuned_ok}/{item.n} = {item.tuned_rate * 100:.0f}% | {item.delta_pp:+.0f} "
            f"| {item.b} | {item.c} | {p:.4f} ({method}) "
            f"| [{low * 100:+.1f}, {high * 100:+.1f}] | {verdict} |"
        )
    lines += ["", "## 2. 逐指标配对列联表", ""]
    for item in results:
        p, method = mcnemar(item.b, item.c)
        low, high = newcombe_paired(item.a, item.b, item.c, item.d)
        lines += [
            f"### {item.name}",
            "",
            f"- 口径：{item.note}；n={item.n}",
            "",
            "|  | 微调 对 | 微调 错 |",
            "|---|---|---|",
            f"| **基线 对** | a={item.a} | b={item.b} |",
            f"| **基线 错** | c={item.c} | d={item.d} |",
            "",
            f"- 基线 {item.base_rate * 100:.0f}%（{item.base_ok}/{item.n}）→ 微调 {item.tuned_rate * 100:.0f}%"
            f"（{item.tuned_ok}/{item.n}），差 **{item.delta_pp:+.0f}pp**",
            f"- McNemar p = **{p:.4f}**（{method}），不一致对数 b+c = {item.b + item.c}",
            f"- 配对差值 95% CI：**[{low * 100:+.1f}, {high * 100:+.1f}]pp**",
            "",
        ]
    lines += [
        "## 3. 样本量限制（必须先看这一节）",
        "",
        "| 指标 | n | ±1 条样本 = | 小于该差值视为不可分辨 | 50% 处的 CI 半宽 |",
        "|---|---|---|---|---|",
    ]
    for item in results:
        r = resolution(item.n)
        lines.append(
            f"| {item.name} | {r['n']} | {r['pp_per_sample']:.1f}pp | "
            f"<{r['indistinguishable_below_pp']:.1f}pp | ±{r['half_width_at_50pct_pp']:.1f}pp |"
        )
    lines += [
        "",
        "**读法**：n=50 时 1 条样本就是 2pp，3 条同向才算信号 —— 所以「planner -2pp」「抗注入 -10pp」这类数字，",
        "**先看 CI 是否跨 0，再看 b/c 的绝对值**，不要只比百分数。",
        "",
    ]
    return "\n".join(lines)


def judge(item: Paired, p: float, low: float, high: float) -> str:
    """只给"统计上如何"，不给 Go/No-Go 结论（那是决策框架的事）。"""
    crosses_zero = low <= 0 <= high
    if crosses_zero and p >= 0.05:
        return "无差异"
    if item.delta_pp > 0 and p < 0.05:
        return "显著提升"
    if item.delta_pp < 0 and p < 0.05:
        return "显著下降"
    return "方向有差但不显著"


def main() -> int:
    parser = argparse.ArgumentParser(description="配对统计检验（McNemar + Newcombe）")
    parser.add_argument("--input", default="项目规划/第5周/evidence/eval_q8_aligned/eval_v2_20261106_2310.json")
    parser.add_argument("--out-dir", default="项目规划/第6周/evidence")
    args = parser.parse_args()

    src = Path(args.input)
    if not src.exists():
        print(f"[FATAL] 输入不存在：{src}")
        return 1
    data = json.loads(src.read_text(encoding="utf-8"))
    base_rows = data["models"]["base"]["rows"]
    tuned_rows = data["models"]["tuned"]["rows"]
    if len(base_rows) != len(tuned_rows):
        print(f"[FATAL] base/tuned 行数不等：{len(base_rows)} vs {len(tuned_rows)}")
        return 1

    results = [analyse(name, field, kinds, note, base_rows, tuned_rows) for name, field, kinds, note in METRICS]

    def cite_wrong(rows: list[dict[str, Any]]) -> int:
        return sum(
            1 for r in rows
            if r.get("kind") == "grounded"
            and (r.get("checks") or {}).get("cite_rate")
            and not (r.get("checks") or {}).get("mcq_acc")
        )

    grounded_n = sum(1 for r in base_rows if r.get("kind") == "grounded") or 1
    base_cw, tuned_cw = cite_wrong(base_rows), cite_wrong(tuned_rows)

    payload = {
        "input": str(src),
        "base_model": data.get("base_model"),
        "tuned_model": data.get("tuned_model"),
        "n": len(base_rows),
        "proxy": {
            "base_cite_wrong": base_cw,
            "tuned_cite_wrong": tuned_cw,
            "base_ratio": round(base_cw / grounded_n, 4),
            "tuned_ratio": round(tuned_cw / grounded_n, 4),
            "note": "有引用但答案错；仅用于缩小人工审计范围，不是假引用率",
        },
        "metrics": [],
    }
    for item in results:
        p, method = mcnemar(item.b, item.c)
        low, high = newcombe_paired(item.a, item.b, item.c, item.d)
        payload["metrics"].append(
            {
                "指标": item.name,
                "口径": item.note,
                "n": item.n,
                "a_both_ok": item.a,
                "b_base_only": item.b,
                "c_tuned_only": item.c,
                "d_both_wrong": item.d,
                "base_rate": round(item.base_rate, 4),
                "tuned_rate": round(item.tuned_rate, 4),
                "delta_pp": round(item.delta_pp, 2),
                "mcnemar_p": round(p, 6),
                "mcnemar_method": method,
                "ci95_pp": [round(low * 100, 2), round(high * 100, 2)],
                "统计判定": judge(item, p, low, high),
                "分辨率": resolution(item.n),
            }
        )

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "stat_test.json"
    md_path = out_dir / "stat_test.md"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    md = render_md(results, payload)
    md_path.write_text(md, encoding="utf-8")
    print(md)
    print(f"\n[json] {json_path}\n[md] {md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
