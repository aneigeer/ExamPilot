"""``GET /v1/health`` —— 健康检查。

**这是最重要的降级端点**：Java 侧的 ``AiIndexStartupChecker`` / ``/ai/capability``
靠它决定"语义检索是否可用""OCR 是否可用"，进而决定 App 端展示与降级提示。

约定：
* HTTP 状态码**恒为 200**（哪怕多项能力不可用）——
  因为"服务活着但 OCR 没装"不是错误，而是**已知的降级态**。
  各能力就绪与否看 body 里的 ``ready`` 字段。
* 整份响应**零延迟成本**：embedding 只读懒加载状态，Qdrant 探活带短超时。
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter

from ..clients.embedding_client import get_embedding_client
from ..clients.llm_client import get_llm_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..ocr.paddle_ocr_engine import get_ocr_engine
from ..schemas import (
    ComponentHealth,
    EmbeddingHealth,
    HealthResponse,
    SecurityHealth,
)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["health"])


def _security_health(settings: Any) -> SecurityHealth:
    """由配置推导安全姿态三键（**只暴露布尔，不碰令牌值**）。

    Args:
        settings: 全局配置（``Settings``）。

    Returns:
        :class:`SecurityHealth`，``agentAuthRequired = 已配置令牌 && !匿名放行``。
    """
    token_configured = bool(settings.agent.internal_token)
    anonymous_allowed = bool(settings.agent.allow_anonymous)
    return SecurityHealth(
        internal_token_configured=token_configured,
        agent_auth_required=token_configured and not anonymous_allowed,
        anonymous_allowed=anonymous_allowed,
    )


@router.get(
    "/v1/health",
    response_model=HealthResponse,
    summary="健康检查（各能力就绪状态 + 安全姿态）",
    description=(
        "返回 embedding / qdrant / ocr / llm 四项能力的就绪状态。"
        "status=ok 表示全部就绪，degraded 表示至少一项不可用（系统仍可运行，Java 侧自动降级）。"
        "另含 security 段：internal_token_set / agent_anonymous_allowed / agent_enabled，"
        "**只暴露布尔状态，绝不返回令牌值**。"
        "**本接口恒返回 HTTP 200**。"
    ),
)
def health() -> HealthResponse:
    """汇总各能力健康状态。

    Returns:
        :class:`HealthResponse`，含四项能力 ``ready`` 标记 + 安全姿态布尔量。
    """
    settings = get_settings()

    # —— Embedding：capable 判定（依赖 + 模型文件存在），**不触发模型加载**
    embedding_client = get_embedding_client()
    embedding_ready = embedding_client.ready      # = capable：能力是否可用
    embedding_loaded = embedding_client.loaded    # 本进程内是否已加载
    embedding_health = EmbeddingHealth(
        ready=embedding_ready,
        detail=embedding_client.health_detail(),
        dim=embedding_client.dim if embedding_ready else 0,
        model_id=settings.embedding.model_id,
        loaded=embedding_loaded,
    )

    # —— Qdrant：主动探活一次（短超时 5s），失败标 degraded 而非报错
    qdrant_client = get_qdrant_client()
    qdrant_ready = qdrant_client.probe()
    qdrant_health = ComponentHealth(
        ready=qdrant_ready, detail=qdrant_client.health_detail()
    )

    # —— OCR：只做 find_spec 探测，不 import paddleocr、不加载模型
    ocr_engine = get_ocr_engine()
    ocr_ready = ocr_engine.is_available()
    ocr_health = ComponentHealth(ready=ocr_ready, detail=ocr_engine.health_detail())

    # —— LLM：未配置 Key 即不可用（Java 侧走抽取式，不算系统故障）
    llm_client = get_llm_client()
    llm_ready = llm_client.ready
    llm_health = ComponentHealth(ready=llm_ready, detail=llm_client.health_detail())

    all_ready = embedding_ready and qdrant_ready and ocr_ready and llm_ready
    status = "ok" if all_ready else "degraded"
    if not all_ready:
        logger.info(
            "[Health] status=degraded embedding=%s qdrant=%s ocr=%s llm=%s",
            embedding_ready, qdrant_ready, ocr_ready, llm_ready,
        )

    return HealthResponse(
        status=status,
        embedding=embedding_health,
        qdrant=qdrant_health,
        ocr=ocr_health,
        llm=llm_health,
        version=settings.version,
        # —— 安全姿态：只给布尔量（契约 §3 A11）。运维/QA 据此确认
        #    "/v1/agent/* 是否真的受保护"，而不需要读 .env
        #    （读 .env 必然要看到明文令牌，那才是真正的泄露面）。
        #    agentAuthRequired = 已配置令牌 且 未开匿名放行；
        #    **不参与** status / all_ready 判定（鉴权不是"能力不可用"）。
        security=_security_health(settings),
    )
