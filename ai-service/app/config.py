"""AI 微服务配置。

设计要点：
1. **零第三方依赖**：只用标准库 ``os`` 读取环境变量，``.env`` 文件由本模块自行解析。
   这样即使 ``pydantic-settings`` 未安装，配置模块依然可用（避免因配置层崩溃导致全服务不可用）。
2. **全部字段有默认值**：任何环境变量缺失都不会抛异常，只会让对应能力标记为 ``ready=false``。
3. **敏感信息（API Key）绝不写日志**：``__repr__`` / ``safe_dump()`` 中做脱敏。

环境变量清单见 ``.env.example`` 与 README。
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Mapping, Optional

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------------------
# 服务基础路径
# --------------------------------------------------------------------------------------

#: ai-service 目录（本文件位于 ai-service/app/config.py）
SERVICE_ROOT: Path = Path(__file__).resolve().parent.parent

#: 默认模型目录：模型文件放这里，.gitignore 已排除大文件
DEFAULT_MODELS_DIR: Path = SERVICE_ROOT / "models"

#: 默认环境变量文件
DEFAULT_ENV_FILE: Path = SERVICE_ROOT / ".env"


def load_dotenv(env_file: Optional[Path] = None) -> Dict[str, str]:
    """极简 ``.env`` 解析器（避免引入 python-dotenv 依赖）。

    支持 ``KEY=VALUE``、``#`` 注释、空行、以及成对的引号包裹。
    **不会覆盖已存在的真实环境变量**（容器部署时以环境变量为准）。

    Args:
        env_file: ``.env`` 文件路径；``None`` 时使用默认路径，文件不存在则静默跳过。

    Returns:
        实际被载入的 ``{key: value}`` 映射（未写回 os.environ，由调用方决定）。
    """
    path = env_file or DEFAULT_ENV_FILE
    loaded: Dict[str, str] = {}
    if not path.is_file():
        return loaded
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError:
        return loaded
    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if key:
            loaded[key] = value
    return loaded


def _get(env: Mapping[str, str], key: str, default: str = "") -> str:
    """从 ``os.environ`` 取值，缺失时回退到 ``.env`` 解析结果，再回退默认值。"""
    value = os.environ.get(key)
    if value is not None and value.strip() != "":
        return value.strip()
    return env.get(key, default).strip() or default


def _get_int(env: Mapping[str, str], key: str, default: int) -> int:
    """读取整型环境变量，非法值回退默认（不抛异常，保证服务能起）。"""
    raw = _get(env, key, "")
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _get_float(env: Mapping[str, str], key: str, default: float) -> float:
    """读取浮点型环境变量，非法值回退默认。"""
    raw = _get(env, key, "")
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _get_bool(env: Mapping[str, str], key: str, default: bool) -> bool:
    """读取布尔型环境变量，接受 ``1/true/yes/on`` 及其大写形式。"""
    raw = _get(env, key, "").lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")


# --------------------------------------------------------------------------------------
# 配置数据类
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class EmbeddingSettings:
    """Embedding（bge-small-zh-v1.5 ONNX INT8）相关配置。

    Attributes:
        models_dir: 模型根目录，内含各模型子目录。
        model_id: 模型标识，写入 Qdrant payload；换模型必须全量重建索引。
        onnx_file: ONNX 权重文件名（INT8 量化版约 28MB）。
        tokenizer_file: HuggingFace tokenizer.json。
        dim: 输出维度，bge-small-zh-v1.5 固定 512。
        max_length: 分词最大长度，本项目语料为 400 字内短文本，512 足够。
        batch_size: 单次批量推理条数。
        intra_op_num_threads: ONNX 线程数，默认 0 表示由 onnxruntime 自行决定。
        query_instruction: bge 官方推荐的检索问句前缀（提升召回质量）。
        auto_download: 模型缺失时是否尝试从 HuggingFace 自动下载。
    """

    models_dir: Path = DEFAULT_MODELS_DIR
    model_id: str = "bge-small-zh-v1.5"
    onnx_file: str = "model.onnx"
    tokenizer_file: str = "tokenizer.json"
    dim: int = 512
    max_length: int = 512
    batch_size: int = 8
    intra_op_num_threads: int = 0
    query_instruction: str = "为这个句子生成表示以用于检索相关文章："
    auto_download: bool = False

    @property
    def model_dir(self) -> Path:
        """当前模型的完整目录：``{models_dir}/{model_id}/``。"""
        return self.models_dir / self.model_id

    @property
    def onnx_path(self) -> Path:
        """ONNX 权重文件完整路径。"""
        return self.model_dir / self.onnx_file

    @property
    def tokenizer_path(self) -> Path:
        """tokenizer 文件完整路径。"""
        return self.model_dir / self.tokenizer_file


@dataclass(frozen=True)
class QdrantSettings:
    """Qdrant 向量库配置（地址来自 docker-compose.ai.yml 暴露的 6333）。"""

    url: str = "http://127.0.0.1:6333"
    api_key: str = ""
    collection: str = "exam_chunks"
    dim: int = 512
    timeout_seconds: float = 5.0
    prefer_grpc: bool = False


@dataclass(frozen=True)
class LlmSettings:
    """大模型代理配置（GLM，OpenAI 兼容协议）。

    ``api_key`` 为空即视为"未配置"，``/v1/llm`` 返回 503，Java 侧走抽取式兜底。
    """

    api_key: str = ""
    base_url: str = "https://open.bigmodel.cn/api/paas/v4"
    model: str = "glm-4-flash"
    temperature: float = 0.3
    max_tokens: int = 1500
    timeout_seconds: float = 30.0
    #: 模型标识，写入响应的 ``generator`` 字段，便于 Java 侧区分生成器
    generator_name: str = "glm"


@dataclass(frozen=True)
class OcrSettings:
    """OCR 配置（PaddleOCR 懒加载）。

    ``enabled=False`` 时 ``/v1/ocr`` 直接 503，不做任何 import。
    """

    enabled: bool = True
    lang: str = "ch"
    model_dir: Path = DEFAULT_MODELS_DIR / "paddle"
    use_angle_cls: bool = False
    drop_score: float = 0.3
    timeout_seconds: float = 60.0
    #: 低于该置信度时 Java 侧应判为"需人工重录"
    stem_confidence_threshold: float = 0.75
    #: 低于该置信度的字段留空并高亮，强制人工补全
    field_confidence_threshold: float = 0.90


@dataclass(frozen=True)
class AgentSettings:
    """Agent Harness 配置。

    Java 是 SSE 入口与工具数据提供方；本服务负责规划/工具循环。

    **鉴权姿态（fail-closed）**：``internal_token`` 为空时 ``/v1/agent/*`` 一律返回 401，
    唯一例外是显式打开 ``allow_anonymous``（``AGENT_ALLOW_ANONYMOUS=1``，仅限本地开发）。
    """

    #: 是否启用 Agent 路由（默认启用，Java 侧另有总开关）
    enabled: bool = True
    #: Java 后端地址（含 context-path /api）
    java_base_url: str = "http://127.0.0.1:8080/api"
    #: 内部接口令牌（与 Java app.ai.internal-token 一致）；为空即"未配置"→ /v1/agent/* 401
    internal_token: str = ""
    #: 是否允许匿名访问 ``/v1/agent/*``（关闭鉴权的逃生舱，**仅限本地开发**，默认关闭）
    allow_anonymous: bool = False
    #: 单次会话最大步数
    max_steps: int = 6
    #: 单次会话最大工具调用次数
    max_tool_calls: int = 4
    #: prompt 字符预算（≈8k tokens）
    max_prompt_chars: int = 8000
    #: 单次工具调用超时（秒）
    tool_timeout_seconds: float = 10.0
    #: 规划/生成 LLM 超时（秒）
    llm_timeout_seconds: float = 15.0
    #: 会话级 wall-clock deadline（秒）；必须小于 Java SSE 总超时 120s
    session_timeout_seconds: float = 120.0
    #: ★ 受控实验：subagent（拆解 → 逐个子问题 → 汇总）。
    #:
    #: **默认 False**。关闭时执行路径与事件序列跟"单 Agent"版本逐字节一致，
    #: 不新增任何事件、不新增任何一次 LLM 调用、不新增任何可测量延迟。
    subagent_enabled: bool = False
    #: 拆解出的子问题个数上限（硬上限 :data:`SUBAGENT_MAX_QUESTIONS_LIMIT`，越界启动时 clamp）。
    subagent_max_questions: int = 3


@dataclass(frozen=True)
class WikiSettings:
    """LLM Wiki 热层配置。"""

    #: Wiki 热层是否启用（Python 侧只影响向量集合名与蒸馏兜底）
    enabled: bool = True
    #: Wiki 页面摘要向量集合（与 RAG 的 exam_chunks 分开）
    collection: str = "wiki_pages"
    #: 单次蒸馏最多处理多少页（防止本地模型跑一整夜）
    distill_max_pages: int = 20
    #: 蒸馏输出最长字符数（超出截断，避免把 7B 的胡说八道整段入库）
    distill_max_chars: int = 4000
    #: 问答时是否先查 Wiki 热层（命中则直接返回预编译页，不调大模型）
    hot_enabled: bool = True
    #: 热层命中阈值：低于此分数视为未命中，降级到 RAG 冷层
    hot_threshold: float = 0.62
    #: 热层返回正文的最大字符数（预编译页可能很长，控制响应体积）
    hot_max_chars: int = 1200


@dataclass(frozen=True)
class JobsSettings:
    """Redis Stream 异步任务消费者配置。"""

    #: 业务任务消费者开关
    worker_enabled: bool = False
    #: 评估任务消费者开关（与业务流隔离）
    eval_worker_enabled: bool = False
    #: Wiki 蒸馏消费者开关（LLM 长任务，独立 stream + 独立连接池）
    wiki_worker_enabled: bool = False
    redis_url: str = "redis://127.0.0.1:6379/0"
    stream: str = "exam:jobs"
    eval_stream: str = "exam:eval"
    wiki_stream: str = "exam:wiki"
    group: str = "expert-workers"
    #: 消费者名（hostname-pid，避免多进程同名）
    consumer: str = ""
    #: XREADGROUP 阻塞毫秒
    block_ms: int = 5000
    #: 判定 pending 超时的空闲毫秒
    claim_idle_ms: int = 60000
    #: claim/trim 管理协程周期（毫秒）
    trim_interval_ms: int = 60000


@dataclass(frozen=True)
class Settings:
    """聚合全部配置的服务级单例数据。"""

    app_name: str = "examprep-ai-service"
    version: str = "1.0.0"
    # 默认只监听回环：本服务只被同机 Java 调用，学生端不应直连 AI 接口。
    # 确需跨机调试时用环境变量 HOST=0.0.0.0 显式开启（并配好防火墙，别裸奔到局域网）。
    host: str = "127.0.0.1"
    port: int = 8000
    log_level: str = "INFO"
    #: 允许的跨域来源，``*`` 表示全部（仅本机使用用）
    cors_origins: str = "*"
    #: 统一错误体中的默认耗时上限说明（毫秒），仅用于日志
    default_timeout_ms: int = 30000

    embedding: EmbeddingSettings = field(default_factory=EmbeddingSettings)
    qdrant: QdrantSettings = field(default_factory=QdrantSettings)
    llm: LlmSettings = field(default_factory=LlmSettings)
    ocr: OcrSettings = field(default_factory=OcrSettings)
    agent: AgentSettings = field(default_factory=AgentSettings)
    jobs: JobsSettings = field(default_factory=JobsSettings)
    wiki: WikiSettings = field(default_factory=WikiSettings)

    @classmethod
    def from_env(cls, env_file: Optional[Path] = None) -> "Settings":
        """从环境变量 + ``.env`` 文件构建配置（不抛异常，非法值一律回退默认）。"""
        env = load_dotenv(env_file)
        models_dir = Path(_get(env, "MODELS_DIR", str(DEFAULT_MODELS_DIR)))
        return cls(
            app_name=_get(env, "APP_NAME", "examprep-ai-service"),
            version=_get(env, "APP_VERSION", "1.0.0"),
            host=_get(env, "HOST", "127.0.0.1"),
            port=_get_int(env, "PORT", 8000),
            log_level=_get(env, "LOG_LEVEL", "INFO").upper(),
            cors_origins=_get(env, "CORS_ORIGINS", "*"),
            default_timeout_ms=_get_int(env, "DEFAULT_TIMEOUT_MS", 30000),
            embedding=EmbeddingSettings(
                models_dir=models_dir,
                model_id=_get(env, "EMBEDDING_MODEL_ID", "bge-small-zh-v1.5"),
                onnx_file=_get(env, "EMBEDDING_ONNX_FILE", "model.onnx"),
                tokenizer_file=_get(env, "EMBEDDING_TOKENIZER_FILE", "tokenizer.json"),
                dim=_get_int(env, "EMBEDDING_DIM", 512),
                max_length=_get_int(env, "EMBEDDING_MAX_LENGTH", 512),
                batch_size=_get_int(env, "EMBEDDING_BATCH_SIZE", 8),
                intra_op_num_threads=_get_int(env, "EMBEDDING_INTRA_OP_THREADS", 0),
                query_instruction=_get(
                    env, "EMBEDDING_QUERY_INSTRUCTION", "为这个句子生成表示以用于检索相关文章："
                ),
                auto_download=_get_bool(env, "EMBEDDING_AUTO_DOWNLOAD", False),
            ),
            qdrant=QdrantSettings(
                url=_get(env, "QDRANT_URL", "http://127.0.0.1:6333"),
                api_key=_get(env, "QDRANT_API_KEY", ""),
                collection=_get(env, "QDRANT_COLLECTION", "exam_chunks"),
                dim=_get_int(env, "QDRANT_DIM", 512),
                timeout_seconds=_get_float(env, "QDRANT_TIMEOUT_SECONDS", 5.0),
                prefer_grpc=_get_bool(env, "QDRANT_PREFER_GRPC", False),
            ),
            llm=LlmSettings(
                api_key=_get(env, "LLM_API_KEY", ""),
                base_url=_get(env, "LLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4").rstrip("/"),
                model=_get(env, "LLM_MODEL", "glm-4-flash"),
                temperature=_get_float(env, "LLM_TEMPERATURE", 0.3),
                max_tokens=_get_int(env, "LLM_MAX_TOKENS", 1500),
                timeout_seconds=_get_float(env, "LLM_TIMEOUT_SECONDS", 30.0),
                generator_name=_get(env, "LLM_GENERATOR_NAME", "glm"),
            ),
            ocr=OcrSettings(
                enabled=_get_bool(env, "OCR_ENABLED", True),
                lang=_get(env, "OCR_LANG", "ch"),
                model_dir=Path(_get(env, "OCR_MODEL_DIR", str(DEFAULT_MODELS_DIR / "paddle"))),
                use_angle_cls=_get_bool(env, "OCR_USE_ANGLE_CLS", False),
                drop_score=_get_float(env, "OCR_DROP_SCORE", 0.3),
                timeout_seconds=_get_float(env, "OCR_TIMEOUT_SECONDS", 60.0),
                stem_confidence_threshold=_get_float(env, "OCR_STEM_CONFIDENCE_THRESHOLD", 0.75),
                field_confidence_threshold=_get_float(env, "OCR_FIELD_CONFIDENCE_THRESHOLD", 0.90),
            ),
            agent=AgentSettings(
                enabled=_get_bool(env, "AGENT_ENABLED", True),
                java_base_url=_get(
                    env, "JAVA_BASE_URL", "http://127.0.0.1:8080/api"
                ).rstrip("/"),
                internal_token=_get(env, "AI_INTERNAL_TOKEN", ""),
                allow_anonymous=_get_bool(env, "AGENT_ALLOW_ANONYMOUS", False),
                max_steps=_get_int(env, "AGENT_MAX_STEPS", 6),
                max_tool_calls=_get_int(env, "AGENT_MAX_TOOL_CALLS", 4),
                max_prompt_chars=_get_int(env, "AGENT_MAX_PROMPT_CHARS", 8000),
                tool_timeout_seconds=_get_float(env, "AGENT_TOOL_TIMEOUT_SECONDS", 10.0),
                llm_timeout_seconds=_get_float(env, "AGENT_LLM_TIMEOUT_SECONDS", 15.0),
                session_timeout_seconds=_get_float(env, "AGENT_SESSION_TIMEOUT_SECONDS", 120.0),
                subagent_enabled=_get_bool(env, "AGENT_SUBAGENT_ENABLED", False),
                subagent_max_questions=_get_int(env, "AGENT_SUBAGENT_MAX_QUESTIONS", 3),
            ),
            jobs=JobsSettings(
                worker_enabled=_get_bool(env, "JOB_WORKER_ENABLED", False),
                eval_worker_enabled=_get_bool(env, "EVAL_WORKER_ENABLED", False),
                wiki_worker_enabled=_get_bool(env, "WIKI_WORKER_ENABLED", False),
                redis_url=_get(env, "REDIS_URL", "redis://127.0.0.1:6379/0"),
                stream=_get(env, "JOB_STREAM", "exam:jobs"),
                eval_stream=_get(env, "EVAL_STREAM", "exam:eval"),
                wiki_stream=_get(env, "WIKI_STREAM", "exam:wiki"),
                group=_get(env, "JOB_GROUP", "expert-workers"),
                consumer=_get(env, "JOB_CONSUMER", ""),
                block_ms=_get_int(env, "JOB_BLOCK_MS", 5000),
                claim_idle_ms=_get_int(env, "JOB_CLAIM_IDLE_MS", 60000),
                trim_interval_ms=_get_int(env, "JOB_TRIM_INTERVAL_MS", 60000),
            ),
            wiki=WikiSettings(
                enabled=_get_bool(env, "WIKI_ENABLED", True),
                collection=_get(env, "WIKI_COLLECTION", "wiki_pages"),
                distill_max_pages=_get_int(env, "WIKI_DISTILL_MAX_PAGES", 20),
                distill_max_chars=_get_int(env, "WIKI_DISTILL_MAX_CHARS", 4000),
                hot_enabled=_get_bool(env, "WIKI_HOT_ENABLED", True),
                hot_threshold=_get_float(env, "WIKI_HOT_THRESHOLD", 0.62),
                hot_max_chars=_get_int(env, "WIKI_HOT_MAX_CHARS", 1200),
            ),
        )

    @property
    def llm_configured(self) -> bool:
        """是否配置了大模型 API Key（未配置时 ``/v1/llm`` 返回 503）。"""
        return bool(self.llm.api_key)

    def safe_dump(self) -> Dict[str, object]:
        """脱敏后的配置摘要，用于启动日志（API Key 只显示是否配置）。"""
        return {
            "app": f"{self.app_name} v{self.version}",
            "listen": f"{self.host}:{self.port}",
            "embedding": {
                "model_id": self.embedding.model_id,
                "dim": self.embedding.dim,
                "model_dir": str(self.embedding.model_dir),
                "auto_download": self.embedding.auto_download,
            },
            "qdrant": {
                "url": self.qdrant.url,
                "collection": self.qdrant.collection,
                "api_key_set": bool(self.qdrant.api_key),
            },
            "llm": {
                "model": self.llm.model,
                "base_url": self.llm.base_url,
                "api_key_set": self.llm_configured,
            },
            "ocr": {
                "enabled": self.ocr.enabled,
                "lang": self.ocr.lang,
                "model_dir": str(self.ocr.model_dir),
            },
            "agent": {
                "enabled": self.agent.enabled,
                "java_base_url": self.agent.java_base_url,
                "internal_token_set": bool(self.agent.internal_token),
                "allow_anonymous": self.agent.allow_anonymous,
                "max_steps": self.agent.max_steps,
                "max_tool_calls": self.agent.max_tool_calls,
                "max_prompt_chars": self.agent.max_prompt_chars,
                "subagent_enabled": self.agent.subagent_enabled,
                "subagent_max_questions": self.agent.subagent_max_questions,
            },
            "jobs": {
                "worker_enabled": self.jobs.worker_enabled,
                "eval_worker_enabled": self.jobs.eval_worker_enabled,
                "wiki_worker_enabled": self.jobs.wiki_worker_enabled,
                "redis_url": self.jobs.redis_url,
                "stream": self.jobs.stream,
                "eval_stream": self.jobs.eval_stream,
                "wiki_stream": self.jobs.wiki_stream,
                "group": self.jobs.group,
            },
            "wiki": {
                "enabled": self.wiki.enabled,
                "collection": self.wiki.collection,
                "distill_max_pages": self.wiki.distill_max_pages,
            },
        }


#: 进程级全局配置单例（首次导入时构建）
_settings: Optional[Settings] = None


def log_security_posture(settings: Settings) -> None:
    """启动时打印鉴权姿态（**只打布尔状态，绝不打令牌值**）。

    为什么要单独一条日志：``/v1/agent/*`` 的 fail-closed 一旦被
    ``AGENT_ALLOW_ANONYMOUS=1`` 绕过，服务会"看起来正常但完全裸奔"。
    因此匿名放行必须打 **ERROR**（人眼必然看到），而"未配置令牌"打 WARNING
    并给出修复方法（此时 AI 问答仍可用，只是 Agent 工具链 401）。

    Args:
        settings: 已构建的配置单例。
    """
    agent = settings.agent
    token_set = bool(agent.internal_token)
    if agent.allow_anonymous:
        logger.error(
            "[安全] /v1/agent/* 鉴权已关闭（AGENT_ALLOW_ANONYMOUS=true，**生产禁止开启**，"
            "仅限本地开发）：internal_token_set=%s allow_anonymous=true agent_enabled=%s",
            str(token_set).lower(), str(agent.enabled).lower(),
        )
    elif not token_set:
        logger.warning(
            "[安全] AI_INTERNAL_TOKEN 未配置：/v1/agent/* 将一律返回 401（fail-closed）。"
            "修复：在 ai-service/.env 写入 AI_INTERNAL_TOKEN 并与 Java "
            "app.ai.internal-token 保持同值后重启；本地临时联调可设 AGENT_ALLOW_ANONYMOUS=1"
        )
    else:
        logger.info(
            "[安全] /v1/agent/* 需要内部令牌：internal_token_set=true "
            "allow_anonymous=%s agent_enabled=%s",
            str(agent.allow_anonymous).lower(), str(agent.enabled).lower(),
        )
    # 预算生效值：clamp 之后**实际**会用的数字（越界配置已在上一步被收敛并 WARNING）
    logger.info(
        "[启动] Agent 预算生效值：max_steps=%d max_tool_calls=%d（配置上限 %d）",
        agent.max_steps, agent.max_tool_calls, MAX_TOOL_CALLS_LIMIT,
    )
    # Subagent 受控实验姿态：开启是"实验性行为变更"，必须让运维一眼看到；
    # 关闭也必须打一条，证明"线上确实没开"，否则事后无法从日志判断。
    if agent.subagent_enabled:
        logger.warning(
            "[启动] Subagent 受控实验**已开启**（AGENT_SUBAGENT_ENABLED=true）："
            "max_questions=%d（硬上限 %d），工具预算仍为全局共享 %d 次；"
            "拆解/子问题/汇总任一环节失败会自动回退单 Agent（meta.degraded_reason）",
            agent.subagent_max_questions, SUBAGENT_MAX_QUESTIONS_LIMIT, agent.max_tool_calls,
        )
    else:
        logger.info(
            "[启动] Subagent 受控实验：enabled=false max_questions=%d"
            "（默认关闭：执行路径与事件序列与单 Agent 一致）",
            agent.subagent_max_questions,
        )


#: 单次会话工具调用次数的**配置级**上限（契约 §3 A8）。
#:
#: ``AGENT_MAX_TOOL_CALLS`` 不开放请求覆盖，只在启动时对越界配置 clamp + WARNING：
#: 这是运维操作失误，不该让线上请求返回 400（用户看不懂"工具预算配置错"）。
#: 上限 8 的依据：默认 4 已覆盖"检索→查题→复盘"，8 是翻倍的容错余量。
MAX_TOOL_CALLS_LIMIT: int = 8


def _clamp_tool_budget(settings: "Settings") -> "Settings":
    """把越界的 ``max_tool_calls`` 收敛到 ``[1, MAX_TOOL_CALLS_LIMIT]`` 并告警。

    Args:
        settings: 刚由环境变量构建出的配置。

    Returns:
        可能被替换过 ``agent.max_tool_calls`` 的新配置（frozen dataclass，用 replace）。

    Note:
        "启动时 clamp + WARNING"而不是返回 400：预算上限属配置项，
        压住它的目的是"别把单次会话拖成分钟级"，不是"让请求失败"。
    """
    configured = settings.agent.max_tool_calls
    clamped = min(max(configured, 1), MAX_TOOL_CALLS_LIMIT)
    if clamped == configured:
        return settings
    logger.warning(
        "[安全] AGENT_MAX_TOOL_CALLS=%d 越界，已 clamp 为 %d（允许 1..%d）",
        configured, clamped, MAX_TOOL_CALLS_LIMIT,
    )
    from dataclasses import replace

    return replace(settings, agent=replace(settings.agent, max_tool_calls=clamped))


#: 单次会话拆解出的子问题个数的**硬上限**（受控实验边界，不可通过配置突破）。
#:
#: 为什么是 3：
#: * 子问题共享同一份 4 次工具预算，4 个子问题平均每个不到 1 次，已经失去意义；
#: * 每个子问题都要跑一遍规划循环，子问题越多延迟越线性膨胀（P95 是本次评审的出口指标）。
SUBAGENT_MAX_QUESTIONS_LIMIT: int = 3


def _clamp_subagent_questions(settings: "Settings") -> "Settings":
    """把越界的 ``subagent_max_questions`` 收敛到 ``[1, SUBAGENT_MAX_QUESTIONS_LIMIT]``。

    Args:
        settings: 刚由环境变量构建出的配置。

    Returns:
        可能被替换过 ``agent.subagent_max_questions`` 的新配置（frozen dataclass）。

    Note:
        与 ``AGENT_MAX_TOOL_CALLS`` 同理：这是配置写错，不是请求参数错，
        所以启动时 clamp + WARNING，而不是让线上请求 400。
    """
    configured = settings.agent.subagent_max_questions
    clamped = min(max(configured, 1), SUBAGENT_MAX_QUESTIONS_LIMIT)
    if clamped == configured:
        return settings
    logger.warning(
        "[安全] AGENT_SUBAGENT_MAX_QUESTIONS=%d 越界，已 clamp 为 %d（允许 1..%d）",
        configured, clamped, SUBAGENT_MAX_QUESTIONS_LIMIT,
    )
    from dataclasses import replace

    return replace(
        settings, agent=replace(settings.agent, subagent_max_questions=clamped)
    )


def get_settings() -> Settings:
    """获取全局配置单例（懒初始化，测试中可调用 ``reset_settings()`` 重置）。

    **首次构建时**做两件事（等价于启动自检）：
    1. 对越界的配置级预算做 clamp（见 :func:`_clamp_tool_budget` 与
       :func:`_clamp_subagent_questions`）；
    2. 打印一条鉴权姿态日志（见 :func:`log_security_posture`）。
    """
    global _settings
    if _settings is None:
        _settings = _clamp_subagent_questions(_clamp_tool_budget(Settings.from_env()))
        log_security_posture(_settings)
    return _settings


def reset_settings() -> None:
    """重置配置单例（仅供测试使用）。"""
    global _settings
    _settings = None
