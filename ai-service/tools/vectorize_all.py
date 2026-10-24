"""批量向量化：把 MySQL ai_chunk 中未同步的块推送到 Qdrant。

为什么需要这个脚本
------------------
Java 侧 ``POST /api/admin/ai/reindex`` 只遍历 QUESTION / RESOURCE / KNOWLEDGE
三类来源，**不覆盖 COURSEWARE**（课件由灌库脚本直接写入 ai_chunk）。
课件块必须先手工推送向量，否则向量通道检索不到它们。

流程
----
1. 捞 ``vector_synced <> 1 AND status='ACTIVE'`` 的块（每批 500，Python 侧上限）
2. 调 ``POST /v1/index/sync`` 写入 Qdrant
3. 成功后回写 MySQL ``vector_synced=1, vector_synced_at=NOW()``

用法
----
    python ai-service/tools/vectorize_all.py            # 全量补齐
    python ai-service/tools/vectorize_all.py --limit 500  # 只处理 500 块（冒烟）
    python ai-service/tools/vectorize_all.py --dry-run     # 只报告待处理数量
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from typing import Any

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password="123456",
          database="exam_prep", charset="utf8mb4")
API = "http://127.0.0.1:8000"
BATCH = 500

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(path: str, body: dict[str, Any]) -> dict[str, Any]:
    """调 AI 服务（绕过系统代理）。"""
    req = urllib.request.Request(
        API + path, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(_opener.open(req, timeout=600).read())


def main() -> int:
    ap = argparse.ArgumentParser(description="批量向量化 ai_chunk")
    ap.add_argument("--limit", type=int, default=0, help="最多处理多少块（0=全部）")
    ap.add_argument("--dry-run", action="store_true", help="只统计不写入")
    args = ap.parse_args()

    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM ai_chunk "
                        "WHERE status='ACTIVE' AND (vector_synced IS NULL OR vector_synced<>1)")
            pending = cur.fetchone()[0]
    finally:
        conn.commit()
        conn.close()

    print(f"待向量化块数：{pending}")
    if args.dry_run or pending == 0:
        conn.close()
        return 0

    total = args.limit if args.limit else pending
    done = synced_total = failed_total = 0
    started = time.time()

    while done < total:
        size = min(BATCH, total - done)
        conn = pymysql.connect(**DB)
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, content, source_type, source_id, title, subject_id, "
                    "       knowledge_id, difficulty, ref_type, ref_id, page_no, status, content_hash "
                    "FROM ai_chunk WHERE status='ACTIVE' AND (vector_synced IS NULL OR vector_synced<>1) "
                    "ORDER BY id LIMIT %s", (size,))
                rows = cur.fetchall()

            if not rows:
                conn.close()
                break

            chunks = [{
                "chunk_id": r[0], "content": r[1], "source_type": r[2],
                "source_id": r[3], "title": r[4], "subject_id": r[5],
                "knowledge_id": r[6], "difficulty": r[7], "ref_type": r[8],
                "ref_id": r[9], "page_no": r[10], "status": r[11],
                "content_hash": r[12],
            } for r in rows]

            try:
                resp = call("/v1/index/sync", {"chunks": chunks})
                ok = resp.get("synced", 0)
                bad = resp.get("failed", 0)
                ids = [c["chunk_id"] for c in chunks]
                with conn.cursor() as cur:
                    fmt = ",".join(["%s"] * len(ids))
                    cur.execute(
                        f"UPDATE ai_chunk SET vector_synced=1, vector_synced_at=NOW() "
                        f"WHERE id IN ({fmt})", ids)
                conn.commit()
                synced_total += ok
                failed_total += bad
                print(f"  批次 {done + 1}-{done + len(chunks)}：synced={ok} failed={bad} "
                      f"耗时={resp.get('took_ms')}ms")
            except (urllib.error.URLError, json.JSONDecodeError, KeyError) as exc:
                failed_total += len(chunks)
                print(f"  批次 {done + 1}-{done + len(chunks)} 失败：{str(exc)[:120]}")
                conn.rollback()
                # 连续失败没有意义，直接退出，保留未同步状态待重试
                if failed_total >= BATCH * 2:
                    print("  连续失败过多，停止。请检查 AI 服务与 Qdrant 是否可用。")
                    break
            finally:
                done += len(chunks)
        finally:
            try:
                conn.close()
            except Exception:
                pass

    cost = time.time() - started
    print(f"\n完成：处理 {done} 块，成功 {synced_total}，失败 {failed_total}，耗时 {cost:.1f}s")
    print(f"平均 {cost / max(done, 1) * 1000:.0f} ms/块")
    return 0


if __name__ == "__main__":
    sys.exit(main())
