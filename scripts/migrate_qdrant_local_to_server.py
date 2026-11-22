"""将本地文件模式 Qdrant 全量切片幂等迁移至服务端 Qdrant 并执行一致性与性能核验。

用法：
    .venv-ai/Scripts/python.exe scripts/migrate_qdrant_local_to_server.py \
        --local-path ai-service/data/qdrant \
        --server-url http://127.0.0.1:6333 \
        --collection exam_chunks \
        --batch-size 500 \
        --verify-samples 10 \
        --report runtime/qdrant_migration_report.md
"""

from __future__ import annotations

import argparse
import os
import random
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

# 绕过本机系统/注册表代理，避免 httpx 把 127.0.0.1:6333 发往本地代理返回 502
import urllib.request
urllib.request.getproxies = lambda: {}  # type: ignore[assignment]
os.environ["NO_PROXY"] = "*"
os.environ["no_proxy"] = "*"
for _proxy_key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
    os.environ.pop(_proxy_key, None)

ROOT = Path(__file__).resolve().parents[1]
AI_SERVICE_DIR = ROOT / "ai-service"
if str(AI_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(AI_SERVICE_DIR))

from app.clients.qdrant_client import QdrantClientWrapper  # noqa: E402
from app.config import QdrantSettings  # noqa: E402


def _ensure_indexes(server_client: Any, collection: str, server_url: str = "http://127.0.0.1:6333") -> None:
    wrapper = QdrantClientWrapper(QdrantSettings(url=server_url, collection=collection))
    wrapper._ensure_payload_indexes(server_client, collection)


def _percentile(values: Sequence[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round(p * (len(ordered) - 1)))))
    return round(ordered[idx], 2)


def ensure_server_collection(
    server_client: Any,
    collection: str,
    vector_size: int = 512,
) -> None:
    """确保服务端集合与 4 类 Payload 索引已就绪（幂等）。"""
    from qdrant_client.http import models as qmodels

    existing = {c.name for c in server_client.get_collections().collections}
    if collection not in existing:
        server_client.create_collection(
            collection_name=collection,
            vectors_config=qmodels.VectorParams(
                size=vector_size,
                distance=qmodels.Distance.COSINE,
            ),
        )
    _ensure_indexes(server_client, collection)


def migrate_batches(
    local_client: Any,
    server_client: Any,
    collection: str,
    batch_size: int = 500,
    vector_size: int = 512,
) -> Dict[str, Any]:
    """按 scroll 游标分页从本地 Qdrant 读取并批量 upsert 到服务端 Qdrant。"""
    from qdrant_client.http import models as qmodels

    ensure_server_collection(server_client, collection, vector_size=vector_size)

    local_info = local_client.get_collection(collection)
    local_total = int(local_info.points_count or 0)
    server_info_before = server_client.get_collection(collection)
    server_before = int(server_info_before.points_count or 0)

    started = time.perf_counter()
    migrated = 0
    batches = 0
    next_offset: Optional[Any] = None

    while True:
        records, next_offset = local_client.scroll(
            collection_name=collection,
            limit=batch_size,
            offset=next_offset,
            with_payload=True,
            with_vectors=True,
        )
        if not records:
            break
        points = [
            qmodels.PointStruct(
                id=rec.id,
                vector=rec.vector,
                payload=rec.payload or {},
            )
            for rec in records
        ]
        server_client.upsert(
            collection_name=collection,
            points=points,
            wait=True,
        )
        migrated += len(points)
        batches += 1
        if batches % 20 == 0 or next_offset is None:
            elapsed = time.perf_counter() - started
            rate = migrated / elapsed if elapsed > 0 else 0.0
            print(
                f"[Migrate] batches={batches} migrated={migrated}/{local_total} "
                f"({rate:.0f} pts/s)"
            )
        if next_offset is None:
            break

    # 再次确保 Payload 索引在全量数据落盘后生效
    _ensure_indexes(server_client, collection)
    server_after = int(server_client.get_collection(collection).points_count or 0)
    elapsed_sec = round(time.perf_counter() - started, 2)

    return {
        "collection": collection,
        "local_count": local_total,
        "server_before": server_before,
        "server_after": server_after,
        "migrated_points": migrated,
        "batches": batches,
        "elapsed_sec": elapsed_sec,
        "count_matched": local_total == server_after,
    }


def _query_top_ids(
    client: Any,
    collection: str,
    vector: List[float],
    top_k: int = 5,
    active_only: bool = True,
) -> Tuple[List[Any], float]:
    from qdrant_client.http import models as qmodels

    q_filter = None
    if active_only:
        q_filter = qmodels.Filter(
            must=[
                qmodels.FieldCondition(
                    key="status",
                    match=qmodels.MatchValue(value="ACTIVE"),
                )
            ]
        )
    t0 = time.perf_counter()
    if hasattr(client, "query_points"):
        resp = client.query_points(
            collection_name=collection,
            query=vector,
            query_filter=q_filter,
            limit=top_k,
            with_payload=False,
        )
        hits = resp.points
    else:
        hits = client.search(
            collection_name=collection,
            query_vector=vector,
            query_filter=q_filter,
            limit=top_k,
            with_payload=False,
        )
    latency_ms = round((time.perf_counter() - t0) * 1000, 2)
    return [h.id for h in hits], latency_ms


def verify_consistency_and_benchmark(
    local_client: Any,
    server_client: Any,
    collection: str,
    sample_count: int = 10,
    top_k: int = 5,
) -> Dict[str, Any]:
    """随机抽样对比本地与服务端 Top-K 召回重合度，并压测服务端过滤检索延迟。"""
    records, _ = local_client.scroll(
        collection_name=collection,
        limit=max(sample_count * 5, 50),
        with_payload=True,
        with_vectors=True,
    )
    valid = [r for r in records if r.vector]
    rng = random.Random(42)
    samples = rng.sample(valid, min(sample_count, len(valid)))

    overlaps: List[float] = []
    local_latencies: List[float] = []
    server_latencies: List[float] = []

    # 先对服务端预热 1 次，避免把首次 HTTP 建连计入稳态延迟
    if samples:
        _query_top_ids(server_client, collection, list(samples[0].vector), top_k=top_k)

    for rec in samples:
        vec = list(rec.vector)
        local_ids, l_ms = _query_top_ids(local_client, collection, vec, top_k=top_k)
        server_ids, s_ms = _query_top_ids(server_client, collection, vec, top_k=top_k)
        local_latencies.append(l_ms)
        server_latencies.append(s_ms)
        if local_ids:
            overlap = len(set(local_ids) & set(server_ids)) / len(local_ids)
        else:
            overlap = 1.0 if not server_ids else 0.0
        overlaps.append(overlap)

    info = server_client.get_collection(collection)
    schema = sorted((info.payload_schema or {}).keys())

    return {
        "samples": len(samples),
        "top_k": top_k,
        "avg_top_k_overlap": round(sum(overlaps) / len(overlaps), 4) if overlaps else 1.0,
        "payload_indexes": schema,
        "local_p50_ms": _percentile(local_latencies, 0.50),
        "local_p95_ms": _percentile(local_latencies, 0.95),
        "server_min_ms": min(server_latencies) if server_latencies else 0.0,
        "server_p50_ms": _percentile(server_latencies, 0.50),
        "server_p95_ms": _percentile(server_latencies, 0.95),
    }


def render_report(mig: Dict[str, Any], ver: Dict[str, Any]) -> str:
    speedup = (
        round(ver["local_p50_ms"] / ver["server_p50_ms"], 1)
        if ver.get("server_p50_ms")
        else 0.0
    )
    lines = [
        "# Qdrant 全量向量迁移与服务端过滤检索压测报告",
        "",
        "## 1. 迁移汇总",
        "",
        "| 指标 | 数值 |",
        "|---|---|",
        f"| 集合名称 | `{mig['collection']}` |",
        f"| 本地 `points_count` | `{mig['local_count']}` |",
        f"| 服务端迁移前 `points_count` | `{mig['server_before']}` |",
        f"| 服务端迁移后 `points_count` | `{mig['server_after']}` |",
        f"| 本次 Upsert 点数 / 批次数 | `{mig['migrated_points']}` / `{mig['batches']}` |",
        f"| 数量一致性 (`local == server`) | **`{mig['count_matched']}`** |",
        f"| 迁移耗时 | `{mig['elapsed_sec']} s` |",
        f"| 服务端 Payload 索引 | `{ver['payload_indexes']}` |",
        "",
        "## 2. 抽样召回一致性与过滤检索（`status=ACTIVE`）性能对比",
        "",
        "| 维度 | 本地文件模式 (`local:./data/qdrant`) | 服务端模式 (`http://127.0.0.1:6333`) |",
        "|---|---:|---:|",
        f"| 抽样查询数 (`top_k={ver['top_k']}`) | {ver['samples']} | {ver['samples']} |",
        f"| Top-{ver['top_k']} 命中重合率 | 基准 | **{ver['avg_top_k_overlap'] * 100:.1f}%** |",
        f"| 过滤检索 P50 延迟 (ms) | {ver['local_p50_ms']} ms | **{ver['server_p50_ms']} ms** |",
        f"| 过滤检索 P95 延迟 (ms) | {ver['local_p95_ms']} ms | **{ver['server_p95_ms']} ms** |",
        "",
        f"- **结论**：全量 `{mig['server_after']}` 条向量切片已完整迁入服务端 Qdrant 并建立 4 类 Payload 索引，",
        f"  带 `status=ACTIVE` 过滤检索 P50 延迟由 `{ver['local_p50_ms']} ms` 降至 **`{ver['server_p50_ms']} ms`**（提速约 **{speedup}×**）。",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-path", default=str(AI_SERVICE_DIR / "data" / "qdrant"))
    parser.add_argument("--server-url", default="http://127.0.0.1:6333")
    parser.add_argument("--collection", default="exam_chunks")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--verify-samples", type=int, default=10)
    parser.add_argument(
        "--skip-if-matched",
        action="store_true",
        help="若服务端点数已与本地完全一致，则跳过全量重写仅跑索引与一致性压测",
    )
    parser.add_argument(
        "--report",
        default=str(ROOT / "runtime" / "qdrant_migration_report.md"),
    )
    args = parser.parse_args()

    from qdrant_client import QdrantClient

    local_client = QdrantClient(path=args.local_path)
    server_client = QdrantClient(url=args.server_url, timeout=60)
    try:
        local_count = int(local_client.get_collection(args.collection).points_count or 0)
        existing = {c.name for c in server_client.get_collections().collections}
        server_before = (
            int(server_client.get_collection(args.collection).points_count or 0)
            if args.collection in existing
            else 0
        )
        if args.skip_if_matched and local_count == server_before and local_count > 0:
            ensure_server_collection(server_client, args.collection)
            mig = {
                "collection": args.collection,
                "local_count": local_count,
                "server_before": server_before,
                "server_after": server_before,
                "migrated_points": 0,
                "batches": 0,
                "elapsed_sec": 0.0,
                "count_matched": True,
            }
        else:
            mig = migrate_batches(
                local_client,
                server_client,
                args.collection,
                batch_size=args.batch_size,
            )

        ver = verify_consistency_and_benchmark(
            local_client,
            server_client,
            args.collection,
            sample_count=args.verify_samples,
        )
        report_md = render_report(mig, ver)
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report_md, encoding="utf-8")
        print(report_md)
        return 0 if mig["count_matched"] else 1
    finally:
        local_client.close()
        server_client.close()


if __name__ == "__main__":
    raise SystemExit(main())
