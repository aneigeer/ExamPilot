"""Agent Harness：规划 → 工具 → 观察 → 回答。

约束：
* 最多 ``max_steps`` 步、``max_tool_calls`` 次工具调用；
* prompt 超过字符预算时从最老的工具结果开始丢弃，并发 ``budget_truncated`` 事件；
* 最终答案统一交给 Java（L3 校验 + 抽取式兜底），Python 不直接对外定稿；
* 取消（客户端断开）时 ``asyncio.CancelledError`` 必须原样抛出。

Subagent（阶段四受控实验，``AGENT_SUBAGENT_ENABLED``，**默认关**）：
* 关闭时 :meth:`AgentHarness.run` 直接走 :meth:`_run_single`，事件序列与单 Agent 逐条一致；
* 开启时先拆解子问题，再逐个子问题复用同一套 ``planner/tools/context_manager``，
  **工具预算与步数预算都是全局共享**（见 :class:`~app.agent.subagent.ToolBudget`
  与 :class:`~app.agent.subagent.StepBudget`；3 个子问题不是 3×4 次工具、3×6 步），
  最后把全部子答案 + 原始问题交给一次 Java 定稿；
* 任一环节失败 → 回退 :meth:`_run_single`，并在 ``final`` 事件写 ``degradedReason``，
  **绝不向上抛异常**（SSE 出口只允许出现 error 事件的既有语义，不新增 500）。
"""

from __future__ import annotations

import asyncio
import json
import re
import logging
import secrets
import time
from dataclasses import replace
from typing import Any, AsyncIterator, Dict, List, Optional

from ..clients.java_bridge import (JavaBridge, JavaBridgeError, llm_usage_snapshot,
                                   reset_llm_usage)
from ..observability.otel import span
from ..config import SUBAGENT_MAX_QUESTIONS_LIMIT, AgentSettings
from .events import (
    EVENT_APPROVAL_REQUIRED,
    EVENT_BUDGET,
    EVENT_DONE,
    EVENT_FINAL,
    EVENT_META,
    EVENT_THOUGHT,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    event,
)
from .context_manager import ContextPolicy, build_context
from .planner import (
    PROMPT_VERSION,
    TOOL_NAMES,
    Decision,
    LlmPlanner,
    PlannerError,
    RulePlanner,
)
from .subagent import (
    SubagentError,
    StepBudget,
    SubAttempt,
    ToolBudget,
    decompose,
    render_sub_answers,
)
from .tools import AgentContext, ToolRegistry, ToolResult, requires_approval, risk_of

logger = logging.getLogger(__name__)

REFUSAL_TEXT = "未找到相关资料"

#: 上游（Java 定稿）失败时的兜底文案：必须与业务拒答 ``REFUSAL_TEXT`` 可区分，
#: 否则 Java 侧只能把上游 5xx 记成 REFUSED，故障在失败率统计里不可见。
UPSTREAM_ERROR_TEXT = "回答生成服务暂时不可用（上游故障），请稍后重试。"

#: 上游失败的显式错误码（写入 ``final.errorCode``，供 Java 落终态 FAILED）。
UPSTREAM_ERROR_CODE = "AGENT_UPSTREAM_ERROR"

#: 子步骤 thought 文本的固定前缀（Java 侧按它区分"子 Agent 的思考"与"主 Agent 的思考"）
SUBAGENT_PREFIX = "[subagent] "


class AgentHarness:
    """一次 Agent 会话的执行器（无状态，可并发复用）。"""

    def __init__(self, settings: AgentSettings, bridge: JavaBridge) -> None:
        self.settings = settings
        self.bridge = bridge
        self.tools = ToolRegistry(bridge)
        self.llm_planner = LlmPlanner(bridge)
        self.rule_planner = RulePlanner()

    # ------------------------------------------------------------------ 入口

    async def run(self, ctx: AgentContext) -> AsyncIterator[Dict[str, Any]]:
        """执行一次会话，按需路由到单 Agent 或 subagent 实验路径。"""
        started = time.perf_counter()
        reset_llm_usage()
        policy = ContextPolicy(max_chars=self.settings.max_prompt_chars)
        yield event(EVENT_META, self._meta_payload(ctx, policy))

        if self.settings.subagent_enabled:
            async for item in self._run_subagent(ctx, policy, started):
                yield item
            return

        # ---------- 单 Agent（基线路径，与阶段二/三完全一致） ----------
        attempt = SubAttempt(question=ctx.question)
        budget = ToolBudget(limit=max(0, self.settings.max_tool_calls))
        try:
            # 留 50ms 调度余量，保证外部观测到的总耗时不会越过配置 deadline
            async with asyncio.timeout(
                max(0.01, self.settings.session_timeout_seconds - 0.05)
            ):
                approval_paused = False
                async for item in self._agent_loop(ctx, policy, budget, attempt):
                    if item.get("event") == EVENT_APPROVAL_REQUIRED:
                        approval_paused = True
                    yield item
                if approval_paused:
                    yield event(
                        EVENT_DONE,
                        {
                            "totalMs": int((time.perf_counter() - started) * 1000),
                            "waitingApproval": True,
                        },
                    )
                else:
                    async for item in self._emit_final(ctx, attempt, attempt.observations, started):
                        yield item
        except TimeoutError:
            logger.warning(
                "[Agent] 会话超过 deadline %.1fs，提前截止",
                self.settings.session_timeout_seconds,
            )
            yield event(
                EVENT_FINAL,
                {
                    "answer": "会话超时，已截止",
                    "generator": "agent-rule",
                    "degraded": True,
                    "refused": False,
                    "sources": [],
                    "degradedReason": (
                        f"session deadline 截止（>{self.settings.session_timeout_seconds}s）"
                    ),
                    "step_count": attempt.steps,
                    "tool_calls": attempt.tool_calls,
                },
            )
            yield event(
                EVENT_DONE,
                {"totalMs": int((time.perf_counter() - started) * 1000)},
            )

    async def collect(self, ctx: AgentContext) -> Dict[str, Any]:
        """非流式执行（离线评估复用）。"""
        events: List[Dict[str, Any]] = []
        async for item in self.run(ctx):
            events.append(item)
        final = next((e["data"] for e in events if e["event"] == EVENT_FINAL), {})
        approval = next((e["data"] for e in events if e["event"] == EVENT_APPROVAL_REQUIRED), None)
        return {
            "events": events,
            "final": final,
            "answer": final.get("answer", REFUSAL_TEXT),
            "generator": final.get("generator", "none"),
            "degraded": final.get("degraded", True),
            "refused": final.get("refused", False),
            "step_count": final.get("step_count", 0),
            "tool_calls": final.get("tool_calls", 0),
            "waiting_approval": approval is not None,
            "approval": approval,
            # 受控实验观测字段（不进 HTTP 响应模型，仅供离线评估/调试读取）
            "subagent": bool(final.get("subagent", False)),
            "degraded_reason": str(final.get("degradedReason") or ""),
        }

    # ------------------------------------------------------------------ meta / final

    def _meta_payload(self, ctx: AgentContext, policy: ContextPolicy) -> Dict[str, Any]:
        """构造 ``meta`` 事件数据（关闭态与单 Agent 基线字段一致，只多一个 ``subagent``）。"""
        max_steps = max(1, self.settings.max_steps)
        max_tools = max(0, self.settings.max_tool_calls)
        initial = build_context(
            question=ctx.question,
            history=ctx.history,
            memory_summary=ctx.memory_summary,
            weak_knowledge_ids=ctx.weak_knowledge_ids,
            observations=[],
            policy=policy,
            trace_id=ctx.trace_id,
        )
        payload: Dict[str, Any] = {
            "traceId": ctx.trace_id,
            "sessionId": ctx.session_id,
            "question": ctx.question,
            "maxSteps": max_steps,
            "maxToolCalls": max_tools,
            "userId": ctx.user_id,
            "subjectId": ctx.subject_id,
            "historyTurns": len(ctx.history),
            "memoryChars": len(ctx.memory_summary or ""),
            "promptVersion": PROMPT_VERSION,
            "contextChars": len(initial.text),
            "contextTokens": initial.estimated_tokens,
            "contextBudget": policy.max_chars,
            # ★ 受控实验标记：false = 走单 Agent 基线路径（默认）；true = 走了拆解路径
            "subagent": bool(self.settings.subagent_enabled),
        }
        if self.settings.subagent_enabled:
            payload["subagentMaxQuestions"] = self._subagent_limit()
        return payload

    async def _emit_final(
        self,
        ctx: AgentContext,
        attempt: SubAttempt,
        observations: List[Dict[str, Any]],
        started: float,
        *,
        extra: Optional[Dict[str, Any]] = None,
        force_degraded: bool = False,
    ) -> AsyncIterator[Dict[str, Any]]:
        """定稿并发出 ``final`` + ``done``（两条路径共用，保证字段口径一致）。"""
        contexts: List[Dict[str, Any]] = []
        for item in observations:
            contexts.extend(item.get("contexts") or [])
        final = await self._finalize(ctx, attempt.draft, contexts, observations)
        data: Dict[str, Any] = {
            "answer": final.get("answer") or REFUSAL_TEXT,
            "generator": final.get("generator") or "none",
            "degraded": bool(final.get("degraded")),
            "refused": bool(final.get("refused")),
            "step_count": attempt.steps,
            "tool_calls": attempt.tool_calls,
            "sources": final.get("sources") or [],
        }
        # Token / 模型用量（由 Java 内部 LLM 代理返回的 usage 累加，缺字段时 estimated=true）
        data.update(llm_usage_snapshot())
        if extra:
            data.update(extra)
        # 上游失败要能被 Java 侧识别为 FAILED（而不是业务拒答 REFUSED）：
        # 错误码/降级原因必须在 final 顶层可见，且不被 extra 覆盖。
        for key in ("errorCode", "degradedReason"):
            if final.get(key) and key not in data:
                data[key] = final[key]
        if force_degraded:
            # 实验路径失败 → 答案其实来自单 Agent 回退，必须让 Java 侧看得出"这不是实验结果"
            data["degraded"] = True
        yield event(EVENT_FINAL, data)
        yield event(
            EVENT_DONE,
            {"totalMs": int((time.perf_counter() - started) * 1000)},
        )

    # ------------------------------------------------------------------ 单问题规划循环

    async def _agent_loop(
        self,
        ctx: AgentContext,
        policy: ContextPolicy,
        budget: ToolBudget,
        attempt: SubAttempt,
        *,
        prefix: str = "",
        step_base: int = 0,
        max_steps: Optional[int] = None,
    ) -> AsyncIterator[Dict[str, Any]]:
        """对**一个**问题跑"规划 → 工具 → 观察"循环，直到 final / 步数或预算耗尽。

        单 Agent 与每个子问题都走这一段：预算通过 ``budget`` 外部注入，
        因此 subagent 模式下多个子问题共享同一份额度是**结构上保证**的，
        而不是靠"记得别超"。

        Args:
            ctx: 本次循环的问题上下文（子问题模式传替换过 ``question`` 的副本）。
            policy: prompt 字符预算。
            budget: 共享工具预算（会被就地消耗）。
            attempt: 产出容器（就地写入 draft/observations/steps/tool_calls）。
            prefix: 事件文本前缀；空串 = 单 Agent 基线（逐字一致）。
            step_base: 步号偏移（子问题模式下让 step 字段单调递增，便于前端排布）。
            max_steps: 本次循环的步数上限；``None`` = 用配置值（单 Agent 基线）。

        Yields:
            内部事件对象（budget_truncated / thought / tool_call / tool_result）。
        """
        limit_steps = max(
            1, max_steps if max_steps is not None else self.settings.max_steps
        )
        observations = attempt.observations
        llm_available = True
        seen_tool_calls: Dict[str, bool] = {}

        while attempt.steps < limit_steps:
            attempt.steps += 1
            step = step_base + attempt.steps

            # ---------- 预算闸门：Context Manager 统一裁剪并显式上报 ----------
            bundle = build_context(
                question=ctx.question,
                history=ctx.history,
                memory_summary=ctx.memory_summary,
                weak_knowledge_ids=ctx.weak_knowledge_ids,
                observations=observations,
                policy=policy,
                trace_id=ctx.trace_id,
            )
            if bundle.dropped_observations or bundle.dropped_turns:
                yield event(
                    EVENT_BUDGET,
                    {
                        "droppedObservations": bundle.dropped_observations,
                        "droppedTurns": bundle.dropped_turns,
                        "remaining": len(observations) - bundle.dropped_observations,
                        "estimatedTokens": bundle.estimated_tokens,
                        "offloadedArtifacts": bundle.offloaded_artifacts,
                        "budgetChars": policy.max_chars,
                    },
                )

            # ---------- 规划 ----------
            decision: Optional[Decision] = None
            if llm_available:
                try:
                    decision = await self.llm_planner.plan(ctx, observations, policy)
                except PlannerError as exc:
                    llm_available = False
                    logger.warning("[Agent] LLM 规划不可用，切换规则规划器：%s", exc)
                    yield event(
                        EVENT_THOUGHT,
                        {"step": step, "text": prefix + "大模型规划不可用，已切换规则规划器"},
                    )
            if decision is None:
                decision = self.rule_planner.plan(ctx, observations)

            if decision.thought:
                yield event(EVENT_THOUGHT, {"step": step, "text": prefix + decision.thought})

            # ---------- 最终回答 ----------
            if decision.action == "final":
                attempt.draft = decision.answer
                break

            # ---------- 工具白名单（执行前硬校验） ----------
            # 未知工具（典型是本地小模型幻觉出的工具名）必须在执行前拦掉：
            # 不发 tool_call/tool_result、不扣预算、不访问 Java，只留一条可解释的 thought。
            if decision.tool not in TOOL_NAMES:
                logger.warning(
                    "[Agent] 拦截未知工具：%s（不在工具白名单 %d 个内，未执行）",
                    decision.tool, len(TOOL_NAMES),
                )
                yield event(
                    EVENT_THOUGHT,
                    {
                        "step": step,
                        "text": prefix
                        + f"未知工具「{decision.tool}」已拦截：不在工具白名单内，"
                        + "不执行、不消耗工具预算",
                    },
                )
                continue

            # ---------- 指代消解：检索前把「它/这个/那题」补成上一轮主题 ----------
            # 2026-11-13 实测：多轮里问「它和微调有什么区别」，检索词只有「它」，
            # 会命中完全无关的 Wiki 页。这里用上一轮问题补全检索语句。
            call_args = self._resolve_arguments(decision.tool, decision.arguments, ctx)

            # ---------- 重复调用去重（相同 tool + arguments 只执行一次） ----------
            call_signature = json.dumps(
                [decision.tool, call_args],
                ensure_ascii=False,
                sort_keys=True,
                default=str,
            )
            if call_signature in seen_tool_calls:
                yield event(
                    EVENT_THOUGHT,
                    {
                        "step": step,
                        "text": prefix + "重复调用已跳过：相同工具与参数不再执行、不重复扣预算",
                    },
                )
                continue
            seen_tool_calls[call_signature] = True

            # ---------- 高风险写操作人工审批挂起（HITL） ----------
            approved_set = set(ctx.approved_tools or ())
            if requires_approval(decision.tool) and decision.tool not in approved_set:
                approval_token = secrets.token_hex(12)
                yield event(
                    EVENT_APPROVAL_REQUIRED,
                    {
                        "step": step,
                        "tool": decision.tool,
                        "riskLevel": risk_of(decision.tool),
                        "argKeys": sorted(call_args.keys()) if isinstance(call_args, dict) else [],
                        "approvalToken": approval_token,
                        "reason": f"工具「{decision.tool}」属于高风险写操作，需人工审批确认后继续",
                    },
                )
                return

            # ---------- 工具调用（预算闸门：全局共享，跨子问题累计） ----------
            if not budget.try_consume():
                yield event(
                    EVENT_THOUGHT,
                    {"step": step, "text": prefix + "工具调用已达上限，直接进入最终回答"},
                )
                break
            attempt.tool_calls += 1
            yield event(
                EVENT_TOOL_CALL,
                {"step": step, "tool": decision.tool, "arguments": call_args},
            )
            tool_started = time.perf_counter()
            try:
                with span("agent.tool", {"examprep.tool.name": decision.tool}):
                    result = await asyncio.wait_for(
                        self.tools.execute(decision.tool, call_args, ctx),
                        timeout=max(0.01, self.settings.tool_timeout_seconds),
                    )
            except asyncio.TimeoutError:
                result = ToolResult(
                    ok=False,
                    summary=f"工具调用超时（>{self.settings.tool_timeout_seconds}s）",
                )
            elapsed = int((time.perf_counter() - tool_started) * 1000)
            if result.ok:
                error_code = "OK"
            elif str(result.summary).startswith("参数错误"):
                error_code = "PARAM_ERROR"
            elif "超时" in str(result.summary):
                error_code = "TOOL_TIMEOUT"
            else:
                error_code = "TOOL_ERROR"
            logger.info(
                "[AgentAudit] trace_id=%s user_id=%s step=%s tool=%s ok=%s "
                "elapsed_ms=%s error_code=%s arg_keys=%s",
                ctx.trace_id,
                ctx.user_id,
                step,
                decision.tool,
                result.ok,
                elapsed,
                error_code,
                sorted(decision.arguments.keys()),
            )
            yield event(
                EVENT_TOOL_RESULT,
                {
                    "step": step,
                    "tool": decision.tool,
                    "ok": result.ok,
                    "summary": result.summary,
                    "elapsedMs": elapsed,
                    "data": result.data,
                },
            )
            observations.append(
                {
                    "tool": decision.tool,
                    "ok": result.ok,
                    "summary": result.summary,
                    "data": result.data,
                    "contexts": result.contexts,
                }
            )

    # ------------------------------------------------------------------ subagent 实验路径

    def _subagent_limit(self) -> int:
        """子问题个数上限（配置已 clamp，这里再按硬上限兜一次）。"""
        configured = int(self.settings.subagent_max_questions or 0)
        return max(1, min(configured, SUBAGENT_MAX_QUESTIONS_LIMIT))

    async def _run_subagent(
        self, ctx: AgentContext, policy: ContextPolicy, started: float
    ) -> AsyncIterator[Dict[str, Any]]:
        """拆解 → 逐个子问题（共享预算）→ 汇总定稿；失败回退单 Agent。"""
        limit = self._subagent_limit()
        max_tools = max(0, self.settings.max_tool_calls)
        budget = ToolBudget(limit=max_tools)
        # 步数与工具预算一样是**会话级**资源：子问题不能各自领一份 max_steps
        step_budget = StepBudget(limit=max(1, self.settings.max_steps))
        degrade_reason = ""
        attempts: List[SubAttempt] = []

        try:
            yield event(
                EVENT_THOUGHT,
                {"step": 0, "text": SUBAGENT_PREFIX + "正在拆解问题（受控实验路径）"},
            )
            sub_questions = await decompose(self.bridge, ctx, limit)
            yield event(
                EVENT_THOUGHT,
                {
                    "step": 0,
                    "text": SUBAGENT_PREFIX
                    + f"拆解为 {len(sub_questions)} 个子问题（工具预算全局共享 {max_tools} 次）",
                },
            )
            for index, sub_question in enumerate(sub_questions, start=1):
                remaining_groups = len(sub_questions) - index + 1
                allowance = step_budget.allocate(remaining_groups)
                if budget.exhausted or step_budget.remaining <= 0:
                    yield event(
                        EVENT_THOUGHT,
                        {
                            "step": 0,
                            "text": SUBAGENT_PREFIX
                            + f"预算已耗尽（工具 {budget.remaining} 次 / 步数 "
                            + f"{step_budget.remaining} 步），跳过剩余 {remaining_groups} 个子问题",
                        },
                    )
                    break
                yield event(
                    EVENT_THOUGHT,
                    {
                        "step": 0,
                        "text": SUBAGENT_PREFIX
                        + f"子问题 {index}/{len(sub_questions)}：{sub_question}"
                        + f"（剩余工具预算 {budget.remaining} 次 / 步数 "
                        + f"{step_budget.remaining} 步，本次最多 {allowance} 步）",
                    },
                )
                # 子问题只替换 question，其余画像/记忆/历史保持不变：
                # 子问题不是新的会话，不能丢掉用户的画像与多轮上下文。
                sub_budget_before = budget.used
                attempt = SubAttempt(question=sub_question)
                async for item in self._agent_loop(
                    replace(ctx, question=sub_question),
                    policy,
                    budget,
                    attempt,
                    prefix=SUBAGENT_PREFIX,
                    step_base=step_budget.used,
                    max_steps=allowance,
                ):
                    yield item
                step_budget.record(attempt.steps)
                attempts.append(attempt)
                logger.info(
                    "[Agent] 子问题 %d/%d 完成 steps=%d tool_calls=%d（本子问题消耗工具 %d 次）",
                    index, len(sub_questions), attempt.steps, attempt.tool_calls,
                    budget.used - sub_budget_before,
                )
            yield event(
                EVENT_THOUGHT,
                {
                    "step": 0,
                    "text": SUBAGENT_PREFIX
                    + f"汇总 {len(attempts)} 个子答案，交由最终生成",
                },
            )
        except SubagentError as exc:
            degrade_reason = str(exc)
        except asyncio.CancelledError:
            # 客户端断开：必须原样抛出，让 Starlette 关闭生成器
            raise
        except Exception as exc:  # noqa: BLE001
            logger.exception("[Agent] subagent 执行异常，回退单 Agent")
            degrade_reason = f"{type(exc).__name__}: {exc}"

        if degrade_reason:
            logger.warning("[Agent] subagent 降级回退单 Agent：%s", degrade_reason)
            yield event(
                EVENT_THOUGHT,
                {
                    "step": 0,
                    "text": SUBAGENT_PREFIX + f"实验路径不可用，回退单 Agent 原路径：{degrade_reason}",
                },
            )
            # 回退路径**重新给满预算**：实验失败不该让用户少拿一次工具调用。
            fallback_budget = ToolBudget(limit=max_tools)
            fallback = SubAttempt(question=ctx.question)
            async for item in self._agent_loop(ctx, policy, fallback_budget, fallback):
                yield item
            # 记账按**真实消耗**：拆解/子问题阶段已经花掉的工具调用与步数也要计入
            # （正常情况下降级发生在拆解处，这两项都是 0；万一子问题中途异常，
            #  不让最终指标"看起来"比实际便宜）。
            fallback.tool_calls += budget.used
            fallback.steps += step_budget.used
            async for item in self._emit_final(
                ctx,
                fallback,
                fallback.observations,
                started,
                extra={"subagent": False, "degradedReason": degrade_reason},
                force_degraded=True,
            ):
                yield item
            return

        aggregated = SubAttempt(
            question=ctx.question,
            draft=render_sub_answers(attempts),
            observations=[obs for item in attempts for obs in item.observations],
            steps=sum(item.steps for item in attempts),
            tool_calls=budget.used,
        )
        async for item in self._emit_final(
            ctx,
            aggregated,
            aggregated.observations,
            started,
            extra={"subagent": True, "subQuestions": [a.question for a in attempts]},
        ):
            yield item

    # ------------------------------------------------------------------ 定稿

    async def _finalize(
        self,
        ctx: AgentContext,
        draft: str,
        contexts: List[Dict[str, Any]],
        observations: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """定稿顺序：

        1. 结构化工具结果（计划/错题/薄弱点/题目）→ 本地规则合成答案；
        2. 有检索上下文 → 交给 Java 做 L3 校验 / 生成；
        3. 什么都没有 → Java 完整 RAG 兜底。
        """
        if not contexts:
            structured = self._structured_answer(observations)
            if structured:
                return {
                    "answer": structured,
                    "generator": "agent-rule",
                    "degraded": True,
                    "refused": False,
                    "sources": [],
                }
        try:
            return await self.bridge.full_answer(
                ctx.question, ctx.subject_id, draft, contexts
            )
        except JavaBridgeError as exc:
            # 上游故障 ≠ 业务拒答：不得用「未找到相关资料」表达 5xx，
            # 否则 Java 侧会把终态记成 REFUSED，故障在 errorTop5/失败率里不可见。
            logger.warning(
                "[Agent] Java 定稿失败（errorCode=%s）：%s", UPSTREAM_ERROR_CODE, exc
            )
            return {
                "answer": UPSTREAM_ERROR_TEXT,
                "generator": "upstream-error",
                "degraded": True,
                "refused": False,
                "sources": [],
                "errorCode": UPSTREAM_ERROR_CODE,
                "degradedReason": f"Java 定稿失败：{exc}",
            }

    @staticmethod
    def _with_label(prefix: str, summary: str) -> str:
        """拼接「标签 + 工具摘要」。

        ★ 摘要本身常已带同类标签（如 ``get_weak_knowledge`` 返回
        「薄弱知识点 5 个：…」），再补一次前缀就会输出
        「薄弱知识点：薄弱知识点 5 个：…」。这里按标签前缀判重。
        """
        text = (summary or "").strip()
        core = prefix.rstrip("：")
        if not text:
            return core
        return text if text.startswith(core) else prefix + text

    #: 指代词：出现这些词说明当前问题依赖上一轮语境
    _PRONOUN_RE = re.compile(r"(它|他们|这个|那个|这题|那题|上面|刚才|该题|继续)")

    def _resolve_arguments(self, tool: str, arguments: Dict[str, Any],
                           ctx: "AgentContext") -> Dict[str, Any]:
        """检索前做指代消解：把「它/这个/那题」补成上一轮问题。

        只影响 search_knowledge / search_wiki 的 query，其余工具原样透传；
        补全词只用于检索，不会进入最终回答。
        """
        args = dict(arguments or {})
        if tool not in ("search_knowledge", "search_wiki"):
            return args
        if not self._PRONOUN_RE.search(ctx.question or ""):
            return args
        topic = ""
        for turn in reversed(ctx.history or []):
            q = str(turn.get("question") or "").strip()
            if q and not self._PRONOUN_RE.search(q):
                topic = q
                break
        if not topic and ctx.memory_summary:
            topic = str(ctx.memory_summary).strip()[-120:]
        if topic:
            query = str(args.get("query") or "").strip()
            args["query"] = (topic + " " + query).strip()[:200]
        return args

    @staticmethod
    def _structured_answer(observations: List[Dict[str, Any]]) -> str:
        """把结构化工具结果合成可读答案（不经过 LLM / L3）。"""
        for item in reversed(observations):
            if not item.get("ok"):
                continue
            tool = str(item.get("tool") or "")
            summary = str(item.get("summary") or "")
            data = item.get("data")
            if tool == "get_study_plan":
                answer = AgentHarness._with_label("计划：", summary)
                names: List[str] = []
                if isinstance(data, list):
                    names = [
                        str(row.get("knowledgeName"))
                        for row in data
                        if isinstance(row, dict) and row.get("knowledgeName")
                    ]
                    if names:
                        answer += "。任务：" + "、".join(names[:5])
                if not names and "0 题" in summary:
                    return ("你今天还没有安排复习。建议先做 10 道题热身（约 15 分钟）："
                            "做完之后我再根据你的错题和薄弱点安排下一步；"
                            "想直接开始的话，说「随机 10 题」即可。")
                return answer
            if tool == "get_wrong_book":
                answer = AgentHarness._with_label("错题：", summary)
                stems: List[str] = []
                if isinstance(data, list):
                    stems = [
                        str(row.get("stem"))
                        for row in data[:3]
                        if isinstance(row, dict) and row.get("stem")
                    ]
                    if stems:
                        answer += "。" + "；".join(stems)
                if not stems and ("0 道" in summary or not summary.strip()):
                    return ("错题本还是空的。建议先做一套 10 题的随机练习，"
                            "做错的题会自动进错题本，我再带你逐题复盘。")
                return answer
            if tool == "get_weak_knowledge":
                if "0 个" in summary or not summary.strip():
                    return ("目前还没有足够的作答记录来判断薄弱点。"
                            "做完 20 道题后我就能按知识点给出掌握度排名，并告诉你先补哪一块。")
                return AgentHarness._with_label("薄弱知识点：", summary)
            if tool == "get_question":
                return AgentHarness._with_label("题目信息：", summary)
        return ""
