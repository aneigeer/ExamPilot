"""LLM Wiki 热层向量接口。

* ``POST /v1/wiki/index/sync``  审核通过后 Java 推送页面摘要向量
* ``POST /v1/wiki/index/delete`` 页面下架后删除向量点
* ``POST /v1/wiki/search``      问答热层语义检索（返回 page_id）

**与 RAG 的隔离**：Wiki 使用独立 collection（默认 ``wiki_pages``），
RAG 语料向量仍在 ``exam_chunks``。两者共用同一个 Qdrant 客户端实例
（本地目录模式单进程独占），但集合、payload、检索入口完全分离。
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List

from fastapi import APIRouter

from ..clients.embedding_client import get_embedding_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..schemas import (
    WikiDeleteRequest,
    WikiDeleteResponse,
    WikiIndexSyncRequest,
    WikiIndexSyncResponse,
    WikiSearchHit,
    WikiSearchRequest,
    WikiSearchResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["wiki"])


def _page_text(page: Any) -> str:
    """把页面标题 + 摘要 + 要点拼成向量化文本（正文不参与，避免主题页过长）。"""
    parts: List[str] = []
    if page.title:
        parts.append(page.title.strip())
    if page.summary:
        parts.append(page.summary.strip())
    points = [str(item).strip() for item in (page.key_points or []) if str(item).strip()]
    if points:
        parts.append("要点：" + "；".join(points))
    return "\n".join(parts).strip()


@router.post(
    "/v1/wiki/index/sync",
    response_model=WikiIndexSyncResponse,
    summary="写入 Wiki 页面摘要向量",
    description=(
        "对页面标题 + 摘要 + 要点做向量化并 upsert 到 `wiki_pages`。"
        "**只送摘要不送正文**：正文唯一来源是 MySQL wiki_page，避免双写不一致。"
        "Qdrant 不可用时返回 503，Java 侧仅记录失败，Wiki 仍可凭 ngram 字面命中。"
    ),
)
def wiki_index_sync(request: WikiIndexSyncRequest) -> WikiIndexSyncResponse:
    settings = get_settings()
    started = time.perf_counter()
    usable = [page for page in request.pages if _page_text(page)]
    skipped = len(request.pages) - len(usable)
    if not usable:
        return WikiIndexSyncResponse(synced=0, skipped=skipped, failed=0,
                                     took_ms=int((time.perf_counter() - started) * 1000))

    vectors = get_embedding_client().encode(
        [_page_text(page) for page in usable], is_query=False
    )
    payloads = [
        {
            "page_id": int(page.page_id),
            "title": page.title,
            "subject_id": page.subject_id,
            "knowledge_id": page.knowledge_id,
            "version": page.version,
        }
        for page in usable
    ]
    written = get_qdrant_client().upsert_wiki_pages(
        settings.wiki.collection, payloads, vectors, settings.embedding.dim
    )
    failed = len(usable) - written
    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info("[WikiIndex] synced=%d skipped=%d failed=%d took_ms=%d",
                written, skipped, failed, took_ms)
    return WikiIndexSyncResponse(synced=written, skipped=skipped, failed=failed,
                                 took_ms=took_ms)


@router.post(
    "/v1/wiki/index/delete",
    response_model=WikiDeleteResponse,
    summary="删除 Wiki 页面向量（页面下架）",
)
def wiki_index_delete(request: WikiDeleteRequest) -> WikiDeleteResponse:
    settings = get_settings()
    deleted = get_qdrant_client().delete_wiki_pages(
        settings.wiki.collection, request.page_ids
    )
    return WikiDeleteResponse(deleted=deleted, requested=len(request.page_ids))


@router.post(
    "/v1/wiki/search",
    response_model=WikiSearchResponse,
    summary="Wiki 热层语义检索",
    description=(
        "对问题做向量化后在 `wiki_pages` 中检索页面摘要。"
        "**无命中返回 200 + 空 hits**；依赖不可用返回 503，Java 侧自动降级到 MySQL ngram。"
    ),
)
def wiki_search(request: WikiSearchRequest) -> WikiSearchResponse:
    settings = get_settings()
    started = time.perf_counter()
    query_vector = get_embedding_client().encode_query(request.query)

    filters: Dict[str, Any] = {}
    subject_id = request.filters.get("subject_id") if request.filters else None
    if subject_id is not None:
        filters["must"] = [{"key": "subject_id", "match": {"value": int(subject_id)}}]
    raw_hits = get_qdrant_client().search_wiki(
        settings.wiki.collection,
        query_vector,
        top_k=request.top_k,
        query_filter=filters or None,
    )
    hits = [
        WikiSearchHit(page_id=hit["page_id"], score=hit["score"], payload=hit["payload"])
        for hit in raw_hits
    ]
    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info("[WikiSearch] top_k=%d hits=%d took_ms=%d",
                request.top_k, len(hits), took_ms)
    return WikiSearchResponse(hits=hits, took_ms=took_ms, dim=len(query_vector))
