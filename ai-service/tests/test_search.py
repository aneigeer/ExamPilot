"""``POST /v1/search`` 与 ``/v1/index/*`` 测试。

覆盖：
* **空结果返回 200 + hits=[]**（检索无命中不是错误，拒答由 Java 侧闸门决定）
* payload 过滤（subject_id / knowledge_id / source_type）
* 索引同步 → 检索闭环
* 模型不一致返回 5208
* Qdrant 不可用返回 503
"""

from __future__ import annotations

from typing import Any


# --------------------------------------------------------------------------------------
# /v1/search
# --------------------------------------------------------------------------------------


def test_search_empty_result_returns_200(client: Any) -> None:
    """**关键契约**：检索无命中返回 200 + 空 hits，而不是 404/500。"""
    response = client.post("/v1/search", json={"query": "完全不相关的问题xyz", "top_k": 5})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["hits"] == []
    assert data["took_ms"] >= 0


def test_search_after_index_sync_returns_hits(
    client: Any, fake_qdrant: Any, fake_embedding: Any
) -> None:
    """先 sync 再 search，命中链路闭环。"""
    chunks = [
        {
            "chunk_id": 1,
            "content": "值传递与地址传递的区别：值传递不会改变实参",
            "title": "C语言值传递",
            "source_type": "KNOWLEDGE",
            "subject_id": 5,
            "knowledge_id": 8,
            "difficulty": 2,
            "status": "ACTIVE",
        },
        {
            "chunk_id": 2,
            "content": "TCP 三次握手用于建立可靠连接",
            "title": "计算机网络",
            "source_type": "RESOURCE",
            "subject_id": 3,
            "status": "ACTIVE",
        },
    ]
    sync_response = client.post("/v1/index/sync", json={"chunks": chunks})
    assert sync_response.status_code == 200, sync_response.text
    assert sync_response.json()["synced"] == 2

    # 用第一个 chunk 的原句做查询，桩用点积，命中第一个
    search_response = client.post("/v1/search", json={"query": "值传递与地址传递的区别", "top_k": 2})
    assert search_response.status_code == 200
    hits = search_response.json()["hits"]
    assert len(hits) == 2
    assert all("chunk_id" in hit and "score" in hit and "payload" in hit for hit in hits)


def test_search_hit_payload_has_rerank_score(client: Any) -> None:
    """命中项 payload 里有轻量重排分。"""
    client.post(
        "/v1/index/sync",
        json={"chunks": [{"chunk_id": 9, "content": "进程与线程的区别", "title": "操作系统"}]},
    )
    hits = client.post("/v1/search", json={"query": "进程与线程", "top_k": 1}).json()["hits"]
    assert hits
    assert "rerank_score" in hits[0]["payload"]


def test_search_filters_by_subject_id(client: Any) -> None:
    """subject_id 过滤生效。"""
    client.post(
        "/v1/index/sync",
        json={
            "chunks": [
                {"chunk_id": 1, "content": "C语言指针", "subject_id": 5},
                {"chunk_id": 2, "content": "C语言指针", "subject_id": 3},
            ]
        },
    )
    data = client.post(
        "/v1/search", json={"query": "C语言指针", "top_k": 10, "filters": {"subject_id": 5}}
    ).json()
    assert [hit["chunk_id"] for hit in data["hits"]] == [1]


def test_search_filters_by_source_type(client: Any) -> None:
    """source_type 过滤生效。"""
    client.post(
        "/v1/index/sync",
        json={
            "chunks": [
                {"chunk_id": 1, "content": "同一段内容", "source_type": "QUESTION"},
                {"chunk_id": 2, "content": "同一段内容", "source_type": "KNOWLEDGE"},
            ]
        },
    )
    data = client.post(
        "/v1/search",
        json={"query": "同一段内容", "top_k": 10, "filters": {"source_type": "KNOWLEDGE"}},
    ).json()
    assert [hit["chunk_id"] for hit in data["hits"]] == [2]


def test_search_filters_combined_with_and(client: Any) -> None:
    """多条件为 AND 组合。"""
    client.post(
        "/v1/index/sync",
        json={
            "chunks": [
                {"chunk_id": 1, "content": "内容A", "subject_id": 5, "source_type": "QUESTION"},
                {"chunk_id": 2, "content": "内容A", "subject_id": 5, "source_type": "RESOURCE"},
                {"chunk_id": 3, "content": "内容A", "subject_id": 3, "source_type": "QUESTION"},
            ]
        },
    )
    data = client.post(
        "/v1/search",
        json={
            "query": "内容A",
            "top_k": 10,
            "filters": {"subject_id": 5, "source_type": "QUESTION"},
        },
    ).json()
    assert [hit["chunk_id"] for hit in data["hits"]] == [1]


def test_search_empty_query_returns_400(client: Any) -> None:
    """空 query 返回 400。"""
    response = client.post("/v1/search", json={"query": "   "})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_search_top_k_out_of_range_returns_400(client: Any) -> None:
    """top_k 越界返回 400（1-100）。"""
    assert client.post("/v1/search", json={"query": "x", "top_k": 0}).status_code == 400
    assert client.post("/v1/search", json={"query": "x", "top_k": 101}).status_code == 400


def test_search_returns_503_when_model_mismatch(client: Any, fake_qdrant: Any) -> None:
    """换模型未重建 → 5208（Java 侧触发 reindex），不是 500。"""
    client.post("/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "内容"}]})
    fake_qdrant._meta = {"__meta__": True, "model_id": "bge-base-zh-v1.5", "dim": 768}
    response = client.post("/v1/search", json={"query": "内容", "top_k": 3})
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == 5208
    assert body["degraded"] is True
    assert "rebuild" in body["message"]


def test_search_returns_503_when_embedding_unavailable(raw_client: Any) -> None:
    """模型缺失 → /v1/search 同样 503（Java 侧降级 ngram）。"""
    response = raw_client.post("/v1/search", json={"query": "值传递", "top_k": 5})
    assert response.status_code == 503, f"实际 {response.status_code}：{response.text}"
    body = response.json()
    assert body["degraded"] is True
    assert body["code"] in (5201, 5203, 5204)


# --------------------------------------------------------------------------------------
# /v1/index/sync
# --------------------------------------------------------------------------------------


def test_index_sync_counts(client: Any) -> None:
    """同步返回 synced / skipped / failed 三计数。"""
    response = client.post(
        "/v1/index/sync",
        json={
            "chunks": [
                {"chunk_id": 1, "content": "内容1"},
                {"chunk_id": 2, "content": "内容2"},
            ]
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["synced"] == 2
    assert data["skipped"] == 0
    assert data["failed"] == 0


def test_index_sync_skips_blank_content(client: Any) -> None:
    """空正文的 chunk 被跳过（计入 skipped，不报错）。"""
    response = client.post(
        "/v1/index/sync",
        json={"chunks": [{"chunk_id": 1, "content": "有内容"}, {"chunk_id": 2, "content": "  "}]},
    )
    data = response.json()
    assert data["synced"] == 1
    assert data["skipped"] == 1


def test_index_sync_empty_list(client: Any) -> None:
    """空列表不报错。"""
    response = client.post("/v1/index/sync", json={"chunks": []})
    assert response.status_code == 200
    assert response.json()["synced"] == 0


def test_index_sync_too_many_returns_400(client: Any) -> None:
    """超过 500 条上限返回 400。"""
    response = client.post("/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "x"}] * 501})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_index_sync_returns_503_when_embedding_unavailable(raw_client: Any) -> None:
    """模型缺失 → 同步 503，Java 侧置 vector_synced=2 待重试。"""
    response = raw_client.post(
        "/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "内容"}]}
    )
    assert response.status_code == 503
    assert response.json()["degraded"] is True


# --------------------------------------------------------------------------------------
# /v1/index/rebuild 与 /v1/index/status
# --------------------------------------------------------------------------------------


def test_index_rebuild_clears_and_sets_meta(client: Any) -> None:
    """重建清空集合并写入新模型标识。"""
    client.post("/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "旧内容"}]})
    response = client.post("/v1/index/rebuild", json={})
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "READY"
    assert data["dim"] == 512
    status = client.get("/v1/index/status").json()
    assert status["chunk_synced"] == 0, "重建后应清空旧向量"


def test_index_status_ready_after_sync(client: Any) -> None:
    """同步后状态为 READY 且计数一致（与 MySQL 对账的依据）。"""
    client.post(
        "/v1/index/sync",
        json={"chunks": [{"chunk_id": 1, "content": "a"}, {"chunk_id": 2, "content": "b"}]},
    )
    data = client.get("/v1/index/status").json()
    assert data["status"] == "READY"
    assert data["chunk_synced"] == 2
    assert data["model_id"] == data["config_model_id"]


def test_index_status_collection_missing(client: Any, fake_qdrant: Any) -> None:
    """集合不存在时状态为 COLLECTION_MISSING。"""
    fake_qdrant._meta = None
    fake_qdrant._store.clear()
    data = client.get("/v1/index/status").json()
    assert data["status"] == "COLLECTION_MISSING"
    assert data["chunk_synced"] == 0


def test_index_status_model_mismatch(client: Any, fake_qdrant: Any) -> None:
    """模型不一致时状态为 MODEL_MISMATCH。"""
    client.post("/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "a"}]})
    fake_qdrant._meta = {"__meta__": True, "model_id": "old-model", "dim": 512}
    data = client.get("/v1/index/status").json()
    assert data["status"] == "MODEL_MISMATCH"
    assert "重建" in data["message"]


def test_index_status_returns_503_when_qdrant_unavailable(raw_client: Any) -> None:
    """Qdrant 不可用 → 503（Java 侧告警 + 保留旧索引）。"""
    response = raw_client.get("/v1/index/status")
    assert response.status_code == 503
    assert response.json()["degraded"] is True
