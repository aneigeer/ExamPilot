"""Qdrant 向量库客户端。

**核心定位（设计方案铁律）**：Qdrant 是**可重建的派生存储，不是数据源**。
删掉整个 Qdrant volume，只要 MySQL ``ai_chunk`` 还在，跑一次 ``/v1/index/rebuild``
即可完全恢复。因此本客户端的所有异常都可以安全地降级为 503。

**设计要点**：

1. **懒连接**：FastAPI 启动不连 Qdrant，``/v1/health`` 才做一次轻量探活（可关闭）。
2. **payload 只存元数据不存正文**：正文唯一来源是 MySQL ``ai_chunk``，避免双写不一致。
3. **model_id 写入 collection 之外的元数据点**：用于检测"换模型未重建"，
   检索时发现不匹配直接抛 :class:`VectorIndexStaleError`（5208）。
4. **批量 upsert**：单次最多 500 条，避免单请求过大拖慢 Qdrant。
"""

from __future__ import annotations

import logging
import threading
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Sequence

from ..config import QdrantSettings
from ..errors import CapabilityUnavailableError, VectorIndexStaleError, VectorSearchError

if TYPE_CHECKING:  # pragma: no cover —— 仅类型检查期需要，运行期不 import pydantic
    from ..schemas import IndexChunk

logger = logging.getLogger(__name__)

#: 记录 collection 元信息的点 ID（固定 UUID，便于反复覆盖）
_META_POINT_ID = "00000000-0000-0000-0000-00000000cafe"

#: 集合不存在 / 模型不匹配时的状态常量
STATUS_READY = "READY"
STATUS_COLLECTION_MISSING = "COLLECTION_MISSING"
STATUS_MODEL_MISMATCH = "MODEL_MISMATCH"
STATUS_ERROR = "ERROR"


class QdrantClientWrapper:
    """Qdrant HTTP 客户端封装（懒连接 + 统一错误映射）。

    Attributes:
        settings: Qdrant 配置。
        _client: ``qdrant_client.QdrantClient`` 实例，首次使用时创建。
        _lock: 保护懒连接的互斥锁。
    """

    def __init__(self, settings: QdrantSettings) -> None:
        """初始化包装器（**不建立连接**）。

        Args:
            settings: Qdrant 配置。
        """
        self.settings: QdrantSettings = settings
        self._client: Optional[Any] = None
        self._lock: threading.Lock = threading.Lock()
        self._last_error: str = ""

    # ------------------------------------------------------------------ 健康检查

    @property
    def ready(self) -> bool:
        """最近一次探活是否成功（不主动发起探活，避免健康检查拖慢响应）。"""
        return self._client is not None and not self._last_error

    def health_detail(self) -> str:
        """健康检查诊断信息。"""
        if not self.ready:
            if self._last_error:
                return f"不可用：{self._last_error}"
            return f"未连接（懒加载，{self.settings.url}）"
        return f"collection={self.settings.collection} url={self.settings.url}"

    def probe(self) -> bool:
        """主动探活 Qdrant（供 ``/v1/health`` 调用）。

        Returns:
            Qdrant 可达返回 ``True``；缺依赖或不可达返回 ``False``（不抛异常）。
        """
        try:
            client = self._ensure_client()
            client.get_collections()
            self._last_error = ""
            return True
        except Exception as exc:  # noqa: BLE001
            self._last_error = f"{type(exc).__name__}"
            return False

    # ------------------------------------------------------------------ 连接

    def _ensure_client(self) -> Any:
        """确保 Qdrant 客户端已创建（并发安全）。

        Returns:
            ``QdrantClient`` 实例。

        Raises:
            CapabilityUnavailableError: 未安装 ``qdrant-client``。
            VectorSearchError: 创建客户端失败。
        """
        if self._client is not None:
            return self._client
        with self._lock:
            if self._client is not None:
                return self._client
            try:
                from qdrant_client import QdrantClient  # type: ignore[import-not-found]
            except ImportError as exc:
                raise CapabilityUnavailableError(
                    "向量库能力不可用：未安装 qdrant-client，请执行 install.bat 后重启服务"
                ) from exc
            try:
                self._client = QdrantClient(
                    url=self.settings.url,
                    api_key=self.settings.api_key or None,
                    timeout=self.settings.timeout_seconds,
                    prefer_grpc=self.settings.prefer_grpc,
                )
                self._last_error = ""
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"向量库连接失败（{self.settings.url}）：{type(exc).__name__}", detail=str(exc)
                ) from exc
            return self._client

    # ------------------------------------------------------------------ 集合管理

    def ensure_collection(self, *, force_recreate: bool = False) -> str:
        """确保目标集合存在，维度与距离类型正确。

        Args:
            force_recreate: 为 ``True`` 时先删除再重建（用于换模型场景）。

        Returns:
            集合名。

        Raises:
            VectorSearchError: 集合操作失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        client = self._ensure_client()
        collection = self.settings.collection
        try:
            if force_recreate and client.collection_exists(collection_name=collection):
                client.delete_collection(collection_name=collection)
            if not client.collection_exists(collection_name=collection):
                client.create_collection(
                    collection_name=collection,
                    vectors_config=qmodels.VectorParams(
                        size=self.settings.dim, distance=qmodels.Distance.COSINE
                    ),
                )
                logger.info("[Qdrant] 已创建集合 %s dim=%d", collection, self.settings.dim)
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"向量集合操作失败（{collection}）：{type(exc).__name__}", detail=str(exc)
            ) from exc
        return collection

    def count_points(self) -> int:
        """统计集合内点数；集合不存在返回 0。

        Returns:
            点数。

        Raises:
            VectorSearchError: 统计失败。
        """
        client = self._ensure_client()
        try:
            if not client.collection_exists(collection_name=self.settings.collection):
                return 0
            result = client.count(collection_name=self.settings.collection, exact=True)
            return int(result.count)
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"向量库计数失败：{type(exc).__name__}", detail=str(exc)
            ) from exc

    def upsert_meta(self, model_id: str, dim: int) -> None:
        """写入集合元信息（模型标识 + 维度），用于检测模型不一致。

        Args:
            model_id: 当前模型标识。
            dim: 向量维度。

        Raises:
            VectorSearchError: 写入失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        client = self._ensure_client()
        try:
            client.upsert(
                collection_name=self.settings.collection,
                points=[
                    qmodels.PointStruct(
                        id=_META_POINT_ID,
                        vector=[0.0] * dim,
                        payload={"__meta__": True, "model_id": model_id, "dim": dim},
                    )
                ],
            )
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"写入向量库元信息失败：{type(exc).__name__}", detail=str(exc)
            ) from exc

    def read_meta(self) -> Optional[Dict[str, Any]]:
        """读取集合元信息。

        Returns:
            含 ``model_id`` / ``dim`` 的 dict；集合不存在或未写入时返回 ``None``。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        client = self._ensure_client()
        try:
            if not client.collection_exists(collection_name=self.settings.collection):
                return None
            points = client.retrieve(
                collection_name=self.settings.collection, ids=[_META_POINT_ID], with_payload=True
            )
            for point in points:
                payload = dict(point.payload or {})
                if payload.get("__meta__"):
                    return payload
            return None
        except Exception:  # noqa: BLE001
            return None

    def assert_model_matches(self, model_id: str) -> None:
        """校验集合内模型标识与当前配置一致。

        Args:
            model_id: 当前模型标识。

        Raises:
            VectorIndexStaleError: 集合内记录的是另一个模型，必须全量重建。
        """
        meta = self.read_meta()
        if meta is None:
            return
        stored = str(meta.get("model_id", ""))
        if stored and stored != model_id:
            raise VectorIndexStaleError(
                f"向量索引与当前模型不一致（索引={stored} 当前={model_id}），"
                "请调用 POST /v1/index/rebuild 全量重建",
                detail=f"stored={stored} current={model_id}",
            )

    # ------------------------------------------------------------------ 写入

    def upsert_chunks(
        self, chunks: Sequence["IndexChunk"], vectors: Sequence[Sequence[float]]
    ) -> int:
        """批量写入向量点。

        Args:
            chunks: 语料块列表。
            vectors: 与 ``chunks`` 一一对应的向量。

        Returns:
            成功写入条数。

        Raises:
            VectorSearchError: 数量不匹配或写入失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        if len(chunks) != len(vectors):
            raise VectorSearchError(
                f"向量数量与语料块数量不一致（{len(vectors)} vs {len(chunks)}）"
            )
        if not chunks:
            return 0
        self.ensure_collection()
        client = self._ensure_client()
        points: List[Any] = []
        for index, chunk in enumerate(chunks):
            vector = list(vectors[index])
            if len(vector) != self.settings.dim:
                raise VectorSearchError(
                    f"向量维度不匹配：期望 {self.settings.dim}，实际 {len(vector)}", detail=str(chunk.chunk_id)
                )
            points.append(
                qmodels.PointStruct(
                    # chunk_id 复用为 point id（int），Qdrant 支持 uint64/uuid
                    id=int(chunk.chunk_id),
                    vector=vector,
                    payload=build_payload(chunk),
                )
            )
        try:
            client.upsert(collection_name=self.settings.collection, points=points, wait=True)
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"向量写入失败：{type(exc).__name__}", detail=str(exc)
            ) from exc
        return len(points)

    def delete_chunks(self, chunk_ids: Sequence[int]) -> int:
        """按 chunk_id 删除向量点。

        Args:
            chunk_ids: 待删除的 ``ai_chunk`` 主键列表。

        Returns:
            请求删除的条数。

        Raises:
            VectorSearchError: 删除失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        if not chunk_ids:
            return 0
        client = self._ensure_client()
        try:
            client.delete(
                collection_name=self.settings.collection,
                points_selector=qmodels.PointIdsList(points=[int(i) for i in chunk_ids]),
                wait=True,
            )
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"向量删除失败：{type(exc).__name__}", detail=str(exc)
            ) from exc
        return len(chunk_ids)

    # ------------------------------------------------------------------ 检索

    def search(
        self,
        vector: Sequence[float],
        *,
        top_k: int = 10,
        query_filter: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        """向量检索。

        Args:
            vector: 查询向量（512 维，L2 归一化）。
            top_k: 返回条数。
            query_filter: Qdrant filter 结构；``None`` 表示不过滤。

        Returns:
            ``[{"chunk_id": int, "score": float, "payload": dict}, ...]``，按 score 降序。

        Raises:
            VectorSearchError: 集合不存在或检索失败。
        """
        client = self._ensure_client()
        collection = self.settings.collection
        try:
            if not client.collection_exists(collection_name=collection):
                raise VectorSearchError(
                    f"向量集合不存在（{collection}），请先调用 POST /v1/index/rebuild 或 /v1/index/sync"
                )
            hits = client.search(
                collection_name=collection,
                query_vector=list(vector),
                limit=top_k,
                query_filter=query_filter,
                with_payload=True,
            )
        except VectorSearchError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"向量检索失败：{type(exc).__name__}", detail=str(exc)
            ) from exc

        results: List[Dict[str, Any]] = []
        for hit in hits:
            payload = dict(getattr(hit, "payload", None) or {})
            if payload.get("__meta__"):
                # 跳过元信息点，它是内部标记不是语料
                continue
            chunk_id = payload.get("chunk_id", getattr(hit, "id", None))
            if chunk_id is None:
                continue
            results.append(
                {
                    "chunk_id": int(chunk_id),
                    "score": float(getattr(hit, "score", 0.0) or 0.0),
                    "payload": payload,
                }
            )
        return results

    # ------------------------------------------------------------------ 重建 / 状态

    def rebuild(self, model_id: str, dim: int) -> Dict[str, Any]:
        """重建集合（清空后重新写入元信息）。

        Args:
            model_id: 目标模型标识。
            dim: 目标向量维度。

        Returns:
            ``{"status": ..., "collection": ..., "model_id": ..., "dim": ...}``。

        Raises:
            VectorSearchError: 重建失败。
        """
        original_dim = self.settings.dim
        try:
            # 临时切换维度配置（dataclass 是 frozen 的，用 object.__setattr__ 绕过）
            object.__setattr__(self.settings, "dim", dim)
            self.ensure_collection(force_recreate=True)
            self.upsert_meta(model_id, dim)
        except Exception as exc:  # noqa: BLE001
            return {
                "status": "FAILED",
                "collection": self.settings.collection,
                "model_id": model_id,
                "dim": dim,
                "message": f"重建失败：{type(exc).__name__}",
            }
        finally:
            object.__setattr__(self.settings, "dim", original_dim)
        return {
            "status": "READY",
            "collection": self.settings.collection,
            "model_id": model_id,
            "dim": dim,
            "message": "集合已重建，请从 MySQL ai_chunk 重新推送向量",
        }

    def status(self, config_model_id: str, config_dim: int) -> Dict[str, Any]:
        """查询索引状态（供与 MySQL ``ai_vector_index_meta`` 对账）。

        Args:
            config_model_id: 当前配置的模型标识。
            config_dim: 当前配置的向量维度。

        Returns:
            ``IndexStatusResponse`` 对应的 dict。

        Raises:
            VectorSearchError: 状态查询失败。
        """
        collection = self.settings.collection
        try:
            client = self._ensure_client()
            if not client.collection_exists(collection_name=collection):
                return {
                    "collection": collection,
                    "model_id": "",
                    "dim": config_dim,
                    "chunk_synced": 0,
                    "config_model_id": config_model_id,
                    "status": STATUS_COLLECTION_MISSING,
                    "message": "集合不存在，需执行 /v1/index/rebuild",
                }
            meta = self.read_meta() or {}
            synced = self.count_points()
            # 元信息点本身不计入语料数
            synced = max(0, synced - 1) if meta else synced
            stored_model = str(meta.get("model_id", ""))
            stored_dim = int(meta.get("dim", config_dim))
            if stored_model and stored_model != config_model_id:
                status = STATUS_MODEL_MISMATCH
                message = f"模型不一致：索引={stored_model} 当前={config_model_id}，需重建"
            elif stored_dim != config_dim:
                status = STATUS_MODEL_MISMATCH
                message = f"维度不一致：索引={stored_dim} 当前={config_dim}，需重建"
            else:
                status = STATUS_READY
                message = "索引正常"
            return {
                "collection": collection,
                "model_id": stored_model,
                "dim": stored_dim,
                "chunk_synced": synced,
                "config_model_id": config_model_id,
                "status": status,
                "message": message,
            }
        except VectorSearchError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"查询索引状态失败：{type(exc).__name__}", detail=str(exc)
            ) from exc


def build_payload(chunk: "IndexChunk") -> Dict[str, Any]:
    """把语料块转成 Qdrant payload。

    **只存元数据，不存正文** —— 正文唯一来源是 MySQL ``ai_chunk``（设计方案 B.3）。

    Args:
        chunk: 语料块。

    Returns:
        payload dict。
    """
    payload: Dict[str, Any] = {
        "chunk_id": int(chunk.chunk_id),
        "source_type": chunk.source_type,
        "title": chunk.title,
        "status": chunk.status or "ACTIVE",
        "hot_count": int(chunk.hot_count or 0),
    }
    optional_ints = {
        "source_id": chunk.source_id,
        "subject_id": chunk.subject_id,
        "knowledge_id": chunk.knowledge_id,
        "difficulty": chunk.difficulty,
        "ref_id": chunk.ref_id,
        "page_no": chunk.page_no,
    }
    for key, value in optional_ints.items():
        if value is not None:
            payload[key] = int(value)
    if chunk.ref_type:
        payload["ref_type"] = chunk.ref_type
    if chunk.content_hash:
        payload["content_hash"] = chunk.content_hash
    if chunk.source_updated_at:
        payload["source_updated_at"] = chunk.source_updated_at
    return payload


# --------------------------------------------------------------------------------------
# 进程级单例
# --------------------------------------------------------------------------------------

_client: Optional[QdrantClientWrapper] = None
_client_lock: threading.Lock = threading.Lock()


def get_qdrant_client() -> QdrantClientWrapper:
    """获取进程级 QdrantClientWrapper 单例（懒创建，不建立连接）。"""
    global _client
    if _client is None:
        with _client_lock:
            if _client is None:
                from ..config import get_settings

                _client = QdrantClientWrapper(get_settings().qdrant)
    return _client


def reset_qdrant_client() -> None:
    """重置单例（仅供测试使用）。"""
    global _client
    with _client_lock:
        _client = None
