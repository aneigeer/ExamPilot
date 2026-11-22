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

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator

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

    ready: bool = Field(
        description="能力是否可用（依赖已装 + 资源存在）。false 时 Java 侧应熔断降级"
    )
    detail: str = Field(default="", description="诊断信息，仅用于排障，不含堆栈")


class EmbeddingHealth(ComponentHealth):
    """Embedding 能力健康状态（额外带向量维度与加载态）。"""

    dim: int = Field(default=0, description="向量维度；不可用时为 0")
    model_id: str = Field(default="", description="模型标识；换模型需重建索引")
    loaded: bool = Field(
        default=False,
        description=(
            "模型是否已在本进程内加载。"
            "ready=true 且 loaded=false 表示「可用但冷启动未加载」，"
            "首次 /v1/embed 约需 0.2-1s，**Java 侧不应据此降级**"
        ),
    )


class SecurityHealth(BaseModel):
    """安全姿态（**只有布尔状态，绝不包含令牌值/长度/前缀**）。

    字段名按契约 ``docs/Agent阶段三接口契约.md`` §3 A11 固定为 camelCase
    （Java 侧 ``/ai/capability`` 按此解析），因此这里用 alias 而不是默认 snake_case。

    Attributes:
        internal_token_configured: ``AI_INTERNAL_TOKEN`` 是否非空。
        agent_auth_required: 是否强制校验令牌
            （``internal_token_configured && !anonymous_allowed``）。
        anonymous_allowed: ``AGENT_ALLOW_ANONYMOUS`` 是否为真。
    """

    model_config = ConfigDict(populate_by_name=True)

    internal_token_configured: bool = Field(
        default=False,
        alias="internalTokenConfigured",
        description="是否配置了 AI_INTERNAL_TOKEN（绝不返回令牌值本身）",
    )
    agent_auth_required: bool = Field(
        default=False,
        alias="agentAuthRequired",
        description="是否强制校验内部令牌 = 已配置令牌 且 未开匿名放行",
    )
    anonymous_allowed: bool = Field(
        default=False,
        alias="anonymousAllowed",
        description="是否显式关闭了 /v1/agent/* 鉴权（AGENT_ALLOW_ANONYMOUS）；true 仅限本地开发",
    )


class HealthResponse(BaseModel):
    """``GET /v1/health`` 响应。"""

    status: str = Field(description="ok / degraded；全部就绪为 ok")
    embedding: EmbeddingHealth = Field(description="Embedding 能力状态")
    qdrant: ComponentHealth = Field(description="向量库状态")
    ocr: ComponentHealth = Field(description="OCR 能力状态")
    llm: ComponentHealth = Field(description="大模型代理状态")
    version: str = Field(default="", description="服务版本号")
    #: 带默认值：老调用方（只读四段能力）不会因新增字段而反序列化失败；
    #: **不参与** all_ready / status 判定（安全姿态不是"能力不可用"）
    security: SecurityHealth = Field(
        default_factory=SecurityHealth, description="安全姿态（内部令牌是否配置等布尔状态）"
    )


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
    top_k: int = Field(
        default=10,
        ge=1,
        le=100,
        validation_alias=AliasChoices("top_k", "topK"),
        description="返回条数，1-100（接口实际返回上限 50；兼容 top_k / topK 两种写法）",
    )
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
    """``POST /v1/index/sync`` 响应（Java 侧据此置 ``vector_synced``）。

    ★ ``failed`` 拆成两类，语义不同、处置也不同：

    * ``rejected``：**写入前**被判非法（NaN/Inf/零范数）而拒收，**未进库**；
    * ``mismatched``：**写入后校准**读回的向量与输入不一致 —— 已进库但存错了，
      说明向量库写入异常，必须置 ``vector_synced=2`` 重试，
      反复出现则需删除向量库目录全量重建。

    带上 ``*_ids`` 是为了让 Java 侧能**精确**把出问题的块置为失败态，
    而不是把整批 500 条一起重推。
    """

    synced: int = Field(default=0, description="成功写入且写入后校准通过的条数")
    skipped: int = Field(default=0, description="因 content_hash 未变化而跳过条数")
    failed: int = Field(default=0, description="失败条数 = rejected + mismatched")
    rejected: int = Field(default=0, description="写入前校验被拒收的条数（未进库）")
    mismatched: int = Field(default=0, description="写入后校准不通过的条数（已进库但存错了）")
    rejected_ids: List[int] = Field(default_factory=list, description="被拒收的 chunk_id")
    mismatched_ids: List[int] = Field(default_factory=list, description="校准不通过的 chunk_id")
    took_ms: int = Field(default=0, description="耗时（毫秒）")


class IndexDeleteRequest(BaseModel):
    """``POST /v1/index/delete`` 请求（来源失效后清理孤儿向量）。

    MySQL 侧 ``ai_chunk`` 被置 ``INVALID`` 后，Qdrant 里的向量点不会自动消失；
    且它的 ``payload.status`` 是**入库时的快照**（``ACTIVE``），
    因此 ``/v1/search`` 的 ``status='ACTIVE'`` 过滤拦不住它，
    孤儿向量会挤占 top-K 名额、把有效结果挤出去。故必须显式删除。
    """

    chunk_ids: List[int] = Field(description="待删除的 ai_chunk 主键，单次最多 500 条")

    @field_validator("chunk_ids")
    @classmethod
    def _validate_ids(cls, value: List[int]) -> List[int]:
        """校验单次最多 500 条，避免单请求过大。"""
        if len(value) > 500:
            raise ValueError("chunk_ids 单次最多 500 条")
        return value


class IndexDeleteResponse(BaseModel):
    """``POST /v1/index/delete`` 响应。"""

    deleted: int = Field(
        default=0,
        description="已发起删除的向量点条数（Qdrant 不返回逐点结果，故等价于 requested）",
    )
    requested: int = Field(default=0, description="请求删除条数")


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
# Wiki 热层（独立 collection，与 RAG 语料向量互相隔离）
# --------------------------------------------------------------------------------------


class WikiIndexPage(BaseModel):
    """待索引的 Wiki 页面（只送标题 / 摘要 / 要点，正文仍以 MySQL 为准）。"""

    page_id: int = Field(description="wiki_page 主键")
    title: str = Field(default="", description="主题标题")
    summary: str = Field(description="页面摘要，向量化主文本")
    key_points: List[str] = Field(default_factory=list, description="要点列表（拼进摘要一起向量化）")
    subject_id: Optional[int] = Field(default=None, description="科目 ID")
    knowledge_id: Optional[int] = Field(default=None, description="知识点 ID")
    version: Optional[int] = Field(default=None, description="发布版本，便于排障")


class WikiIndexSyncRequest(BaseModel):
    """``POST /v1/wiki/index/sync`` 请求。"""

    pages: List[WikiIndexPage] = Field(description="待索引页面，单次最多 200 条")

    @field_validator("pages")
    @classmethod
    def _validate_pages(cls, value: List[WikiIndexPage]) -> List[WikiIndexPage]:
        if len(value) > 200:
            raise ValueError("pages 单次最多 200 条")
        return value


class WikiIndexSyncResponse(BaseModel):
    """``POST /v1/wiki/index/sync`` 响应。"""

    synced: int = Field(default=0, description="成功写入条数")
    skipped: int = Field(default=0, description="空摘要等被跳过条数")
    failed: int = Field(default=0, description="写入失败条数")
    took_ms: int = Field(default=0, description="耗时（毫秒）")


class WikiDeleteRequest(BaseModel):
    """``POST /v1/wiki/index/delete`` 请求。"""

    page_ids: List[int] = Field(description="待删除的 wiki_page 主键，单次最多 500 条")


class WikiDeleteResponse(BaseModel):
    """``POST /v1/wiki/index/delete`` 响应。"""

    deleted: int = Field(default=0, description="实际删除条数")
    requested: int = Field(default=0, description="请求删除条数")


class WikiSearchRequest(BaseModel):
    """``POST /v1/wiki/search`` 请求。"""

    query: str = Field(description="用户问题")
    top_k: int = Field(default=5, ge=1, le=20, description="返回条数")
    filters: Dict[str, Any] = Field(default_factory=dict, description="过滤条件：subject_id")

    @field_validator("query")
    @classmethod
    def _validate_query(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("query 不能为空")
        return value


class WikiSearchHit(BaseModel):
    """单条 Wiki 页面命中。"""

    page_id: int = Field(description="wiki_page 主键")
    score: float = Field(description="余弦相似度，范围 [-1, 1]")
    payload: Dict[str, Any] = Field(default_factory=dict, description="页面元数据（不含正文）")


class WikiSearchResponse(BaseModel):
    """``POST /v1/wiki/search`` 响应。"""

    hits: List[WikiSearchHit] = Field(default_factory=list, description="命中列表，按 score 降序")
    took_ms: int = Field(default=0, description="检索耗时（毫秒）")
    dim: int = Field(default=0, description="查询向量维度")


# --------------------------------------------------------------------------------------
# OCR
# --------------------------------------------------------------------------------------


class OcrRequest(BaseModel):
    """``POST /v1/ocr`` 请求。

    ``file_path`` 与 ``image_base64`` **二选一**，都不给则 400。
    """

    file_path: Optional[str] = Field(default=None, description="图片绝对路径（仅本机/容器内可读）")
    image_base64: Optional[str] = Field(default=None, max_length=14_000_000, description="图片 base64（不含 data URI 前缀亦可）；A-09 约 10 MB 二进制上限")
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


# --------------------------------------------------------------------------------------
# Agent Harness
# --------------------------------------------------------------------------------------


class AgentProfile(BaseModel):
    """离线评估注入的模拟画像（不读真实用户）。"""

    model_config = ConfigDict(populate_by_name=True)

    weak_knowledge_ids: List[int] = Field(
        default_factory=list, alias="weakKnowledgeIds", description="薄弱知识点 ID"
    )
    subject_id: Optional[int] = Field(default=None, alias="subjectId")


class AgentTurn(BaseModel):
    """一轮历史问答（Java 从 ai_agent_session / ai_agent_run 读取）。"""

    question: str = Field(default="", description="用户问题")
    answer: str = Field(default="", description="助手答案（Java 已截断到 300 字）")


#: ``maxSteps`` 允许范围（含端点，契约 §3 A8）。
#:
#: 下限 1：0 步的会话没有意义（harness 内部 ``max(1, ...)`` 会静默改语义）。
#: 上限 ``MAX_STEPS_LIMIT=12``：每步都可能是一次真实 LLM 调用，放开了等于把
#: "单次会话时长"交给调用方；12 步已覆盖最复杂的"检索→查题→复盘→作答"链路。
MAX_STEPS_MIN: int = 1
MAX_STEPS_LIMIT: int = 12


class AgentRequest(BaseModel):
    """``POST /v1/agent/stream`` / ``/v1/agent/run`` 请求。"""

    model_config = ConfigDict(populate_by_name=True)

    question: str = Field(description="学生问题")
    user_id: Optional[int] = Field(default=None, alias="userId", description="Java 透传的用户 ID")
    subject_id: Optional[int] = Field(default=None, alias="subjectId")
    exam_category_id: Optional[int] = Field(default=None, alias="examCategoryId")
    trace_id: Optional[str] = Field(default=None, alias="traceId")
    max_steps: Optional[int] = Field(
        default=None,
        alias="maxSteps",
        description=(
            f"覆盖最大步数，允许 {MAX_STEPS_MIN}..{MAX_STEPS_LIMIT}；"
            "不传则沿用 AGENT_MAX_STEPS（默认 6）"
        ),
    )
    profile: Optional[AgentProfile] = Field(default=None, description="离线评估用模拟画像")
    session_id: Optional[str] = Field(
        default=None, alias="sessionId", max_length=32, description="会话 ID（跨轮次记忆）"
    )
    history: List[AgentTurn] = Field(
        default_factory=list, description="最近 N 轮问答（时间正序）"
    )
    approved_tools: List[str] = Field(
        default_factory=list,
        alias="approvedTools",
        description="已通过人工审批的高风险工具白名单（仅 Java 侧审批恢复时透传）",
    )
    memory_summary: Optional[str] = Field(
        default=None, alias="memorySummary", description="Java 维护的滚动摘要"
    )

    @field_validator("question")
    @classmethod
    def _question_not_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("question 不能为空")
        if len(value) > 200:
            raise ValueError("question 过长（上限 200 字）")
        return value.strip()

    @field_validator("history")
    @classmethod
    def _history_limit(cls, value: List[AgentTurn]) -> List[AgentTurn]:
        if len(value) > 10:
            raise ValueError("history 单次最多 10 轮")
        return value

    @field_validator("max_steps")
    @classmethod
    def _max_steps_in_range(cls, value: Optional[int]) -> Optional[int]:
        """约束 ``maxSteps`` 只接受 1..12；不传（``None``）表示"用配置默认值"。

        **为什么校验放在 schema 而不是路由/``_harness``**：
        ``/v1/agent/run`` 与 ``/v1/agent/stream`` 共用同一个请求模型，schema 校验由
        FastAPI 在**进入路由函数之前**执行 —— 一处声明即可覆盖两条链路，且失败自动
        走全局 ``RequestValidationError`` 处理器（400 + code=5200，统一错误体），
        不需要在每个路由里重复写一遍校验分支。

        **"未传" ≠ "传 0"**：``None`` 走默认值 6；``0`` 是非法值直接 400。

        Raises:
            ValueError: 越界（含 0 与负数）。pydantic 会转成 400/5200。
        """
        if value is None:
            return None
        if value < MAX_STEPS_MIN or value > MAX_STEPS_LIMIT:
            # 文案只写后半句：handler 会拼成
            # "参数错误：maxSteps 必须在 1..12 之间（当前 0）"
            raise ValueError(
                f"必须在 {MAX_STEPS_MIN}..{MAX_STEPS_LIMIT} 之间（当前 {value}）"
            )
        return value


class AgentRunResponse(BaseModel):
    """``POST /v1/agent/run`` 非流式响应（评估/调试用）。"""

    answer: str = Field(default="")
    generator: str = Field(default="none")
    degraded: bool = Field(default=True)
    refused: bool = Field(default=False)
    step_count: int = Field(default=0)
    tool_calls: int = Field(default=0)
    events: List[Dict[str, Any]] = Field(default_factory=list, description="完整事件数组")
