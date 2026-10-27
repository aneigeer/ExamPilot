"""Wiki 离线批量蒸馏（v5 任务1：把 wiki_page 从 2 页扩到 30-50 页）。

## 与在线管线的关系

复用**同一套蒸馏内核**（``app/wiki/distiller.py``：JSON 校验 / 引用校验 /
接地校验 / 抽取式兜底）与**写侧来源校验**（``app/wiki/source_guard.py``，
R04 上轮整改产物）。区别只在取数与落库方式：

* 在线：Java 聚类 → Redis Stream → worker → 回传草稿（PENDING）→ 人工审核
* 离线（本脚本）：直接按 courseware 文档聚类 → 本地 Ollama 蒸馏 →
  **直接写 wiki_page(PUBLISHED)** → 经运行中的 AI 服务推摘要向量

为什么直接 PUBLISHED：离线批量的产物事后有人工抽查（team-lead 验收），
走 PENDING 会卡住"wiki_page 总数"验收项；slug 采用 ``doc-{courseware_id}}``，
重复执行 = 更新同一页（uk_wiki_slug 幂等），不会堆重复页。

## 聚类策略（数据驱动，不拍脑袋）

ai_chunk 里每份 COURSEWARE 文档本身就是一个主题（如「BERT相关面试题」
「第1章 进程与线程」），所以：
* 每份课件 = 一个候选主题页；块数太少（< MIN_DOC_CHUNKS）的不成页
* 单主题最多取 MAX_CLUSTER_CHUNKS 块（蒸馏 prompt 预算限制，与在线一致），
  按 chunk_no **均匀抽样**——首尾中都有，覆盖整份文档而不是只取开头
* 操作系统 6 章 PPT 每章一页（team-lead 点名主题都来自这里）

## 用法

    python ai-service/tools/distill_wiki_batch.py --dry-run          # 只看聚类计划
    python ai-service/tools/distill_wiki_batch.py                    # 全量蒸馏（默认 40 页）
    python ai-service/tools/distill_wiki_batch.py --limit 5          # 冒烟
    python ai-service/tools/distill_wiki_batch.py --only-subject 操作系统

前置：AI 服务已在 8000 端口运行（向量同步走 HTTP，Qdrant 本地模式单进程独占）；
Ollama qwen2.5-7b 在线。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import pymysql

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import get_settings  # noqa: E402
from app.db import db_config  # noqa: E402
from app.wiki.source_guard import prune_clusters  # noqa: E402
from app.wiki.distiller import distill_cluster  # noqa: E402

API = "http://127.0.0.1:8000"
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

#: 单主题最多送入蒸馏的块数（与在线 worker 的 maxChunks 上限一致）
MAX_CLUSTER_CHUNKS = 6
#: 文档块数少于该值不成主题页（内容太薄，蒸出的页面没有信息量）
MIN_DOC_CHUNKS = 3
#: 单批向量同步的页面数（/v1/wiki/index/sync 上限 200）
VECTOR_SYNC_BATCH = 200
#: 排除的文档：公司落地篇 787 块为花名册类噪声（灌库期已确认）
EXCLUDED_DOC_IDS = {20}


def call_api(path: str, body: Dict[str, Any], timeout: float = 120) -> Dict[str, Any]:
    """调 AI 服务 HTTP 接口（绕过系统代理）。"""
    req = urllib.request.Request(
        API + path, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(_opener.open(req, timeout=timeout).read())


def load_catalog(conn: Any) -> List[Dict[str, Any]]:
    """读已有 wiki 页做目录（蒸馏器用它约束 links.to_slug 不编造）。"""
    with conn.cursor() as cur:
        cur.execute("SELECT slug, title FROM wiki_page WHERE status='PUBLISHED'")
        return [{"slug": r[0], "title": r[1]} for r in cur.fetchall()]


def select_docs(conn: Any, limit: int, only_subject: str) -> List[Dict[str, Any]]:
    """选出候选主题文档：按块数降序， OS PPT 与普通课件一视同仁。"""
    sql = (
        "SELECT c.id, c.title, c.subject_id, s.name AS subject_name, "
        "       COUNT(ch.id) AS chunk_count "
        "FROM courseware c "
        "JOIN ai_chunk ch ON ch.source_type='COURSEWARE' AND ch.source_id=c.id "
        "                  AND ch.status='ACTIVE' "
        "LEFT JOIN subject s ON s.id=c.subject_id "
        "WHERE c.is_deleted=0 AND c.status='ACTIVE' "
        "GROUP BY c.id, c.title, c.subject_id, s.name "
        "HAVING COUNT(ch.id) >= %s"
    )
    params: List[Any] = [MIN_DOC_CHUNKS]
    if only_subject:
        sql += " AND s.name=%s"
        params.append(only_subject)
    sql += " ORDER BY chunk_count DESC"
    with conn.cursor() as cur:
        cur.execute(sql, params)
        rows = [
            {"doc_id": int(r[0]), "title": str(r[1]), "subject_id": r[2],
             "subject_name": r[3] or "", "chunk_count": int(r[4])}
            for r in cur.fetchall()
        ]
    return [r for r in rows if r["doc_id"] not in EXCLUDED_DOC_IDS][:limit]


def pick_chunks_evenly(rows: List[Tuple[int, str, str, Optional[int]]],
                       count: int) -> List[Tuple[int, str, str, Optional[int]]]:
    """从按 chunk_no 排好的块列表里均匀抽 count 块（保证覆盖首中尾）。"""
    if len(rows) <= count:
        return rows
    step = len(rows) / count
    return [rows[min(len(rows) - 1, int(i * step))] for i in range(count)]


def build_clusters(conn: Any, docs: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """把文档列表展开成蒸馏簇（每簇最多 MAX_CLUSTER_CHUNKS 块，均匀抽样）。"""
    clusters: List[Dict[str, Any]] = []
    for doc in docs:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, title, content, page_no FROM ai_chunk "
                "WHERE source_type='COURSEWARE' AND source_id=%s AND status='ACTIVE' "
                "ORDER BY chunk_no", (doc["doc_id"],))
            rows = cur.fetchall()
        picked = pick_chunks_evenly(rows, MAX_CLUSTER_CHUNKS)
        clusters.append({
            "slug": f"doc-{doc['doc_id']}",
            "title": doc["title"],
            "subjectId": doc["subject_id"],
            "knowledgeId": None,
            "chunks": [
                {"chunkId": int(r[0]), "sourceType": "COURSEWARE",
                 "title": str(r[1] or doc["title"]), "content": str(r[2] or "")}
                for r in picked
            ],
            "_meta": {"doc": doc, "total_chunks": len(rows)},
        })
    return clusters


def upsert_wiki_page(conn: Any, slug: str, draft: Dict[str, Any],
                     cluster: Dict[str, Any], used_fallback: bool) -> int:
    """把蒸馏稿写入 wiki_page（按 slug 幂等，直接 PUBLISHED）。"""
    meta = cluster["_meta"]["doc"]
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO wiki_page "
            " (slug, title, subject_id, knowledge_id, summary, content, key_points,"
            "  source_chunk_ids, status, version, model, quality_score, published_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,'PUBLISHED',1,%s,%s,NOW())"
            " ON DUPLICATE KEY UPDATE"
            "  title=VALUES(title), subject_id=VALUES(subject_id),"
            "  summary=VALUES(summary), content=VALUES(content),"
            "  key_points=VALUES(key_points), source_chunk_ids=VALUES(source_chunk_ids),"
            "  model=VALUES(model), quality_score=VALUES(quality_score),"
            "  published_at=NOW(), version=version+1",
            (slug, draft["title"], meta["subject_id"], None,
             draft["summary"], draft["content"], json.dumps(draft["key_points"], ensure_ascii=False),
             json.dumps(draft["source_chunk_ids"]), draft["model"], draft["quality_score"]))
        conn.commit()
        cur.execute("SELECT id FROM wiki_page WHERE slug=%s", (slug,))
        return int(cur.fetchone()[0])


def sync_vectors(pages: List[Dict[str, Any]]) -> Tuple[int, int]:
    """把页面摘要向量经运行中的 AI 服务推入 wiki_pages 集合（分批 200）。"""
    synced = failed = 0
    for start in range(0, len(pages), VECTOR_SYNC_BATCH):
        batch = pages[start:start + VECTOR_SYNC_BATCH]
        try:
            resp = call_api("/v1/wiki/index/sync", {"pages": batch})
            synced += int(resp.get("synced", 0))
            failed += int(resp.get("failed", 0))
            print(f"  向量同步 {start + 1}-{start + len(batch)}：synced={resp.get('synced')}"
                  f" failed={resp.get('failed')} skipped={resp.get('skipped')}")
        except (urllib.error.URLError, json.JSONDecodeError) as exc:
            failed += len(batch)
            print(f"  ⚠ 向量同步失败（{str(exc)[:80]}）——页面已入库，可重跑本脚本补推")
    return synced, failed


async def distill_all(clusters: List[Dict[str, Any]], catalog: List[Dict[str, Any]],
                      conn: Any) -> Tuple[int, int, int, List[Dict[str, Any]]]:
    """逐簇蒸馏 + 落库。返回 (published, fallback, skipped, 待同步向量页)。"""
    settings = get_settings()
    published = fallback = skipped = 0
    to_sync: List[Dict[str, Any]] = []
    total = len(clusters)
    for index, cluster in enumerate(clusters, start=1):
        slug = str(cluster["slug"])
        started = time.time()
        # R04 写侧保险：剔除已下架来源块（不足 2 块的簇直接跳过）
        pruned, stats = prune_clusters([cluster])
        if not pruned:
            print(f"  [{index}/{total}] {slug} 来源全部失效，跳过")
            skipped += 1
            continue
        cluster = pruned[0]
        try:
            draft, used_fallback = await distill_cluster(cluster, catalog, settings)
        except Exception as exc:  # noqa: BLE001 —— 单页失败不中断整批
            print(f"  [{index}/{total}] {slug} 蒸馏异常 {type(exc).__name__}，跳过")
            skipped += 1
            continue
        page_id = upsert_wiki_page(conn, slug, draft, cluster, used_fallback)
        catalog.append({"slug": slug, "title": draft["title"]})  # 后续页可 link 到它
        to_sync.append({
            "page_id": page_id, "title": draft["title"], "summary": draft["summary"],
            "key_points": draft["key_points"],
            "subject_id": cluster.get("subjectId"), "knowledge_id": None, "version": 1,
        })
        fallback += 1 if used_fallback else 0
        published += 1
        tag = "兜底" if used_fallback else "OK"
        print(f"  [{index}/{total}] {slug}《{draft['title']}》{tag} "
              f"quality={draft['quality_score']} blocks={len(cluster['chunks'])} "
              f"({time.time() - started:.1f}s)")
    return published, fallback, skipped, to_sync


def main() -> int:
    ap = argparse.ArgumentParser(description="Wiki 离线批量蒸馏（courseware → wiki_page）")
    ap.add_argument("--limit", type=int, default=40, help="最多蒸馏多少个主题（0=全部候选）")
    ap.add_argument("--only-subject", default="", help="只蒸某科目的课件")
    ap.add_argument("--dry-run", action="store_true", help="只列聚类计划，不调大模型不写库")
    ap.add_argument("--no-vector", action="store_true", help="只写库不推向量")
    args = ap.parse_args()

    conn = pymysql.connect(**db_config())
    try:
        docs = select_docs(conn, args.limit, args.only_subject)
        clusters = build_clusters(conn, docs)
        catalog = load_catalog(conn)
        print(f"候选主题 {len(clusters)} 个（目录现有 {len(catalog)} 页）"
              f"{'　[DRY-RUN]' if args.dry_run else ''}")
        for cluster in clusters:
            meta = cluster["_meta"]
            print(f"  {cluster['slug']}《{cluster['title']}》"
                  f"[{meta['doc']['subject_name']}] 全文档 {meta['total_chunks']} 块")
        if args.dry_run:
            print("★ DRY-RUN 未写库。")
            return 0

        published, fallback, skipped, to_sync = asyncio.run(
            distill_all(clusters, catalog, conn))
        print(f"\n蒸馏完成：published={published} fallback={fallback} skipped={skipped}")
    finally:
        conn.close()

    if args.no_vector:
        print("★ --no-vector：未推向量。热层要命中必须推 wiki_pages 向量。")
        return 0
    if to_sync:
        print("同步页面摘要向量…")
        synced, failed = sync_vectors(to_sync)
        print(f"向量同步完成：synced={synced} failed={failed}")
    print("验证方式：POST /v1/ask 问主题相关问题，看 final_strategy 是否 = WIKI_HOT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
