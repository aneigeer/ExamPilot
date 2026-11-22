"""``/v1/agent`` —— Agent Harness 流式 / 非流式入口。

* ``POST /v1/agent/stream``：SSE，Java 汇聚后转发给前端；
* ``POST /v1/agent/run``：非流式，供离线评估与调试复用。

鉴权：``X-Internal-Token`` **fail-closed** —— 未配置 ``AI_INTERNAL_TOKEN`` 时
一律 401 + ``code=5231``（与 Java ``InternalTokenFilter`` 语义对齐），唯一例外是
显式设置 ``AGENT_ALLOW_ANONYMOUS=1``（仅限本地开发，启动打 ERROR）。

降级：``AGENT_ENABLED=false`` 返回 503 / ``5230``；Java 侧另有总开关，两层互不依赖。
"""

from __future__ import annotations

import asyncio
import logging
import secrets
from typing import AsyncIterator, Dict

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import StreamingResponse

from ..agent.events import sse
from ..agent.harness import AgentHarness
from ..agent.tools import AgentContext
from ..clients.java_bridge import JavaBridge
from ..config import get_settings
from ..errors import AiServiceError, CapabilityUnavailableError, ErrorCode, ParamError
from ..observability.trace import get_trace_id
from ..schemas import (
    MAX_STEPS_LIMIT,
    MAX_STEPS_MIN,
    AgentRequest,
    AgentRunResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["agent"])

#: 内部令牌请求头（与 Java ``InternalTokenFilter.HEADER`` 保持一致）
INTERNAL_TOKEN_HEADER = "X-Internal-Token"

#: 未授权提示（契约 §3 逐字固定）：**不区分**"没带""带错""服务端没配"，
#: 避免给爆破者提供区分信息。
UNAUTHORIZED_MESSAGE = "内部接口未授权：X-Internal-Token 缺失或无效"

#: 运行期"匿名放行"WARNING 只打一次的进程内标记（防刷屏；必须可被测试重置）
_anonymous_warned: bool = False


def _internal_token() -> str:
    """当前配置的内部令牌（单独抽出来便于测试替换）。"""
    return get_settings().agent.internal_token


def _allow_anonymous() -> bool:
    """是否显式关闭了 ``/v1/agent/*`` 鉴权（单独抽出来便于测试替换）。"""
    return get_settings().agent.allow_anonymous


def _reset_anonymous_warning() -> None:
    """重置"匿名放行已告警"标记（测试用；进程内只该告警一次）。"""
    global _anonymous_warned
    _anonymous_warned = False


def _warn_anonymous_once(path: str) -> None:
    """匿名放行时打一条 WARNING（**进程内只打一次**）。

    契约 §3 要求"运行期进程内 flag 只 WARNING 一次，防刷屏"：每个请求都打
    会把日志冲成噪音，反而让人看不见真正的错误。
    """
    global _anonymous_warned
    if _anonymous_warned:
        return
    _anonymous_warned = True
    logger.warning(
        "[Agent] /v1/agent/* 鉴权已关闭（AGENT_ALLOW_ANONYMOUS=true，生产禁止），"
        "已放行匿名请求 path=%s；生产必须配置 AI_INTERNAL_TOKEN",
        path,
    )


def _unauthorized() -> AiServiceError:
    """构造契约版 401 异常（``{code:5231,message,degraded:false}``，恰 3 字段）。

    必须用 :class:`AiServiceError` 而不是 ``HTTPException``：
    后者会走 ``main.handle_http_exception``，把状态码 <500 统一映射成
    ``code=5200``（参数错误），Java 侧就无法区分"没授权"和"参数写错"。
    """
    return AiServiceError(
        ErrorCode.UNAUTHORIZED,
        UNAUTHORIZED_MESSAGE,
        http_status=401,
        degraded=False,
    )


async def require_internal_token(
    request: Request,
    x_internal_token: str = Header(default="", alias=INTERNAL_TOKEN_HEADER),
) -> None:
    """``/v1/agent/run|stream`` 的令牌闸门：**fail-closed**。

    **为什么要它**：这两条路由会把 ``userId`` 直接交给工具层查该用户的
    错题本/学情等私有数据。只要 8000 端口可直连，任何人伪造 ``userId``
    就能读到别人的数据。

    **fail-closed（阶段三修正）**：``AI_INTERNAL_TOKEN`` 为空时**一律 401**。
    阶段二是 fail-open（未配置即放行），而实际 ``.env`` 恰恰缺这一项，
    于是"忘了配令牌"等于"完全无鉴权"，日志只有一条容易被淹掉的 WARNING。

    **唯一例外**：``AGENT_ALLOW_ANONYMOUS=1``（启动打 ERROR、首次放行打 WARNING）。
    作用域**仅这两条路由**，其余接口不受该开关影响。

    Args:
        request: 当前请求（仅用于日志里的 path）。
        x_internal_token: 调用方带来的令牌。

    Raises:
        AiServiceError: 未配置令牌且未开匿名 / 令牌不匹配 → 401 / 5231。
    """
    expected = _internal_token()
    if not expected:
        if _allow_anonymous():
            _warn_anonymous_once(request.url.path)
            return
        logger.warning(
            "[Agent] 拒绝请求（fail-closed）：AI_INTERNAL_TOKEN 未配置 path=%s；"
            "修复：配置与 Java 同值的令牌，或本地临时设 AGENT_ALLOW_ANONYMOUS=1",
            request.url.path,
        )
        raise _unauthorized()
    if not secrets.compare_digest(x_internal_token or "", expected):
        # 不区分"没带"与"带错"，避免给爆破者提供区分信息
        raise _unauthorized()


async def require_internal_token_if_configured(
    request: Request,
    x_internal_token: str = Header(default="", alias=INTERNAL_TOKEN_HEADER),
) -> None:
    """**配置了才校验**的令牌闸门 —— 给 ``/v1/route`` 等"非 agent"内部链路用。

    契约 §3 明确 fail-closed 的作用域**只有** ``/v1/agent/run|stream``；
    ``/v1/route``、``/v1/embed|search|ocr|llm|wiki|interview`` 维持阶段二行为
    （未配置令牌时放行 + WARNING），否则一次配置遗漏会把整条降级链路打挂 ——
    "Agent 不可用"应当降级到 Java 问答，而不是连意图路由也一起丢。

    Args:
        request: 当前请求（仅用于日志）。
        x_internal_token: 调用方带来的令牌。

    Raises:
        AiServiceError: 配置了令牌但缺失/不匹配 → 401 / 5231。
    """
    expected = _internal_token()
    if not expected:
        logger.warning(
            "[Agent] AI_INTERNAL_TOKEN 未配置，%s 处于无鉴权模式（仅限本地开发）",
            request.url.path,
        )
        return
    if not secrets.compare_digest(x_internal_token or "", expected):
        raise _unauthorized()


def _context(request: AgentRequest) -> AgentContext:
    """请求体 → :class:`AgentContext`（含 traceId 取值优先级）。

    **traceId 以头为准**（契约 §1 第 5 项）：中间件已把入站 ``X-Trace-Id``
    （或新生成的值）写进 contextvar，这里优先取它，body 的 ``traceId`` 只作兜底。
    只读 body 会让"只带头"的调用方（离线评估脚本、其它内部服务）拿到空 traceId，
    ``meta.traceId`` 与响应头不一致，"按 traceId grep 全链路"当场断链。

    .. note::
       **HTTP 路径上 ``request.trace_id`` 实际走不到**：中间件总会 set contextvar
       （无头时生成新值），因此"无头 + 仅 body"时 ``meta.traceId`` 取的是生成值
       而非 body 值。这是**刻意**的 —— 契约要求"以头为准 + 生成兜底"，
       且保证"响应头 == meta"（若让 body 赢，响应头与 meta 会分裂成两个值，
       排查时根本不知道该信哪个）。body 分支保留的意义在于**非 HTTP 调用方**：
       离线评估脚本/单测直接 ``_context(AgentRequest(...))`` 时没有中间件，
       此时 body 的 traceId 就是唯一线索。
    """
    weak_ids = list(request.profile.weak_knowledge_ids) if request.profile else []
    subject_id = request.subject_id
    if subject_id is None and request.profile is not None:
        subject_id = request.profile.subject_id
    return AgentContext(
        question=request.question,
        user_id=request.user_id,
        subject_id=subject_id,
        exam_category_id=request.exam_category_id,
        weak_knowledge_ids=weak_ids,
        # ★ 头（contextvar）优先，body 兜底：保证 meta.traceId == 响应头 == Java 侧 MDC
        trace_id=get_trace_id() or request.trace_id or "",
        session_id=request.session_id or "",
        history=[
            {"question": turn.question, "answer": turn.answer}
            for turn in request.history
        ],
        memory_summary=request.memory_summary or "",
        approved_tools=list(request.approved_tools or []),
    )


def _harness(max_steps_override: int | None) -> AgentHarness:
    """构建本次会话的 Harness；``max_steps_override`` 非 None 时覆盖配置值。

    Args:
        max_steps_override: 请求体 ``maxSteps``；``None`` 表示沿用配置默认值（6）。

    Returns:
        绑定好 Java 桥的 :class:`AgentHarness`。

    Raises:
        CapabilityUnavailableError: ``AGENT_ENABLED=false``（503 / 5230）。
        ParamError: 步数越界（400 / 5200）。正常 HTTP 路径上已被
            ``AgentRequest`` 的校验拦下，这里兜住"绕过 schema 直接调 ``_harness``"
            的内部调用方（如评估脚本）。
    """
    settings = get_settings()
    if not settings.agent.enabled:
        raise CapabilityUnavailableError(
            "Agent 能力未启用（AGENT_ENABLED=false）",
            code=5230,
        )
    bridge = JavaBridge(settings.agent)
    # ★ 必须判 `is not None`：阶段二用 `if max_steps_override:`，导致显式传 0 被
    #   静默忽略（0 是 falsy），调用方以为限制了步数其实用了默认值。0 现在由
    #   schema 直接 400，这里只保证"传了就一定生效、绝不静默降级为默认值"。
    if max_steps_override is not None:
        if max_steps_override < MAX_STEPS_MIN or max_steps_override > MAX_STEPS_LIMIT:
            raise ParamError(
                f"maxSteps 必须在 {MAX_STEPS_MIN}..{MAX_STEPS_LIMIT} 之间"
                f"（当前 {max_steps_override}）"
            )
        # 仅覆盖步数，不改变其余配置
        from dataclasses import replace

        settings = replace(
            settings,
            agent=replace(settings.agent, max_steps=max_steps_override),
        )
    return AgentHarness(settings.agent, bridge)


@router.post(
    "/v1/agent/stream",
    dependencies=[Depends(require_internal_token)],
    summary="Agent 流式执行（SSE）",
    description=(
        "事件顺序：meta → thought → tool_call → tool_result … → final → done。"
        "客户端断开时生成器会收到 CancelledError，必须停止上游 LLM/工具调用。"
    ),
)
async def agent_stream(request: AgentRequest) -> StreamingResponse:
    harness = _harness(request.max_steps)
    ctx = _context(request)

    async def generate() -> AsyncIterator[str]:
        try:
            async for item in harness.run(ctx):
                yield sse(str(item["event"]), dict(item["data"]))
        except asyncio.CancelledError:
            # 客户端断开 / 上游取消：原样抛出，让 Starlette 关闭生成器
            logger.info("[Agent] 客户端断开，收到 CancelledError（停止 LLM/工具调用）")
            raise
        except Exception:  # noqa: BLE001
            logger.exception("[Agent] 会话执行失败")
            yield sse("error", {"message": "Agent 执行失败，请稍后重试"})

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


@router.post(
    "/v1/agent/run",
    dependencies=[Depends(require_internal_token)],
    response_model=AgentRunResponse,
    summary="Agent 非流式执行",
    description="一次性执行并返回完整事件数组，供离线评估与调试使用。",
)
async def agent_run(request: AgentRequest) -> AgentRunResponse:
    harness = _harness(request.max_steps)
    ctx = _context(request)
    result = await harness.collect(ctx)
    return AgentRunResponse(**result)
