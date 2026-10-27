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
import math
import threading
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Sequence, Tuple

from ..config import QdrantSettings
from ..errors import CapabilityUnavailableError, VectorIndexStaleError, VectorSearchError

if TYPE_CHECKING:  # pragma: no cover —— 仅类型检查期需要，运行期不 import pydantic
    from ..schemas import IndexChunk

logger = logging.getLogger(__name__)

#: 记录 collection 元信息的点 ID（固定 UUID，便于反复覆盖）
_META_POINT_ID = "00000000-0000-0000-0000-00000000cafe"

#: 写入后校准阈值：读回的存储向量与输入向量的余弦必须 ≥ 该值。
#: 0.9999 而不是 1.0 —— 浮点往返（float32 存储）会有 1e-7 级误差。
_VERIFY_MIN_COS = 0.9999

#: 集合不存在 / 模型不匹配时的状态常量
STATUS_READY = "READY"
STATUS_COLLECTION_MISSING = "COLLECTION_MISSING"
STATUS_MODEL_MISMATCH = "MODEL_MISMATCH"
STATUS_ERROR = "ERROR"


def _cosine(a: Sequence[float], b: Sequence[float]) -> float:
    """纯 Python 余弦相似度（不依赖 numpy，也不原地修改入参）。

    ★ 刻意不复用 qdrant 本地实现的 ``cosine_similarity``：那个函数会**原地归一化**
    传入矩阵，正是需要绕开的对象。

    Args:
        a: 向量 A。
        b: 向量 B。

    Returns:
        余弦相似度；任一向量长度为 0 或含非有限值时返回 0.0。
    """
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        if not (math.isfinite(x) and math.isfinite(y)):
            return 0.0
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 0.0 or nb <= 0.0:
        return 0.0
    return dot / math.sqrt(na * nb)


def _vector_reject_reason(vector: Sequence[float]) -> Optional[str]:
    """判断向量是否不可入库。

    ★ 为什么零范数必须拒绝：余弦检索对零向量无定义。本地模式的实现会
    ``query /= max(norm, EPSILON)``，零行保持全 0，看似无害；
    但只要库里混入损坏行，不同 query 的打分就失去可比性，
    检索会**静默退化**成「永远返回同一批结果」。写入侧拦掉是最省事的治本点。

    Args:
        vector: 待写入向量。

    Returns:
        不可入库的原因（中文）；合法时返回 ``None``。
    """
    if not vector:
        return "空向量"
    for value in vector:
        if not math.isfinite(value):
            return "含 NaN/Inf"
    norm_sq = sum(v * v for v in vector)
    if norm_sq <= 0.0:
        return "零范数向量（余弦检索无定义）"
    return None


@dataclass
class UpsertResult:
    """批量写入结果。

    Attributes:
        written: 实际写入条数（已通过校验与校准）。
        rejected: ``chunk_id -> 拒绝原因``（写入前校验未通过，未进库）。
        mismatched: 写入后校准不通过的 chunk_id（已进库但读回不一致）。
    """

    written: int = 0
    rejected: Dict[int, str] = field(default_factory=dict)
    mismatched: List[int] = field(default_factory=list)



def _local_location(url: str) -> Optional[str]:
    """把「本地模式」写法映射为 qdrant-client 的本地位置（免 Docker 运行）。

    - ``:memory:``                     → 进程内内存模式（重启即失，适合演示/测试）
    - ``local:<路径>`` / ``file:<路径>`` → 本地目录持久化（单进程独占）

    返回 ``None`` 表示走常规远程 ``url`` 模式（http(s)://host:port）。

    注：qdrant-client 对本地路径只接受 ``path=`` 参数（``location=`` 会把它当 URL 解析，
    报 "Unknown scheme"），因此调用方需按返回值分流。
    """
    u = (url or "").strip()
    if u == ":memory:":
        return ":memory:"
    for prefix in ("local:", "file:"):
        if u.startswith(prefix):
            return u[len(prefix):].strip() or ":memory:"
    return None


def _to_qdrant_filter(query_filter: Optional[Any]) -> Optional[Any]:
    """把 schemas 层产出的 filter **dict** 转成 qdrant-client 的 ``models.Filter``。

    真实 ``QdrantClient.search`` 只接受 ``models.Filter`` 对象；直接传 dict 会报
    ``'dict' object has no attribute 'must'``（该坑此前未被真实运行暴露）。
    已是 Filter 对象或 None 时原样返回。
    """
    if query_filter is None or not isinstance(query_filter, dict):
        return query_filter
    from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

    must = []
    for cond in query_filter.get("must", []) or []:
        match = cond.get("match") or {}
        # 支持两种写法：
        #   {"value": x}      → 单值匹配（历史用法，所有既有调用方不受影响）
        #   {"any": [x, y]}   → 多值匹配（Router 分流用：如 QUESTION + KNOWLEDGE）
        if "any" in match:
            must.append(
                qmodels.FieldCondition(
                    key=cond.get("key"),
                    match=qmodels.MatchAny(any=list(match["any"])),
                )
            )
        else:
            must.append(
                qmodels.FieldCondition(
                    key=cond.get("key"),
                    match=qmodels.MatchValue(value=match.get("value")),
                )
            )
    return qmodels.Filter(must=must) if must else None


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
        #: 串行化向量读写：本地模式底层是 SQLite/文件，多线程并发访问不稳定；
        #: 生产上单个 AI 服务的检索吞吐远低于该锁的成本，稳定性优先。
        self._op_lock: threading.RLock = threading.RLock()
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
            return f"未连接（懒加载，{self._mode_desc()}）"
        return f"collection={self.settings.collection} {self._mode_desc()}"

    def _mode_desc(self) -> str:
        """当前连接模式的可读描述（远程 url / 本地模式）。"""
        loc = _local_location(self.settings.url)
        if loc is None:
            return f"url={self.settings.url}"
        kind = "内存" if loc == ":memory:" else "本地持久化"
        return f"本地模式（{kind} {loc}）"

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
                location = _local_location(self.settings.url)
                if location == ":memory:":
                    # 进程内内存模式（qdrant-client 内置实现）
                    self._client = QdrantClient(location=":memory:")
                elif location is not None:
                    # 本地目录持久化（path= 为本地模式入口，免 Docker 与 Qdrant 服务）
                    self._client = QdrantClient(path=location)
                else:
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

    # ------------------------------------------------------------------ Wiki 独立集合

    def ensure_named_collection(self, collection: str, dim: Optional[int] = None,
                                *, force_recreate: bool = False) -> str:
        """确保指定集合存在（Wiki 热层独立于 RAG 的 exam_chunks）。

        ★ 复用同一个 QdrantClient 实例：本地目录模式（``local:./data/qdrant``）
        对同一路径是**单进程独占**的，再 new 一个 client 会直接报
        "already accessed by another instance"。因此这里只切换 collection 名。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]

        client = self._ensure_client()
        size = int(dim or self.settings.dim)
        try:
            if force_recreate and client.collection_exists(collection_name=collection):
                client.delete_collection(collection_name=collection)
            if not client.collection_exists(collection_name=collection):
                client.create_collection(
                    collection_name=collection,
                    vectors_config=qmodels.VectorParams(
                        size=size, distance=qmodels.Distance.COSINE
                    ),
                )
                logger.info("[Qdrant] 已创建集合 %s dim=%d", collection, size)
        except Exception as exc:  # noqa: BLE001
            raise VectorSearchError(
                f"Wiki 向量集合操作失败（{collection}）：{type(exc).__name__}", detail=str(exc)
            ) from exc
        return collection

    def upsert_wiki_pages(
        self,
        collection: str,
        pages: Sequence[Dict[str, Any]],
        vectors: Sequence[Sequence[float]],
        dim: Optional[int] = None,
    ) -> int:
        """写入 Wiki 页面摘要向量。

        Args:
            collection: 目标集合（默认配置里的 wiki_pages）。
            pages: 每页 ``{page_id, title, subject_id?, knowledge_id?, version?}``。
            vectors: 与 pages 一一对应的向量。
            dim: 向量维度，缺省用配置维度。

        Returns:
            实际写入条数。

        Raises:
            VectorSearchError: 数量 / 维度不匹配或写入失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]

        if len(pages) != len(vectors):
            raise VectorSearchError(
                f"Wiki 向量数量与页面数量不一致（{len(vectors)} vs {len(pages)}）"
            )
        if not pages:
            return 0
        size = int(dim or self.settings.dim)
        self.ensure_named_collection(collection, size)
        client = self._ensure_client()
        points: List[Any] = []
        for page, vector in zip(pages, vectors):
            values = [float(x) for x in vector]
            if len(values) != size:
                raise VectorSearchError(
                    f"Wiki 向量维度不匹配：期望 {size}，实际 {len(values)}"
                )
            reason = _vector_reject_reason(values)
            if reason is not None:
                logger.warning("[Qdrant] 拒绝写入非法 Wiki 向量 page_id=%s 原因=%s",
                               page.get("page_id"), reason)
                continue
            payload = {k: v for k, v in page.items() if v is not None}
            payload["page_id"] = int(page["page_id"])
            points.append(
                qmodels.PointStruct(
                    id=int(page["page_id"]), vector=values, payload=payload
                )
            )
        if not points:
            return 0
        with self._op_lock:
            try:
                client.upsert(collection_name=collection, points=points, wait=True)
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"Wiki 向量写入失败：{type(exc).__name__}", detail=str(exc)
                ) from exc
        return len(points)

    def delete_wiki_pages(self, collection: str, page_ids: Sequence[int]) -> int:
        """按 page_id 删除 Wiki 向量（先 retrieve 过滤，兼容本地模式）。"""
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]

        ids = [int(i) for i in page_ids]
        if not ids:
            return 0
        client = self._ensure_client()
        with self._op_lock:
            try:
                if not client.collection_exists(collection_name=collection):
                    return 0
                points = client.retrieve(
                    collection_name=collection, ids=ids,
                    with_payload=False, with_vectors=False,
                )
                existing = [int(point.id) for point in points]
                if not existing:
                    return 0
                client.delete(
                    collection_name=collection,
                    points_selector=qmodels.PointIdsList(points=existing),
                    wait=True,
                )
                return len(existing)
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"Wiki 向量删除失败：{type(exc).__name__}", detail=str(exc)
                ) from exc

    def search_wiki(self, collection: str, vector: Sequence[float], *,
                    top_k: int = 5,
                    query_filter: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """Wiki 页面摘要向量检索（返回 ``page_id`` 而非 chunk_id）。"""
        client = self._ensure_client()
        with self._op_lock:
            try:
                if not client.collection_exists(collection_name=collection):
                    raise VectorSearchError(
                        f"Wiki 向量集合不存在（{collection}），请先发布页面或调用 /v1/wiki/index/sync"
                    )
                hits = client.search(
                    collection_name=collection,
                    query_vector=list(vector),
                    limit=top_k,
                    query_filter=_to_qdrant_filter(query_filter),
                    with_payload=True,
                )
            except VectorSearchError:
                raise
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"Wiki 向量检索失败：{type(exc).__name__}", detail=str(exc)
                ) from exc
        results: List[Dict[str, Any]] = []
        for hit in hits:
            payload = dict(getattr(hit, "payload", None) or {})
            page_id = payload.get("page_id", getattr(hit, "id", None))
            if page_id is None:
                continue
            results.append({
                "page_id": int(page_id),
                "score": float(getattr(hit, "score", 0.0) or 0.0),
                "payload": payload,
            })
        return results

    def count_points(self) -> int:
        """统计集合内点数；集合不存在返回 0。

        Returns:
            点数。

        Raises:
            VectorSearchError: 统计失败。
        """
        client = self._ensure_client()
        with self._op_lock:
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
    ) -> "UpsertResult":
        """批量写入向量点（**带写入侧校验 + 写入后校准**）。

        ★ 为什么必须在写入侧拦住坏向量：
        Qdrant 本地模式的检索是「暴力余弦」，一旦库里存在非法/损坏的向量行，
        **整库检索会静默退化**（表现为「无论问什么都返回同一批结果」），
        而现有对账只比对**数量**，发现不了。所以坏向量绝不允许进库。

        两道关：
        1. **写入前校验**：NaN / Inf / 零范数向量直接拒绝（不进库）；
        2. **写入后校准**：读回存储向量与输入逐一比余弦，低于
           :data:`_VERIFY_MIN_COS` 即判定「写了但存进去的不是这条」，
           返回其 id 交由调用方置 ``vector_synced=2`` 重试。

        Args:
            chunks: 语料块列表。
            vectors: 与 ``chunks`` 一一对应的向量。

        Returns:
            :class:`UpsertResult`：``written`` / ``rejected``（id→原因） /
            ``mismatched``（校准不通过的 id）。

        Raises:
            VectorSearchError: 数量不匹配、维度不匹配或写入失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        if len(chunks) != len(vectors):
            raise VectorSearchError(
                f"向量数量与语料块数量不一致（{len(vectors)} vs {len(chunks)}）"
            )
        if not chunks:
            return UpsertResult(written=0, rejected={}, mismatched=[])

        self.ensure_collection()
        client = self._ensure_client()

        points: List[Any] = []
        accepted: List[Tuple[int, List[float]]] = []
        rejected: Dict[int, str] = {}
        for index, chunk in enumerate(chunks):
            chunk_id = int(chunk.chunk_id)
            vector = [float(x) for x in vectors[index]]
            if len(vector) != self.settings.dim:
                raise VectorSearchError(
                    f"向量维度不匹配：期望 {self.settings.dim}，实际 {len(vector)}",
                    detail=str(chunk_id),
                )
            reason = _vector_reject_reason(vector)
            if reason is not None:
                rejected[chunk_id] = reason
                logger.warning(
                    "[Qdrant] 拒绝写入非法向量 chunk_id=%s 原因=%s（该块不会进向量库）",
                    chunk_id, reason,
                )
                continue
            accepted.append((chunk_id, vector))
            points.append(
                qmodels.PointStruct(
                    # chunk_id 复用为 point id（int），Qdrant 支持 uint64/uuid
                    id=chunk_id,
                    vector=vector,
                    payload=build_payload(chunk),
                )
            )
        if not points:
            return UpsertResult(written=0, rejected=rejected, mismatched=[])

        with self._op_lock:
            try:
                client.upsert(collection_name=self.settings.collection, points=points, wait=True)
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"向量写入失败：{type(exc).__name__}", detail=str(exc)
                ) from exc

            mismatched = self._verify_written(client, accepted)
        if mismatched:
            logger.error(
                "[Qdrant] 写入后校准不通过 %d/%d 条（存储向量与输入不一致）：%s"
                "——这属于向量库写入异常，调用方应将这批块置 vector_synced=2 重试；"
                "若反复出现需删除向量库目录后全量重建",
                len(mismatched), len(accepted), mismatched[:10],
            )
        return UpsertResult(written=len(points), rejected=rejected, mismatched=mismatched)

    def _verify_written(
        self, client: Any, accepted: Sequence[Tuple[int, List[float]]]
    ) -> List[int]:
        """写入后校准：读回存储向量，与输入比余弦，返回不一致的 chunk_id。

        Args:
            client: 已建立的 Qdrant 客户端。
            accepted: ``(chunk_id, 向量)`` 列表。

        Returns:
            校准不通过的 chunk_id 列表（读不回来也算不通过）。
        """
        if not accepted:
            return []
        ids = [cid for cid, _ in accepted]
        try:
            points = client.retrieve(
                collection_name=self.settings.collection,
                ids=ids,
                with_vectors=True,
                with_payload=False,
            )
        except Exception as exc:  # noqa: BLE001
            # 校准本身失败不该阻断写入（数据已落库），但要明确报出来
            logger.warning(
                "[Qdrant] 写入后校准读取失败：%s——本批按「未校准」处理",
                type(exc).__name__,
            )
            return ids
        stored = {int(point.id): list(point.vector or []) for point in points}
        bad: List[int] = []
        for chunk_id, vector in accepted:
            got = stored.get(chunk_id)
            if not got or _cosine(vector, got) < _VERIFY_MIN_COS:
                bad.append(chunk_id)
        return bad

    def delete_chunks(self, chunk_ids: Sequence[int]) -> int:
        """按 chunk_id 删除向量点。

        ★ 为什么要先 ``retrieve`` 过滤：**Qdrant 本地模式对不存在的 point id 会抛
        ``KeyError``**（云端实现是静默忽略）。孤儿清理天然会出现「这一批里有的点已被删过」
        的情况，直接 ``delete`` 会让整批失败；先取出真实存在的点再删，
        既避免报错，也让「重复清理」保持幂等。

        Args:
            chunk_ids: 待删除的 ``ai_chunk`` 主键列表。

        Returns:
            实际发起删除的条数（不存在的点不计入）。

        Raises:
            VectorSearchError: 删除失败。
        """
        from qdrant_client import models as qmodels  # type: ignore[import-not-found]  # noqa: PLC0415

        if not chunk_ids:
            return 0
        ids = [int(i) for i in chunk_ids]
        client = self._ensure_client()
        with self._op_lock:
            try:
                points = client.retrieve(
                    collection_name=self.settings.collection,
                    ids=ids,
                    with_payload=False,
                    with_vectors=False,
                )
                ids = [int(point.id) for point in points]
                if not ids:
                    return 0
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"向量删除前查询失败：{type(exc).__name__}", detail=str(exc)
                ) from exc
            try:
                client.delete(
                    collection_name=self.settings.collection,
                    points_selector=qmodels.PointIdsList(points=ids),
                    wait=True,
                )
            except Exception as exc:  # noqa: BLE001
                raise VectorSearchError(
                    f"向量删除失败：{type(exc).__name__}", detail=str(exc)
                ) from exc
        return len(ids)

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
        with self._op_lock:
            try:
                if not client.collection_exists(collection_name=collection):
                    raise VectorSearchError(
                        f"向量集合不存在（{collection}），请先调用 POST /v1/index/rebuild 或 /v1/index/sync"
                    )
                hits = client.search(
                    collection_name=collection,
                    query_vector=list(vector),
                    limit=top_k,
                    query_filter=_to_qdrant_filter(query_filter),
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
            # ★ 重建后必须复核点数：本地模式的 ``delete_collection`` 是"把目录移入回收站"，
            #   若运行环境有删除保护（实测沙箱的 safe-delete 钩子会 trash-failed），
            #   删除会**静默失效**——外观上集合"已删"，但存储文件还在，重建后旧数据原样复活，
            #   于是 rebuild 报 READY 而点数一条没少（2026-10 实测：11482 点重建后仍是 11482）。
            #   这里显式对账，让"假重建"以 FAILED 暴露出来，而不是给调用方一个 READY 的假象。
            leftover = self.count_points()
            if leftover > 0:
                return {
                    "status": "FAILED",
                    "collection": self.settings.collection,
                    "model_id": model_id,
                    "dim": dim,
                    "message": (
                        f"重建未生效：删除后集合仍残留 {leftover} 个点（本地模式存储文件未被真正删除，"
                        f"常见于文件删除保护）。请停止服务后手工删除 "
                        f"data/qdrant/collection/{self.settings.collection}/ 并重启，再执行本接口"
                    ),
                }
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
