"""来源在架校验（v4 整改 R04）。

## 问题背景

Wiki 热层返回的是**预编译页**（``wiki_page.content``），它由若干 ``ai_chunk`` 蒸馏而来，
来源块 id 存在 ``wiki_page.source_chunk_ids``（JSON 数组文本）。

但来源是会变的：课件/题目被管理员**下架**（``ai_chunk.status != 'ACTIVE'``）后，
引用它的 wiki 页面并不会自动失效——热层照样把"已下架内容蒸馏出的答案"返回给学生，
这就是外部评审指出的 R04 漏洞。

**payload 里的 status 是写入时快照，过滤救不了它**（向量点不随 MySQL 状态更新），
所以读侧必须在命中热层后**回 MySQL 实时校验**；写侧（蒸馏）也要在输入端剔除
已下架的块，避免草稿一出生就引用失效来源。

## 两侧语义（刻意不同，勿"统一"）

* **读侧（ask 热层）**：校验失败 = **fail-closed**（宁可降级 RAG 冷层，不放行未验证内容）。
  理由：热层本身就是可选项，MySQL 挂了 RAG 冷层也取不到正文，此时返回什么都不可信。
* **写侧（蒸馏 worker）**：校验失败 = **fail-open**（按原样蒸馏并记日志）。
  理由：蒸馏输入本就来自 Java（它已过滤过一次），Python 侧校验只是第二道保险；
  因保险自身故障而中断整批蒸馏任务，代价大于收益。Java 的
  ``WikiService`` 还有"source_chunk_ids 全部无效则拒绝"的最终拦截。

## 判定规则

* ``source_chunk_ids`` 为空 / 无法解析 → 视为**无来源可校验，放行**
  （管理端手建页没有来源块，不能因校验逻辑误伤）；
* 来源块**全部**仍 ACTIVE → 放行；
* **任一**来源块已非 ACTIVE → 拦截。页面内容是跨块综合的，无法区分哪段话
  来自哪个块，部分失效时只能整页不放行（宁可降级 RAG，也不冒险）。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Sequence, Set, Tuple

logger = logging.getLogger(__name__)

#: 单次 IN 查询的最大 id 数（MySQL 对 IN 列表长度没有硬限制，但分批更稳）
_QUERY_BATCH = 500

#: 蒸馏簇剔除后允许的最少块数：低于该值不足以支撑一页，跳过蒸馏
MIN_CLUSTER_CHUNKS = 2


def parse_chunk_ids(raw: Any) -> List[int]:
    """把 ``source_chunk_ids`` 字段解析为去重后的 id 列表。

    兼容三种形态：JSON 数组文本（MySQL 存储格式）、list、单个整数。
    解析不了返回空列表（调用方按"无来源"处理，不抛异常）。

    Args:
        raw: 数据库里的原始值。

    Returns:
        去重、保序的正整数 id 列表。
    """
    if raw is None:
        return []
    if isinstance(raw, (list, tuple)):
        candidates: List[Any] = list(raw)
    elif isinstance(raw, int):
        candidates = [raw]
    else:
        try:
            decoded = json.loads(str(raw))
        except (ValueError, TypeError):
            return []
        if not isinstance(decoded, (list, tuple)):
            return []
        candidates = list(decoded)
    ids: List[int] = []
    for value in candidates:
        try:
            chunk_id = int(value)
        except (TypeError, ValueError):
            continue
        if chunk_id > 0 and chunk_id not in ids:
            ids.append(chunk_id)
    return ids


def filter_active_chunk_ids(chunk_ids: Sequence[int]) -> Tuple[Set[int], bool]:
    """查询 MySQL，返回**仍为 ACTIVE** 的 chunk id 集合。

    Args:
        chunk_ids: 待校验的 ai_chunk 主键。

    Returns:
        ``(active_ids, checked)``：
        * ``checked=True`` —— 校验已执行，``active_ids`` 可信；
        * ``checked=False`` —— 校验本身失败（未装 pymysql / DB 不可达 / 口令未配），
          ``active_ids`` 为空集，**调用方必须按自己的 fail-open/fail-closed 语义处理**。
    """
    ids = [int(i) for i in chunk_ids if int(i) > 0]
    if not ids:
        return set(), True
    try:
        import pymysql  # 局部导入：不用 MySQL 的部署不装它
        from ..db import db_config
    except Exception as exc:  # noqa: BLE001 —— 依赖缺失按"校验失败"处理
        logger.warning("[SourceGuard] 来源校验依赖不可用：%s", type(exc).__name__)
        return set(), False
    active: Set[int] = set()
    try:
        conn = pymysql.connect(**db_config())
        try:
            with conn.cursor() as cur:
                for start in range(0, len(ids), _QUERY_BATCH):
                    batch = ids[start:start + _QUERY_BATCH]
                    fmt = ",".join(["%s"] * len(batch))
                    cur.execute(
                        f"SELECT id FROM ai_chunk WHERE id IN ({fmt}) AND status='ACTIVE'",
                        batch,
                    )
                    active.update(int(row[0]) for row in cur.fetchall())
        finally:
            conn.close()
    except (Exception, SystemExit) as exc:  # noqa: BLE001 —— DB 任何异常都不能向上抛（热层/worker 都要自兜）
        # SystemExit 也要接：db_config() 在口令未配时抛 SystemExit（带操作指引），
        # 若放它会直接击穿热层的「任何异常都只是降级」约定
        logger.warning("[SourceGuard] 来源在架校验失败（DB 不可用）：%s", str(exc)[:120])
        return set(), False
    return active, True



def page_sources_active(raw_source_ids: Any) -> Tuple[bool, str]:
    """热层读侧判定：wiki 页的来源是否全部在架（fail-closed）。

    Args:
        raw_source_ids: ``wiki_page.source_chunk_ids`` 原始值（JSON 文本）。

    Returns:
        ``(allow, reason)``：``allow=True`` 放行热层；``False`` 时 ``reason``
        为机器可读原因（``SOURCE_CHECK_UNAVAILABLE`` / ``SOURCE_DELISTED``）。
    """
    ids = parse_chunk_ids(raw_source_ids)
    if not ids:
        # 无来源可校验（管理端手建页）：不拦截，避免校验逻辑误伤合法页面
        return True, ""
    active, checked = filter_active_chunk_ids(ids)
    if not checked:
        return False, "SOURCE_CHECK_UNAVAILABLE"
    if any(chunk_id not in active for chunk_id in ids):
        return False, "SOURCE_DELISTED"
    return True, ""


def prune_clusters(
    clusters: List[Dict[str, Any]],
    *,
    content_key: str = "chunks",
    id_key: str = "chunkId",
) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    """蒸馏写侧判定：把不再 ACTIVE 的块从蒸馏输入里剔除（fail-open）。

    Java 回传的每个主题簇带若干 ``chunk``；本函数先一次性查出全部 id 的在架状态，
    再逐簇剔除失效块；剔除后块数不足 :data:`MIN_CLUSTER_CHUNKS` 的整簇跳过
    （块太少蒸不出合格页面，产出只会是低质稿）。

    Args:
        clusters: ``bridge.wiki_input`` 返回的主题簇列表。
        content_key: 簇里装块列表的字段名。
        id_key: 块里 id 的字段名。

    Returns:
        ``(pruned_clusters, stats)``，``stats`` 含：
        ``checked``（送检 id 数）、``removed``（剔除块数）、
        ``skipped_clusters``（整簇跳过数）、``check_failed``（0/1，校验失败时为 1，
        此时簇**原样返回**——写侧 fail-open）。
    """
    stats: Dict[str, int] = {"checked": 0, "removed": 0, "skipped_clusters": 0, "check_failed": 0}
    all_ids: List[int] = []
    for cluster in clusters or []:
        for chunk in cluster.get(content_key) or []:
            value = chunk.get(id_key)
            try:
                chunk_id = int(value)  # type: ignore[arg-type]
            except (TypeError, ValueError):
                continue
            if chunk_id > 0:
                all_ids.append(chunk_id)
    if not all_ids:
        return list(clusters or []), stats
    stats["checked"] = len(all_ids)
    active, checked = filter_active_chunk_ids(all_ids)
    if not checked:
        stats["check_failed"] = 1
        logger.warning(
            "[SourceGuard] 蒸馏输入来源校验失败（DB 不可用），本批按原样蒸馏；"
            "Java 侧 WikiService 仍有「来源全部无效则拒绝」的兜底"
        )
        return list(clusters), stats

    pruned: List[Dict[str, Any]] = []
    for cluster in clusters:
        kept: List[Dict[str, Any]] = []
        for chunk in cluster.get(content_key) or []:
            try:
                chunk_id = int(chunk.get(id_key))  # type: ignore[arg-type]
            except (TypeError, ValueError):
                kept.append(chunk)  # 无 id 的块不归本函数管，原样保留
                continue
            if chunk_id in active:
                kept.append(chunk)
            else:
                stats["removed"] += 1
        if len(kept) < MIN_CLUSTER_CHUNKS:
            stats["skipped_clusters"] += 1
            logger.info(
                "[SourceGuard] 主题簇 %s 在架块不足 %d 个（剩 %d），跳过蒸馏",
                cluster.get("slug"), MIN_CLUSTER_CHUNKS, len(kept),
            )
            continue
        cluster[content_key] = kept
        pruned.append(cluster)
    return pruned, stats
