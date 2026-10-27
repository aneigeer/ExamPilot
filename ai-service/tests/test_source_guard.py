"""来源在架校验测试（v4 R04：``app/wiki/source_guard.py`` + 蒸馏写侧裁剪）。

全部 monkeypatch ``filter_active_chunk_ids``，不连真实 MySQL。
"""

from __future__ import annotations

from typing import Dict, List, Set, Tuple

import pytest

from app.wiki import source_guard
from app.wiki.source_guard import (
    filter_active_chunk_ids,
    page_sources_active,
    parse_chunk_ids,
    prune_clusters,
)


# --------------------------------------------------------------------------------------
# parse_chunk_ids
# --------------------------------------------------------------------------------------


class TestParseChunkIds:
    def test_json_text(self) -> None:
        assert parse_chunk_ids('[101, 102, 101]') == [101, 102]  # 去重保序

    def test_list_passthrough(self) -> None:
        assert parse_chunk_ids([3, 1, 2]) == [3, 1, 2]

    def test_single_int(self) -> None:
        assert parse_chunk_ids(7) == [7]

    def test_garbage_returns_empty(self) -> None:
        for raw in (None, "", "not json", '{"a":1}', "12a", [None, "x", -1, 0]):
            assert parse_chunk_ids(raw) == []


# --------------------------------------------------------------------------------------
# filter_active_chunk_ids（真实实现 + monkeypatch pymysql/db_config）
# --------------------------------------------------------------------------------------


class TestFilterActiveChunkIds:
    def test_empty_input_skips_db(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """空入参不碰数据库（即使 pymysql 不可用也返回 checked=True）。"""
        import sys

        monkeypatch.setitem(sys.modules, "pymysql", None)  # import 即失败
        active, checked = filter_active_chunk_ids([])
        assert active == set() and checked is True

    def test_db_error_reports_unchecked(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """DB 异常 → (空集, checked=False)，绝不向上抛。"""
        import sys

        class _FakePymysql:
            class connect:  # noqa: N801 - 模拟 pymysql.connect(...) 直接抛
                def __init__(self, *a: object, **k: object) -> None:
                    raise RuntimeError("db down")

        monkeypatch.setitem(sys.modules, "pymysql", _FakePymysql)
        active, checked = filter_active_chunk_ids([1, 2])
        assert active == set() and checked is False

    def test_query_only_active(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """SQL 只取 ACTIVE 行；分批逻辑正确。"""
        seen_batches: List[List[int]] = []

        class _FakeCursor:
            def __init__(self, rows: List[Tuple[int]]) -> None:
                self._rows = rows

            def __enter__(self) -> "_FakeCursor":
                return self

            def __exit__(self, *exc: object) -> None:
                pass

            def execute(self, sql: str, params: object) -> None:
                assert "status='ACTIVE'" in sql
                seen_batches.append(list(params))  # type: ignore[arg-type]

            def fetchall(self) -> List[Tuple[int]]:
                return [(i,) for i in seen_batches[-1] if i in (1, 3)]

        class _FakeConn:
            def cursor(self) -> _FakeCursor:
                return _FakeCursor([])

            def close(self) -> None:
                pass

        import sys

        monkeypatch.setitem(sys.modules, "pymysql", type("M", (), {"connect": lambda *a, **k: _FakeConn()}))
        monkeypatch.setattr(source_guard, "_QUERY_BATCH", 2)
        active, checked = filter_active_chunk_ids([1, 2, 3, 4, 5])
        assert checked is True
        assert active == {1, 3}
        assert seen_batches == [[1, 2], [3, 4], [5]]  # 分批 2 个一组


# --------------------------------------------------------------------------------------
# page_sources_active（热层读侧）
# --------------------------------------------------------------------------------------


class TestPageSourcesActive:
    def _patch(self, monkeypatch: pytest.MonkeyPatch, active: Set[int],
               checked: bool = True) -> None:
        monkeypatch.setattr(source_guard, "filter_active_chunk_ids",
                            lambda ids: (set(active), checked))

    def test_no_sources_allowed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """无来源（管理端手建页）→ 放行，且不触发 DB 查询。"""
        calls: List[object] = []

        def _spy(ids: object) -> Tuple[Set[int], bool]:
            calls.append(ids)
            return set(), True

        monkeypatch.setattr(source_guard, "filter_active_chunk_ids", _spy)
        assert page_sources_active(None) == (True, "")
        assert page_sources_active("[]") == (True, "")
        assert calls == []  # 没查库

    def test_all_active_allowed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, {1, 2})
        assert page_sources_active("[1,2]") == (True, "")

    def test_partial_delisted_blocked(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, {1})
        allow, reason = page_sources_active("[1,2]")
        assert allow is False and reason == "SOURCE_DELISTED"

    def test_db_down_fail_closed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, set(), checked=False)
        allow, reason = page_sources_active("[1]")
        assert allow is False and reason == "SOURCE_CHECK_UNAVAILABLE"


# --------------------------------------------------------------------------------------
# prune_clusters（蒸馏写侧）
# --------------------------------------------------------------------------------------


def _cluster(slug: str, chunk_ids: List[int]) -> Dict[str, object]:
    return {"slug": slug, "chunks": [{"chunkId": i, "content": f"c{i}"} for i in chunk_ids]}


class TestPruneClusters:
    def test_inactive_chunks_removed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setattr(source_guard, "filter_active_chunk_ids",
                            lambda ids: ({1, 2, 3}, True))
        clusters = [_cluster("kp-1", [1, 2, 9]), _cluster("kp-2", [3, 4])]
        pruned, stats = prune_clusters(clusters)
        # kp-1 剩 {1,2} 保留；kp-2 剩 {3} 不足 2 块被整簇跳过
        assert [c["slug"] for c in pruned] == ["kp-1"]
        assert pruned[0]["chunks"] == [{"chunkId": 1, "content": "c1"},
                                       {"chunkId": 2, "content": "c2"}]
        assert stats["removed"] == 2  # 9 和 4
        assert stats["skipped_clusters"] == 1

    def test_check_failed_fail_open(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """DB 校验失败 → 原样返回（写侧 fail-open）。"""
        monkeypatch.setattr(source_guard, "filter_active_chunk_ids",
                            lambda ids: (set(), False))
        clusters = [_cluster("kp-1", [1, 2])]
        pruned, stats = prune_clusters(clusters)
        assert pruned == clusters
        assert stats["check_failed"] == 1
        assert stats["removed"] == 0

    def test_empty_input(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setattr(source_guard, "filter_active_chunk_ids",
                            lambda ids: (set(), True))
        pruned, stats = prune_clusters([])
        assert pruned == [] and stats["checked"] == 0

    def test_min_cluster_chunks_respected(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """剔除后剩 1 个块（< MIN_CLUSTER_CHUNKS=2）→ 整簇跳过。"""
        monkeypatch.setattr(source_guard, "filter_active_chunk_ids",
                            lambda ids: ({1}, True))
        pruned, stats = prune_clusters([_cluster("kp-1", [1, 2])])
        assert pruned == [] and stats["skipped_clusters"] == 1
