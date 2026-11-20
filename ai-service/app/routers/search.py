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
import re
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

#: 问句壳词：从 Query 里剥掉，只留实体词做字面匹配。
#: 原因：原先用「整句子串是否出现在标题里」判命中，像「什么是Claude Code」永远匹配不上
#: 「Claude Code 实战从入门到精通」这种标题，字面加分形同虚设。
_QUESTION_STOPWORDS = (
    "请问", "什么是", "是什么", "什么叫", "怎么", "怎样", "如何", "为什么",
    "有哪些", "哪些", "介绍一下", "解释一下", "说一下", "说明一下",
    "解释", "说明", "介绍", "一下",
)

#: 同一文档最多返回几块。
#: 原因：超长文档会切出上百块（如「Claude Code 实战从入门到精通」366 块），
#: 每块对任意查询都有中等相似度，会把 top-K 全部挤占，导致看起来"答非所问"。
_MAX_CHUNKS_PER_DOC = 2

#: 候选池倍数：先从 Qdrant 多取 candidates，再重排取 Top-K。
#: 原因：只取 top_k 条候选时，重排无法把排在第 20 名的正确内容提上来。
_CANDIDATE_MULTIPLIER = 8
_MAX_CANDIDATES = 100

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
    # ★ 候选池必须大于最终条数：只取 top_k 条时重排无法把后面的正确内容提上来。
    candidate_k = min(max(top_k * _CANDIDATE_MULTIPLIER, top_k), _MAX_CANDIDATES)
    query_filter = request.filters.to_qdrant_filter()
    raw_hits: List[Dict[str, Any]] = qdrant_client.search(
        query_vector, top_k=candidate_k, query_filter=query_filter
    )

    # ④ 轻量重排（不覆盖 score 字段语义，只在 payload 里补 rerank_score）
    ranked: List[Tuple[float, Dict[str, Any]]] = [
        (_rerank_score(request.query, hit["score"], hit["payload"]), hit) for hit in raw_hits
    ]
    ranked.sort(key=lambda item: item[0], reverse=True)

    hits: List[SearchHit] = []
    per_doc: Dict[Any, int] = {}
    for rerank_score, hit in ranked:
        if len(hits) >= top_k:
            break
        payload_src = hit["payload"]
        # 单文档限流：同一 source_id 最多取 _MAX_CHUNKS_PER_DOC 块，防止超长文档霸榜
        doc_key = (
            payload_src.get("source_id")
            or payload_src.get("ref_id")
            or hit["chunk_id"]
        )
        if per_doc.get(doc_key, 0) >= _MAX_CHUNKS_PER_DOC:
            continue
        per_doc[doc_key] = per_doc.get(doc_key, 0) + 1
        payload = dict(payload_src)
        payload["rerank_score"] = round(rerank_score, 6)
        hits.append(
            SearchHit(chunk_id=hit["chunk_id"], score=hit["score"], payload=payload)
        )

    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info(
        "[Search] top_k=%d cand=%d hits=%d took_ms=%d filter=%s",
        top_k, candidate_k, len(hits), took_ms, bool(query_filter),
    )
    return SearchResponse(hits=hits, took_ms=took_ms, dim=len(query_vector))


def _extract_terms(query: str) -> List[str]:
    """从问句里抽出用于字面匹配的实体词。

    剥掉「什么是 / 怎么 / 如何」这类问句壳，再按标点分词，只保留长度 >= 2 的词
    （单字噪声太大，容易把「的」「了」也算命中）。

    Args:
        query: 用户原始查询。

    Returns:
        实体词列表；无可用词时返回空列表。
    """
    text = (query or "").strip()
    for word in _QUESTION_STOPWORDS:
        text = text.replace(word, " ")
    parts = re.split(r"[\s,，。、；;：:！!？?（）()\[\]【】\"'“”‘’/]+", text)
    return [p for p in parts if len(p) >= 2]


def _rerank_score(query: str, vector_score: float, payload: Dict[str, Any]) -> float:
    """轻量重排打分（向量分 + 标题实体命中 + 热度 - 难度惩罚）。

    Args:
        query: 原始查询词。
        vector_score: Qdrant 余弦相似度。
        payload: 命中点 payload（不含正文，只能匹配标题）。

    Returns:
        重排分；不做归一化，仅用于相对排序。
    """
    score = vector_score * _WEIGHT_VECTOR

    # 标题实体命中：按抽出的实体词逐个匹配标题，命中越多加越多（封顶 2 倍权重）。
    # 原实现要求「整句出现在标题里」，对「什么是X」这类问句永远不触发，等于没有字面信号。
    title = str(payload.get("title") or "")
    terms = _extract_terms(query)
    if title and terms:
        hit_count = sum(1 for term in terms if term in title)
        if hit_count:
            score += min(_WEIGHT_TITLE_HIT * (1 + 0.5 * (hit_count - 1)),
                         _WEIGHT_TITLE_HIT * 2)

    # 热度加成（封顶，避免热门老题霸榜）
    hot = int(payload.get("hot_count") or 0)
    score += min(hot, 100) / 100.0 * _WEIGHT_HOT

    # 难度惩罚：过难的内容对多数用户不友好
    difficulty = payload.get("difficulty")
    if isinstance(difficulty, int) and difficulty >= 4:
        score -= _WEIGHT_DIFFICULTY_PENALTY
    return score
