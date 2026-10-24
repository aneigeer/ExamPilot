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
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Sequence

import pytest

# 保证能以 `pytest`（而非 `python -m pytest`）方式从仓库任意位置运行
SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))


# --------------------------------------------------------------------------------------
# 环境隔离：测试期间不读开发者本机 .env
# --------------------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _isolated_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """清空可能影响测试的环境变量，保证结果可复现。

    ★ 仅清空 ``os.environ`` 不够：``config._get`` 在环境变量为空时会回退读取
    开发者本机的 ``ai-service/.env``。一旦本机配置了 Ollama（``LLM_API_KEY=ollama``），
    「未配置 Key 必须 503」这类契约断言就会失真。因此这里同时把
    ``DEFAULT_ENV_FILE`` 指向不存在的路径，并重置配置单例。
    """
    from app import config as config_module
    from app.clients import llm_client as llm_module

    monkeypatch.setattr(
        config_module, "DEFAULT_ENV_FILE",
        SERVICE_ROOT / "__no_such_env_file__.env", raising=False
    )
    for key in (
        "LLM_API_KEY",
        "LLM_BASE_URL",
        "LLM_MODEL",
        "EMBEDDING_MODEL_ID",
        "EMBEDDING_DIM",
        "QDRANT_URL",
        "QDRANT_COLLECTION",
        "OCR_ENABLED",
        "APP_NAME",
    ):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("LLM_API_KEY", "")
    config_module.reset_settings()
    llm_module.reset_llm_client()
    yield
    config_module.reset_settings()
    llm_module.reset_llm_client()


# --------------------------------------------------------------------------------------
# 桩实现
# --------------------------------------------------------------------------------------


class FakeEmbeddingClient:
    """Embedding 桩：用确定性哈希造向量，维度与真实一致（512）。

    用途：在不下载模型的前提下验证 **API 契约、维度、降级行为**。
    真实推理的数值正确性由 ``test_embedding.py`` 在装了模型的环境里验证。

    Attributes:
        dim: 向量维度。
        capable: 能力是否可用（对应真实实现的 ``capable``）。
        loaded: 是否已加载（对应真实实现的 ``loaded``）。
    """

    def __init__(self, dim: int = 512) -> None:
        self.dim: int = dim
        self._capable: bool = True
        self._loaded: bool = True
        self.calls: int = 0

    @property
    def capable(self) -> bool:
        """桩：能力是否可用。"""
        return self._capable

    @property
    def loaded(self) -> bool:
        """桩：是否已加载。"""
        return self._loaded

    @property
    def ready(self) -> bool:
        """桩：``ready`` 语义等同 ``capable``（与真实实现一致）。"""
        return self._capable

    @property
    def load_ms(self) -> int:
        """桩：加载耗时（恒 0）。"""
        return 0

    def health_detail(self) -> str:
        """桩的健康诊断。"""
        return "fake-embedding-client"

    def set_ready(self, ready: bool) -> None:
        """切换就绪状态，用于模拟"模型缺失"。

        Args:
            ready: 目标状态（同时影响 capable 与 loaded）。
        """
        self._capable = ready
        self._loaded = ready

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


class UnavailableEmbeddingClient:
    """桩：模拟「模型/依赖缺失」的 embedding 客户端。

    用途：让「缺依赖 → 503 降级」这类断言**与机器环境解耦**。本仓库的测试设计前提是
    「只装 requirements.txt、不下载模型」；一旦机器上真放了模型（本机即如此），
    真实客户端就会加载成功、返回 200，使断言失真。用本桩显式制造不可用态即可复现。
    """

    dim: int = 0

    @property
    def capable(self) -> bool:
        """能力不可用（缺依赖/缺模型文件）。"""
        return False

    @property
    def loaded(self) -> bool:
        """从未加载。"""
        return False

    @property
    def ready(self) -> bool:
        """``ready`` 等价于 ``capable``（与真实实现语义一致）。"""
        return False

    @property
    def load_ms(self) -> int:
        """未加载，耗时为 0。"""
        return 0

    def health_detail(self) -> str:
        """与真实客户端不可用时的诊断口径一致。"""
        return "不可用：模型文件缺失，请执行 install.bat 或放入模型（懒加载）"

    def encode(
        self, texts: Sequence[str], *, is_query: bool = False, batch_size: Optional[int] = None
    ) -> List[List[float]]:
        """抛能力不可用（5201），与真实模型缺失时的行为一致。"""
        raise _capability_unavailable()

    def encode_query(self, query: str) -> List[float]:
        """抛能力不可用（5201）。"""
        raise _capability_unavailable()


def _capability_unavailable() -> Exception:
    """构造与真实实现同类的 5201 异常（懒导入，保持 conftest 顶层零第三方依赖）。"""
    from app.errors import CapabilityUnavailableError

    return CapabilityUnavailableError(
        "向量化能力不可用：模型文件缺失或 onnxruntime/tokenizers 未安装；"
        "请执行 install.bat 或放入模型后重启（Java 侧降级到 ngram 检索）",
        detail="stub: model missing",
    )


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
            # 文案与真实客户端保持一致（真实实现含 rebuild 指引，Java 侧据此提示重建）
            raise VectorIndexStaleError(
                "向量索引与当前模型不一致，请调用 POST /v1/index/rebuild 全量重建"
            )

    def upsert_chunks(
        self, chunks: Sequence[Any], vectors: Sequence[Sequence[float]]
    ) -> Any:
        """批量写入（**与真实实现相同的契约**：写入前校验 + 写入后校准）。

        ★ 桩必须复刻真实语义，否则「坏向量被拦下」「写进去的不是这条」这两条契约
        在测试里是**假绿**的——真实客户端曾经就因为写入异常导致整库检索静默退化。

        Args:
            chunks: 语料块。
            vectors: 对应向量。

        Returns:
            ``UpsertResult``：written / rejected / mismatched。

        Raises:
            app.errors.VectorSearchError: 维度不匹配。
        """
        from app.clients.qdrant_client import UpsertResult, _cosine, _vector_reject_reason
        from app.errors import VectorSearchError

        rejected: Dict[int, str] = {}
        mismatched: List[int] = []
        written = 0
        for index, chunk in enumerate(chunks):
            chunk_id = int(chunk.chunk_id)
            vector = [float(x) for x in vectors[index]]
            if len(vector) != self.settings.dim:
                raise VectorSearchError(f"向量维度不匹配：期望 {self.settings.dim}")
            reason = _vector_reject_reason(vector)
            if reason is not None:
                rejected[chunk_id] = reason
                continue
            self._store[chunk_id] = {
                "chunk_id": chunk_id,
                "vector": vector,
                "payload": _payload_of(chunk),
            }
            written += 1
            if _cosine(vector, self._store[chunk_id]["vector"]) < 0.9999:
                mismatched.append(chunk_id)
        return UpsertResult(written=written, rejected=rejected, mismatched=mismatched)

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
            # 文案与真实客户端一致：不一致时必须给出「需重建」的可执行指引
            "message": "索引正常" if matched else "模型不一致，需重建",
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


def _match_filter(payload: Dict[str, Any], query_filter: Any) -> bool:
    """判断 payload 是否命中 filter（仅支持 must + match.value）。

    兼容两种入参：生产 wrapper 会先把 schemas 的 filter dict 转成 qdrant 的
    ``models.Filter`` 对象再交给客户端，而部分测试直接传 dict，因此两种都要认。
    """
    if query_filter is None:
        return True
    if isinstance(query_filter, dict):
        conditions = query_filter.get("must", []) or []
        for condition in conditions:
            key = condition.get("key")
            expected = (condition.get("match") or {}).get("value")
            if payload.get(key) != expected:
                return False
        return True
    for condition in getattr(query_filter, "must", None) or []:
        key = getattr(condition, "key", None)
        match = getattr(condition, "match", None)
        expected = getattr(match, "value", None)
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


@contextmanager
def embedding_unavailable_client(
    monkeypatch: pytest.MonkeyPatch, *, disable_ocr: bool = False
) -> Iterator[Any]:
    """构造一个「模型/依赖缺失」的 ``TestClient``（确定性复现降级态）。

    本仓库测试的设计前提是「不下载模型、不装 paddleocr」；一旦机器上真的具备这些，
    「缺依赖必 503」的断言就会失真。这里显式把 embedding 换成 :class:`UnavailableEmbeddingClient`，
    （可选）再关掉 OCR，使降级契约的验证与机器环境解耦。

    Args:
        monkeypatch: pytest 夹具。
        disable_ocr: 是否同时关闭 OCR 能力（涉及 /v1/ocr 的端到端降级用例需要）。

    Yields:
        ``TestClient``，其 embedding（及可选 OCR）能力被显式置为不可用。
    """
    from fastapi.testclient import TestClient

    from app.clients import embedding_client as embedding_module
    from app.config import reset_settings
    from app.main import app
    from app.ocr.paddle_ocr_engine import reset_ocr_engine

    stub = UnavailableEmbeddingClient()
    monkeypatch.setattr(embedding_module, "get_embedding_client", lambda: stub)
    # 路由器多采用 `from ... import get_embedding_client`，需同步替换已绑定的引用
    for module_name in ("embed", "search", "index", "health"):
        module = __import__(f"app.routers.{module_name}", fromlist=["*"])
        if hasattr(module, "get_embedding_client"):
            monkeypatch.setattr(module, "get_embedding_client", lambda: stub, raising=False)

    if disable_ocr:
        monkeypatch.setenv("OCR_ENABLED", "false")
        reset_settings()
        reset_ocr_engine()
    try:
        with TestClient(app, raise_server_exceptions=False) as test_client:
            yield test_client
    finally:
        if disable_ocr:
            reset_settings()
            reset_ocr_engine()


@pytest.fixture()
def embedding_down(monkeypatch: pytest.MonkeyPatch) -> Callable[..., Any]:
    """返回工厂函数：``with embedding_down(disable_ocr=True) as c: ...``。

    把「依赖缺失」的 TestClient 构造收敛为一个夹具，测试文件无需 import conftest。
    """
    def _make(*, disable_ocr: bool = False) -> Any:
        return embedding_unavailable_client(monkeypatch, disable_ocr=disable_ocr)

    return _make


class UnavailableQdrantClient:
    """桩：模拟「Qdrant 不可用」——所有操作都抛 5203。

    与 :class:`UnavailableEmbeddingClient` 同理：本机 Docker 正常运行时，
    真实 Qdrant 会返回 200，使「Qdrant 挂 → 503」的断言失真，故显式制造不可用态。
    """

    def __init__(self) -> None:
        self.settings: Any = _FakeSettings(512)

    @property
    def ready(self) -> bool:
        """永远不可用。"""
        return False

    def health_detail(self) -> str:
        """诊断口径与真实客户端一致。"""
        return "不可用：Qdrant 连接被拒绝"

    def probe(self) -> bool:
        """探活恒失败。"""
        return False

    def _raise(self) -> None:
        """抛出与真实实现一致的 5203。"""
        from app.errors import VectorSearchError

        raise VectorSearchError("向量库连接失败（测试桩）")

    def ensure_collection(self, *, force_recreate: bool = False) -> str:
        """不可用。"""
        self._raise()
        return ""

    def count_points(self) -> int:
        """不可用。"""
        self._raise()
        return 0

    def upsert_meta(self, model_id: str, dim: int) -> None:
        """不可用。"""
        self._raise()

    def read_meta(self) -> Optional[Dict[str, Any]]:
        """不可用。"""
        return None

    def assert_model_matches(self, model_id: str) -> None:
        """不可用。"""
        self._raise()

    def upsert_chunks(self, chunks: Sequence[Any], vectors: Sequence[Sequence[float]]) -> Any:
        """不可用。"""
        self._raise()

    def delete_chunks(self, chunk_ids: Sequence[int]) -> int:
        """不可用。"""
        self._raise()
        return 0

    def search(self, *args: Any, **kwargs: Any) -> List[Dict[str, Any]]:
        """不可用。"""
        self._raise()
        return []

    def rebuild(self, model_id: str, dim: int) -> Dict[str, Any]:
        """不可用。"""
        self._raise()
        return {}

    def status(self, config_model_id: str, config_dim: int) -> Dict[str, Any]:
        """不可用。"""
        self._raise()
        return {}


@contextmanager
def qdrant_unavailable_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[Any]:
    """构造一个「Qdrant 不可用」的 ``TestClient``（确定性复现 503 降级态）。

    Args:
        monkeypatch: pytest 夹具。

    Yields:
        ``TestClient``，其 Qdrant 能力被显式置为不可用。
    """
    from fastapi.testclient import TestClient

    from app.clients import qdrant_client as qdrant_module
    from app.main import app

    stub = UnavailableQdrantClient()
    monkeypatch.setattr(qdrant_module, "get_qdrant_client", lambda: stub)
    for module_name in ("search", "index", "health"):
        module = __import__(f"app.routers.{module_name}", fromlist=["*"])
        if hasattr(module, "get_qdrant_client"):
            monkeypatch.setattr(module, "get_qdrant_client", lambda: stub, raising=False)

    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


@pytest.fixture()
def qdrant_down(monkeypatch: pytest.MonkeyPatch) -> Callable[[], Any]:
    """返回工厂函数：``with qdrant_down() as c: ...``。"""
    def _make() -> Any:
        return qdrant_unavailable_client(monkeypatch)

    return _make


@pytest.fixture()
def llm_unconfigured() -> Iterator[None]:
    """临时清空 LLM Key，让「未配置 Key」类断言与机器环境解耦。

    本机可能配置了 Ollama 等本地模型（``LLM_API_KEY=ollama``），
    若不显式清空，健康检查会如实返回 ``llm.ready=True``，断言必然失败。
    这正是「测试不依赖本机环境」的硬约束。
    """
    from app.clients import llm_client as llm_module
    from app.config import get_settings

    settings = get_settings()
    original = settings.llm.api_key
    object.__setattr__(settings.llm, "api_key", "")
    llm_module.reset_llm_client()
    try:
        yield
    finally:
        object.__setattr__(settings.llm, "api_key", original)
        llm_module.reset_llm_client()


#: 供其他测试文件复用的桩类型
__all__ = [
    "FakeEmbeddingClient",
    "FakeQdrantClient",
    "UnavailableEmbeddingClient",
    "UnavailableQdrantClient",
    "client",
    "raw_client",
    "embedding_unavailable_client",
    "qdrant_unavailable_client",
    "fake_embedding",
    "fake_qdrant",
    "embedding_down",
    "qdrant_down",
    "llm_unconfigured",
]
