"""Subagent（受控实验）：拆解 → 逐个子问题 → 汇总。

设计边界（**这段注释就是契约**）：

1. **默认关闭**。``AGENT_SUBAGENT_ENABLED=false`` 时本模块的代码一行都不执行，
   执行路径 / SSE 事件序列 / 延迟与单 Agent 版本完全一致。
2. **预算全局共享**。总 ``max_tool_calls`` 仍是配置值（默认 4），
   **不是**"每个子问题各给 4 次"。共享逻辑见 :class:`ToolBudget`。
3. **失败一律回退**。拆解调用失败 / JSON 解析失败 / 子问题为空 / 超预算，
   全部回退单 Agent 原路径，并在 ``final`` 事件里带 ``degradedReason``；
   ``run()`` 永不因为实验路径抛异常（绝不 500）。
4. **不新增事件类型**。子步骤沿用 ``thought``，文本前缀 ``[subagent]``，
   Java 侧反序列化逻辑零改动。

为什么不把拆解结果放进 ``meta`` 再让 Java 决定：``meta`` 必须是会话的第一条事件，
而拆解要花一次 LLM 调用 —— 要么阻塞在 meta 之前（增加首字节延迟），
要么把拆解结果延后到 thought（Java 已经在收流，不需要它做路由决策）。
本实现选后者，代价是 Java 只能通过 ``meta.subagent`` 知道"这次开了实验"。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List

from ..clients.java_bridge import JavaBridge, JavaBridgeError
from .tools import AgentContext

logger = logging.getLogger(__name__)

#: 拆解提示词版本（写入 meta，便于改版后做回归对比）
SUBAGENT_PROMPT_VERSION = "agent-subagent-v1"

#: 单个子问题的最大字符数（防止模型把整段问题原样复制多遍撑爆 prompt）
SUB_QUESTION_MAX_CHARS = 200

#: 拆解系统提示词：**严格 JSON**，且明确"少而独立"。
#:
#: 为什么不要求模型给"检索计划"而只要求子问题：检索计划需要模型理解工具目录，
#: 7B 本地模型经常凭空造工具名；子问题只需要语言理解，失败率低得多。
DECOMPOSE_SYSTEM_PROMPT = (
    "你是大学生备考助教的任务拆解器。请把学生的问题拆成 1-3 个**互相独立**的子问题。\n"
    "严格遵守：\n"
    "1. 只输出一个 JSON 对象，不要输出 Markdown、代码块或任何解释文字。\n"
    '2. 输出格式固定为：{"sub_questions":["子问题1","子问题2"]}\n'
    "3. 每个子问题都必须是可独立检索的完整问句，不要把同一件事拆成两半。\n"
    "4. 如果问题本身已经很具体、拆开没有意义，就只返回一个子问题。\n"
    "5. 子问题用简体中文。\n\n"
    "示例：\n"
    "学生问题：帮我看看错题，再给我制定今天的复习计划\n"
    '{"sub_questions":["我最近有哪些未掌握的错题","今天应该安排哪些复习任务"]}'
)

#: 兜底的子问题字段名（不同模型偶尔会用这些同义 key，语义明确才接受）
_SUB_QUESTION_KEYS = ("sub_questions", "subQuestions", "questions")


class SubagentError(Exception):
    """拆解阶段失败（调用失败 / JSON 非法 / 字段缺失 / 结果为空）。

    抛出它的地方就是"该回退单 Agent"的地方，**不要在更上层再吞一次**：
    回退决策只有一处，避免出现"回退里再回退"的双层降级。
    """


@dataclass
class ToolBudget:
    """跨子问题**共享**的工具调用预算。

    单 Agent 的预算是 ``max_tool_calls`` 次；拆成 3 个子问题后，
    如果每个子问题各给 4 次，实际预算会放大 3 倍 —— 这既违背契约里的
    "单次会话最多 4 次工具调用"，也会把延迟放大到不可接受。

    Attributes:
        limit: 全局上限（= ``settings.max_tool_calls``）。
        used: 已消耗次数（含失败的调用：失败也花了时间和一次上行请求）。
    """

    limit: int = 0
    used: int = 0

    @property
    def remaining(self) -> int:
        """剩余可用次数（不会为负）。"""
        return max(0, self.limit - self.used)

    @property
    def exhausted(self) -> bool:
        """预算是否已耗尽。"""
        return self.used >= self.limit

    def try_consume(self) -> bool:
        """尝试消耗一次预算。

        Returns:
            ``True`` 表示拿到额度（调用方可以真正执行工具），``False`` 表示已耗尽。
        """
        if self.used >= self.limit:
            return False
        self.used += 1
        return True


@dataclass
class StepBudget:
    """跨子问题**共享**的步数预算（单次会话的 ``max_steps``）。

    为什么要共享步数：工具预算是 4 次，但每次工具调用都要花一步"规划"。
    3 个子问题各给 ``max_steps=6`` → 最坏 18 步，是单 Agent 的 3 倍延迟，
    并且直接违反"单次会话最多 max_steps 步"这条契约。

    **公平分配**而不是"先来先占"：先来的子问题若把步数吃光，后面的子问题
    连一次工具都调不到，汇总阶段就会变成"只有第一个子问题的答案" —— 那还不如
    不做拆解。因此每次给"剩余步数 / 剩余子问题数"（至少 1 步）。

    Attributes:
        limit: 会话级步数上限（= ``settings.max_steps``）。
        used: 已消耗步数。
    """

    limit: int = 1
    used: int = 0

    @property
    def remaining(self) -> int:
        """剩余步数（不会为负）。"""
        return max(0, self.limit - self.used)

    def allocate(self, groups: int) -> int:
        """给"当前子问题"分配步数上限。

        Args:
            groups: 剩余待跑的子问题个数（含当前这个）。

        Returns:
            本次子问题最多可走的步数（至少 1）。
        """
        return max(1, self.remaining // max(1, groups))

    def record(self, steps: int) -> None:
        """登记某个子问题实际用掉的步数。"""
        self.used += max(0, steps)


@dataclass
class SubAttempt:
    """一个子问题（或单 Agent 整体）跑完规划循环后的产出。

    Attributes:
        question: 该次循环针对的问题文本。
        draft: 规划器给出的最终草稿（空串表示"没有直接答案，交给 Java 定稿"）。
        observations: 工具观察列表，结构与 :class:`~app.agent.tools.ToolResult` 对齐。
        steps: 本次循环实际走的步数。
        tool_calls: 本次循环实际发起的工具调用数。
    """

    question: str = ""
    draft: str = ""
    observations: List[Dict[str, Any]] = field(default_factory=list)
    steps: int = 0
    tool_calls: int = 0

    def contexts(self) -> List[Dict[str, Any]]:
        """拍平所有观察里的检索上下文（交给 Java 定稿用）。"""
        contexts: List[Dict[str, Any]] = []
        for item in self.observations:
            contexts.extend(item.get("contexts") or [])
        return contexts


def parse_sub_questions(raw: str, limit: int) -> List[str]:
    """从模型输出中解析子问题列表（严格校验，宁可失败也不猜）。

    容忍的东西：代码块包裹、前后多余文字、``{"subQuestions": [...]}`` 这类同义 key、
    ``[{"question": "..."}]`` 这类对象元素（取 ``question`` 字段）。
    **不容忍**的东西：没有 JSON、key 缺失、key 不是数组、数组里没有可用字符串 ——
    这些一律抛 :class:`SubagentError` 交给上层回退，不做"猜一个子问题出来"。

    Args:
        raw: 模型原始输出。
        limit: 子问题个数上限（调用方已 clamp 到硬上限）。

    Returns:
        去重并按原顺序截断到 ``limit`` 的子问题列表（非空）。

    Raises:
        SubagentError: 空输出 / 非 JSON / 缺字段 / 字段类型错 / 结果为空。
    """
    if not raw or not raw.strip():
        raise SubagentError("拆解输出为空")
    start = raw.find("{")
    end = raw.rfind("}")
    if start < 0 or end <= start:
        raise SubagentError("拆解输出不含 JSON 对象")
    try:
        # strict=False：本地 7B 常把换行等控制字符直接写进 JSON 字符串
        data = json.loads(raw[start:end + 1], strict=False)
    except (json.JSONDecodeError, ValueError) as exc:
        raise SubagentError(f"拆解输出 JSON 解析失败：{exc}") from exc
    if not isinstance(data, dict):
        raise SubagentError("拆解输出不是 JSON 对象")

    raw_items: Any = None
    for key in _SUB_QUESTION_KEYS:
        if key in data:
            raw_items = data.get(key)
            break
    if raw_items is None:
        raise SubagentError("拆解 JSON 缺少 sub_questions 字段")
    if not isinstance(raw_items, list):
        raise SubagentError("sub_questions 不是数组")

    questions = _clean_questions(raw_items)
    if not questions:
        raise SubagentError("拆解结果为空")
    return questions[:max(1, limit)]


def _clean_questions(items: Iterable[Any]) -> List[str]:
    """清洗子问题元素：取字符串 / 取对象里的 question，去空去重、逐条截断。"""
    cleaned: List[str] = []
    seen = set()
    for item in items:
        text = ""
        if isinstance(item, str):
            text = item
        elif isinstance(item, dict):
            for key in ("question", "q", "text", "title"):
                value = item.get(key)
                if isinstance(value, str) and value.strip():
                    text = value
                    break
        text = " ".join(text.split()).strip()
        if not text:
            continue
        if len(text) > SUB_QUESTION_MAX_CHARS:
            text = text[:SUB_QUESTION_MAX_CHARS]
        if text in seen:
            continue
        seen.add(text)
        cleaned.append(text)
    return cleaned


async def decompose(bridge: JavaBridge, ctx: AgentContext, limit: int) -> List[str]:
    """用一次 LLM 调用把问题拆成子问题。

    Args:
        bridge: Java 桥（Key 只在 Java 侧，本服务不持 Key）。
        ctx: 当前会话上下文（只用 ``question``，历史/记忆由 Java 侧拼装，
            拆解只需要"这一问"）。
        limit: 子问题上限（调用方已 clamp）。

    Returns:
        子问题列表（非空）。

    Raises:
        SubagentError: 调用失败 / 解析失败 / 结果为空。**调用方必须回退单 Agent**。
    """
    prompt = (
        f"学生问题：{ctx.question}\n"
        '请按要求只输出 JSON：{"sub_questions":["...","..."]}'
    )
    try:
        raw = await bridge.llm_complete(DECOMPOSE_SYSTEM_PROMPT, prompt)
    except JavaBridgeError as exc:
        raise SubagentError(f"拆解调用失败：{exc}") from exc
    return parse_sub_questions(raw, limit)


def render_sub_answers(attempts: List[SubAttempt]) -> str:
    """把各子答案拼成一段草稿，交给最终的 Java（L3 校验 / 生成）调用。

    只拼"有内容"的草稿：某个子问题没产出草稿时留一行说明，
    让最终生成知道"这条子问题只拿到了检索上下文，没有现成答案"。

    Args:
        attempts: 各子问题的循环产出（按执行顺序）。

    Returns:
        形如 ``【子问题 1】…\n【子问题 2】…`` 的草稿文本；无任何内容时返回空串。
    """
    lines: List[str] = []
    for index, attempt in enumerate(attempts, start=1):
        text = (attempt.draft or "").strip()
        if not text:
            snippets = _snippet_of(attempt.observations)
            text = snippets or "（该子问题仅取得检索上下文，请结合上下文作答）"
        lines.append(f"【子问题 {index}】{attempt.question}\n{text}")
    return "\n".join(lines)


def _snippet_of(observations: List[Dict[str, Any]]) -> str:
    """从观察里的工具摘要拼一句可用素材（结构化工具没有草稿时的兜底）。"""
    parts: List[str] = []
    for item in observations:
        if not item.get("ok"):
            continue
        summary = str(item.get("summary") or "").strip()
        if summary:
            parts.append(summary)
    return "；".join(parts[:3])
