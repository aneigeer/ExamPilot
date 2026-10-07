"""``POST /v1/embed`` —— 文本向量化。

**降级语义**：onnxruntime / tokenizers 未安装，或模型文件缺失
→ 503 ``{"code":5201|5204, "message":"...", "degraded":true}``，
Java 侧 ``VectorChannel`` 记熔断并降级 ngram 全文检索。
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter

from ..clients.embedding_client import get_embedding_client
from ..schemas import EmbedRequest, EmbedResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["embedding"])


@router.post(
    "/v1/embed",
    response_model=EmbedResponse,
    summary="文本向量化（bge-small-zh-v1.5 ONNX INT8，512 维）",
    description=(
        "输入文本列表，返回 L2 归一化后的 512 维向量。"
        "首次调用会加载模型（约 1-3 秒），之后单条约 10-30ms。"
        "**依赖不可用时返回 503**，Java 侧降级到 ngram 全文检索。"
    ),
)
def embed(request: EmbedRequest) -> EmbedResponse:
    """批量向量化文本。

    Args:
        request: 请求体，``texts`` 最多 64 条。

    Returns:
        :class:`EmbedResponse`，``vectors[i]`` 与 ``texts[i]`` 一一对应。

    Raises:
        app.errors.AiServiceError: 依赖不可用或推理失败，由全局处理器转 503。
    """
    client = get_embedding_client()
    started = time.perf_counter()
    vectors = client.encode(request.texts, is_query=False)
    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info("[Embed] count=%d dim=%d took_ms=%d", len(vectors), client.dim, took_ms)
    return EmbedResponse(
        dim=client.dim,
        vectors=vectors,
        took_ms=took_ms,
    )
