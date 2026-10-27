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
    # 先建一个「存在但为空」的集合：否则会命中"集合不存在"这条独立的 503 契约
    client.post("/v1/index/rebuild", json={})
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


def test_search_returns_503_when_embedding_unavailable(embedding_down: Any) -> None:
    """模型缺失 → /v1/search 同样 503（Java 侧降级 ngram）。

    用 embedding_down 显式制造「模型缺失」，与机器上是否真装了模型解耦。
    """
    with embedding_down() as test_client:
        response = test_client.post("/v1/search", json={"query": "值传递", "top_k": 5})
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


def test_index_sync_returns_503_when_embedding_unavailable(embedding_down: Any) -> None:
    """模型缺失 → 同步 503，Java 侧置 vector_synced=2 待重试。"""
    with embedding_down() as test_client:
        response = test_client.post(
            "/v1/index/sync", json={"chunks": [{"chunk_id": 1, "content": "内容"}]}
        )
    assert response.status_code == 503
    assert response.json()["degraded"] is True


# --------------------------------------------------------------------------------------
# 写入侧校验 + 写入后校准（向量库静默退化的治本点）
# --------------------------------------------------------------------------------------


def test_vector_reject_reason_blocks_bad_vectors() -> None:
    """★ 非法向量必须在写入前被拦下：NaN / Inf / 零范数。

    背景：Qdrant 本地模式一旦混入坏向量行，**整库检索会静默退化**成
    「无论问什么都返回同一批结果」，而现有对账只比对数量、发现不了。
    """
    from app.clients.qdrant_client import _vector_reject_reason

    assert _vector_reject_reason([0.1, 0.2, 0.3]) is None, "合法向量不应被拒"
    assert _vector_reject_reason([]) is not None
    assert _vector_reject_reason([0.0, 0.0, 0.0]) is not None, "零范数向量必须拒绝"
    assert _vector_reject_reason([float("nan"), 0.1]) is not None
    assert _vector_reject_reason([float("inf"), 0.1]) is not None
    assert _vector_reject_reason([float("-inf"), 0.1]) is not None


def test_index_sync_rejects_non_finite_vectors(client: Any, fake_embedding: Any,
                                              monkeypatch: Any) -> None:
    """接口层：embedding 产出零向量 → 拒收，且**不写进向量库**。"""
    monkeypatch.setattr(
        fake_embedding, "encode",
        lambda texts, *, is_query=False, batch_size=None: [[0.0] * 512 for _ in texts],
    )
    response = client.post(
        "/v1/index/sync", json={"chunks": [{"chunk_id": 771, "content": "内容"}]}
    )
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["synced"] == 0
    assert data["rejected"] == 1
    assert data["rejected_ids"] == [771]
    assert data["mismatched"] == 0
    assert data["failed"] == 1, "failed 应等于 rejected + mismatched"


def test_upsert_chunks_rejects_and_calibrates() -> None:
    """真实 wrapper：坏向量被拒、好向量写入**且写入后校准通过**。

    用内存 Qdrant（`location=":memory:"`）直接驱动真实 `upsert_chunks`，
    不依赖 Docker、不依赖模型。
    """
    from qdrant_client import QdrantClient, models

    from app.clients.qdrant_client import QdrantClientWrapper
    from app.config import get_settings
    from app.schemas import IndexChunk

    wrapper = QdrantClientWrapper(get_settings().qdrant)
    client = QdrantClient(location=":memory:")
    client.create_collection(
        wrapper.settings.collection,
        vectors_config=models.VectorParams(size=wrapper.settings.dim,
                                          distance=models.Distance.COSINE),
    )
    wrapper._client = client

    good = [0.1] * wrapper.settings.dim
    chunks = [IndexChunk(chunk_id=1, content="好向量"), IndexChunk(chunk_id=2, content="坏向量")]
    result = wrapper.upsert_chunks(chunks, [good, [0.0] * wrapper.settings.dim])

    assert result.written == 1, "只有合法向量被写入"
    assert 2 in result.rejected, "零范数向量必须被拒收"
    assert result.mismatched == [], "合法写入的点必须通过写入后校准"
    stored = client.retrieve(wrapper.settings.collection, ids=[1, 2], with_vectors=True)
    assert [int(p.id) for p in stored] == [1], "被拒的坏向量绝不能进库"


def test_upsert_chunks_detects_write_corruption() -> None:
    """★ 写入后校准必须能发现「写进去了但存错了」。

    用一个「会把向量写歪」的低层客户端模拟未知根因的写入异常
    （真实场景：本地 Qdrant 持久化曾出现存储向量与输入不一致，
    导致相同文本的向量余弦只有 0.22–0.45）。校准不通过必须报出来，
    交由调用方置 vector_synced=2。
    """
    from qdrant_client import models

    from app.clients.qdrant_client import QdrantClientWrapper
    from app.config import get_settings
    from app.schemas import IndexChunk

    class _CorruptingClient:
        """写入时把向量换成完全不同的值（模拟存储层写歪）。"""

        def __init__(self, collection: str, dim: int) -> None:
            self._store: dict[int, list[float]] = {}
            self._dim = dim

        def upsert(self, collection_name: str, points: Any, wait: bool = True) -> None:
            for point in points:
                self._store[int(point.id)] = [0.9 if i % 2 else -0.9 for i in range(self._dim)]

        def retrieve(self, collection_name: str, ids: Any, **kwargs: Any) -> list[Any]:
            from types import SimpleNamespace

            return [SimpleNamespace(id=i, vector=self._store[i]) for i in ids if i in self._store]

    settings = get_settings().qdrant
    wrapper = QdrantClientWrapper(settings)
    wrapper._client = _CorruptingClient(settings.collection, settings.dim)
    # ensure_collection 对非内存客户端会尝试建表，这里直接跳过它
    wrapper.ensure_collection = lambda: None  # type: ignore[method-assign]

    chunks = [IndexChunk(chunk_id=9, content="会被写歪的块")]
    result = wrapper.upsert_chunks(chunks, [[0.1] * settings.dim])

    assert result.written == 1, "数据已落库"
    assert result.mismatched == [9], "写入后校准必须报出这条——否则静默退化的根因永远查不到"


# --------------------------------------------------------------------------------------
# /v1/index/delete
# --------------------------------------------------------------------------------------


def test_index_delete_removes_vectors_from_search(client: Any) -> None:
    """**关键契约**：删除后向量点真的消失。

    这条是孤儿清理能生效的前提——Qdrant payload 里的 ``status`` 是入库快照，
    块置 INVALID 后 ``status='ACTIVE'`` 过滤仍会命中它；只有真删才拦得住。
    """
    client.post(
        "/v1/index/sync",
        json={
            "chunks": [
                {"chunk_id": 1, "content": "资料分析增长率"},
                {"chunk_id": 2, "content": "言语理解主旨"},
            ]
        },
    )
    assert client.get("/v1/index/status").json()["chunk_synced"] == 2

    response = client.post("/v1/index/delete", json={"chunk_ids": [1]})
    assert response.status_code == 200, response.text
    assert response.json() == {"deleted": 1, "requested": 1}

    assert client.get("/v1/index/status").json()["chunk_synced"] == 1
    hits = client.post("/v1/search", json={"query": "资料分析增长率", "top_k": 10}).json()["hits"]
    assert [hit["chunk_id"] for hit in hits] == [2]


def test_index_delete_unknown_ids_is_idempotent(client: Any) -> None:
    """删除不存在的点不报错（幂等，重复清理安全）。"""
    client.post("/v1/index/rebuild", json={})
    response = client.post("/v1/index/delete", json={"chunk_ids": [999, 1000]})
    assert response.status_code == 200
    assert response.json()["requested"] == 2


def test_index_delete_empty_list(client: Any) -> None:
    """空列表返回 0，不报错。"""
    response = client.post("/v1/index/delete", json={"chunk_ids": []})
    assert response.status_code == 200
    assert response.json() == {"deleted": 0, "requested": 0}


def test_index_delete_too_many_returns_400(client: Any) -> None:
    """超过 500 条上限返回 400。"""
    response = client.post("/v1/index/delete", json={"chunk_ids": list(range(501))})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_index_delete_returns_503_when_qdrant_unavailable(qdrant_down: Any) -> None:
    """Qdrant 不可用 → 503（Java 侧保留 vector_synced=1，下次 reindex 重试清理）。"""
    with qdrant_down() as test_client:
        response = test_client.post("/v1/index/delete", json={"chunk_ids": [1]})
    assert response.status_code == 503
    assert response.json()["degraded"] is True


def test_delete_chunks_skips_missing_ids() -> None:
    """**回归**：Qdrant 本地模式对不存在的 point id 会抛 ``KeyError``（云端静默忽略）。

    孤儿清理必然出现「这批里有已被删过的点」，若不先 ``retrieve`` 过滤，
    整批删除都会失败——真机上就表现为 ``orphanPurge.purged`` 恒为 0、返回 503。

    这里用一个复刻本地模式严格语义的低层客户端，直接测真实 ``delete_chunks``。
    """
    from types import SimpleNamespace

    from app.clients.qdrant_client import QdrantClientWrapper
    from app.config import get_settings

    class _StrictLocalClient:
        """复刻本地模式：删除不存在的 id 抛 ``KeyError``。"""

        def __init__(self, existing: set[int]) -> None:
            self.existing = set(existing)
            self.deleted: list[int] = []

        def retrieve(self, collection_name: str, ids: list[int],
                     with_payload: bool = True, with_vectors: bool = False) -> list[Any]:
            return [SimpleNamespace(id=i) for i in ids if i in self.existing]

        def delete(self, collection_name: str, points_selector: Any, wait: bool = True) -> None:
            for pid in points_selector.points:
                if pid not in self.existing:
                    raise KeyError(pid)
            for pid in points_selector.points:
                self.existing.discard(pid)
                self.deleted.append(pid)

    wrapper = QdrantClientWrapper(get_settings().qdrant)
    stub = _StrictLocalClient({1, 2})
    wrapper._client = stub

    assert wrapper.delete_chunks([1, 999, 2]) == 2, "不存在的 999 应被跳过，而不是让整批失败"
    assert wrapper.delete_chunks([999]) == 0, "已删过的 id 再删一次必须幂等"
    assert stub.deleted == [1, 2]


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


def test_index_status_returns_503_when_qdrant_unavailable(qdrant_down: Any) -> None:
    """Qdrant 不可用 → 503（Java 侧告警 + 保留旧索引）。"""
    with qdrant_down() as test_client:
        response = test_client.get("/v1/index/status")
    assert response.status_code == 503
    assert response.json()["degraded"] is True


class _LeftoverQdrant:
    """Qdrant 桩：模拟「delete_collection 静默失效」——重建后点数没少。

    这是 2026-10 本机实测到的真实故障：本地模式的 delete_collection 靠"目录移入
    回收站"实现，环境有删除保护时 trash-failed 会静默中止，rebuild 报 READY
    而点数一条没少（11482 → 11482），后续对账才发现。
    """

    def __init__(self, count: int) -> None:
        self._count = count

    def collection_exists(self, collection_name: str) -> bool:
        """集合始终存在。"""
        return True

    def delete_collection(self, collection_name: str) -> None:
        """删除静默无效（点数不变）。"""

    def create_collection(self, *args: Any, **kwargs: Any) -> None:
        """创建同样不改变点数。"""

    def get_collections(self) -> Any:
        """仅用于探活。"""
        raise AssertionError("不应调用")

    def count(self, collection_name: str, exact: bool = True) -> Any:
        """返回固定残留点数。"""
        return type("Count", (), {"count": self._count})()

    def upsert(self, *args: Any, **kwargs: Any) -> None:
        """写元信息（不应被执行到）。"""


def test_rebuild_reports_failure_when_delete_silently_fails(monkeypatch: Any) -> None:
    """重建后点数未清零 → 必须报 FAILED 并给出可执行指引，不能给假 READY。"""
    from app.clients.qdrant_client import QdrantClientWrapper

    wrapper = QdrantClientWrapper.__new__(QdrantClientWrapper)  # 跳过 __init__ 的参数依赖
    wrapper.settings = type("S", (), {"dim": 512, "collection": "exam_chunks"})()
    wrapper._client = _LeftoverQdrant(count=11482)
    wrapper._lock = __import__("threading").Lock()
    wrapper._op_lock = __import__("threading").RLock()
    wrapper._last_error = ""

    result = wrapper.rebuild("bge-small-zh-v1.5", 512)
    assert result["status"] == "FAILED"
    assert "11482" in result["message"]
    assert "data/qdrant/collection/exam_chunks" in result["message"]
