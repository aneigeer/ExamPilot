"""统一错误码与异常体系。

**段位约定**：Java 侧 ``ResultCode.java`` 第 62 行已明确注释
"52xx 段由 FastAPI 侧使用"，因此本服务的错误码落在 **52xx**，
与 Java 基础段（5001-5007）和 Java 扩展段（5011-5020）均不冲突。

错误码表：

======  ==========================================  ====  =========================================
 code  含义                                        HTTP  Java 侧对应码（已落盘，仅供映射参考）
======  ==========================================  ====  =========================================
 5200  参数错误（缺字段/格式错误）                     400   400 PARAM_ERROR
 5201  AI 服务能力不可用（依赖缺失/未装）                503   5011 AI_FASTAPI_UNAVAILABLE
 5202  上游依赖超时                                   503   5012 AI_FASTAPI_TIMEOUT
 5203  向量检索失败                                   503   5013 AI_VECTOR_SEARCH_FAILED
 5204  Embedding 推理失败                              503   5014 AI_EMBEDDING_FAILED
 5205  OCR 能力不可用（未装 paddleocr / 已关闭）         503   5018 AI_OCR_UNAVAILABLE
 5206  OCR 识别失败（模型在，但识别过程出错）             503   5019 AI_OCR_RECOGNIZE_FAILED
 5207  OCR 结构化失败（PDF 无文本层 / 解析不出结构）        503   5020 AI_OCR_STRUCTURE_FAILED
 5208  向量索引不同步（model_id 不匹配）                 503   5015 AI_VECTOR_INDEX_STALE
 5209  大模型不可用（未配置 Key / 上游失败/超时）         503   5003 AI_LLM_UNAVAILABLE
 5210  对象存储不可用（**预留**，本服务不直连 MinIO）       503   5017 AI_STORAGE_UNAVAILABLE
 5299  服务内部错误                                   500   —（兜底，正常不应出现）
======  ==========================================  ====  =========================================

.. note::

   **两段码并存是刻意的**：Java 侧的 5011-5020 表达"**对用户说什么**"
   （如"已降级为全文检索"），本服务的 52xx 表达"**哪一环坏了**"。
   Java 侧按右列做一次映射即可，语义边界比混用一套码更清晰。

**三点设计说明**：

1. **OCR 拆成三码而非一码**（这是本服务与初版实现的主要差异）：
   "能力不可用"（没装依赖 —— Java 侧该提示"请手动录入"且**不该反复重试**）
   与"识别失败"（装了但这张图识别不出来 —— Java 侧该提示"换张图重试"）
   对 Java 的处理路径不同。合并成一位会让 Java 侧无法区分"重试有用"还是"重试没用"，
   熔断策略也会失准。
2. **大模型超时归 5209 而非 5202**：Java 侧只关心"大模型能不能用"，
   超时与"没配 Key"的处理路径完全一致（都走抽取式），
   细分只会让 Java 侧多写一个无意义的分支。
3. **5210 是预留位**：本服务不直连 MinIO（文件由 Java 侧上传，本服务只接收
   ``file_path`` 或 ``image_base64``），因此没有对象存储故障点。

**铁律：所有对外错误都返回统一错误体，绝不返回 500 堆栈。**

.. code-block:: json

    {"code": 5205, "message": "OCR 能力不可用：未安装 paddleocr，请降级为人工录入", "degraded": true}
"""

from __future__ import annotations

from typing import Any, Dict, Optional

# --------------------------------------------------------------------------------------
# 错误码常量
# --------------------------------------------------------------------------------------


class ErrorCode:
    """AI 微服务错误码常量（52xx 段，Java 侧已预留）。"""

    PARAM_ERROR = 5200
    CAPABILITY_UNAVAILABLE = 5201
    UPSTREAM_TIMEOUT = 5202
    VECTOR_SEARCH_FAILED = 5203
    EMBEDDING_FAILED = 5204
    OCR_UNAVAILABLE = 5205
    OCR_RECOGNIZE_FAILED = 5206
    OCR_STRUCTURE_FAILED = 5207
    VECTOR_INDEX_STALE = 5208
    LLM_UNAVAILABLE = 5209
    STORAGE_UNAVAILABLE = 5210
    INTERNAL_ERROR = 5299


#: 错误码 → 默认中文提示（异常未显式给 message 时使用）
DEFAULT_MESSAGES: Dict[int, str] = {
    ErrorCode.PARAM_ERROR: "参数错误",
    ErrorCode.CAPABILITY_UNAVAILABLE: "AI 能力不可用，请降级",
    ErrorCode.UPSTREAM_TIMEOUT: "上游依赖超时，请降级",
    ErrorCode.VECTOR_SEARCH_FAILED: "向量检索失败，请降级到全文检索",
    ErrorCode.EMBEDDING_FAILED: "向量化失败，请降级到全文检索",
    ErrorCode.OCR_UNAVAILABLE: "OCR 能力不可用，请降级为人工录入",
    ErrorCode.OCR_RECOGNIZE_FAILED: "OCR 识别失败，请人工重录",
    ErrorCode.OCR_STRUCTURE_FAILED: "OCR 结构化失败，原图已归档，请人工补录",
    ErrorCode.VECTOR_INDEX_STALE: "向量索引与当前模型不匹配，需重建",
    ErrorCode.LLM_UNAVAILABLE: "大模型不可用，请走抽取式兜底",
    ErrorCode.STORAGE_UNAVAILABLE: "对象存储不可用",
    ErrorCode.INTERNAL_ERROR: "AI 服务内部错误",
}


# --------------------------------------------------------------------------------------
# 异常体系
# --------------------------------------------------------------------------------------


class AiServiceError(Exception):
    """AI 微服务统一基类异常。

    所有路由只需 ``raise AiServiceError(...)``（或其子类），
    由 :func:`app.main.register_exception_handlers` 注册的全局处理器转成统一错误体。

    Attributes:
        code: 业务错误码（52xx 段）。
        message: 面向 Java 侧与日志的中文提示，**不包含堆栈与敏感信息**。
        http_status: HTTP 状态码，依赖不可用一律 503。
        degraded: 是否处于降级状态，Java 侧据此决定走兜底链路。
        detail: 附加诊断信息（**只写日志，不返回给调用方**）。
    """

    def __init__(
        self,
        code: int,
        message: Optional[str] = None,
        *,
        http_status: int = 503,
        degraded: bool = True,
        detail: Optional[str] = None,
    ) -> None:
        resolved = message or DEFAULT_MESSAGES.get(code, "AI 服务错误")
        super().__init__(resolved)
        self.code: int = code
        self.message: str = resolved
        self.http_status: int = http_status
        self.degraded: bool = degraded
        self.detail: Optional[str] = detail

    def to_body(self) -> Dict[str, Any]:
        """转换为统一错误响应体（字段名 Java 侧已按此对接，**不许改**）。"""
        return {
            "code": self.code,
            "message": self.message,
            "degraded": self.degraded,
        }


class CapabilityUnavailableError(AiServiceError):
    """能力不可用（缺依赖 / 未配置 / 未启用），统一 503 → Java 映射 5011。"""

    def __init__(
        self,
        message: str,
        *,
        code: int = ErrorCode.CAPABILITY_UNAVAILABLE,
        detail: Optional[str] = None,
    ) -> None:
        super().__init__(code, message, http_status=503, degraded=True, detail=detail)


class ParamError(AiServiceError):
    """参数错误，400。"""

    def __init__(self, message: str) -> None:
        super().__init__(ErrorCode.PARAM_ERROR, message, http_status=400, degraded=False)


class UpstreamTimeoutError(AiServiceError):
    """上游依赖超时，503 → Java 映射 5012。"""

    def __init__(
        self,
        message: str,
        *,
        code: int = ErrorCode.UPSTREAM_TIMEOUT,
        detail: Optional[str] = None,
    ) -> None:
        super().__init__(code, message, http_status=503, degraded=True, detail=detail)


class EmbeddingError(AiServiceError):
    """Embedding 推理失败，503 → Java 映射 5014。"""

    def __init__(self, message: str, *, detail: Optional[str] = None) -> None:
        super().__init__(
            ErrorCode.EMBEDDING_FAILED, message, http_status=503, degraded=True, detail=detail
        )


class VectorSearchError(AiServiceError):
    """向量检索失败（Qdrant 不可达/集合缺失/维度不匹配），503 → Java 映射 5013。"""

    def __init__(
        self,
        message: str,
        *,
        code: int = ErrorCode.VECTOR_SEARCH_FAILED,
        detail: Optional[str] = None,
    ) -> None:
        super().__init__(code, message, http_status=503, degraded=True, detail=detail)


class VectorIndexStaleError(VectorSearchError):
    """向量索引与当前模型不匹配（model_id 不同），需全量重建 → Java 映射 5015。"""

    def __init__(self, message: str, *, detail: Optional[str] = None) -> None:
        super().__init__(message, code=ErrorCode.VECTOR_INDEX_STALE, detail=detail)


class OcrUnavailableError(AiServiceError):
    """OCR **能力**不可用（未装 paddleocr / OCR_ENABLED=false）→ Java 映射 5018。

    ⚠️ 与 :class:`OcrRecognizeFailedError` 严格区分：
    本异常意味着"**重试多少次都没用**"，Java 侧应停止重试并长期熔断；
    而识别失败意味着"**换张图可能有用**"。
    """

    def __init__(self, message: str, *, detail: Optional[str] = None) -> None:
        super().__init__(
            ErrorCode.OCR_UNAVAILABLE, message, http_status=503, degraded=True, detail=detail
        )


class OcrRecognizeFailedError(AiServiceError):
    """OCR **识别**失败（模型在，但识别过程出错）→ Java 映射 5019。"""

    def __init__(self, message: str, *, detail: Optional[str] = None) -> None:
        super().__init__(
            ErrorCode.OCR_RECOGNIZE_FAILED, message, http_status=503, degraded=True, detail=detail
        )


class OcrStructureError(AiServiceError):
    """OCR **结构化**失败（PDF 无文本层 / 解析不出题目结构）→ Java 映射 5020。"""

    def __init__(self, message: str, *, detail: Optional[str] = None) -> None:
        super().__init__(
            ErrorCode.OCR_STRUCTURE_FAILED, message, http_status=503, degraded=True, detail=detail
        )


class LlmUnavailableError(AiServiceError):
    """大模型不可用（未配置 Key / 上游失败 / 超时），503 → Java 映射 5003。"""

    def __init__(
        self,
        message: str,
        *,
        code: int = ErrorCode.LLM_UNAVAILABLE,
        detail: Optional[str] = None,
    ) -> None:
        super().__init__(code, message, http_status=503, degraded=True, detail=detail)
