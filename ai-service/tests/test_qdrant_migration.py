"""Qdrant 本地 -> 服务端迁移脚本单元测试。"""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.migrate_qdrant_local_to_server import (  # noqa: E402
    migrate_batches,
    render_report,
    verify_consistency_and_benchmark,
)


class _FakeQdrant:
    def __init__(self, points: Optional[List[Any]] = None) -> None:
        self.points: Dict[Any, Any] = {p.id: p for p in (points or [])}
        self.indexes: List[str] = []

    def get_collections(self) -> Any:
        return SimpleNamespace(collections=[SimpleNamespace(name="exam_chunks")])

    def create_collection(self, **kwargs: Any) -> None:
        pass

    def create_payload_index(self, collection_name: str, field_name: str, **kwargs: Any) -> None:
        if field_name not in self.indexes:
            self.indexes.append(field_name)

    def get_collection(self, collection_name: str) -> Any:
        return SimpleNamespace(
            points_count=len(self.points),
            payload_schema={k: {} for k in self.indexes},
        )

    def scroll(
        self,
        collection_name: str,
        limit: int = 10,
        offset: Optional[int] = None,
        with_payload: bool = True,
        with_vectors: bool = True,
    ) -> Tuple[List[Any], Optional[int]]:
        ordered = list(self.points.values())
        start = int(offset or 0)
        chunk = ordered[start : start + limit]
        next_off = start + limit if start + limit < len(ordered) else None
        return chunk, next_off

    def upsert(self, collection_name: str, points: List[Any], wait: bool = True) -> None:
        for p in points:
            self.points[p.id] = p

    def query_points(self, collection_name: str, query: List[float], limit: int = 5, **kwargs: Any) -> Any:
        items = list(self.points.values())[:limit]
        return SimpleNamespace(points=[SimpleNamespace(id=x.id) for x in items])


def test_migrate_batches_and_verify() -> None:
    src_points = [
        SimpleNamespace(
            id=i,
            vector=[0.1] * 8,
            payload={"status": "ACTIVE", "subject_id": 1, "source_type": "QUESTION"},
        )
        for i in range(1, 16)
    ]
    local = _FakeQdrant(src_points)
    server = _FakeQdrant()

    mig = migrate_batches(local, server, "exam_chunks", batch_size=6, vector_size=8)
    assert mig["local_count"] == 15
    assert mig["server_after"] == 15
    assert mig["count_matched"] is True
    assert mig["batches"] == 3
    assert set(server.indexes) == {"status", "source_type", "subject_id", "knowledge_id"}

    ver = verify_consistency_and_benchmark(local, server, "exam_chunks", sample_count=4, top_k=3)
    assert ver["samples"] == 4
    assert ver["avg_top_k_overlap"] == 1.0
    md = render_report(mig, ver)
    assert "97,251" not in md and "`15`" in md
    assert "100.0%" in md
