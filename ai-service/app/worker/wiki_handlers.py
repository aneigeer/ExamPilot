"""WIKI_DISTILL 处理器：Java 入队 → Python 拉取聚类 → LLM 蒸馏 → 回传草稿。"""

from __future__ import annotations

import logging
from typing import Any, Dict, List

from ..clients.java_bridge import JavaBridgeError
from ..config import get_settings
from ..wiki.distiller import distill_cluster
from .handlers import ProgressReporter, register

logger = logging.getLogger(__name__)


@register("WIKI_DISTILL")
async def handle_wiki_distill(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """蒸馏一个批次的知识点主题页。

    **只写草稿**：每页回传 Java 后落在 `wiki_page_revision(PENDING)`，
    管理端人工审核通过才会进入 `wiki_page`。
    """
    settings = get_settings()
    scope = str(payload.get("scope") or "ALL").upper()
    subject_id = payload.get("subjectId")
    try:
        limit = int(payload.get("limit") or 10)
    except (TypeError, ValueError):
        limit = 10
    try:
        max_chunks = int(payload.get("maxChunks") or 6)
    except (TypeError, ValueError):
        max_chunks = 6
    limit = max(1, min(limit, settings.wiki.distill_max_pages))
    max_chunks = max(2, min(max_chunks, 12))

    logger.info("[WikiDistill] 任务 #%s 开始：scope=%s subject=%s limit=%s chunks=%s",
                job_id, scope, subject_id, limit, max_chunks)
    await progress(1)

    data = await bridge.wiki_input(scope, subject_id, limit, max_chunks)
    clusters: List[Dict[str, Any]] = list(data.get("clusters") or [])
    catalog: List[Dict[str, Any]] = list(data.get("catalog") or [])
    if not clusters:
        logger.info("[WikiDistill] 任务 #%s 没有可蒸馏主题（知识库暂无知识点聚类）", job_id)
        return {"total": 0, "drafted": 0, "failed": 0, "fallback": 0, "items": []}

    drafted = 0
    failed = 0
    fallback = 0
    items: List[Dict[str, Any]] = []
    total = len(clusters)

    for index, cluster in enumerate(clusters, start=1):
        draft, used_fallback = await distill_cluster(cluster, catalog, settings)
        fallback += 1 if used_fallback else 0
        draft.update({
            "slug": cluster.get("slug"),
            "subject_id": cluster.get("subjectId"),
            "knowledge_id": cluster.get("knowledgeId"),
            "task_id": job_id,
        })
        item = {
            "slug": cluster.get("slug"),
            "title": draft.get("title"),
            "fallback": used_fallback,
            "quality": draft.get("quality_score"),
            "posted": False,
        }
        try:
            result = await bridge.post_wiki_revision(draft)
            drafted += 1
            item["posted"] = True
            item["revisionId"] = result.get("revisionId")
        except JavaBridgeError as exc:
            failed += 1
            item["error"] = str(exc)[:200]
            logger.warning("[WikiDistill] 草稿回传失败 %s：%s", cluster.get("slug"), exc)
        items.append(item)
        await progress(max(1, int(index / total * 99)))

    logger.info("[WikiDistill] 任务 #%s 完成：total=%d drafted=%d failed=%d fallback=%d",
                job_id, total, drafted, failed, fallback)
    return {
        "total": total,
        "drafted": drafted,
        "failed": failed,
        "fallback": fallback,
        "items": items,
    }
