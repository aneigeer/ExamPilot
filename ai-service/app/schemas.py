"""Pydantic 请求 / 响应模型 —— **与 Java 侧接口契约严格对应**。

⚠️ 字段名一旦定下**不许改**。Java 侧 ``FastApiClient``（T09）按本文件字段名解析。

契约速查（与 docs/AI微服务扩展设计方案.md 章节 C.1 对齐）：

===========================  ==========================================================
 路由                         请求 → 响应
===========================  ==========================================================
 ``GET  /v1/health``          无 → ``{status, embedding:{ready,dim}, qdrant:{ready}, ocr:{ready}}``
 ``POST /v1/embed``          ``{texts:[str]}`` → ``{dim, vectors:[[float]]}``
 ``POST /v1/search``         ``{query, top_k, filters:{subject_id?,knowledge_id?,source_type?}}``
                             → ``{hits:[{chunk_id, score, payload}], took_ms}``
 ``POST /v1/ocr``            ``{file_path|image_base64, question_type_hint?}``
                             → ``{blocks:[{type,text,bbox}], confidence}``
 ``POST /v1/llm``            ``{question, contexts:[str], max_tokens?}``
                             → ``{answer, sources, generator, cost_ms}``
 ``POST /v1/index/sync``     ``{chunks:[...]}`` → ``{synced, skipped, failed}``
 ``POST /v1/index/rebuild``  ``{model_id?, dim?}`` → ``{status, collection}``
 ``GET  /v1/index/status``   无 → ``{collection, model_id, dim, chunk_total, chunk_synced, status}``
===========================  ==========================================================
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator

# --------------------------------------------------------------------------------------
# 通用
# --------------------------------------------------------------------------------------


class ErrorResponse(BaseModel):
    """统一错误体。

    ⚠️ 本服务**所有** 4xx/5xx 响应都是这个结构，Java 侧不再二次包装。
    """

    code: int = Field(description="业务错误码，52xx 段（映射 Java ResultCode）")
    message: str = Field(description="中文提示，不含堆栈与敏感信息")
    degraded: bool = Field(description="是否降级状态；true 时 Java 侧走兜底链路")


# --------------------------------------------------------------------------------------
# 健康检查
# --------------------------------------------------------------------------------------


class ComponentHealth(BaseModel):
    """单个能力组件的健康状态。"""

    ready: bool = Field(description="是否可用；false 表示该能力缺失或依赖未就绪")
    detail: str = Field(default="", description="诊断信息，仅用于排障，不含堆栈")


class EmbeddingHealth(ComponentHealth):
    """Embedding 能力健康状态（额外带向量维度）。"""

    dim: int = Field(default=0, description="向量维度；未就绪时为 0")
    model_id: str = Field(default="", description="模型标识；换模型需重建索引")


class HealthResponse(BaseModel):
    """``GET /v1/health`` 响应。"""

    status: str = Field(description="ok / degraded；全部就绪为 ok")
    embedding: EmbeddingHealth = Field(description="Embedding 能力状态")
    qdrant: ComponentHealth = Field(description="向量库状态")
    ocr: ComponentHealth = Field(description="OCR 能力状态")
    llm: ComponentHealth = Field(description="大模型代理状态")
    version: str = Field(default="", description="服务版本号")


# --------------------------------------------------------------------------------------
# Embedding
# --------------------------------------------------------------------------------------


class EmbedRequest(BaseModel):
    """``POST /v1/embed`` 请求。"""

    texts: List[str] = Field(description="待向量化文本列表，单次最多 64 条")

    @field_validator("texts")
    @classmethod
    def _validate_texts(cls, value: List[str]) -> List[str]:
        """校验文本列表：非空、单条非空白、条数上限 64。"""
        if not value:
            raise ValueError("texts 不能为空列表")
        if len(value) > 64:
            raise ValueError("texts 单次最多 64 条")
        cleaned: List[str] = []
        for index, item in enumerate(value):
            if not isinstance(item, str) or not item.strip():
                raise ValueError(f"texts[{index}] 不能为空字符串")
            cleaned.append(item)
        return cleaned


class EmbedResponse(BaseModel):
    """``POST /v1/embed`` 响应。"""

    dim: int = Field(description="向量维度，bge-small-zh-v1.5 固定 512")
    vectors: List[List[float]] = Field(description="与 texts 一一对应的 L2 归一化向量")
    took_ms: int = Field(default=0, description="推理耗时（毫秒）")


# --------------------------------------------------------------------------------------
# 向量检索
# --------------------------------------------------------------------------------------


class SearchFilters(BaseModel):
    """payload 过滤条件，对应 Qdrant payload 字段。

    三个条件均为可选，AND 组合；全部为空即不过滤。
    """

    subject_id: Optional[int] = Field(default=None, description="科目 ID")
    knowledge_id: Optional[int] = Field(default=None, description="知识点 ID")
    source_type: Optional[str] = Field(default=None, description="来源类型 QUESTION/RESOURCE/KNOWLEDGE")
    status: Optional[str] = Field(default=None, description="语料状态，固定 ACTIVE")

    def to_qdrant_filter(self) -> Optional[Dict[str, Any]]:
        """转换为 Qdrant filter 结构；无条件时返回 ``None``。

        Returns:
            Qdrant REST filter dict；无条件时 ``None``。
        """
        must: List[Dict[str, Any]] = []
        for key in ("subject_id", "knowledge_id", "source_type", "status"):
            value = getattr(self, key)
            if value is not None:
                must.append({"key": key, "match": {"value": value}})
        if not must:
            return None
        return {"must": must}


class SearchRequest(BaseModel):
    """``POST /v1/search`` 请求。"""

    query: str = Field(description="查询文本，内部自动加 bge 检索问句前缀")
    top_k: int = Field(default=10, ge=1, le=100, description="返回条数，1-100")
    filters: SearchFilters = Field(default_factory=SearchFilters, description="payload 过滤条件")

    @field_validator("query")
    @classmethod
    def _validate_query(cls, value: str) -> str:
        """查询词不能为空白。"""
        if not value or not value.strip():
            raise ValueError("query 不能为空")
        return value


class SearchHit(BaseModel):
    """单条检索命中。"""

    chunk_id: int = Field(description="ai_chunk 主键，Java 侧据此回表取正文")
    score: float = Field(description="余弦相似度，范围 [-1, 1]，越大越相关")
    payload: Dict[str, Any] = Field(
        default_factory=dict,
        description="Qdrant payload 元数据（不含正文；正文在 MySQL ai_chunk）",
    )


class SearchResponse(BaseModel):
    """``POST /v1/search`` 响应。"""

    hits: List[SearchHit] = Field(default_factory=list, description="命中列表，按 score 降序")
    took_ms: int = Field(default=0, description="检索耗时（毫秒），含 embedding 推理")
    dim: int = Field(default=0, description="查询向量维度")


# --------------------------------------------------------------------------------------
# 索引同步 / 重建 / 状态
# --------------------------------------------------------------------------------------


class IndexChunk(BaseModel):
    """待向量化的语料块（由 Java ``ChunkSyncService`` 推送）。

    正文 ``content`` 只用于算向量，**不写入 payload**（正文唯一来源是 MySQL）。
    """

    chunk_id: int = Field(description="ai_chunk 主键")
    content: str = Field(description="切块后的检索文本")
    source_type: str = Field(default="", description="QUESTION / RESOURCE / KNOWLEDGE")
    source_id: Optional[int] = Field(default=None, description="来源业务 ID")
    title: str = Field(default="", description="来源标题")
    subject_id: Optional[int] = Field(default=None, description="科目 ID")
    knowledge_id: Optional[int] = Field(default=None, description="知识点 ID")
    difficulty: Optional[int] = Field(default=None, description="难度 1-5")
    ref_type: str = Field(default="", description="跳转类型")
    ref_id: Optional[int] = Field(default=None, description="跳转业务 ID")
    page_no: Optional[int] = Field(default=None, description="PDF 页码")
    status: str = Field(default="ACTIVE", description="语料状态")
    content_hash: str = Field(default="", description="内容哈希，用于幂等去重")
    source_updated_at: Optional[str] = Field(default=None, description="来源更新时间 ISO 格式")
    hot_count: int = Field(default=0, description="热度")


class IndexSyncRequest(BaseModel):
    """``POST /v1/index/sync`` 请求。"""

    chunks: List[IndexChunk] = Field(description="待同步语料块列表，单次最多 500 条")

    @field_validator("chunks")
    @classmethod
    def _validate_chunks(cls, value: List[IndexChunk]) -> List[IndexChunk]:
        """校验 chunk 列表上限 500 条，避免单请求过大。"""
        if len(value) > 500:
            raise ValueError("chunks 单次最多 500 条")
        return value


class IndexSyncResponse(BaseModel):
    """``POST /v1/index/sync`` 响应（Java 侧据此置 ``vector_synced``）。"""

    synced: int = Field(default=0, description="成功写入条数")
    skipped: int = Field(default=0, description="因 content_hash 未变化而跳过条数")
    failed: int = Field(default=0, description="失败条数（Java 侧置 vector_synced=2 待重试）")
    took_ms: int = Field(default=0, description="耗时（毫秒）")


class IndexRebuildRequest(BaseModel):
    """``POST /v1/index/rebuild`` 请求。"""

    model_id: Optional[str] = Field(default=None, description="目标模型标识；缺省用配置值")
    dim: Optional[int] = Field(default=None, ge=1, description="目标维度；缺省用配置值")


class IndexRebuildResponse(BaseModel):
    """``POST /v1/index/rebuild`` 响应。"""

    status: str = Field(description="READY / FAILED")
    collection: str = Field(description="重建后的 collection 名")
    model_id: str = Field(description="模型标识")
    dim: int = Field(description="向量维度")
    message: str = Field(default="", description="诊断信息")


class IndexStatusResponse(BaseModel):
    """``GET /v1/index/status`` 响应（与 MySQL ``ai_vector_index_meta`` 对账用）。"""

    collection: str = Field(description="collection 名")
    model_id: str = Field(description="collection 内记录的模型标识")
    dim: int = Field(description="collection 维度")
    chunk_synced: int = Field(default=0, description="Qdrant 实际点数")
    config_model_id: str = Field(default="", description="当前配置的模型标识")
    status: str = Field(
        description="READY / MODEL_MISMATCH / COLLECTION_MISSING / ERROR；非 READY 时 Java 侧应触发重建"
    )
    message: str = Field(default="", description="诊断信息")


# --------------------------------------------------------------------------------------
# OCR
# --------------------------------------------------------------------------------------


class OcrRequest(BaseModel):
    """``POST /v1/ocr`` 请求。

    ``file_path`` 与 ``image_base64`` **二选一**，都不给则 400。
    """

    file_path: Optional[str] = Field(default=None, description="图片绝对路径（仅本机/容器内可读）")
    image_base64: Optional[str] = Field(default=None, description="图片 base64（不含 data URI 前缀亦可）")
    question_type_hint: Optional[str] = Field(default=None, description="题型提示，如 SINGLE_CHOICE")
    page_from: int = Field(default=1, ge=1, description="PDF 起始页")
    page_to: Optional[int] = Field(default=None, ge=1, description="PDF 结束页，默认到末页")

    def has_source(self) -> bool:
        """是否提供了可识别的图片来源。"""
        return bool((self.file_path and self.file_path.strip())
                    or (self.image_base64 and self.image_base64.strip()))


class OcrBlock(BaseModel):
    """OCR 识别出的一个文本块。"""

    type: str = Field(description="块类型：stem 题干 / option 选项 / answer 答案 / unknown")
    text: str = Field(default="", description="识别文本")
    bbox: Optional[List[float]] = Field(
        default=None, description="外接框 [x1, y1, x2, y2]，坐标为原图像素"
    )
    confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="块级置信度 0-1")


class OcrResponse(BaseModel):
    """``POST /v1/ocr`` 响应。

    **本服务只负责"识别 + 分块 + 标注置信度"，不写题库**；
    结果进 ``question.audit_status='DRAFT'`` 由人工审核（复用既有审核流）。
    """

    blocks: List[OcrBlock] = Field(default_factory=list, description="识别出的文本块")
    confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="整页平均置信度")
    page_no: int = Field(default=1, description="页码")
    took_ms: int = Field(default=0, description="耗时（毫秒）")
    #: 低于该阈值时 Java 侧应判为"需人工重录"，不入库
    stem_confidence_threshold: float = Field(default=0.75, description="题干置信度阈值")
    need_manual_input: bool = Field(
        default=True, description="是否建议人工确认（G1 置信度分流，恒为 true：永不自动入库）"
    )


# --------------------------------------------------------------------------------------
# 大模型代理
# --------------------------------------------------------------------------------------


class LlmRequest(BaseModel):
    """``POST /v1/llm`` 请求。"""

    question: str = Field(description="用户问题")
    contexts: List[str] = Field(default_factory=list, description="检索到的语料片段，作为 RAG 上下文")
    max_tokens: Optional[int] = Field(default=None, ge=1, le=8192, description="最大生成 token 数")
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0, description="采样温度")

    @field_validator("question")
    @classmethod
    def _validate_question(cls, value: str) -> str:
        """问题不能为空白。"""
        if not value or not value.strip():
            raise ValueError("question 不能为空")
        return value


class LlmSource(BaseModel):
    """溯源引用（与 Java ``SourceRef`` 语义对齐）。"""

    chunk_id: Optional[int] = Field(default=None, description="语料块 ID；本服务无正文故可能为空")
    index: int = Field(default=0, description="对应 contexts 的下标（1 起），对应答案中的 [n] 标注")
    snippet: str = Field(default="", description="命中的上下文片段")


class LlmResponse(BaseModel):
    """``POST /v1/llm`` 响应。"""

    answer: str = Field(default="", description="生成正文，引用标注形如 [1][2]")
    sources: List[LlmSource] = Field(default_factory=list, description="溯源列表，无来源时为空 list")
    generator: str = Field(description="生成器标识，如 glm；不可用时接口 503，Java 走抽取式")
    cost_ms: int = Field(default=0, description="生成耗时（毫秒）")
    degraded: bool = Field(default=False, description="是否降级；本服务降级即 503，此处恒 false")
