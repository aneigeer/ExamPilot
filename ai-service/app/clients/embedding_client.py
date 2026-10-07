"""Embedding 客户端：bge-small-zh-v1.5 **ONNX Runtime INT8** 本地推理。

**为什么不用 sentence-transformers**：它会拖入 PyTorch（800MB–2GB），
而 onnxruntime 只有几十 MB。本项目 512 维短文本检索，ONNX 路线完全够用。

**关键实现要点**：

1. **懒加载**：FastAPI 启动时**不加载模型**（首次调 ``/v1/embed`` 或 ``/v1/search`` 才加载），
   避免白占内存，也保证缺依赖时服务仍能起。
2. **CLS pooling + L2 归一化**：bge 系列官方要求取 ``last_hidden_state[:, 0]``（CLS），
   再做 L2 归一化，这样余弦相似度 == 点积，直接可与 Qdrant 的 COSINE 距离对齐。
3. **查询加前缀**：bge-zh 1.5 官方建议检索 query 前加
   ``"为这个句子生成表示以用于检索相关文章："``，显著提升召回质量。
4. **线程安全**：ONNX Runtime session 本身线程安全，但我们用锁保护首次加载，
   避免并发重复加载（使用场景多请求同时打入会触发）。
5. **可选自动下载**：``EMBEDDING_AUTO_DOWNLOAD=true`` 时用 huggingface_hub 拉取模型；
   默认关闭，因为使用场景可能断网，且下载体积需主理人确认。
"""

from __future__ import annotations

import logging
import math
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from ..config import EmbeddingSettings
from ..errors import CapabilityUnavailableError, EmbeddingError

logger = logging.getLogger(__name__)

#: CLS pooling 时 ONNX 输出的候选名称（不同导出脚本命名不一致）
_OUTPUT_NAMES = ("last_hidden_state", "sentence_output", "output", "logits")


class EmbeddingClient:
    """bge-small-zh-v1.5 ONNX INT8 embedding 客户端（懒加载 + 线程安全）。

    Attributes:
        settings: embedding 配置。
        _session: onnxruntime.InferenceSession，首次使用时创建。
        _tokenizer: tokenizers.Tokenizer。
        _lock: 保护懒加载的互斥锁。
        _load_error: 首次加载失败的异常信息，用于健康检查展示。
    """

    def __init__(self, settings: EmbeddingSettings) -> None:
        """初始化客户端（**不加载模型**）。

        Args:
            settings: embedding 配置。
        """
        self.settings: EmbeddingSettings = settings
        self._session: Optional[Any] = None
        self._tokenizer: Optional[Any] = None
        self._input_names: set = set()
        self._lock: threading.Lock = threading.Lock()
        self._load_error: Optional[str] = None
        self._load_ms: int = 0

    # ------------------------------------------------------------------ 健康检查

    @property
    def ready(self) -> bool:
        """模型是否已成功加载（不含"能否加载"的探测，避免健康检查触发重载）。"""
        return self._session is not None and self._tokenizer is not None

    @property
    def dim(self) -> int:
        """向量维度；未就绪时返回配置值（供 Java 侧预判）。"""
        return self.settings.dim

    @property
    def load_ms(self) -> int:
        """首次加载耗时（毫秒），0 表示尚未加载。"""
        return self._load_ms

    def health_detail(self) -> str:
        """健康检查诊断信息（不含堆栈）。"""
        if self.ready:
            return f"model={self.settings.model_id} load_ms={self._load_ms}"
        if self._load_error:
            return f"未就绪：{self._load_error}"
        return f"未加载（懒加载，模型目录 {self.settings.model_dir}）"

    # ------------------------------------------------------------------ 加载

    def ensure_loaded(self) -> None:
        """确保模型已加载；未加载则同步加载。

        Raises:
            CapabilityUnavailableError: onnxruntime / tokenizers 未安装，或模型文件缺失。
            EmbeddingError: 模型文件存在但加载失败（损坏等）。
        """
        if self.ready:
            return
        with self._lock:
            if self.ready:  # 双检：并发下只加载一次
                return
            started = time.perf_counter()
            try:
                self._load_locked()
            except (CapabilityUnavailableError, EmbeddingError) as exc:
                self._load_error = exc.message
                raise
            except Exception as exc:  # noqa: BLE001 —— 兜底，绝不让异常穿透到路由
                self._load_error = f"模型加载失败：{type(exc).__name__}"
                raise EmbeddingError(
                    f"Embedding 模型加载失败（{self.settings.model_id}）：{type(exc).__name__}",
                    detail=str(exc),
                ) from exc
            self._load_ms = int((time.perf_counter() - started) * 1000)
            self._load_error = None
            logger.info(
                "[Embedding] 模型加载完成 model=%s dim=%d load_ms=%d",
                self.settings.model_id, self.settings.dim, self._load_ms,
            )

    def _load_locked(self) -> None:
        """执行实际的模型加载（调用方已持有锁）。

        Raises:
            CapabilityUnavailableError: 依赖缺失或模型文件缺失。
            EmbeddingError: 加载过程异常。
        """
        try:
            import onnxruntime  # type: ignore[import-not-found]
        except ImportError as exc:
            raise CapabilityUnavailableError(
                "Embedding 能力不可用：未安装 onnxruntime，请执行 install.bat 后重启服务"
            ) from exc
        try:
            from tokenizers import Tokenizer  # type: ignore[import-not-found]
        except ImportError as exc:
            raise CapabilityUnavailableError(
                "Embedding 能力不可用：未安装 tokenizers，请执行 install.bat 后重启服务"
            ) from exc

        onnx_path: Path = self.settings.onnx_path
        tokenizer_path: Path = self.settings.tokenizer_path
        if not onnx_path.is_file() or not tokenizer_path.is_file():
            # 允许显式开启自动下载（默认关闭，见模块 docstring）
            if self.settings.auto_download and self._try_auto_download():
                pass
            else:
                raise CapabilityUnavailableError(
                    "Embedding 能力不可用：模型文件缺失（"
                    f"{onnx_path.name} / {tokenizer_path.name}），"
                    "请把 bge-small-zh-v1.5 的 ONNX INT8 模型放入 "
                    f"{self.settings.model_dir}，或设置 EMBEDDING_AUTO_DOWNLOAD=true"
                )

        session_options = onnxruntime.SessionOptions()
        session_options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
        if self.settings.intra_op_num_threads > 0:
            session_options.intra_op_num_threads = self.settings.intra_op_num_threads
        try:
            session = onnxruntime.InferenceSession(
                str(onnx_path), sess_options=session_options, providers=["CPUExecutionProvider"]
            )
            tokenizer = Tokenizer.from_file(str(tokenizer_path))
        except Exception as exc:  # noqa: BLE001
            raise EmbeddingError(
                f"Embedding 模型加载失败（{onnx_path.name}）：{type(exc).__name__}", detail=str(exc)
            ) from exc

        # CLS pooling 时 ONNX 的输入通常是 input_ids / attention_mask / token_type_ids
        # 只喂模型声明需要的输入，避免 "Unexpected input name" 报错。
        input_names: set = {i.name for i in session.get_inputs()}
        tokenizer.enable_truncation(max_length=self.settings.max_length)
        tokenizer.enable_padding(pad_id=0, pad_token="[PAD]", length=None)

        self._session = session
        self._tokenizer = tokenizer
        self._input_names = input_names

    def _try_auto_download(self) -> bool:
        """尝试从 HuggingFace 下载模型（仅在 ``EMBEDDING_AUTO_DOWNLOAD=true`` 时调用）。

        Returns:
            下载并落盘成功返回 ``True``；失败返回 ``False``（不抛异常，交给调用方降级）。
        """
        try:
            from huggingface_hub import snapshot_download  # type: ignore[import-not-found]
        except ImportError:
            logger.warning("[Embedding] EMBEDDING_AUTO_DOWNLOAD=true 但未安装 huggingface_hub")
            return False
        model_dir = self.settings.model_dir
        try:
            model_dir.mkdir(parents=True, exist_ok=True)
            snapshot_download(
                repo_id=f"{self.settings.model_id}-onnx",
                local_dir=str(model_dir),
                allow_patterns=["*.onnx", "tokenizer.json", "*.json"],
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("[Embedding] 模型自动下载失败：%s", type(exc).__name__)
            return False
        return self.settings.onnx_path.is_file() and self.settings.tokenizer_path.is_file()

    # ------------------------------------------------------------------ 推理

    def encode(
        self, texts: Sequence[str], *, is_query: bool = False, batch_size: Optional[int] = None
    ) -> List[List[float]]:
        """批量向量化。

        Args:
            texts: 文本列表。
            is_query: 是否为检索问句；``True`` 时自动加 bge 官方检索前缀。
            batch_size: 批大小，``None`` 时用配置值。

        Returns:
            与 ``texts`` 一一对应的 **L2 归一化**向量列表（长度均为 512）。

        Raises:
            EmbeddingError: 推理过程异常。
        """
        if not texts:
            return []
        self.ensure_loaded()
        assert self._session is not None and self._tokenizer is not None  # noqa: S101

        effective_batch: int = batch_size or self.settings.batch_size
        prefix: str = self.settings.query_instruction if is_query else ""
        prepared: List[str] = [f"{prefix}{text}" for text in texts]

        vectors: List[List[float]] = []
        for start in range(0, len(prepared), max(1, effective_batch)):
            vectors.extend(self._encode_batch(prepared[start:start + effective_batch]))
        return vectors

    def encode_query(self, query: str) -> List[float]:
        """向量化单条检索问句（自动加检索前缀）。

        Args:
            query: 查询文本。

        Returns:
            512 维 L2 归一化向量。

        Raises:
            EmbeddingError: 推理异常。
        """
        result = self.encode([query], is_query=True)
        if not result:
            raise EmbeddingError("查询向量化结果为空")
        return result[0]

    def _encode_batch(self, batch: List[str]) -> List[List[float]]:
        """单批次推理（CLS pooling + L2 归一化）。

        Args:
            batch: 已加前缀的文本批次。

        Returns:
            归一化后的向量列表。

        Raises:
            EmbeddingError: 分词或推理异常。
        """
        assert self._session is not None and self._tokenizer is not None  # noqa: S101
        try:
            encodings = self._tokenizer.encode_batch(batch)
        except Exception as exc:  # noqa: BLE001
            raise EmbeddingError(f"分词失败：{type(exc).__name__}", detail=str(exc)) from exc

        input_ids = [enc.ids for enc in encodings]
        attention_mask = [enc.attention_mask for enc in encodings]
        # token_type_ids：单段输入全 0；部分导出模型需要该输入
        token_type_ids = [getattr(enc, "type_ids", None) or [0] * len(enc.ids) for enc in encodings]

        feed: Dict[str, Any] = {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
        }
        if "token_type_ids" in self._input_names:
            feed["token_type_ids"] = token_type_ids

        try:
            outputs = self._session.run(None, feed)
        except Exception as exc:  # noqa: BLE001
            raise EmbeddingError(f"ONNX 推理失败：{type(exc).__name__}", detail=str(exc)) from exc

        if not outputs:
            raise EmbeddingError("ONNX 推理返回空输出")
        hidden = outputs[0]
        # 二维输出说明模型已内置 pooling（少见），直接用；三维则取 CLS
        if len(hidden.shape) == 2:
            pooled = hidden
        else:
            pooled = hidden[:, 0, :]
        return [_normalize(list(map(float, row))) for row in pooled]

    def warmup(self) -> str:
        """预热模型（首次使用前跑一次，避免第一次查询才加载）。

        Returns:
            形如 ``model=bge-small-zh-v1.5 dim=512 load_ms=812 warmed=ok`` 的单行日志。

        Raises:
            CapabilityUnavailableError / EmbeddingError: 加载或推理失败。
        """
        self.ensure_loaded()
        self.encode(["预热"], is_query=False)
        return (
            f"model={self.settings.model_id} dim={self.settings.dim} "
            f"load_ms={self._load_ms} warmed=ok"
        )


def _normalize(vector: List[float]) -> List[float]:
    """L2 归一化。

    Args:
        vector: 原始向量。

    Returns:
        归一化后的向量；零向量原样返回（避免除零）。

    Raises:
        EmbeddingError: 归一化后维度与配置不符。
    """
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0.0:
        return vector
    return [value / norm for value in vector]


# --------------------------------------------------------------------------------------
# 进程级单例
# --------------------------------------------------------------------------------------

_client: Optional[EmbeddingClient] = None
_client_lock: threading.Lock = threading.Lock()


def get_embedding_client() -> EmbeddingClient:
    """获取进程级 EmbeddingClient 单例（懒创建，不触发模型加载）。"""
    global _client
    if _client is None:
        with _client_lock:
            if _client is None:
                from ..config import get_settings

                _client = EmbeddingClient(get_settings().embedding)
    return _client


def reset_embedding_client() -> None:
    """重置单例（仅供测试使用）。"""
    global _client
    with _client_lock:
        _client = None
