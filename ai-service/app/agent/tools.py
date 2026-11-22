"""Agent 只读工具集。

所有工具都通过 Java 内部接口取业务数据（``X-Internal-Token``），
本服务不直连 MySQL，避免与 Java 侧的数据访问逻辑漂移。

截断规则（写死，测试会断言）：
* 每条结果保留 ``title + id + 正文前 300 字符``；
* 超出部分追加 ``…[truncated]``；
* ``search_knowledge`` 只保留 top3 片段。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from ..clients.java_bridge import JavaBridge, JavaBridgeError
from .artifact_store import ArtifactStore

logger = logging.getLogger(__name__)

TRUNCATE_LIMIT = 300
TRUNCATE_MARKER = "…[truncated]"
SEARCH_KEEP = 3


def truncate(text: Any, limit: int = TRUNCATE_LIMIT) -> str:
    """按字符截断，超出部分以固定标记结尾。"""
    if text is None:
        return ""
    value = str(text)
    if len(value) <= limit:
        return value
    return value[:limit] + TRUNCATE_MARKER


@dataclass
class AgentContext:
    """一次 Agent 会话的上下文。"""

    question: str
    user_id: Optional[int] = None
    subject_id: Optional[int] = None
    exam_category_id: Optional[int] = None
    weak_knowledge_ids: List[int] = field(default_factory=list)
    trace_id: str = ""
    #: 会话 ID（跨轮次记忆）
    session_id: str = ""
    #: 最近轮次 [{question, answer}]，时间正序
    history: List[Dict[str, Any]] = field(default_factory=list)
    #: Java 维护的滚动摘要
    memory_summary: str = ""
    #: 本轮已获人工审批的高风险工具名列表
    approved_tools: List[str] = field(default_factory=list)


@dataclass
class ToolResult:
    ok: bool
    summary: str
    data: Any = None
    contexts: List[Dict[str, Any]] = field(default_factory=list)


class ArgumentError(Exception):
    """工具参数非法：统一映射为「参数错误」，且不访问 Java。"""


def _validate_arguments(name: str, args: Any) -> Dict[str, Any]:
    """执行前校验必填/类型；可选数值参数由各工具自行 clamp。

    失败信息统一以「语义 + 正确写法」两段呈现（见 :func:`_argument_hint`）：
    这条 summary 会作为 ``tool_result`` 回喂给 planner，是模型唯一能看到的
    「我该怎么改」的线索，所以不能只写抽象约束；也**不会**静默改用默认值，
    必填参数缺失一律判失败（避免把错误参数当成正确答案的证据）。
    """
    if not isinstance(args, dict):
        raise ArgumentError("arguments 必须是对象" + _argument_hint(name))
    if name == "read_artifact":
        artifact_id = args.get("artifact_id")
        if not isinstance(artifact_id, str) or not artifact_id.strip():
            raise ArgumentError("artifact_id 必须是非空字符串" + _argument_hint(name))
    if name in ("search_knowledge", "search_wiki"):
        query = args.get("query")
        if not isinstance(query, str) or not query.strip():
            raise ArgumentError("query 必须是非空字符串" + _argument_hint(name))
    elif name == "get_wiki_page":
        page_id = args.get("pageId")
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id <= 0:
            raise ArgumentError("pageId 必须是正整数" + _argument_hint(name))
    elif name == "get_question":
        question_id = args.get("questionId")
        if not isinstance(question_id, int) or isinstance(question_id, bool) or question_id <= 0:
            raise ArgumentError("questionId 必须是正整数" + _argument_hint(name))
    return args


#: 工具目录（写进提示词，顺序即推荐优先级）
#:
#: ``required`` / ``example`` 是**给校验层用**的事实，不改变工具语义与返回结构：
#: * ``required``：必填参数名，校验失败时据此拼接「必填参数：…」回喂给模型；
#: * ``example``：该工具一次**合法调用**的参数写法，同样用于错误回喂。
#: 两者都在 :func:`_argument_hint` 里被消费，避免「提示词说一套、校验说另一套」。
TOOL_SPECS: List[Dict[str, Any]] = [
    {
        "name": "search_knowledge",
        "risk_level": "READ",
        "requires_approval": False,
        "description": "检索站内已审核的题目/资料/知识点，返回最相关的语料片段。任何需要事实依据的问题都应先调用它。",
        "required": ["query"],
        "example": '{"query": "C语言 int 占几个字节", "topK": 5}',
        "arguments": {
            "query": "string，必填，非空检索语句（尽量保留学生原话中的关键词）",
            "topK": "int，可选 1-10，默认 5",
        },
    },
    {
        "name": "search_wiki",
        "risk_level": "READ",
        "requires_approval": False,
        "description": "检索已沉淀的 Wiki 主题页（跨文档综合的结构化专题，适合概念梳理、知识框架、专题总结类问题）。",
        "required": ["query"],
        "example": '{"query": "函数 专题", "topK": 3}',
        "arguments": {
            "query": "string，必填，非空检索语句",
            "topK": "int，可选 1-10，默认 3",
        },
    },
    {
        "name": "get_wiki_page",
        "risk_level": "READ",
        "requires_approval": False,
        "description": (
            "按页面 ID 读取 Wiki 主题页正文与关联页。"
            "只接受 pageId（整数，取自 search_wiki 返回的 pageId）；"
            "本工具**不接受 query**，想按关键词找页面请改用 search_wiki。"
        ),
        "required": ["pageId"],
        "example": '{"pageId": 12}',
        "arguments": {
            "pageId": "int，必填，Wiki 页面 ID（正整数），取自 search_wiki 返回的 pageId",
        },
    },
    {
        "name": "get_weak_knowledge",
        "risk_level": "READ",
        "requires_approval": False,
        "description": "读取当前学生的薄弱知识点列表（含掌握度），用于制定针对性练习。",
        "arguments": {"limit": "int，可选 1-20，默认 5"},
    },
    {
        "name": "get_wrong_book",
        "risk_level": "READ",
        "requires_approval": False,
        "description": "读取当前学生最近未掌握的错题。",
        "arguments": {"limit": "int，可选 1-20，默认 5"},
    },
    {
        "name": "get_study_plan",
        "risk_level": "READ",
        "requires_approval": False,
        "description": "读取今日学习计划（任务、题量、完成度）。",
        "arguments": {},
    },
    {
        "name": "read_artifact",
        "risk_level": "READ",
        "requires_approval": False,
        "description": (
            "按 artifact_id 分段读取被落盘的长工具输出（offset/limit 控制字符区间）。"
            "artifact_id 只能取自工具结果里的提示，禁止编造。"
        ),
        "required": ["artifact_id"],
        "example": '{"artifact_id": "0123456789abcdef", "offset": 0, "limit": 1200}',
        "arguments": {
            "artifact_id": "string，必填，16 位十六进制 ID",
            "offset": "int，可选，起始字符，默认 0",
            "limit": "int，可选 1-4000，默认 1200",
        },
    },
    {
        "name": "get_question",
        "risk_level": "READ",
        "requires_approval": False,
        "description": (
            "按题目 ID 读取题干、选项与解析。"
            "questionId 只能取自 search_knowledge 或错题本返回的题目 ID，禁止编造。"
        ),
        "required": ["questionId"],
        "example": '{"questionId": 12}',
        "arguments": {
            "questionId": "int，必填，题目 ID（正整数），取自工具返回结果",
        },
    },
]


def risk_of(name: str) -> str:
    """工具风险级别：READ / WRITE_LOW / WRITE_HIGH（未声明按 READ）。"""
    return str(_spec_of(name).get("risk_level") or "READ")


def requires_approval(name: str) -> bool:
    """高风险写操作是否需要人工审批。"""
    return bool(_spec_of(name).get("requires_approval"))


def _spec_of(name: str) -> Dict[str, Any]:
    """按工具名取目录条目；未知工具返回空 dict。"""
    for spec in TOOL_SPECS:
        if spec["name"] == name:
            return spec
    return {}


def _argument_hint(name: str) -> str:
    """把「必填参数 + 一次合法调用写法」拼成回喂给模型的短提示。

    只用于错误信息，不参与执行逻辑：参数校验失败时模型拿到的不是
    「xxx 必须是什么」这类抽象约束，而是**可以直接照抄的正确写法**，
    降低下一轮继续空参/改错键名的概率。
    """
    spec = _spec_of(name)
    parts: List[str] = []
    required = [str(key) for key in (spec.get("required") or [])]
    if required:
        parts.append("必填参数：" + "、".join(required))
    example = str(spec.get("example") or "")
    if example:
        parts.append("正确写法：" + example)
    return "（" + "；".join(parts) + "）" if parts else ""


def tool_catalog_text() -> str:
    """把工具目录渲染成提示词文本。"""
    lines: List[str] = []
    for spec in TOOL_SPECS:
        args = ", ".join(f"{k}: {v}" for k, v in spec["arguments"].items()) or "无"
        lines.append(f"- {spec['name']}：{spec['description']} 参数：{args}")
    return "\n".join(lines)


class ToolRegistry:
    """工具执行器（只读）。"""

    def __init__(self, bridge: JavaBridge) -> None:
        self.bridge = bridge

    async def execute(self, name: str, arguments: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        try:
            arguments = _validate_arguments(name, arguments)
        except ArgumentError as exc:
            return ToolResult(ok=False, summary=f"参数错误：{exc}")
        approved = set(ctx.approved_tools) if (ctx is not None and ctx.approved_tools) else set()
        if requires_approval(name) and name not in approved:
            return ToolResult(ok=False,
                              summary=f"工具 {name} 属于高风险写操作，需人工审批后执行")
        try:
            if name == "search_knowledge":
                return await self._search(arguments, ctx)
            if name == "search_wiki":
                return await self._search_wiki(arguments, ctx)
            if name == "get_wiki_page":
                return await self._get_wiki_page(arguments)
            if name == "get_weak_knowledge":
                return await self._weak(arguments, ctx)
            if name == "get_wrong_book":
                return await self._wrong(arguments, ctx)
            if name == "get_study_plan":
                return await self._plan(ctx)
            if name == "read_artifact":
                return self._read_artifact(arguments, ctx)
            if name == "get_question":
                return await self._question(arguments)
            return ToolResult(ok=False, summary=f"未知工具：{name}")
        except JavaBridgeError as exc:
            logger.warning("[Agent] 工具 %s 调用失败：%s", name, exc)
            return ToolResult(ok=False, summary=f"工具不可用：{exc}")
        except Exception as exc:  # noqa: BLE001
            logger.exception("[Agent] 工具 %s 异常", name)
            return ToolResult(ok=False, summary=f"工具执行异常：{type(exc).__name__}")

    # ------------------------------------------------------------------ 具体工具

    async def _search(self, args: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        query = str(args.get("query") or ctx.question).strip()
        top_k = _clamp_int(args.get("topK"), 5, 1, 10)
        payload = {
            "question": query,
            "subjectId": ctx.subject_id,
            "examCategoryId": ctx.exam_category_id,
            "topK": top_k,
            # 生产 Agent 默认走拒答闸门；只有评估执行器显式传 False
            "applyGate": True,
        }
        data = await self.bridge.tool_post("/internal/agent/tools/search", payload)
        contexts = list((data or {}).get("contexts") or [])
        trimmed = []
        for item in contexts[:SEARCH_KEEP]:
            trimmed.append(
                {
                    "chunkId": item.get("chunkId"),
                    "title": item.get("title"),
                    "snippet": truncate(item.get("content")),
                    "refType": item.get("refType"),
                    "refId": item.get("refId"),
                    "score": item.get("score"),
                }
            )
        summary = (
            f"检索「{query}」命中 {len(contexts)} 条，最高分 "
            f"{(data or {}).get('bestScore', 0)}，模式 {(data or {}).get('mode', '-')}"
        )
        return ToolResult(ok=True, summary=summary, data=trimmed, contexts=contexts)

    async def _search_wiki(self, args: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        query = str(args.get("query") or ctx.question).strip()
        top_k = _clamp_int(args.get("topK"), 3, 1, 10)
        payload = {
            "question": query,
            "subjectId": ctx.subject_id,
            "topK": top_k,
        }
        data = await self.bridge.tool_post("/internal/agent/tools/wiki-search", payload)
        hits = list((data or {}).get("hits") or [])
        trimmed = []
        contexts = []
        for item in hits[:SEARCH_KEEP]:
            content = truncate(item.get("content") or item.get("summary"), 600)
            page_id = item.get("id")
            trimmed.append({
                "pageId": page_id,
                "slug": item.get("slug"),
                "title": item.get("title"),
                "summary": truncate(item.get("summary")),
                "score": item.get("score"),
                "channel": item.get("channel"),
            })
            contexts.append({
                "chunkId": None,
                "sourceType": "WIKI",
                "sourceId": page_id,
                "title": item.get("title"),
                "content": content,
                "subjectId": item.get("subjectId"),
                "knowledgeId": item.get("knowledgeId"),
                "refType": "WIKI",
                "refId": page_id,
                "score": item.get("score"),
            })
        best = hits[0].get("score", 0) if hits else 0
        return ToolResult(
            ok=True,
            summary=f"Wiki 专题「{query}」命中 {len(hits)} 页，最高分 {best}",
            data=trimmed,
            contexts=contexts,
        )

    def _read_artifact(self, args: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        """只读、按 trace 归属校验的 artifact 分段读取。"""
        result = ArtifactStore().read(
            str(args.get("artifact_id") or ""),
            ctx.trace_id,
            int(args.get("offset") or 0),
            int(args.get("limit") or 1200),
        )
        if not result.get("ok"):
            return ToolResult(ok=False, summary="artifact 读取失败：" + str(result.get("error")))
        return ToolResult(
            ok=True,
            summary=(
                f"artifact {result['artifact_id']} 片段 "
                f"[{result['offset']}, {result['offset'] + len(result['content'])}] / {result['total']}"
            ),
            data=result,
        )

    async def _get_wiki_page(self, args: Dict[str, Any]) -> ToolResult:
        page_id = args.get("pageId")
        if page_id is None:
            return ToolResult(ok=False, summary="缺少 pageId")
        data = await self.bridge.tool_get(f"/internal/agent/tools/wiki-page/{int(page_id)}")
        if not data:
            return ToolResult(ok=False, summary="Wiki 页面不存在")
        related = list(data.get("relatedPages") or [])
        summary = f"Wiki 页面「{data.get('title')}」：{truncate(data.get('summary'), 160)}"
        result = {
            "pageId": data.get("id"),
            "slug": data.get("slug"),
            "title": data.get("title"),
            "summary": data.get("summary"),
            "content": truncate(data.get("content"), 800),
            "keyPoints": data.get("keyPoints") or [],
            "relatedPages": [
                {"pageId": item.get("id"), "title": item.get("title"), "slug": item.get("slug")}
                for item in related[:5]
            ],
        }
        contexts = [{
            "chunkId": None,
            "sourceType": "WIKI",
            "sourceId": data.get("id"),
            "title": data.get("title"),
            "content": truncate(data.get("content"), 800),
            "subjectId": data.get("subjectId"),
            "knowledgeId": data.get("knowledgeId"),
            "refType": "WIKI",
            "refId": data.get("id"),
            "score": 1.0,
        }]
        return ToolResult(ok=True, summary=summary, data=result, contexts=contexts)

    async def _weak(self, args: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        if ctx.user_id is None and ctx.weak_knowledge_ids:
            # 离线评估会注入模拟画像：不读库，直接回显预置薄弱点
            synthetic = [
                {"knowledgeId": kid, "name": None, "mastery": None, "weakness": 1.0}
                for kid in ctx.weak_knowledge_ids
            ]
            return ToolResult(
                ok=True,
                summary=f"预置薄弱知识点 {len(synthetic)} 个",
                data=synthetic,
            )
        if ctx.user_id is None:
            return ToolResult(ok=False, summary="未登录且没有预置画像，无法读取薄弱知识点")
        limit = _clamp_int(args.get("limit"), 5, 1, 20)
        data = await self.bridge.tool_get(
            "/internal/agent/tools/weak-knowledge",
            {"userId": ctx.user_id, "limit": limit},
        )
        items = list(data or [])
        trimmed = [
            {
                "knowledgeId": item.get("knowledgeId"),
                "name": item.get("name"),
                "mastery": item.get("mastery"),
                "weakness": item.get("weakness"),
            }
            for item in items[:limit]
        ]
        return ToolResult(
            ok=True,
            summary=f"薄弱知识点 {len(items)} 个："
            + "、".join(str(i.get("name")) for i in trimmed[:5]),
            data=trimmed,
        )

    async def _wrong(self, args: Dict[str, Any], ctx: AgentContext) -> ToolResult:
        if ctx.user_id is None:
            return ToolResult(ok=False, summary="未登录，无法读取错题本")
        limit = _clamp_int(args.get("limit"), 5, 1, 20)
        data = await self.bridge.tool_get(
            "/internal/agent/tools/wrong-book", {"userId": ctx.user_id, "limit": limit}
        )
        items = list(data or [])
        trimmed = [
            {
                "questionId": item.get("questionId"),
                "stem": truncate(item.get("stem"), 120),
                "wrongCount": item.get("wrongCount"),
            }
            for item in items[:limit]
        ]
        return ToolResult(
            ok=True,
            summary=f"错题 {len(items)} 道：" + "、".join(str(i.get("questionId")) for i in trimmed[:5]),
            data=trimmed,
        )

    async def _plan(self, ctx: AgentContext) -> ToolResult:
        if ctx.user_id is None:
            return ToolResult(ok=False, summary="未登录，无法读取学习计划")
        data = await self.bridge.tool_get(
            "/internal/agent/tools/study-plan", {"userId": ctx.user_id}
        )
        if not data:
            return ToolResult(ok=True, summary="今日暂无学习计划", data=None)
        items = list(data.get("items") or [])
        summary = (
            f"今日计划 {data.get('plannedCount', 0)} 题，已完成 {data.get('doneCount', 0)} 题"
            f"（{data.get('completionPercent', 0)}%），任务 {len(items)} 个"
        )
        trimmed = [
            {
                "knowledgeName": item.get("knowledgeName"),
                "targetCount": item.get("targetCount"),
                "reason": item.get("reason"),
            }
            for item in items[:5]
        ]
        return ToolResult(ok=True, summary=summary, data=trimmed)

    async def _question(self, args: Dict[str, Any]) -> ToolResult:
        question_id = args.get("questionId")
        if question_id is None:
            return ToolResult(ok=False, summary="缺少 questionId")
        data = await self.bridge.tool_get(
            f"/internal/agent/tools/question/{int(question_id)}"
        )
        if not data:
            return ToolResult(ok=False, summary="题目不存在")
        summary = f"题目 {question_id}：{truncate(data.get('stem'), 120)}"
        return ToolResult(ok=True, summary=summary, data=data)


def _clamp_int(value: Any, default: int, low: int, high: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))
