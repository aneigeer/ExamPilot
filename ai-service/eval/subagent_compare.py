"""Subagent 收益评审：同一任务集跑两遍（off / on），产出可复核的对比报告。

为什么必须跑两遍同一任务集：throughput/延迟 与 通过率 只有在**同一份题目 + 同一份
判定规则**下比较才有意义。本脚本因此：

* 任务集固定为 ``eval/agent_tasks.jsonl``；
* 判定规则直接复用 :func:`eval.agent_eval.judge`（**不放宽任何一条**：
  工具序列 / 禁止工具 / 工具成功 / 必需内容 / 步数上限）；
* 两个 arm 唯一的差异是 ``AGENT_SUBAGENT_ENABLED``。

运行模式（报告头会原样写明，禁止读者误读数据）：

* ``rule``（默认）：真实链路 + 规则规划器。没有 ``LLM_API_KEY`` 时这是唯一诚实的模式，
  结论只能说明"规则模式下的结构开销"，**不代表模型效果**。
* ``sim``：脚本化的"模型规划器"（规则等价，仅提供 final 答案文本）。用于观察
  "拆解成功 + 子问题串行"这条路径本身的结构开销，**同样不代表真实模型效果**。

数据来源（报告头会写）：

* ``real``：Java 后端连通，工具数据来自真实接口；
* ``offline``：Java 未连通，工具数据由本地确定性桩桥生成 —— 只衡量 Agent 循环结构，
  不代表真实检索质量。

用法::

    python -m eval.subagent_compare --limit 40
    python -m eval.subagent_compare --planner both
"""

from __future__ import annotations

import argparse
import asyncio
import html
import json
import time
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from app.agent.harness import AgentHarness
from app.agent.planner import Decision, RulePlanner
from app.agent.tools import AgentContext
from app.clients.java_bridge import JavaBridge, JavaBridgeError
from app.config import AgentSettings, Settings, get_settings

from .agent_eval import load_tasks, judge, percentile
from .report import REPORTS_DIR, ensure_reports_dir

#: 报告文件名时间戳格式（分钟级：同一分钟内重复运行会覆盖，避免报告目录爆掉）
STAMP_FORMAT = "%Y%m%d_%H%M"

#: 收益评审的出口阈值（阶段四验收口径，写死在代码里防止事后改口）
MIN_PASS_GAIN_PP = 5.0        # 通过率至少提升 5 个百分点
MAX_P95_INCREASE = 0.5        # P95 延迟上升不得超过 50%

NO_LLM_NOTICE = "规则模式，不代表模型效果"


# --------------------------------------------------------------------------------------
# 数据类
# --------------------------------------------------------------------------------------


@dataclass
class ArmResult:
    """一个 arm（off / on）在同一任务集上的完整结果。"""

    key: str
    label: str
    planner_mode: str
    total: int = 0
    passed: int = 0
    steps: List[int] = field(default_factory=list)
    tool_calls: int = 0
    latencies: List[float] = field(default_factory=list)
    degraded: int = 0
    rows: List[Dict[str, Any]] = field(default_factory=list)

    @property
    def pass_rate(self) -> float:
        return (self.passed / self.total * 100.0) if self.total else 0.0

    @property
    def avg_steps(self) -> float:
        return (sum(self.steps) / len(self.steps)) if self.steps else 0.0

    @property
    def avg_tool_calls(self) -> float:
        return (self.tool_calls / self.total) if self.total else 0.0

    @property
    def avg_ms(self) -> float:
        return (sum(self.latencies) / len(self.latencies)) if self.latencies else 0.0

    @property
    def p95_ms(self) -> float:
        return round(float(percentile(self.latencies, 0.95)), 2)

    @property
    def degraded_rate(self) -> float:
        return (self.degraded / self.total * 100.0) if self.total else 0.0

    def failures(self, limit: int = 5) -> List[Dict[str, Any]]:
        """失败样例（按出现顺序取前 ``limit`` 条）。"""
        return [row for row in self.rows if row.get("result") == "FAIL"][:limit]

    def summary(self) -> Dict[str, Any]:
        return {
            "arm": self.key,
            "label": self.label,
            "planner": self.planner_mode,
            "tasks": self.total,
            "passed": self.passed,
            "pass_rate": round(self.pass_rate, 2),
            "avg_steps": round(self.avg_steps, 2),
            "tool_calls_total": self.tool_calls,
            "tool_calls_avg": round(self.avg_tool_calls, 2),
            "avg_ms": round(self.avg_ms, 2),
            "p95_ms": self.p95_ms,
            "degraded": self.degraded,
            "degraded_rate": round(self.degraded_rate, 2),
        }


# --------------------------------------------------------------------------------------
# 确定性桩桥（Java 未连通时使用；两端完全一致，保证对比公平）
# --------------------------------------------------------------------------------------


class OfflineBridge:  # pragma: no cover - 仅评估脚本使用
    """离线确定性桩桥：**永不联网**，结果只由入参决定。

    与 `JavaBridge` 的方法签名保持一致，可直接塞进 :class:`AgentHarness`。
    任何"外部世界"的信息（检索质量、真实业务数据）都不参与，因此本脚本的
    通过率只反映 **Agent 循环结构**，报告头会显式声明这一点。
    """

    def __init__(self, *, allow_decompose: bool) -> None:
        self.allow_decompose = allow_decompose
        self.llm_calls: List[str] = []

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        self.llm_calls.append("decompose" if "任务拆解器" in system_prompt else "plan")
        if "任务拆解器" in system_prompt:
            if not self.allow_decompose:
                raise JavaBridgeError("离线模式：无 LLM 可用")
            return json.dumps(
                {"sub_questions": _split_question(_extract_question(user_prompt))},
                ensure_ascii=False,
            )
        raise JavaBridgeError("离线模式：无 LLM 可用")

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        query = str(payload.get("question") or payload.get("query") or "").strip()
        return {
            "contexts": [
                {
                    "chunkId": 1,
                    "title": query[:40] or "站内资料",
                    "content": f"关于「{query}」的站内资料片段（离线桩）",
                    "refType": "QUESTION",
                    "refId": 1,
                    "score": 0.9,
                }
            ],
            "bestScore": 0.9,
            "mode": "OFFLINE_STUB",
            "hits": [
                {
                    "id": 1,
                    "slug": "offline-wiki",
                    "title": query[:40] or "专题页",
                    "summary": f"关于「{query}」的专题摘要（离线桩）",
                    "content": f"关于「{query}」的专题正文（离线桩）",
                    "score": 0.8,
                    "channel": "OFFLINE",
                }
            ],
        }

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if path.endswith("weak-knowledge"):
            return [
                {"knowledgeId": 1, "name": "C语言基础", "mastery": 0.3, "weakness": 0.7},
                {"knowledgeId": 2, "name": "数据类型与变量", "mastery": 0.4, "weakness": 0.6},
            ]
        if path.endswith("wrong-book"):
            return [
                {"questionId": 11, "stem": "C语言 int 占几个字节", "wrongCount": 2},
                {"questionId": 12, "stem": "指针与数组的区别", "wrongCount": 1},
            ]
        if path.endswith("study-plan"):
            return {
                "plannedCount": 20,
                "doneCount": 8,
                "completionPercent": 40,
                "items": [
                    {"knowledgeName": "C语言基础", "targetCount": 10, "reason": "薄弱"},
                    {"knowledgeName": "数据类型与变量", "targetCount": 10, "reason": "薄弱"},
                ],
            }
        return {
            "id": 11,
            "stem": "C语言 int 占几个字节",
            "options": ["2", "4", "8"],
            "analysis": "32/64 位平台 int 通常为 4 字节",
        }

    async def full_answer(
        self,
        question: str,
        subject_id: Optional[int] = None,
        draft_answer: str = "",
        contexts: Optional[List[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        """确定性定稿：草稿优先；无草稿时用问题 + 上下文首条合成。

        ⚠️ 合成文本会带上原始问题，因此判定里的"答案必需内容"维度在两个 arm 上
        都容易满足 —— 该维度在本脚本中**不具备区分度**，报告头会写明。
        """
        if draft_answer.strip():
            answer = draft_answer.strip()
        else:
            head = ""
            for item in contexts or []:
                head = str(item.get("title") or item.get("content") or "")[:60]
                if head:
                    break
            answer = f"关于「{question}」，{head or '站内暂无相关资料'}（离线桩合成）"
        return {
            "answer": answer,
            "generator": "offline-stub",
            "degraded": True,
            "refused": False,
            "sources": [{"chunkId": 1}] if contexts else [],
        }


def _extract_question(prompt: str) -> str:
    """从规划提示词里取回当前问题（``【学生问题】`` 之后到空行为止）。"""
    marker = "【学生问题】"
    index = prompt.find(marker)
    if index < 0:
        return prompt.strip()[:120]
    tail = prompt[index + len(marker):]
    return tail.split("\n\n")[0].strip()


def _split_question(question: str) -> List[str]:
    """把问题拆成 1-3 个子问题（确定性规则，供离线模拟拆解使用）。"""
    text = (question or "").strip()
    if not text:
        return []
    separators = ["，然后", "，再", "然后再", "并且", "，", ",", "；", ";", "和"]
    parts = [text]
    for sep in separators:
        if len(parts) >= 3:
            break
        expanded: List[str] = []
        for part in parts:
            pieces = [piece.strip() for piece in part.split(sep) if piece.strip()]
            expanded.extend(pieces if pieces else [part])
        if len(expanded) > len(parts):
            parts = expanded
    parts = parts[:3]
    if len(parts) == 1:
        # 拆不开时补一个"依据"类子问题，模拟模型"先看资料再回答"的行为
        parts = [text, f"{text} 相关的站内资料有哪些"]
    return parts


class SimulatedPlanner:
    """脚本化的"模型规划器"：与 :class:`RulePlanner` 判定等价，但会给出 final 答案。

    存在意义：让 on-arm 能真正走到"拆解 → 子问题 → 汇总"，而不是每次都在拆解处
    降级。**它不是模型**，产出的通过率仅供参考（报告头会标注）。
    """

    def __init__(self) -> None:
        self._rule = RulePlanner()

    async def plan(
        self,
        ctx: AgentContext,
        observations: List[Dict[str, Any]],
        policy: Optional[Any] = None,
    ) -> Decision:
        decision = self._rule.plan(ctx, observations)
        if decision.action == "final" and not decision.answer:
            summaries = [
                str(item.get("summary") or "")
                for item in observations
                if item.get("ok")
            ]
            decision.answer = (
                f"关于「{ctx.question}」："
                + ("；".join(summaries[:3]) if summaries else "站内暂无相关资料")
            )
        return decision


# --------------------------------------------------------------------------------------
# 单 arm 执行
# --------------------------------------------------------------------------------------


def _arm_settings(base: AgentSettings, enabled: bool) -> AgentSettings:
    """构造 arm 的 Agent 配置（只改 subagent 开关，其余完全一致）。"""
    return replace(base, subagent_enabled=enabled)


async def run_arm(
    tasks: Sequence[Dict[str, Any]],
    *,
    key: str,
    label: str,
    planner_mode: str,
    enabled: bool,
    base_agent: AgentSettings,
    bridge: Any,
) -> ArmResult:
    """在给定任务集上跑一个 arm，返回逐任务结果 + 聚合指标。"""
    settings = _arm_settings(base_agent, enabled)
    harness = AgentHarness(settings, bridge)
    if planner_mode == "sim":
        harness.llm_planner = SimulatedPlanner()  # type: ignore[assignment]

    arm = ArmResult(key=key, label=label, planner_mode=planner_mode, total=len(tasks))
    for task in tasks:
        profile = task.get("profile") or {}
        ctx = AgentContext(
            question=str(task.get("goal") or ""),
            user_id=profile.get("userId"),
            subject_id=profile.get("subjectId"),
            weak_knowledge_ids=[int(x) for x in (profile.get("weakKnowledgeIds") or [])],
            trace_id=str(task.get("id") or ""),
        )
        started = time.perf_counter()
        try:
            result = await harness.collect(ctx)
        except Exception as exc:  # noqa: BLE001
            elapsed = round((time.perf_counter() - started) * 1000, 3)
            arm.latencies.append(elapsed)
            arm.rows.append(
                {
                    "id": task.get("id"),
                    "goal": task.get("goal"),
                    "tools": "-",
                    "result": "ERROR",
                    "reason": f"{type(exc).__name__}: {exc}"[:120],
                    "steps": 0,
                    "tool_calls": 0,
                    "ms": elapsed,
                    "subagent": enabled,
                    "degraded_reason": "",
                }
            )
            continue
        elapsed = round((time.perf_counter() - started) * 1000, 3)
        arm.latencies.append(elapsed)

        reasons = judge(task, result)
        if not reasons:
            arm.passed += 1
        arm.steps.append(int(result.get("step_count") or 0))
        arm.tool_calls += int(result.get("tool_calls") or 0)
        if result.get("degraded_reason"):
            arm.degraded += 1
        events = result.get("events") or []
        actual_tools = [
            str(item["data"].get("tool"))
            for item in events
            if item.get("event") == "tool_call"
        ]
        arm.rows.append(
            {
                "id": task.get("id"),
                "goal": task.get("goal"),
                "tools": " → ".join(actual_tools) or "-",
                "result": "PASS" if not reasons else "FAIL",
                "reason": "；".join(reasons),
                "steps": result.get("step_count"),
                "tool_calls": result.get("tool_calls"),
                "ms": elapsed,
                "subagent": bool(result.get("subagent")),
                "degraded_reason": str(result.get("degraded_reason") or ""),
            }
        )
    return arm


# --------------------------------------------------------------------------------------
# 结论
# --------------------------------------------------------------------------------------


def conclude(off: ArmResult, on: ArmResult) -> Dict[str, Any]:
    """按阶段四出口条件给结论（**不为好看而放宽**）。"""
    delta_pp = on.pass_rate - off.pass_rate
    if off.p95_ms > 0:
        p95_ratio = (on.p95_ms - off.p95_ms) / off.p95_ms
    else:
        p95_ratio = 0.0 if on.p95_ms == 0 else 1.0
    gained = delta_pp >= MIN_PASS_GAIN_PP
    too_slow = p95_ratio > MAX_P95_INCREASE
    if not gained or too_slow:
        reasons: List[str] = []
        if not gained:
            reasons.append(
                f"通过率提升 {delta_pp:+.1f}pp < 阈值 {MIN_PASS_GAIN_PP:.1f}pp"
            )
        if too_slow:
            reasons.append(
                f"P95 延迟上升 {p95_ratio * 100:+.1f}% > 阈值 {MAX_P95_INCREASE * 100:.0f}%"
            )
        verdict = "不建议启用（保持默认关）"
        detail = "；".join(reasons)
    else:
        verdict = f"在 {on.total} 条任务上更优，需更大样本复核"
        detail = f"通过率 {delta_pp:+.1f}pp，P95 {p95_ratio * 100:+.1f}%"
    return {
        "verdict": verdict,
        "detail": detail,
        "pass_delta_pp": round(delta_pp, 2),
        "p95_delta_ratio": round(p95_ratio, 4),
        "thresholds": {
            "min_pass_gain_pp": MIN_PASS_GAIN_PP,
            "max_p95_increase_ratio": MAX_P95_INCREASE,
        },
    }


# --------------------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------------------

_CSS = """
:root{--ink:#182230;--muted:#5c6472;--line:#e7ebf2;--brand:#315fea}
*{box-sizing:border-box}
body{margin:0;padding:32px;background:#f4f6fa;color:var(--ink);
     font-family:"Segoe UI","Microsoft YaHei",sans-serif}
h1{font-size:24px;margin:0 0 6px}
h2{font-size:16px;margin:26px 0 10px}
.meta{color:var(--muted);font-size:13px;margin-bottom:18px;line-height:1.8}
.warn{background:#fff5f5;border:1px solid #f3c6c6;color:#a3343a;border-radius:10px;
      padding:12px 14px;font-size:13px;margin-bottom:18px;line-height:1.7}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:18px}
.card{background:#fff;border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.k{font-size:12px;color:var(--muted)}
.v{font-size:22px;font-weight:700;color:#202d42;margin-top:6px}
.panel{background:#fff;border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:18px}
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef1f6;vertical-align:top}
th{background:#f8f9fc;color:#67758a;font-weight:600}
td.fail{color:#c24145}
.note{margin-top:14px;color:var(--muted);font-size:12px;white-space:pre-wrap;line-height:1.8}
"""


def _table(columns: Sequence[Tuple[str, str]], rows: Sequence[Dict[str, Any]]) -> str:
    head = "".join(f"<th>{html.escape(label)}</th>" for _, label in columns)
    body: List[str] = []
    for row in rows:
        css = "fail" if row.get("_fail") else ""
        cells = "".join(
            f'<td class="{css}">{html.escape(str(row.get(key, "")))}</td>'
            for key, _ in columns
        )
        body.append(f"<tr>{cells}</tr>")
    return f"<table><thead><tr>{head}</tr></thead><tbody>{''.join(body)}</tbody></table>"


def _cards(metrics: Dict[str, Any]) -> str:
    return "".join(
        f'<div class="card"><div class="k">{html.escape(str(k))}</div>'
        f'<div class="v">{html.escape(str(v))}</div></div>'
        for k, v in metrics.items()
    )


def render_html(
    *,
    path: Path,
    header: Dict[str, str],
    warnings_text: str,
    summary_rows: Sequence[Dict[str, Any]],
    detail_rows: Sequence[Dict[str, Any]],
    failure_rows: Sequence[Dict[str, Any]],
    verdict: Dict[str, Any],
    note: str,
) -> None:
    """渲染自包含 HTML（双栏对比版式，与 report.py 的单表版式不同）。"""
    header_html = "".join(
        f"{html.escape(key)}：{html.escape(str(value))}<br>" for key, value in header.items()
    )
    summary_columns = [
        ("arm", "实验组"),
        ("label", "说明"),
        ("planner", "规划器"),
        ("tasks", "任务数"),
        ("pass_rate", "通过率 %"),
        ("avg_steps", "平均步数"),
        ("tool_calls_total", "工具调用总数"),
        ("tool_calls_avg", "工具调用/任务"),
        ("avg_ms", "平均延迟 ms"),
        ("p95_ms", "P95 延迟 ms"),
        ("degraded_rate", "降级率 %"),
    ]
    failure_columns = [
        ("arm", "实验组"),
        ("id", "任务"),
        ("goal", "目标"),
        ("tools", "实际工具链"),
        ("result", "结果"),
        ("reason", "失败原因"),
        ("steps", "步数"),
        ("tool_calls", "工具调用"),
        ("ms", "耗时 ms"),
        ("degraded_reason", "降级原因"),
    ]
    detail_columns = [
        ("arm", "实验组"),
        ("id", "任务"),
        ("goal", "目标"),
        ("tools", "实际工具链"),
        ("result", "结果"),
        ("reason", "失败原因"),
        ("steps", "步数"),
        ("tool_calls", "工具调用"),
        ("ms", "耗时 ms"),
    ]
    document = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>Subagent 收益评审（off vs on）</title>
<style>{_CSS}</style></head><body>
<h1>Subagent 收益评审（off vs on，同一任务集）</h1>
<div class="meta">{header_html}生成时间：{datetime.now().strftime("%Y-%m-%d %H:%M:%S")} ｜ 报告：{html.escape(str(path))}</div>
<div class="warn">{html.escape(warnings_text)}</div>
<div class="cards">{_cards({"结论": verdict["verdict"], "判定依据": verdict["detail"]})}</div>
<h2>实验组对比（同任务集、同判定规则）</h2>
<div class="panel">{_table(summary_columns, summary_rows)}</div>
<h2>失败样例 Top5（每个开启组）</h2>
<div class="panel">{_table(failure_columns, failure_rows) or "<div class='note'>无失败样例</div>"}</div>
<h2>逐任务明细</h2>
<div class="panel">{_table(detail_columns, detail_rows)}</div>
<div class="note">{html.escape(note)}</div>
</body></html>"""
    path.write_text(document, encoding="utf-8")


# --------------------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------------------


async def _probe_java(settings: Settings) -> bool:
    """探测 Java 是否连通（任何 HTTP 响应都算连通；连接失败才算离线）。"""
    try:
        import httpx  # type: ignore[import-not-found]
    except ImportError:  # pragma: no cover
        return False
    try:
        async with httpx.AsyncClient(timeout=2.0, trust_env=False) as client:
            await client.get(settings.agent.java_base_url)
        return True
    except Exception:  # noqa: BLE001
        return False


async def run(
    limit: Optional[int] = None,
    planner: str = "rule",
    force_offline: bool = False,
) -> Dict[str, Any]:
    """执行 off / on 两遍（可选两种规划器模式），写报告并返回结构化结果。

    Args:
        limit: 限制任务条数（快速冒烟用；正式评审不要传）。
        planner: ``rule``（默认）/ ``sim`` / ``both``。
        force_offline: 强制使用离线桩桥（不探测 Java）。

    Returns:
        含两（或四）个 arm 指标、结论、报告路径的字典。
    """
    settings = get_settings()
    tasks = load_tasks(limit)
    java_up = False if force_offline else await _probe_java(settings)
    data_source = "real" if java_up else "offline"
    llm_configured = bool(settings.llm.api_key)

    modes = ["rule", "sim"] if planner == "both" else [planner]
    arms: List[ArmResult] = []
    for mode in modes:
        for enabled in (False, True):
            key = f"{mode}-{'on' if enabled else 'off'}"
            label = f"{'开启' if enabled else '关闭'}（{'模型模拟' if mode == 'sim' else '规则'}）"
            # 每次都用全新的桥：避免上一 arm 的调用痕迹影响下一 arm
            bridge: Any = (
                JavaBridge(settings.agent) if java_up
                else OfflineBridge(allow_decompose=(mode == "sim"))
            )
            arm = await run_arm(
                tasks,
                key=key,
                label=label,
                planner_mode=mode,
                enabled=enabled,
                base_agent=settings.agent,
                bridge=bridge,
            )
            arms.append(arm)

    pairs: List[Dict[str, Any]] = []
    for mode in modes:
        off = next(arm for arm in arms if arm.key == f"{mode}-off")
        on = next(arm for arm in arms if arm.key == f"{mode}-on")
        pairs.append({"mode": mode, "off": off, "on": on, "verdict": conclude(off, on)})

    # 结论取"最有利"的那个模式？不 —— 取 rule 模式（默认、真实可复现）为主结论。
    primary = pairs[0]

    ensure_reports_dir()
    stamp = datetime.now().strftime(STAMP_FORMAT)
    html_path = REPORTS_DIR / f"subagent_compare_{stamp}.html"
    json_path = REPORTS_DIR / f"subagent_compare_{stamp}.json"

    planner_label = {
        "rule": "规则规划器（LLM 不可用时自动降级）",
        "sim": "模型规划器（脚本模拟，规则等价）",
    }
    header = {
        "运行模式": "；".join(planner_label[mode] for mode in modes),
        "模型名": settings.llm.model if llm_configured else "未配置 LLM_API_KEY",
        "工具数据来源": "Java 真实接口" if java_up else "本地离线桩桥（Java 未连通）",
        "任务数": str(len(tasks)),
        "日期": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "任务集": "eval/agent_tasks.jsonl",
    }
    warnings: List[str] = []
    if not llm_configured or not java_up:
        warnings.append(
            NO_LLM_NOTICE
            + "：本次没有任何一次真实模型调用，通过率/延迟只反映 Agent 循环结构开销，"
            "不得作为「模型效果」对外引用。\n"
        )
    if not java_up:
        warnings.append(
            "工具数据由本地确定性桩桥生成（Java 未连通）：真实检索质量未参与评估。"
            "桩桥的工具成功率恒为 100%、定稿文本包含原始问题，"
            "因此「工具调用失败」与「答案必需内容」两个维度在本报告中不具区分度 —— "
            "本报告真正衡量的是「工具序列 / 禁止工具 / 步数预算」三项结构指标。\n"
        )
    if "sim" in modes:
        warnings.append(
            "包含「模型模拟」组：规划由脚本按规则等价产生，"
            "仅用于观察拆解路径的结构开销。\n"
        )
    warnings_text = "".join(warnings) or "无（真实链路 + 真实模型）"

    summary_rows = [arm.summary() for arm in arms]
    failure_rows: List[Dict[str, Any]] = []
    for mode in modes:
        on = next(arm for arm in arms if arm.key == f"{mode}-on")
        for row in on.failures(5):
            failure_rows.append({"arm": on.label, **row, "_fail": True})
    detail_rows: List[Dict[str, Any]] = []
    for arm in arms:
        for row in arm.rows:
            detail_rows.append({"arm": arm.label, **row, "_fail": row.get("result") != "PASS"})

    note = (
        "判定规则（与 eval/agent_eval.py 完全一致，未做任何放宽）：\n"
        "  期望工具序列按序出现（允许额外只读工具）+ 未调用禁止工具 + 全部工具调用成功\n"
        "  + 最终答案包含全部必需 token + step_count ≤ max_steps。\n"
        f"出口阈值：通过率提升 ≥ {MIN_PASS_GAIN_PP:.0f} 个百分点，且 P95 延迟上升 ≤ "
        f"{MAX_P95_INCREASE * 100:.0f}%；任一不满足即结论为「不建议启用」。\n"
        "两个 arm 唯一的差异是 AGENT_SUBAGENT_ENABLED；on-arm 内 max_tool_calls 与 max_steps\n"
        "  都是**会话级共享**预算（3 个子问题 ≠ 3×4 次工具、3×6 步），\n"
        "  on-arm 的 step_count = 各子问题步数之和（会话级真实开销），因此与 off-arm 可直接比较。\n"
        f"通过率为主结论依据的模式：{primary['mode']}。"
    )
    render_html(
        path=html_path,
        header=header,
        warnings_text=warnings_text,
        summary_rows=summary_rows,
        detail_rows=detail_rows,
        failure_rows=failure_rows,
        verdict=primary["verdict"],
        note=note,
    )

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "stamp": stamp,
        "header": header,
        "warnings": [w for w in warnings],
        "thresholds": primary["verdict"]["thresholds"],
        "arms": [arm.summary() for arm in arms],
        "comparisons": [
            {
                "mode": pair["mode"],
                "off": pair["off"].summary(),
                "on": pair["on"].summary(),
                "verdict": pair["verdict"],
                "failures_on": pair["on"].failures(5),
            }
            for pair in pairs
        ],
        "verdict": primary["verdict"],
        "report_html": str(html_path),
        "report_json": str(json_path),
        "detail": {arm.key: arm.rows for arm in arms},
    }
    json_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return payload


def main() -> None:
    parser = argparse.ArgumentParser(description="Subagent 收益评审（off vs on）")
    parser.add_argument("--limit", type=int, default=None, help="只跑前 N 条任务（冒烟用）")
    parser.add_argument(
        "--planner",
        choices=["rule", "sim", "both"],
        default="rule",
        help="rule=规则规划器（默认）；sim=脚本模拟模型；both=两种都跑",
    )
    parser.add_argument(
        "--offline", action="store_true", help="强制使用离线桩桥（不探测 Java）"
    )
    args = parser.parse_args()
    result = asyncio.run(run(args.limit, args.planner, args.offline))
    for comparison in result["comparisons"]:
        off, on = comparison["off"], comparison["on"]
        print(
            f"[{comparison['mode']}] off 通过率 {off['pass_rate']}% / 步数 {off['avg_steps']}"
            f" / P95 {off['p95_ms']}ms  →  on 通过率 {on['pass_rate']}%"
            f" / 步数 {on['avg_steps']} / P95 {on['p95_ms']}ms"
        )
    print("结论：", result["verdict"]["verdict"], "—", result["verdict"]["detail"])
    print("报告：", result["report_html"])
    print("数据：", result["report_json"])


if __name__ == "__main__":
    main()
