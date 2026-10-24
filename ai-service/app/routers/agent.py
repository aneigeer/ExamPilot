"""``/v1/agent`` —— Agent Harness 流式 / 非流式入口。

* ``POST /v1/agent/stream``：SSE，Java 汇聚后转发给前端；
* ``POST /v1/agent/run``：非流式，供离线评估与调试复用。

降级：``AGENT_ENABLED=false`` 返回 503；Java 侧另有总开关，两层互不依赖。
"""

from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator, Dict

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from ..agent.events import sse
from ..agent.harness import AgentHarness
from ..agent.tools import AgentContext
from ..clients.java_bridge import JavaBridge
from ..config import get_settings
from ..errors import CapabilityUnavailableError
from ..schemas import AgentRequest, AgentRunResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["agent"])


def _context(request: AgentRequest) -> AgentContext:
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
        trace_id=request.trace_id or "",
    )


def _harness(max_steps_override: int | None) -> AgentHarness:
    settings = get_settings()
    if not settings.agent.enabled:
        raise CapabilityUnavailableError(
            "Agent 能力未启用（AGENT_ENABLED=false）",
            code=5230,
        )
    bridge = JavaBridge(settings.agent)
    if max_steps_override:
        # 仅覆盖步数，不改变其余配置
        from dataclasses import replace

        settings = replace(
            settings,
            agent=replace(settings.agent, max_steps=max_steps_override),
        )
    return AgentHarness(settings.agent, bridge)


@router.post(
    "/v1/agent/stream",
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
    response_model=AgentRunResponse,
    summary="Agent 非流式执行",
    description="一次性执行并返回完整事件数组，供离线评估与调试使用。",
)
async def agent_run(request: AgentRequest) -> AgentRunResponse:
    harness = _harness(request.max_steps)
    ctx = _context(request)
    result = await harness.collect(ctx)
    return AgentRunResponse(**result)
