"""Context Engine v2：长输出落盘、分段读取、三段式压缩。"""

from __future__ import annotations

import json

from app.agent import context_manager
from app.agent.artifact_store import ArtifactStore
from app.agent.context_manager import ContextPolicy, build_context


def test_long_observation_is_offloaded(tmp_path, monkeypatch):
    store = ArtifactStore(root=tmp_path)
    monkeypatch.setattr(context_manager, "ArtifactStore", lambda: store)

    big = {"items": [{"chunkId": 1001, "text": "长内容" * 200}] * 5}
    bundle = build_context(
        question="这道题怎么解",
        history=[],
        memory_summary=None,
        weak_knowledge_ids=[],
        observations=[{"tool": "search_knowledge", "ok": True, "summary": "命中 5 条", "data": big}],
        policy=ContextPolicy(max_chars=8000),
        trace_id="trace-abc",
    )

    assert bundle.offloaded_artifacts == 1
    assert "artifact_id=" in bundle.text
    assert len(list(tmp_path.rglob("*.json"))) == 1
    assert "长内容" * 200 not in bundle.text


def test_read_artifact_pieces_and_guards(tmp_path):
    store = ArtifactStore(root=tmp_path)
    artifact_id, total = store.save({"a": "x" * 500}, "trace-1")
    assert total > 500

    first = store.read(artifact_id, "trace-1", 0, 100)
    assert first["ok"] and first["next_offset"] == 100

    second = store.read(artifact_id, "trace-1", 100, 100)
    assert second["ok"] and second["content"] != first["content"]

    # 跨 trace 读不到
    assert store.read(artifact_id, "trace-2")["ok"] is False
    # 路径穿越 / 非法 ID 一律拒绝
    assert store.read("../../etc/passwd", "trace-1")["ok"] is False
    assert store.read("0123456789abcdeZ", "trace-1")["ok"] is False


def test_structured_summary_keeps_evidence_ids():
    observations = [
        {"tool": "search_knowledge", "ok": True, "summary": "命中题目 3 条",
         "data": {"items": [{"chunkId": 77, "questionId": 12}]}},
        {"tool": "get_question", "ok": True, "summary": "题 12 题干",
         "data": {"questionId": 12, "stem": "x" * 900}},
    ] + [
        {"tool": "search_knowledge", "ok": True, "summary": "补充观察 " + str(i) + " " + "y" * 180,
         "data": {"questionId": 100 + i}}
        for i in range(8)
    ] + [
        {"tool": "search_knowledge", "ok": True, "summary": "补充观察 " + str(i) + " " + "y" * 180,
         "data": {"questionId": 100 + i}}
        for i in range(8)
    ]
    bundle = build_context(
        question="继续解释",
        history=[{"question": "上一题", "answer": "上一答"}],
        memory_summary=None,
        weak_knowledge_ids=[],
        observations=observations,
        policy=ContextPolicy(max_chars=700, history_chars=100, observation_data_chars=50),
        trace_id="trace-budget",
    )
    assert "【已确认结论】" in bundle.text
    assert "【关键证据引用】" in bundle.text
    assert "【下一步待办】" in bundle.text
    assert "id=12" in bundle.text or "id=77" in bundle.text
