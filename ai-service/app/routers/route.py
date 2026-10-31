"""``/v1/route`` —— 意图路由服务接口（A1 契约）。

## 为什么单独开一个接口

主问答链路 ``/v1/ask`` 内部也会做路由，但它是**一次完整问答**（检索 + 生成，
几百毫秒到几秒）。Java 侧真正需要的是一件更轻的事：**在检索之前**先问一句
"这问题该走哪套检索/提示词"，好让 SQL 只查该查的来源类型、让提示词只装该装的内容。

所以本接口只做判定、不做检索生成，目标 P95 ≤ 50ms（bge 句向量 + 原型句点积）。

## 契约（与 Java ``AiRouteClient`` 逐字对齐，snake_case）

请求：``{"question": "...", "subject_id": 5}``（``subject_id`` 预留，当前不参与判定）
响应：``{"skill", "source_types", "confidence", "margin", "degraded", "reason"}``

**失败一律 200 + 默认值**（``skill=COURSE_QA``、``degraded=true``）：
路由是链路最前面的"建议"，它挂了不该让整个问答挂掉；Java 侧拿到 degraded
就直接走默认路径，因此这里连 embedding 不可用都不抛 503。

## skill 映射规则（与 Java 的 KNOWN_SKILLS 严格对齐）

* ``REFUSE`` / ``CLARIFY`` **不出现在响应里**——Java 的白名单没有这两个值，
  出现会被判为"契约漂移"并整体降级。这里把它们折叠成
  ``COURSE_QA + degraded=true + reason=SIM_LOW|MARGIN_LOW``：
  语义仍是"没把握，走覆盖面最广的课程问答"，但契约合法。
* ``source_types`` 取该 skill 的检索来源白名单；空列表 = **不过滤**（与 Java 约定一致）。
  对不需要检索的 skill（SMALLTALK / INTERVIEW）返回空列表。

## 鉴权

与 ``/v1/agent/*`` 同一条内部链路，复用 ``require_internal_token``
（``X-Internal-Token``）。
"""

from __future__ import annotations

import logging
import time
from typing import List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..router.schemas import SkillName
from ..router.router import get_router
from ..skills.registry import get_skill
from .agent import require_internal_token

logger = logging.getLogger(__name__)

router = APIRouter(tags=["route"])

#: 路由不可用/没把握时的兜底 skill（覆盖面最广）
DEFAULT_SKILL = SkillName.COURSE_QA
#: Java 白名单里没有、需要折叠成默认 skill 的判定结果
_FOLDED_SKILLS = frozenset({SkillName.REFUSE, SkillName.CLARIFY})


class RouteRequest(BaseModel):
    """路由请求。"""

    question: str = Field(min_length=1, max_length=1000, description="用户问题原文")
    subject_id: Optional[int] = Field(default=None, description="科目 ID（预留，当前不参与判定）")


class RouteResponse(BaseModel):
    """路由响应（字段与 Java ``AiRouteClient.RouteResponse`` 逐字对齐）。"""

    skill: str = Field(description="COURSE_QA / COURSEWARE_QA / … / INTERVIEW")
    source_types: List[str] = Field(
        default_factory=list,
        description="检索来源白名单（QUESTION/KNOWLEDGE/RESOURCE/COURSEWARE）；空=不过滤",
    )
    confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="最高相似度")
    margin: float = Field(default=0.0, description="top1 与 top2 的相似度差")
    degraded: bool = Field(default=False, description="true 表示走了默认路由/兜底")
    reason: str = Field(default="ROUTED", description="ROUTED / SIM_LOW / MARGIN_LOW / …")


def _source_types_of(skill: SkillName) -> List[str]:
    """取 skill 的检索来源白名单；skill 未注册时返回空（= 不过滤）。"""
    try:
        return [str(item) for item in get_skill(skill).source_types]
    except Exception:  # noqa: BLE001 —— 注册表缺项不应让路由失败
        logger.warning("[Route] skill=%s 未在注册表中，source_types 按空处理", skill)
        return []


def _fallback(reason: str, confidence: float = 0.0, margin: float = 0.0) -> RouteResponse:
    """默认路由（任何失败都走这里，HTTP 状态仍是 200）。"""
    return RouteResponse(
        skill=DEFAULT_SKILL.value,
        source_types=_source_types_of(DEFAULT_SKILL),
        confidence=max(0.0, min(1.0, confidence)),
        margin=margin,
        degraded=True,
        reason=reason,
    )


@router.post(
    "/v1/route",
    response_model=RouteResponse,
    dependencies=[Depends(require_internal_token)],
    summary="意图路由（检索前一次性判定 skill 与来源白名单）",
    description=(
        "只做判定不做检索；P95 目标 ≤ 50ms。"
        "**任何失败（embedding 不可用、内部异常）都返回 200 + 默认路由 + degraded=true**，"
        "Java 侧据此走默认路径，不因路由失败拖垮问答。"
        "REFUSE/CLARIFY 不在 Java 白名单内，会被折叠为 COURSE_QA + degraded=true + reason。"
    ),
)
def route_question(request: RouteRequest) -> RouteResponse:
    """判定问题意图。"""
    started = time.perf_counter()
    try:
        decision = get_router().decide(request.question)
    except Exception as exc:  # noqa: BLE001 —— 路由失败绝不向上抛（Java 侧靠 200+degraded）
        logger.warning("[Route] 判定失败（%s），返回默认路由", type(exc).__name__)
        return _fallback("EMBEDDING_UNAVAILABLE")

    took_ms = (time.perf_counter() - started) * 1000
    skill = decision.skill
    if skill in _FOLDED_SKILLS:
        response = _fallback(decision.reason or "LOW_CONFIDENCE",
                             decision.sim, decision.margin)
    else:
        response = RouteResponse(
            skill=skill.value,
            source_types=_source_types_of(skill),
            confidence=round(max(0.0, min(1.0, decision.sim)), 4),
            margin=round(decision.margin, 4),
            degraded=False,
            reason="ROUTED",
        )
    logger.info("[Route] skill=%s degraded=%s reason=%s sim=%.4f margin=%.4f took=%.1fms",
                response.skill, response.degraded, response.reason,
                response.confidence, response.margin, took_ms)
    return response
