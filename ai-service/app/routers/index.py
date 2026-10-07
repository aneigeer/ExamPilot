"""向量索引管理：``/v1/index/sync``、``/v1/index/rebuild``、``/v1/index/status``。

**与 Java 侧的配合（设计方案 C.2）**：

* ``ChunkSyncService`` 审核通过后 **异步** 调 ``/v1/index/sync``，
  成功置 ``vector_synced=1``，失败置 ``2``，下次对账重试。
* ``POST /api/admin/ai/reindex`` 调 ``/v1/index/rebuild`` 重建集合。
* ``/v1/index/status`` 与 MySQL ``ai_vector_index_meta`` 对账，
  ``chunk_synced != chunk_total`` 即触发补推。

**换模型的强制规则**：绝不允许同一 collection 混用不同模型的向量 ——
本模块用 ``assert_model_matches`` 在**检索路径**上拦住（返回 5208），
在 **rebuild 路径**上主动清空重建。
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter

from ..clients.embedding_client import get_embedding_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..schemas import (
    IndexRebuildRequest,
    IndexRebuildResponse,
    IndexStatusResponse,
    IndexSyncRequest,
    IndexSyncResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["index"])


@router.post(
    "/v1/index/sync",
    response_model=IndexSyncResponse,
    summary="批量写入向量（Java ChunkSyncService 推送）",
    description=(
        "对传入的语料块做向量化并 upsert 到 Qdrant。"
        "返回 synced/skipped/failed 三计数，Java 侧据此更新 vector_synced 状态位。"
        "失败时返回 503，Java 侧置 vector_synced=2 待重试。"
    ),
)
def index_sync(request: IndexSyncRequest) -> IndexSyncResponse:
    """批量向量化并写入。

    Args:
        request: 待同步语料块，单次最多 500 条。

    Returns:
        :class:`IndexSyncResponse`。

    Raises:
        app.errors.AiServiceError: 向量化或写入失败（503）。
    """
    settings = get_settings()
    embedding_client = get_embedding_client()
    qdrant_client = get_qdrant_client()

    started = time.perf_counter()
    chunks = request.chunks

    # ① 保证集合存在且模型标识已写入
    qdrant_client.ensure_collection()
    qdrant_client.upsert_meta(settings.embedding.model_id, settings.embedding.dim)

    # ② 过滤空内容（skipped 计数）
    usable = [chunk for chunk in chunks if chunk.content and chunk.content.strip()]
    skipped = len(chunks) - len(usable)
    if not usable:
        took_ms = int((time.perf_counter() - started) * 1000)
        return IndexSyncResponse(synced=0, skipped=skipped, failed=0, took_ms=took_ms)

    # ③ 向量化（失败即抛 5204，整批置 failed 由 Java 侧重试）
    vectors = embedding_client.encode([chunk.content for chunk in usable], is_query=False)

    # ④ 写入
    synced = qdrant_client.upsert_chunks(usable, vectors)
    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info(
        "[IndexSync] synced=%d skipped=%d failed=0 took_ms=%d", synced, skipped, took_ms
    )
    return IndexSyncResponse(synced=synced, skipped=skipped, failed=0, took_ms=took_ms)


@router.post(
    "/v1/index/rebuild",
    response_model=IndexRebuildResponse,
    summary="重建向量集合（换模型或集合损坏时）",
    description=(
        "**清空并重建** collection，重新写入模型标识与维度。"
        "重建后需从 MySQL ai_chunk 重新推送向量（POST /api/admin/ai/reindex 会自动做这件事）。"
    ),
)
def index_rebuild(request: IndexRebuildRequest) -> IndexRebuildResponse:
    """重建集合。

    Args:
        request: 目标模型与维度；均缺省时用配置值。

    Returns:
        :class:`IndexRebuildResponse`，``status`` 为 READY / FAILED。
    """
    settings = get_settings()
    qdrant_client = get_qdrant_client()
    model_id = request.model_id or settings.embedding.model_id
    dim = request.dim or settings.embedding.dim
    result = qdrant_client.rebuild(model_id, dim)
    logger.info("[IndexRebuild] status=%s collection=%s", result["status"], result["collection"])
    return IndexRebuildResponse(
        status=str(result["status"]),
        collection=str(result["collection"]),
        model_id=str(result["model_id"]),
        dim=int(result["dim"]),
        message=str(result.get("message", "")),
    )


@router.get(
    "/v1/index/status",
    response_model=IndexStatusResponse,
    summary="向量索引状态（与 MySQL 对账）",
    description=(
        "返回 collection 内记录的模型标识、维度与实际点数。"
        "status=MODEL_MISMATCH 时 Java 侧应触发 reindex。"
    ),
)
def index_status() -> IndexStatusResponse:
    """查询索引状态。

    Returns:
        :class:`IndexStatusResponse`。

    Raises:
        app.errors.VectorSearchError: Qdrant 不可用（503）。
    """
    settings = get_settings()
    qdrant_client = get_qdrant_client()
    result = qdrant_client.status(settings.embedding.model_id, settings.embedding.dim)
    return IndexStatusResponse(**result)
