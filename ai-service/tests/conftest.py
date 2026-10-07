"""pytest 公共夹具。

**重要设计**：本套测试**不依赖任何真实外部组件**。

* embedding 用 ``FakeEmbeddingClient`` 桩替代（不加载 ONNX 模型）
* Qdrant 用 ``FakeQdrantClient`` 桩替代（不连容器）
* LLM 不配 Key（天然走 503 降级路径）
* OCR 用 ``find_spec`` 探测（没装 paddleocr 就应该 503，这正是要测的）

这样主理人在**只装完 requirements.txt、不启动 Docker、不下载模型**的情况下
就能跑通全部测试 —— 这是"稳定性优先"项目的测试策略。
"""

from __future__ import annotations

import math
import sys
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence

import pytest

# 保证能以 `pytest`（而非 `python -m pytest`）方式从仓库任意位置运行
SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))


# --------------------------------------------------------------------------------------
# 环境隔离：测试期间不读开发者本机 .env
# --------------------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _isolated_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """清空可能影响测试的环境变量，保证结果可复现。"""
    for key in (
        "LLM_API_KEY",
        "EMBEDDING_MODEL_ID",
        "EMBEDDING_DIM",
        "QDRANT_URL",
        "QDRANT_COLLECTION",
        "OCR_ENABLED",
        "APP_NAME",
    ):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("LLM_API_KEY", "")


# --------------------------------------------------------------------------------------
# 桩实现
# --------------------------------------------------------------------------------------


class FakeEmbeddingClient:
    """Embedding 桩：用确定性哈希造向量，维度与真实一致（512）。

    用途：在不下载模型的前提下验证 **API 契约、维度、降级行为**。
    真实推理的数值正确性由 ``test_embedding_dim.py`` 在装了模型的环境里验证。
    """

    def __init__(self, dim: int = 512) -> None:
        self.dim: int = dim
        self._ready: bool = True
        self.calls: int = 0

    @property
    def ready(self) -> bool:
        """桩永远就绪。"""
        return self._ready

    def health_detail(self) -> str:
        """桩的健康诊断。"""
        return "fake-embedding-client"

    def set_ready(self, ready: bool) -> None:
        """切换就绪状态，用于模拟"模型缺失"。

        Args:
            ready: 目标状态。
        """
        self._ready = ready

    def encode(
        self, texts: Sequence[str], *, is_query: bool = False, batch_size: Optional[int] = None
    ) -> List[List[float]]:
        """造确定性向量。

        Args:
            texts: 输入文本。
            is_query: 忽略（本桩不做前缀）。
            batch_size: 忽略。

        Returns:
            与 ``texts`` 等长的 512 维归一化向量列表。
        """
        self.calls += 1
        return [_fake_vector(text, self.dim) for text in texts]

    def encode_query(self, query: str) -> List[float]:
        """造单条查询向量。

        Args:
            query: 查询文本。

        Returns:
            512 维归一化向量。
        """
        return _fake_vector(query, self.dim)


class FakeQdrantClient:
    """Qdrant 桩：内存字典，支持 payload 过滤，行为与真实客户端一致。"""

    def __init__(self, dim: int = 512, *, ready: bool = True) -> None:
        self.settings: Any = _FakeSettings(dim)
        self._ready: bool = ready
        self._store: Dict[int, Dict[str, Any]] = {}
        self._meta: Optional[Dict[str, Any]] = None

    @property
    def ready(self) -> bool:
        """桩是否就绪。"""
        return self._ready

    def health_detail(self) -> str:
        """桩的健康诊断。"""
        return "fake-qdrant-client"

    def probe(self) -> bool:
        """探活。"""
        return self._ready

    def ensure_collection(self, *, force_recreate: bool = False) -> str:
        """建集合（桩直接清空内存）。"""
        if force_recreate:
            self._store.clear()
            self._meta = None
        return self.settings.collection

    def count_points(self) -> int:
        """点数。"""
        return len(self._store)

    def upsert_meta(self, model_id: str, dim: int) -> None:
        """写元信息。"""
        self._meta = {"__meta__": True, "model_id": model_id, "dim": dim}

    def read_meta(self) -> Optional[Dict[str, Any]]:
        """读元信息。"""
        return self._meta

    def assert_model_matches(self, model_id: str) -> None:
        """校验模型一致性。"""
        from app.errors import VectorIndexStaleError

        if self._meta and self._meta.get("model_id") != model_id:
            raise VectorIndexStaleError("向量索引与当前模型不一致，请重建")

    def upsert_chunks(
        self, chunks: Sequence[Any], vectors: Sequence[Sequence[float]]
    ) -> int:
        """批量写入。

        Args:
            chunks: 语料块。
            vectors: 对应向量。

        Returns:
            写入条数。

        Raises:
            app.errors.VectorSearchError: 维度不匹配。
        """
        from app.errors import VectorSearchError

        for index, chunk in enumerate(chunks):
            vector = list(vectors[index])
            if len(vector) != self.settings.dim:
                raise VectorSearchError(f"向量维度不匹配：期望 {self.settings.dim}")
            self._store[int(chunk.chunk_id)] = {
                "chunk_id": int(chunk.chunk_id),
                "vector": vector,
                "payload": _payload_of(chunk),
            }
        return len(chunks)

    def delete_chunks(self, chunk_ids: Sequence[int]) -> int:
        """删除。"""
        for chunk_id in chunk_ids:
            self._store.pop(int(chunk_id), None)
        return len(chunk_ids)

    def search(
        self,
        vector: Sequence[float],
        *,
        top_k: int = 10,
        query_filter: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        """内存余弦检索（桩用点积近似）。

        Args:
            vector: 查询向量。
            top_k: 返回条数。
            query_filter: Qdrant filter 结构（仅支持 ``must`` 里的 match.value）。

        Returns:
            命中列表，按 score 降序。

        Raises:
            app.errors.VectorSearchError: 集合不存在。
        """
        from app.errors import VectorSearchError

        if self._meta is None and not self._store:
            raise VectorSearchError("向量集合不存在")
        results: List[Dict[str, Any]] = []
        for point in self._store.values():
            if query_filter and not _match_filter(point["payload"], query_filter):
                continue
            score = sum(a * b for a, b in zip(vector, point["vector"]))
            results.append(
                {"chunk_id": point["chunk_id"], "score": round(float(score), 6), "payload": point["payload"]}
            )
        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def rebuild(self, model_id: str, dim: int) -> Dict[str, Any]:
        """重建。"""
        self._store.clear()
        self._meta = {"__meta__": True, "model_id": model_id, "dim": dim}
        return {
            "status": "READY",
            "collection": self.settings.collection,
            "model_id": model_id,
            "dim": dim,
            "message": "ok",
        }

    def status(self, config_model_id: str, config_dim: int) -> Dict[str, Any]:
        """状态。"""
        if self._meta is None:
            return {
                "collection": self.settings.collection,
                "model_id": "",
                "dim": config_dim,
                "chunk_synced": 0,
                "config_model_id": config_model_id,
                "status": "COLLECTION_MISSING",
                "message": "集合不存在",
            }
        matched = self._meta.get("model_id") == config_model_id
        return {
            "collection": self.settings.collection,
            "model_id": str(self._meta.get("model_id", "")),
            "dim": int(self._meta.get("dim", config_dim)),
            "chunk_synced": len(self._store),
            "config_model_id": config_model_id,
            "status": "READY" if matched else "MODEL_MISMATCH",
            "message": "索引正常" if matched else "模型不一致",
        }


class _FakeSettings:
    """Qdrant 配置桩。"""

    def __init__(self, dim: int) -> None:
        self.dim: int = dim
        self.collection: str = "exam_chunks_test"


def _payload_of(chunk: Any) -> Dict[str, Any]:
    """从 IndexChunk 生成 payload（复用生产代码逻辑）。"""
    from app.clients.qdrant_client import build_payload

    return build_payload(chunk)


def _match_filter(payload: Dict[str, Any], query_filter: Dict[str, Any]) -> bool:
    """判断 payload 是否命中 filter（仅支持 must + match.value）。"""
    for condition in query_filter.get("must", []):
        key = condition.get("key")
        expected = (condition.get("match") or {}).get("value")
        if payload.get(key) != expected:
            return False
    return True


def _fake_vector(text: str, dim: int) -> List[float]:
    """用文本哈希造一个确定性单位向量（仅供测试）。"""
    seed = sum(ord(ch) for ch in (text or "")) + len(text or "")
    vector: List[float] = []
    for index in range(dim):
        # 用简单线性同余造可复现的伪随机数
        seed = (seed * 1103515245 + 12345 + index) % 2147483648
        vector.append(((seed / 2147483648.0) * 2.0) - 1.0)
    norm = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [value / norm for value in vector]


# --------------------------------------------------------------------------------------
# 夹具
# --------------------------------------------------------------------------------------


@pytest.fixture()
def fake_embedding() -> FakeEmbeddingClient:
    """提供 embedding 桩。"""
    return FakeEmbeddingClient()


@pytest.fixture()
def fake_qdrant() -> FakeQdrantClient:
    """提供 Qdrant 桩。"""
    return FakeQdrantClient()


@pytest.fixture()
def client(fake_embedding: FakeEmbeddingClient, fake_qdrant: FakeQdrantClient,
           monkeypatch: pytest.MonkeyPatch) -> Iterator[Any]:
    """提供 ``TestClient``，并把 embedding / qdrant 单例替换为桩。

    Args:
        fake_embedding: embedding 桩。
        fake_qdrant: Qdrant 桩。
        monkeypatch: pytest 夹具。

    Yields:
        ``fastapi.testclient.TestClient``。
    """
    from fastapi.testclient import TestClient

    from app.clients import embedding_client as embedding_module
    from app.clients import qdrant_client as qdrant_module
    from app.config import get_settings
    from app.main import app

    monkeypatch.setattr(embedding_module, "get_embedding_client", lambda: fake_embedding)
    monkeypatch.setattr(qdrant_module, "get_qdrant_client", lambda: fake_qdrant)
    # 同步 patch 路由器里已导入的名字（from ... import 会绑定引用）
    for module_name in ("embed", "search", "index"):
        module = __import__(f"app.routers.{module_name}", fromlist=["*"])
        if hasattr(module, "get_embedding_client"):
            monkeypatch.setattr(module, "get_embedding_client", lambda: fake_embedding, raising=False)
        if hasattr(module, "get_qdrant_client"):
            monkeypatch.setattr(module, "get_qdrant_client", lambda: fake_qdrant, raising=False)

    with TestClient(app, raise_server_exceptions=False) as test_client:
        test_client.settings = get_settings()  # type: ignore[attr-defined]
        yield test_client


@pytest.fixture()
def raw_client() -> Iterator[Any]:
    """提供**不替换任何桩**的 ``TestClient``（用于测"依赖缺失时也能起"）。

    Yields:
        ``fastapi.testclient.TestClient``。
    """
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


#: 供其他测试文件复用的桩类型
__all__ = [
    "FakeEmbeddingClient",
    "FakeQdrantClient",
    "client",
    "raw_client",
    "fake_embedding",
    "fake_qdrant",
]
