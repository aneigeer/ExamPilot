"""第7周 Agent 工具执行契约 —— P0 验收门禁（离线、桩桥、可重复）。

对应 ``项目规划/第7周/05_Agent工具执行审计_2026-11-08.md`` §4 的 P0 用例：

* T01 工具名白名单：幻觉工具不执行、不发 ``tool_call``、不扣预算
* T02 参数 Schema：缺参 / 错类型 / 越界统一为「参数错误」，越界值显式 clamp
* T03 超时：单次工具 wall-clock 上限 + 会话级 deadline（含跨层口径）
* T04 Java 定稿失败 → 终态 FAILED，不得伪装成业务拒答（REFUSED）
* T05 相同工具 + 相同参数的重复调用去重、不重复扣预算，且重放幂等
* T06 审计：每条工具调用可按 traceId 追溯（tool / ok / 耗时 / 错误码），不落敏感值

**设计约束**（与本仓库 ``tests/conftest.py`` 的策略一致）：

1. 全部使用桩桥（``StubBridge`` / ``ToolBridge``），**不发任何真实 HTTP**；
   不启动 8000/8080，不调模型，不依赖 Docker。
2. 断言表达的是**审计报告里期望的契约**，而不是"当前代码正好会做什么"。
   当前生产代码存在 G-01/G-02/G-03/G-04/G-06/G-09 缺口，因此本文件部分用例
   **预期为红**（失败即缺口证据，修复后回绿），不得为了变绿而放宽断言。
3. 每个用例把全部违约项收集到 ``violations`` 列表后一次性断言，
   失败信息即完整证据表（不会因为第一条断言失败而丢失后面的观测）。

运行：

    PYTHONPATH=ai-service .venv-ai\\Scripts\\python.exe -m pytest ai-service\\tests\\test_agent_p0.py -q
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Callable, Dict, List, Optional

import pytest

from app.agent.harness import REFUSAL_TEXT, AgentHarness
from app.agent.tools import AgentContext, ToolRegistry
from app.clients.java_bridge import JavaBridgeError
from app.config import AgentSettings

#: 审计要求的敏感值不得进入审计日志（检索 query 明文 / 错题题干）
SECRET_QUERY = "机密查询词-不得入审计日志"
SECRET_STEM = "这是错题题干-不得入审计日志"
TRACE_ID = "0123456789abcdef"


# --------------------------------------------------------------------------------------
# 桩：可编排脚本的 Java 桥（不发真实 HTTP）
# --------------------------------------------------------------------------------------


class StubBridge:
    """按脚本返回 LLM 决策、按路径返回工具数据的 Java 桥桩。

    Attributes:
        script: ``llm_complete`` 依次弹出的原始模型输出；弹空后固定返回 ``final``。
        tools_called: **真实**打到 Java 的工具调用（``{method, path, payload/params}``）。
        llm_calls: ``llm_complete`` 被调用的次数（用于推断预算/步数消耗）。
        answer_calls: ``full_answer``（Java 定稿）被调用的次数。
    """

    def __init__(
        self,
        script: Optional[List[str]] = None,
        *,
        tool_get: Optional[Callable[[str, Dict[str, Any]], Any]] = None,
        tool_post: Optional[Callable[[str, Dict[str, Any]], Any]] = None,
        answer: Optional[Dict[str, Any]] = None,
        answer_error: Optional[Exception] = None,
        llm_error: Optional[Exception] = None,
        llm_delay: float = 0.0,
        tool_delay: float = 0.0,
    ) -> None:
        self._script = list(script or [])
        self._tool_get = tool_get
        self._tool_post = tool_post
        self._answer = answer
        self._answer_error = answer_error
        self._llm_error = llm_error
        self._llm_delay = llm_delay
        self._tool_delay = tool_delay
        self.tools_called: List[Dict[str, Any]] = []
        self.llm_calls = 0
        self.answer_calls = 0

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        self.llm_calls += 1
        if self._llm_delay:
            await asyncio.sleep(self._llm_delay)
        if self._llm_error is not None:
            raise self._llm_error
        if self._script:
            return self._script.pop(0)
        return '{"thought":"信息足够","action":"final","answer":"完成"}'

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if self._tool_delay:
            await asyncio.sleep(self._tool_delay)
        self.tools_called.append({"method": "GET", "path": path, "params": dict(params or {})})
        if self._tool_get is not None:
            return self._tool_get(path, dict(params or {}))
        if "weak-knowledge" in path:
            return [{"knowledgeId": 5, "name": "函数", "mastery": 0.2, "weakness": 0.8}]
        if "wrong-book" in path:
            return [{"questionId": 1, "stem": SECRET_STEM, "wrongCount": 2}]
        if "study-plan" in path:
            return {"plannedCount": 20, "doneCount": 5, "completionPercent": 25, "items": []}
        if "question/" in path:
            return {"questionId": 1, "stem": "int 占几个字节"}
        if "wiki-page/" in path:
            return {"id": 1, "title": "函数专题", "summary": "摘要", "content": "正文"}
        return []

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        if self._tool_delay:
            await asyncio.sleep(self._tool_delay)
        self.tools_called.append({"method": "POST", "path": path, "payload": dict(payload)})
        if self._tool_post is not None:
            return self._tool_post(path, dict(payload))
        if "wiki-search" in path:
            return {"hits": []}
        return {
            "contexts": [{"chunkId": 1, "title": "t", "content": "正文", "score": 0.9}],
            "bestScore": 0.9,
            "mode": "FULLTEXT_AND",
        }

    async def full_answer(
        self,
        question: str,
        subject_id: Optional[int] = None,
        draft_answer: str = "",
        contexts: Optional[list] = None,
    ) -> Dict[str, Any]:
        self.answer_calls += 1
        if self._answer_error is not None:
            raise self._answer_error
        if self._answer is not None:
            return dict(self._answer)
        return {
            "answer": draft_answer or "按资料回答 [1]",
            "generator": "extractive",
            "degraded": True,
            "refused": False,
            "sources": [{"chunkId": 1}],
        }


# --------------------------------------------------------------------------------------
# 辅助
# --------------------------------------------------------------------------------------


def _run(harness: AgentHarness, ctx: AgentContext) -> List[Dict[str, Any]]:
    """跑完一次会话并返回全部事件（不经过 HTTP 层）。"""

    async def _collect() -> List[Dict[str, Any]]:
        return [item async for item in harness.run(ctx)]

    return asyncio.run(_collect())


def _names(events: List[Dict[str, Any]]) -> List[str]:
    """事件名序列。"""
    return [item["event"] for item in events]


def _data_of(events: List[Dict[str, Any]], name: str) -> List[Dict[str, Any]]:
    """取某类事件的 data 列表。"""
    return [item["data"] for item in events if item["event"] == name]


def _final_of(events: List[Dict[str, Any]]) -> Dict[str, Any]:
    """取 ``final`` 事件数据（缺失则返回空 dict，便于断言给出可读失败）。"""
    finals = _data_of(events, "final")
    return finals[-1] if finals else {}


def _tool_decisions(tool: str, arguments: Dict[str, Any], times: int) -> List[str]:
    """生成 N 条完全相同的工具决策脚本（用于预算/去重/超时用例）。"""
    raw = (
        '{"thought":"查一下","action":"tool","tool":"%s","arguments":%s}'
        % (tool, _json_inline(arguments))
    )
    return [raw] * times


def _json_inline(value: Any) -> str:
    """把 dict 渲染成单行 JSON（避免依赖 json 的键序问题）。"""
    import json

    return json.dumps(value, ensure_ascii=False)


# --------------------------------------------------------------------------------------
# T01 工具名白名单：幻觉工具不执行、不发 tool_call、不扣预算
# --------------------------------------------------------------------------------------


def test_t01_hallucinated_tool_is_blocked_before_execution() -> None:
    """审计 T01 / G-01：未知工具名必须在执行前被硬白名单拦截。

    期望：不出现指向 ``wiki_search_typo`` 的 ``tool_call``/``tool_result``；
    ``final.tool_calls == 0``（等价于 ``budget.used == 0``）；桩桥一次工具都没打；
    ``final``/``done`` 仍产出（HTTP 语义 200）。
    """
    bridge = StubBridge(
        [
            '{"thought":"查一下","action":"tool","tool":"wiki_search_typo","arguments":{"query":"x"}}',
            '{"thought":"够了","action":"final","answer":"ok"}',
        ]
    )
    harness = AgentHarness(AgentSettings(max_steps=3, max_tool_calls=4), bridge)  # type: ignore[arg-type]
    events = _run(harness, AgentContext(question="梳理一下函数专题", user_id=3))

    violations: List[str] = []
    tool_calls = _data_of(events, "tool_call")
    tool_results = _data_of(events, "tool_result")
    final = _final_of(events)

    leaked = [c["tool"] for c in tool_calls + tool_results if "wiki_search_typo" in str(c.get("tool") or "")]
    if leaked:
        violations.append(f"幻觉工具未被拦截，仍发出 {len(leaked)} 条事件：{leaked}")
    if bridge.tools_called:
        violations.append(f"幻觉工具仍打到了 Java：{bridge.tools_called}")
    if final.get("tool_calls") != 0:
        violations.append(
            f"final.tool_calls={final.get('tool_calls')}（预期 0）：未知工具仍扣了预算"
        )
    if not final:
        violations.append("缺少 final 事件（HTTP 语义应为 200）")
    if "done" not in _names(events):
        violations.append("缺少 done 事件")
    if "thought" in _names(events) and not any(
        "未知工具" in str(d.get("text") or "") or "拦截" in str(d.get("text") or "")
        for d in _data_of(events, "thought")
    ):
        violations.append("未通过 thought 告知「未知工具已拦截」，用户无法解释为何没查")

    assert not violations, "T01 违约：\n- " + "\n- ".join(violations)


# --------------------------------------------------------------------------------------
# T02 参数 Schema：缺参 / 错类型 / 越界 → 统一「参数错误」；越界值显式 clamp
# --------------------------------------------------------------------------------------


class ToolBridge:
    """只服务 ``ToolRegistry`` 的桩：按路径返回固定数据，并记录每次真实调用。"""

    def __init__(self, payloads: Optional[Dict[str, Any]] = None) -> None:
        self.calls: List[Dict[str, Any]] = []
        self._payloads = payloads or {}

    def _resolve(self, path: str, argument: Any) -> Any:
        for key, value in self._payloads.items():
            if key in path:
                return value
        if "wrong-book" in path:
            return [{"questionId": 1, "stem": SECRET_STEM, "wrongCount": 2}]
        if "weak-knowledge" in path:
            return [{"knowledgeId": 5, "name": "函数"}]
        if "question/" in path:
            return {"questionId": 1, "stem": "int 占几个字节"}
        if "wiki-page/" in path:
            return {"id": 1, "title": "函数专题", "summary": "摘要", "content": "正文"}
        return {"contexts": [], "bestScore": 0.0, "mode": "none"}

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        self.calls.append({"method": "GET", "path": path, "params": dict(params or {})})
        if "question/" in path and "/-" in path:
            # 复刻真实 Java：非法题目 ID 会被路由/参数校验拦成 404
            raise JavaBridgeError("Java 内部接口返回 404：题目不存在")
        return self._resolve(path, params)

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        self.calls.append({"method": "POST", "path": path, "payload": dict(payload)})
        return self._resolve(path, payload)


def test_t02_invalid_arguments_are_rejected_as_parameter_errors() -> None:
    """审计 T02 / G-02：缺参 / 错类型 / 非法 ID 必须先于 Java 被拒，语义统一。

    断言逐条列出（收集全部违约后再断言，避免只看到第一条）：
    1. 缺参 / 类型错 → ``ok=False`` 且 ``summary`` 以「参数错误」开头，且不访问 Java；
    2. 负数 ``questionId`` 不得拼成非法路径调用 Java；
    3. 任何结果都不得把 ``ValueError``/``AttributeError`` 泄漏到 ``summary``；
    4. ``topK=99`` → 10、``limit=-1`` → 1（显式 clamp，可见于 payload）。
    """
    ctx = AgentContext(question="测试", user_id=3, subject_id=1)
    violations: List[str] = []
    observed: List[str] = []

    invalid_cases = [
        ("get_wiki_page 缺 pageId", "get_wiki_page", {}),
        ("get_question questionId 非法字符串", "get_question", {"questionId": "abc"}),
        ("get_question questionId 负数", "get_question", {"questionId": -5}),
        ("search_knowledge query 类型错", "search_knowledge", {"query": 123, "topK": 99}),
    ]

    for label, tool, arguments in invalid_cases:
        bridge = ToolBridge()
        result = asyncio.run(ToolRegistry(bridge).execute(tool, arguments, ctx))  # type: ignore[arg-type]
        observed.append(f"{label}: ok={result.ok} summary={result.summary!r} java_calls={bridge.calls}")
        if result.ok:
            violations.append(f"[{label}] 非法参数被当成成功执行（ok=True）")
        if not str(result.summary).startswith("参数错误"):
            violations.append(
                f"[{label}] summary 未以「参数错误」开头：{result.summary!r}"
                "（契约要求统一语义，而非「缺少 xx」/「工具执行异常」）"
            )
        if bridge.calls:
            violations.append(f"[{label}] 非法参数仍访问了 Java：{bridge.calls}")
        for leak in ("ValueError", "AttributeError", "工具执行异常"):
            if leak in str(result.summary):
                violations.append(f"[{label}] 异常类型泄漏到 summary：{leak}")
        if any("-5" in str(call["path"]) for call in bridge.calls):
            violations.append(f"[{label}] 负数 ID 拼成了非法 Java 路径：{bridge.calls}")

    # 显式 clamp：越界值必须收敛到边界并体现在 Java payload 上
    bridge = ToolBridge()
    result = asyncio.run(
        ToolRegistry(bridge).execute("search_knowledge", {"query": "x", "topK": 99}, ctx)  # type: ignore[arg-type]
    )
    observed.append(f"search_knowledge topK=99: payload={bridge.calls}")
    if not result.ok:
        violations.append("[search_knowledge topK=99] 合法请求被拒（预期 clamp 后执行）")
    if not bridge.calls or bridge.calls[-1]["payload"].get("topK") != 10:
        violations.append(
            f"[search_knowledge topK=99] payload.topK={bridge.calls[-1]['payload'].get('topK') if bridge.calls else None}"
            "（预期 10）"
        )

    bridge = ToolBridge()
    result = asyncio.run(
        ToolRegistry(bridge).execute("get_wrong_book", {"limit": -1}, ctx)  # type: ignore[arg-type]
    )
    observed.append(f"get_wrong_book limit=-1: payload={bridge.calls}")
    if not result.ok:
        violations.append("[get_wrong_book limit=-1] 合法请求被拒（预期 clamp 后执行）")
    if not bridge.calls or bridge.calls[-1]["params"].get("limit") != 1:
        violations.append(
            f"[get_wrong_book limit=-1] params.limit={bridge.calls[-1]['params'].get('limit') if bridge.calls else None}"
            "（预期 1）"
        )

    print("\n[T02 观测表]\n" + "\n".join(observed))
    assert not violations, "T02 违约：\n- " + "\n- ".join(violations)


# --------------------------------------------------------------------------------------
# T03 超时：单次工具 wall-clock 上限 + 会话级 deadline
# --------------------------------------------------------------------------------------


def test_t03_tool_call_is_bounded_by_tool_timeout() -> None:
    """审计 T03-A / G-03：单次工具调用必须被 ``tool_timeout_seconds`` 兜住。

    桩桥刻意**不实现超时**（模拟"Java 侧卡死但 TCP 连接未断"），
    因此只有 harness 自己按 wall-clock 兜底才能保证会话不被拖死。
    期望：≤ 0.6s 返回；``tool_result.ok=false`` 且 summary 含「超时」；
    ``final``/``done`` 齐全。
    """
    bridge = StubBridge(_tool_decisions("search_knowledge", {"query": "x", "topK": 5}, 1), tool_delay=1.2)
    harness = AgentHarness(
        AgentSettings(max_steps=1, max_tool_calls=4, tool_timeout_seconds=0.2),  # type: ignore[arg-type]
        bridge,  # type: ignore[arg-type]
    )
    started = time.perf_counter()
    events = _run(harness, AgentContext(question="C语言 int 占几个字节", user_id=3))
    elapsed = time.perf_counter() - started

    violations: List[str] = []
    if elapsed > 0.6:
        violations.append(
            f"工具超时配置 0.2s，但会话实际耗时 {elapsed:.2f}s：harness 没有兜住单次工具 wall-clock"
        )
    results = _data_of(events, "tool_result")
    if not results:
        violations.append("缺少 tool_result 事件")
    else:
        summary = str(results[-1].get("summary") or "")
        if results[-1].get("ok") is not False:
            violations.append(f"超时未标记失败：ok={results[-1].get('ok')} summary={summary!r}")
        if "超时" not in summary and "timeout" not in summary.lower():
            violations.append(f"超时语义不可见：summary={summary!r}")
    if not _final_of(events):
        violations.append("缺少 final 事件（HTTP 语义应为 200）")
    if "done" not in _names(events):
        violations.append("缺少 done 事件")

    assert not violations, "T03-A 违约：\n- " + "\n- ".join(violations)


def test_t03_session_has_wall_clock_deadline() -> None:
    """审计 T03-B / G-03：会话级 deadline 必须存在且生效，且跨层口径自洽。

    用「慢规划器」放大最坏延迟：每步规划 0.35s，最多 6 步。若存在会话级 deadline
    （建议 ``AGENT_SESSION_TIMEOUT_SECONDS``），总耗时应被截断并给出截止标记；
    当前 Python 最坏 ``max_steps × llm_timeout`` 远超 Java SSE 120s 与
    Java→Python 请求 10s，必须在用例里固化这个口径。
    """
    per_step = 0.35
    expected_deadline = 1.2
    bridge = StubBridge(
        _tool_decisions("get_wrong_book", {"limit": 5}, 6),
        llm_delay=per_step,
        answer={"answer": "ok", "generator": "stub", "degraded": True, "refused": False, "sources": []},
    )
    settings = AgentSettings(max_steps=6, max_tool_calls=4, session_timeout_seconds=1.2)  # type: ignore[arg-type]
    harness = AgentHarness(settings, bridge)  # type: ignore[arg-type]
    started = time.perf_counter()
    events = _run(harness, AgentContext(question="我的错题", user_id=3))
    elapsed = time.perf_counter() - started

    violations: List[str] = []
    if not getattr(settings, "session_timeout_seconds", None):
        violations.append(
            "AgentSettings 无会话级 deadline 配置（建议新增 AGENT_SESSION_TIMEOUT_SECONDS）"
        )
    if elapsed > expected_deadline:
        violations.append(
            f"慢规划最坏耗时 {elapsed:.2f}s（6 步 × {per_step}s）超过 deadline 上限 "
            f"{expected_deadline}s：会话级 deadline 未生效"
        )
    final = _final_of(events)
    if not final:
        violations.append("缺少 final 事件（deadline 截止也必须产出终态）")
    else:
        marker = " ".join(str(final.get(k) or "") for k in ("degradedReason", "answer", "generator"))
        if final.get("degraded") is not True:
            violations.append("deadline 截止未体现 degraded=true")
        if "deadline" not in marker.lower() and "截止" not in marker and "超时" not in marker:
            violations.append(f"终态无法区分「deadline 截止」：{marker!r}")

    # 跨层口径：Python 最坏总时长必须小于 Java SSE 总超时（120s），否则 Java 先断流
    worst = settings.max_steps * settings.llm_timeout_seconds
    if worst >= 120:
        violations.append(
            f"Python 最坏 {settings.max_steps}×{settings.llm_timeout_seconds}s={worst:.0f}s "
            "≥ Java SSE 120s：Java 会先断流，Python 仍在跑"
        )

    assert not violations, "T03-B 违约：\n- " + "\n- ".join(violations)


# --------------------------------------------------------------------------------------
# T04 Java 定稿失败 → 终态 FAILED，不得伪装成业务拒答
# --------------------------------------------------------------------------------------


def test_t04_java_finalize_failure_is_not_reported_as_refusal() -> None:
    """审计 T04 / G-04：上游 5xx 不得被降级成 ``refused=true`` + 「未找到相关资料」。

    期望：``final.degraded == true``；``refused == false``（或新增显式 error 字段）；
    ``generator`` 可区分于业务拒答；答案文案不得与「没检索到资料」同义；
    Java 侧据此落 ``FAILED`` 而不是 ``REFUSED``。
    """
    bridge = StubBridge(
        [
            '{"thought":"先检索","action":"tool","tool":"search_knowledge","arguments":{"query":"x","topK":5}}',
            '{"thought":"结束","action":"final","answer":"草稿"}',
        ],
        answer_error=JavaBridgeError("Java 内部接口返回 502：Bad Gateway"),
    )
    harness = AgentHarness(AgentSettings(max_steps=3, max_tool_calls=4), bridge)  # type: ignore[arg-type]
    events = _run(harness, AgentContext(question="C语言 int 占几个字节", user_id=3))

    final = _final_of(events)
    violations: List[str] = []
    if not final:
        violations.append("缺少 final 事件")
    else:
        if final.get("degraded") is not True:
            violations.append("上游失败未体现 degraded=true")
        explicit_error = any(final.get(k) for k in ("error", "errorCode", "failure", "upstreamError"))
        if final.get("refused") is True and not explicit_error:
            violations.append(
                "refused=true 且无 error 字段：上游 502 被伪装成业务拒答（Java 终态会记 REFUSED 而非 FAILED）"
            )
        if str(final.get("answer") or "") == REFUSAL_TEXT:
            violations.append(
                f"answer={REFUSAL_TEXT!r}：用户与统计都无法区分「没有资料」和「后端故障」"
            )
        if str(final.get("generator") or "none") == "none":
            violations.append("generator='none'：与业务拒答不可区分")
    if bridge.answer_calls != 1:
        violations.append(f"Java 定稿调用次数={bridge.answer_calls}（预期 1，证明确实走了定稿分支）")

    assert not violations, "T04 违约：\n- " + "\n- ".join(violations)


# --------------------------------------------------------------------------------------
# T05 相同工具 + 相同参数去重：不重复执行、不重复扣预算、重放幂等
# --------------------------------------------------------------------------------------


def test_t05_duplicate_tool_call_is_deduplicated() -> None:
    """审计 T06 / G-06：会话内相同 ``tool + arguments`` 只执行一次。

    期望：桩桥 ``tools_called`` 长度 1；出现「重复调用已跳过」thought；
    ``final.tool_calls == 1``；同一输入重跑两次的工具序列与答案完全一致（无跨请求状态）。
    """
    same = _tool_decisions("search_knowledge", {"query": "x", "topK": 5}, 2)
    script = same + ['{"thought":"结束","action":"final","answer":"完成"}']

    def _run_once() -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        bridge = StubBridge(list(script))
        harness = AgentHarness(AgentSettings(max_steps=4, max_tool_calls=4), bridge)  # type: ignore[arg-type]
        events = _run(harness, AgentContext(question="C语言 int", user_id=3))
        return events, bridge.tools_called

    events, calls = _run_once()
    final = _final_of(events)
    violations: List[str] = []

    if len(calls) != 1:
        violations.append(
            f"相同 tool+arguments 被执行 {len(calls)} 次（预期 1）：预算被无效调用重复消耗"
        )
    if final.get("tool_calls") != 1:
        violations.append(f"final.tool_calls={final.get('tool_calls')}（预期 1）")
    thoughts = " ".join(str(d.get("text") or "") for d in _data_of(events, "thought"))
    if not any(word in thoughts for word in ("重复调用已跳过", "不再重复", "重复")):
        violations.append(f"未告知「重复调用已跳过」，thought={thoughts!r}")

    # 重放幂等：同一输入两次运行，工具序列与答案必须逐字一致
    events2, calls2 = _run_once()
    final2 = _final_of(events2)
    signature1 = [(c["method"], c["path"], c.get("payload") or c.get("params")) for c in calls]
    signature2 = [(c["method"], c["path"], c.get("payload") or c.get("params")) for c in calls2]
    if signature1 != signature2:
        violations.append(f"重放工具序列不一致：{signature1} != {signature2}")
    if str(final.get("answer")) != str(final2.get("answer")):
        violations.append("重放答案不一致：存在跨请求状态")

    assert not violations, "T05 违约：\n- " + "\n- ".join(violations)


# --------------------------------------------------------------------------------------
# T06 审计：每条工具调用可按 traceId 追溯（含 ok/耗时/错误码），不落敏感值
# --------------------------------------------------------------------------------------

#: pytest 日志记录里除 message 外要一并检查的结构化字段（审计实现可放任一名字）
_AUDIT_FIELDS = (
    "trace_id", "traceId", "tool", "action", "ok", "success",
    "elapsed_ms", "elapsedMs", "duration_ms", "error_code", "errorCode", "user_id", "userId", "step",
)

_RECORD_KEYS = frozenset(
    ("name", "msg", "args", "levelname", "levelno", "pathname", "filename", "module",
     "exc_info", "exc_text", "stack_info", "lineno", "funcName", "created", "msecs",
     "relativeCreated", "thread", "threadName", "processName", "process", "taskName", "message")
)


def _audit_lines(caplog: pytest.LogCaptureFixture) -> List[str]:
    """把所有日志记录渲染成可搜索文本（message + 常见结构化字段）。"""
    lines: List[str] = []
    for record in caplog.records:
        parts = [record.getMessage()]
        for key, value in record.__dict__.items():
            if key in _RECORD_KEYS or key.startswith("_"):
                continue
            if key in _AUDIT_FIELDS or isinstance(value, (str, int, float, bool, dict)):
                parts.append(f"{key}={value}")
        lines.append(" ".join(str(p) for p in parts))
    return lines


def test_t06_each_tool_call_is_auditable(caplog: pytest.LogCaptureFixture) -> None:
    """审计 T10 / G-09（任务书列为第 6 条 P0）：工具调用必须可归因、可统计、不落敏感值。

    契约（格式不限定，但每条记录必须同时带这些信息）：
    ``traceId`` + ``tool`` + ``ok`` + 耗时 + （失败时）错误分类 + 会话步号 / userId。

    输入：一次会话 3 次工具调用（``get_wrong_book`` 成功、``search_knowledge`` 成功、
    ``get_wiki_page`` 缺参失败），``traceId=0123...``、``userId=3``。
    """
    caplog.set_level(logging.INFO, logger="app.agent")

    bridge = StubBridge(
        [
            '{"thought":"查错题","action":"tool","tool":"get_wrong_book","arguments":{"limit":5}}',
            '{"thought":"查资料","action":"tool","tool":"search_knowledge",'
            '"arguments":{"query":"%s","topK":5}}' % SECRET_QUERY,
            '{"thought":"看Wiki页","action":"tool","tool":"get_wiki_page","arguments":{}}',
            '{"thought":"结束","action":"final","answer":"完成"}',
        ]
    )
    harness = AgentHarness(AgentSettings(max_steps=4, max_tool_calls=4), bridge)  # type: ignore[arg-type]
    events = _run(
        harness,
        AgentContext(question="我的错题和资料", user_id=3, trace_id=TRACE_ID),
    )

    lines = _audit_lines(caplog)
    audit = [line for line in lines if TRACE_ID in line]
    violations: List[str] = []

    if not audit:
        violations.append(
            f"按 traceId 检索不到任何工具审计记录（捕获 {len(lines)} 条日志，均无 traceId）"
        )

    expected = [
        ("get_wrong_book", True),
        ("search_knowledge", True),
        ("get_wiki_page", False),
    ]
    for tool, ok in expected:
        matched = [line for line in audit if tool in line]
        if not matched:
            violations.append(f"[{tool}] 该工具调用没有可归因到 traceId 的审计记录")
            continue
        text = matched[0].lower()
        if not any(
            token in text
            for token in (f"ok={'true' if ok else 'false'}", "success=", "成功", "失败")
        ):
            violations.append(f"[{tool}] 审计记录缺少 ok/成败字段：{matched[0]!r}")
        if not any(token in text for token in ("elapsed", "耗时", "duration", "ms=", "_ms")):
            violations.append(f"[{tool}] 审计记录缺少耗时字段：{matched[0]!r}")
        if "user" not in text:
            violations.append(f"[{tool}] 审计记录缺少 userId 归因：{matched[0]!r}")
        if not ok and not any(token in text for token in ("false", "失败", "error", "错误")):
            violations.append(f"[{tool}] 失败调用没有错误分类（与成功不可区分）：{matched[0]!r}")

    joined = "\n".join(audit)
    for secret in (SECRET_QUERY, SECRET_STEM):
        if secret in joined:
            violations.append(f"审计日志泄漏敏感值：{secret!r}（应只记 argKeys，不记取值/正文）")

    # 事件层至少要有对应数量的 tool_result（审计记录数应与之一一对应）
    results = _data_of(events, "tool_result")
    if len(results) != 3:
        violations.append(f"tool_result 数量={len(results)}（预期 3，审计记录应一一对应）")

    print("\n[T06 捕获的审计日志]\n" + ("\n".join(audit) if audit else "<空>"))
    assert not violations, "T06 违约：\n- " + "\n- ".join(violations)
