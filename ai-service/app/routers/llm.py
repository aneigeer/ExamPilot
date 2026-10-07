"""``POST /v1/llm`` —— 大模型代理（GLM，OpenAI 兼容协议）。

**降级语义（本接口的核心契约）**：

* ``LLM_API_KEY`` 未配置 → **503 / 5209**
  → Java ``AiQaService`` 直接走 ``ExtractiveAnswerClient``，接口对 App 仍是 200。
* 上游超时 / 5xx / 返回格式异常 → 同样 **503 / 5209**，绝不透传成 500。

**为什么不做"服务内兜底"**：本服务一旦自己返回答案，Java 侧就分不清
这个答案是大模型生成的还是抽取式拼接的，会破坏 L3 幻觉校验的前提。
**降级决策权只在 Java 侧**（它是唯一持有 prompt 与溯源上下文的地方）。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from ..clients.llm_client import get_llm_client
from ..schemas import LlmRequest, LlmResponse, LlmSource

logger = logging.getLogger(__name__)

router = APIRouter(tags=["llm"])


@router.post(
    "/v1/llm",
    response_model=LlmResponse,
    summary="大模型代理（GLM / OpenAI 兼容）",
    description=(
        "以检索到的 contexts 为 RAG 上下文生成回答，答案中带 [n] 引用标注。"
        "sources 字段已从标注中提取，供 Java 侧做 L3 幻觉校验（编号与来源是否自洽）。"
        "**未配置 LLM_API_KEY 或上游失败时返回 503**，Java 侧自动走抽取式兜底，App 侧仍为 200。"
    ),
)
def llm(request: LlmRequest) -> LlmResponse:
    """代理大模型生成。

    Args:
        request: 问题 + 检索上下文。

    Returns:
        :class:`LlmResponse`。

    Raises:
        app.errors.LlmUnavailableError: 未配置 Key / 上游异常（503）。
        app.errors.AiServiceError: 其他能力不可用（503）。
    """
    client = get_llm_client()
    result = client.chat(
        request.question,
        request.contexts,
        max_tokens=request.max_tokens,
        temperature=request.temperature,
    )
    sources = [
        LlmSource(chunk_id=None, index=item["index"], snippet=item["snippet"])
        for item in result["sources"]
    ]
    logger.info(
        "[LLM] generator=%s cost_ms=%d sources=%d",
        result["generator"], result["cost_ms"], len(sources),
    )
    return LlmResponse(
        answer=str(result["answer"]),
        sources=sources,
        generator=str(result["generator"]),
        cost_ms=int(result["cost_ms"]),
        degraded=False,
    )
