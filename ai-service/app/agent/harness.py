"""Agent Harness：规划 → 工具 → 观察 → 回答。

约束：
* 最多 ``max_steps`` 步、``max_tool_calls`` 次工具调用；
* prompt 超过字符预算时从最老的工具结果开始丢弃，并发 ``budget_truncated`` 事件；
* 最终答案统一交给 Java（L3 校验 + 抽取式兜底），Python 不直接对外定稿；
* 取消（客户端断开）时 ``asyncio.CancelledError`` 必须原样抛出。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any, AsyncIterator, Dict, List, Optional

from ..clients.java_bridge import JavaBridge, JavaBridgeError
from ..config import AgentSettings
from .events import (
    EVENT_BUDGET,
    EVENT_DONE,
    EVENT_FINAL,
    EVENT_META,
    EVENT_THOUGHT,
    EVENT_TOOL_CALL,
    EVENT_TOOL_RESULT,
    event,
)
from .context_manager import ContextPolicy, build_context, estimate_tokens
from .planner import PROMPT_VERSION, Decision, LlmPlanner, PlannerError, RulePlanner
from .tools import AgentContext, ToolRegistry

logger = logging.getLogger(__name__)

REFUSAL_TEXT = "未找到相关资料"


class AgentHarness:
    """一次 Agent 会话的执行器（无状态，可并发复用）。"""

    def __init__(self, settings: AgentSettings, bridge: JavaBridge) -> None:
        self.settings = settings
        self.bridge = bridge
        self.tools = ToolRegistry(bridge)
        self.llm_planner = LlmPlanner(bridge)
        self.rule_planner = RulePlanner()

    async def run(self, ctx: AgentContext) -> AsyncIterator[Dict[str, Any]]:
        started = time.perf_counter()
        max_steps = max(1, self.settings.max_steps)
        max_tools = max(0, self.settings.max_tool_calls)

        policy = ContextPolicy(max_chars=self.settings.max_prompt_chars)
        initial = build_context(
            question=ctx.question,
            history=ctx.history,
            memory_summary=ctx.memory_summary,
            weak_knowledge_ids=ctx.weak_knowledge_ids,
            observations=[],
            policy=policy,
        )
        yield event(
            EVENT_META,
            {
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
            },
        )

        observations: List[Dict[str, Any]] = []
        tool_calls = 0
        step = 0
        draft = ""
        llm_available = True

        while step < max_steps:
            step += 1

            # ---------- 预算闸门：Context Manager 统一裁剪并显式上报 ----------
            bundle = build_context(
                question=ctx.question,
                history=ctx.history,
                memory_summary=ctx.memory_summary,
                weak_knowledge_ids=ctx.weak_knowledge_ids,
                observations=observations,
                policy=policy,
            )
            if bundle.dropped_observations or bundle.dropped_turns:
                yield event(
                    EVENT_BUDGET,
                    {
                        "droppedObservations": bundle.dropped_observations,
                        "droppedTurns": bundle.dropped_turns,
                        "remaining": len(observations) - bundle.dropped_observations,
                        "estimatedTokens": bundle.estimated_tokens,
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
                        {"step": step, "text": "大模型规划不可用，已切换规则规划器"},
                    )
            if decision is None:
                decision = self.rule_planner.plan(ctx, observations)

            if decision.thought:
                yield event(EVENT_THOUGHT, {"step": step, "text": decision.thought})

            # ---------- 最终回答 ----------
            if decision.action == "final":
                draft = decision.answer
                break

            # ---------- 工具调用 ----------
            if tool_calls >= max_tools:
                yield event(
                    EVENT_THOUGHT,
                    {"step": step, "text": "工具调用已达上限，直接进入最终回答"},
                )
                break
            tool_calls += 1
            yield event(
                EVENT_TOOL_CALL,
                {"step": step, "tool": decision.tool, "arguments": decision.arguments},
            )
            tool_started = time.perf_counter()
            result = await self.tools.execute(decision.tool, decision.arguments, ctx)
            elapsed = int((time.perf_counter() - tool_started) * 1000)
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

        # ---------- 交给 Java 定稿（L3 校验 + 兜底） ----------
        contexts: List[Dict[str, Any]] = []
        for item in observations:
            contexts.extend(item.get("contexts") or [])
        final = await self._finalize(ctx, draft, contexts, observations)

        yield event(
            EVENT_FINAL,
            {
                "answer": final.get("answer") or REFUSAL_TEXT,
                "generator": final.get("generator") or "none",
                "degraded": bool(final.get("degraded")),
                "refused": bool(final.get("refused")),
                "step_count": step,
                "tool_calls": tool_calls,
                "sources": final.get("sources") or [],
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
        return {
            "events": events,
            "final": final,
            "answer": final.get("answer", REFUSAL_TEXT),
            "generator": final.get("generator", "none"),
            "degraded": final.get("degraded", True),
            "refused": final.get("refused", False),
            "step_count": final.get("step_count", 0),
            "tool_calls": final.get("tool_calls", 0),
        }

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
            logger.warning("[Agent] Java 定稿失败，返回拒答兜底：%s", exc)
            return {
                "answer": REFUSAL_TEXT,
                "generator": "none",
                "degraded": True,
                "refused": True,
                "sources": [],
            }

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
                answer = "计划：" + summary
                if isinstance(data, list):
                    names = [
                        str(row.get("knowledgeName"))
                        for row in data
                        if isinstance(row, dict) and row.get("knowledgeName")
                    ]
                    if names:
                        answer += "。任务：" + "、".join(names[:5])
                return answer
            if tool == "get_wrong_book":
                answer = "错题：" + summary
                if isinstance(data, list):
                    stems = [
                        str(row.get("stem"))
                        for row in data[:3]
                        if isinstance(row, dict) and row.get("stem")
                    ]
                    if stems:
                        answer += "。" + "；".join(stems)
                return answer
            if tool == "get_weak_knowledge":
                return "薄弱知识点：" + summary
            if tool == "get_question":
                return "题目信息：" + summary
        return ""

