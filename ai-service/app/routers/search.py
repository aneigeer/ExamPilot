"""``POST /v1/search`` —— 向量检索（+ 轻量重排）。

**降级语义**：
* Qdrant 不可达 / 集合不存在 / 维度不匹配 → 503（5203），Java 侧走 ngram。
* 模型不一致（换过 embedding 模型未重建）→ 503（5208），Java 侧触发 reindex。
* **检索无命中不是错误** → 返回 200 + ``{"hits": []}``，由 Java 侧拒答闸门决定兜底文案。

**重排说明**：本服务做的是**轻量十字特征重排**（向量分 + 标题命中 + 热度 + 难度衰减），
目的是把"向量语义相关"和"字面精确命中"的信号融合。
**Java 侧的五因子重排（``RetrievalService``）依然是最终权威**，
两路结果在 ``VectorChunkMerger`` 里融合去重 —— 本服务不重复实现 Java 的权重口径，
只提供一个不含业务偏好的 ``rerank_score`` 供参考。
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Tuple

from fastapi import APIRouter

from ..clients.embedding_client import get_embedding_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..schemas import SearchHit, SearchRequest, SearchResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["vector"])

#: 轻量重排权重（与 Java 五因子口径解耦，此处只做"向量分 + 字面 + 热度"的最小融合）
_WEIGHT_VECTOR = 1.0
_WEIGHT_TITLE_HIT = 0.15
_WEIGHT_HOT = 0.05
_WEIGHT_DIFFICULTY_PENALTY = 0.02

#: 最终返回条数上限（重排在进程内做，条数过多只会拖慢响应，且 Java 侧只用 Top-5）
_MAX_TOP_K = 50


@router.post(
    "/v1/search",
    response_model=SearchResponse,
    summary="向量检索（embedding + Qdrant + 轻量重排）",
    description=(
        "对 query 做向量化后在 Qdrant 中做 HNSW 检索，支持 payload 过滤"
        "（subject_id / knowledge_id / source_type / status）。"
        "**无命中返回 200 + 空 hits**；依赖不可用返回 503，Java 侧降级 ngram 全文检索。"
    ),
)
def search(request: SearchRequest) -> SearchResponse:
    """向量检索。

    Args:
        request: 查询请求。

    Returns:
        :class:`SearchResponse`，``hits`` 按 ``score`` 降序。

    Raises:
        app.errors.VectorSearchError: 向量库不可用（503）。
        app.errors.VectorIndexStaleError: 模型不一致需重建（503）。
        app.errors.EmbeddingError: 向量化失败（503）。
    """
    settings = get_settings()
    embedding_client = get_embedding_client()
    qdrant_client = get_qdrant_client()

    started = time.perf_counter()

    # ① 换模型未重建 → 直接 5208，避免用错模型给出垃圾结果
    qdrant_client.assert_model_matches(settings.embedding.model_id)

    # ② 查询向量化（自动加 bge 检索前缀）
    query_vector = embedding_client.encode_query(request.query)

    # ③ Qdrant 检索
    # ★ topK 必须是「最终返回条数」的硬上限：请求可能来自 topK 驼峰写法（
    #   SearchRequest 已做别名兼容），也可能被上层桩/实现多返回，这里统一收敛。
    top_k = min(max(1, request.top_k), _MAX_TOP_K)
    query_filter = request.filters.to_qdrant_filter()
    raw_hits: List[Dict[str, Any]] = qdrant_client.search(
        query_vector, top_k=top_k, query_filter=query_filter
    )

    # ④ 轻量重排（不覆盖 score 字段语义，只在 payload 里补 rerank_score）
    ranked: List[Tuple[float, Dict[str, Any]]] = [
        (_rerank_score(request.query, hit["score"], hit["payload"]), hit) for hit in raw_hits
    ]
    ranked.sort(key=lambda item: item[0], reverse=True)

    hits: List[SearchHit] = []
    for rerank_score, hit in ranked[:top_k]:
        payload = dict(hit["payload"])
        payload["rerank_score"] = round(rerank_score, 6)
        hits.append(
            SearchHit(chunk_id=hit["chunk_id"], score=hit["score"], payload=payload)
        )

    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info(
        "[Search] top_k=%d hits=%d took_ms=%d filter=%s",
        top_k, len(hits), took_ms, bool(query_filter),
    )
    return SearchResponse(hits=hits, took_ms=took_ms, dim=len(query_vector))


def _rerank_score(query: str, vector_score: float, payload: Dict[str, Any]) -> float:
    """轻量重排打分（向量分 + 标题字面命中 + 热度 - 难度惩罚）。

    Args:
        query: 原始查询词。
        vector_score: Qdrant 余弦相似度。
        payload: 命中点 payload。

    Returns:
        重排分；不做归一化，仅用于相对排序。
    """
    score = vector_score * _WEIGHT_VECTOR

    # 标题字面命中：专有名词（"C语言"、"printf"、"32位"）向量容易混入噪声，字面匹配一定准
    title = str(payload.get("title") or "")
    if title and query and query.strip() and query.strip() in title:
        score += _WEIGHT_TITLE_HIT

    # 热度加成（封顶，避免热门老题霸榜）
    hot = int(payload.get("hot_count") or 0)
    score += min(hot, 100) / 100.0 * _WEIGHT_HOT

    # 难度惩罚：过难的内容对多数用户不友好
    difficulty = payload.get("difficulty")
    if isinstance(difficulty, int) and difficulty >= 4:
        score -= _WEIGHT_DIFFICULTY_PENALTY
    return score
