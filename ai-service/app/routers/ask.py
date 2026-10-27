"""``POST /v1/ask`` —— Router + Skill 的统一问答入口。

与 ``POST /v1/llm``（纯代理）的区别：**这里做决策**——先判断问题该走哪个 skill，
再按 skill 声明的检索范围取资料，最后才交给大模型。返回体里带完整判定过程，
便于演示时解释"为什么这样答"。

## 决策顺序（与设计文档 B.3 一致）

```
Router 判定
  ├─ REFUSE   → 直接拒答（不检索、不调模型）
  ├─ CLARIFY  → 反问澄清（不检索、不调模型）
  ├─ SMALLTALK→ 模板直出（不调模型，<50ms）
  └─ 其他     → 按 skill 的 source_types 检索
                  ├─ 事实类且无命中 → 拒答（检索验证闸门）
                  └─ 有命中 → 拼上下文 → 大模型 → 带引用返回
```

## 降级

* Router 不可用（embedding 挂了）→ **跳过路由**，按 COURSE_QA 走通用检索，
  响应 ``router.reason=ROUTER_UNAVAILABLE``，行为等价于接入 Router 之前（零退化）
* 大模型不可用 → ``degraded=true`` 并返回抽取式摘要（取首条资料前 200 字），
  **不返回 5xx**——前端与 Java 都能拿到可用内容
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional, Sequence

from fastapi import APIRouter
from pydantic import BaseModel, Field

from ..clients.embedding_client import get_embedding_client
from ..clients.llm_client import LlmUnavailableError, get_llm_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..router.router import get_router
from ..router.schemas import FACT_SKILLS, RouterDecision, SkillName
from ..skills.base import Skill
from ..skills.registry import get_skill

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ask"])

#: 复用后端同一套 MySQL 连接（灌库脚本已验证可用）
# 连接参数由 app/db.py 统一从环境变量/.env 读取，代码里不再保存口令
from ..db import db_config  # noqa: E402


# ---------------------------------------------------------------- 请求/响应
class AskRequest(BaseModel):
    """问答请求。"""

    question: str = Field(min_length=1, max_length=500, description="用户问题")
    top_k: int = Field(default=5, ge=1, le=20, description="检索条数上限")
    subject_id: Optional[int] = Field(default=None, description="限定科目（可选）")
    skill: Optional[str] = Field(
        default=None,
        description="强制指定 skill（调试用）；为空表示由 Router 判定",
    )


class AskSource(BaseModel):
    """一条引用来源。"""

    index: int = Field(description="引用编号，与答案中的 [n] 对应")
    chunk_id: int
    title: str = ""
    source_type: str = ""
    source_id: Optional[int] = None
    score: float = 0.0
    #: 原文页码（仅课件/资料类有），前端可跳到具体某页
    page_no: Optional[int] = None
    ref_path: str = Field(default="", description="前端跳转路径，如 /courseware/12")


class AskResponse(BaseModel):
    """问答响应（含 Router 判定过程，演示用）。"""

    question: str
    answer: str = ""
    refused: bool = False
    skill: str = ""
    requested_skill: str = Field(default="", description="Router 原始判定（降级时与 skill 不同）")
    fallback: bool = Field(default=False, description="skill 未实现而回退到 COURSE_QA")
    sources: List[AskSource] = Field(default_factory=list)
    router: Dict[str, Any] = Field(default_factory=dict)
    degraded: bool = False
    degrade_reason: str = ""
    #: 是否命中 Wiki 热层（True 表示答案来自预编译页，未调大模型）
    hot: bool = False
    hot_score: float = 0.0
    wiki_page_id: Optional[int] = None
    latency_ms: int = 0


# ---------------------------------------------------------------- 检索
def _qdrant_filter(source_types: Sequence[str], subject_id: Optional[int]) -> Dict[str, Any]:
    """构造 Qdrant 过滤条件：状态 + 来源类型（**隔离课件与课程题的关键**）。"""
    must: List[Dict[str, Any]] = [{"key": "status", "match": {"value": "ACTIVE"}}]
    if source_types:
        must.append({"key": "source_type", "match": {"any": list(source_types)}})
    if subject_id is not None:
        must.append({"key": "subject_id", "match": {"value": subject_id}})
    return {"must": must}


def _wiki_hot(question: str, subject_id: Optional[int]) -> Optional[Dict[str, Any]]:
    """Wiki 热层检索：命中预编译页则直接返回，未命中返回 None（降级 RAG）。

    三道保险，任何一道不满足都安静降级，**绝不让热层故障拖垮问答**：
    ① 配置关闭 / 集合为空 → None
    ② 向量或 Qdrant 异常 → None（热层是可选项，不是依赖）
    ③ 最高分低于阈值 → None（宁可不命中，也不拿弱相关页糊弄）
    """
    try:
        settings = get_settings().wiki
        if not settings.enabled or not settings.hot_enabled:
            return None
        vector = get_embedding_client().encode_query(question)
        filters = ({"must": [{"key": "subject_id", "match": {"value": int(subject_id)}}]}
                   if subject_id is not None else None)
        hits = get_qdrant_client().search_wiki(
            settings.collection, vector, top_k=1, query_filter=filters)
        if not hits:
            return None
        top = hits[0]
        if float(top.get("score") or 0.0) < settings.hot_threshold:
            return None
        page_id = int(top.get("page_id") or 0)
        if page_id <= 0:
            return None
        page = _fetch_wiki_page(page_id)
        if not page:
            return None
        page["score"] = round(float(top.get("score") or 0.0), 4)
        return page
    except Exception as exc:  # noqa: BLE001 - 热层任何异常都只是降级
        logger.info("[Ask] Wiki 热层跳过：%s", str(exc)[:80])
        return None


def _fetch_wiki_page(page_id: int) -> Optional[Dict[str, Any]]:
    """按 id 取已发布的 wiki 页（只认 PUBLISHED，草稿不对外）。"""
    import pymysql

    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, slug, title, summary, content FROM wiki_page "
                "WHERE id=%s AND status='PUBLISHED'", (page_id,))
            row = cur.fetchone()
            if not row:
                return None
            return {"id": row[0], "slug": row[1], "title": row[2],
                    "summary": row[3], "content": row[4]}
    finally:
        conn.close()


def _retrieve(question: str, skill: Skill, top_k: int,
              subject_id: Optional[int]) -> List[Dict[str, Any]]:
    """按 skill 的**分组**取候选块，保证各类来源都有配额。

    为什么要分组：单一 `source_type` 集合检索时，块数多的那类会占满 top-K
    （实测课件 8,073 块 vs 题库 2,398 块，课件会把题挤出）。
    分组后每组各给一份配额，再按分数合并，题型与教材都能进上下文。
    """
    if not skill.needs_retrieval:
        return []
    groups = list(skill.retrieval_groups) or (
        [tuple(skill.source_types)] if skill.source_types else [])
    if not groups:
        return []

    vector = get_embedding_client().encode_query(question)
    limit = min(top_k, skill.max_contexts)
    # 每组配额：至少 2 条，余量给最后一组
    per_group = max(2, limit // len(groups) + (1 if limit % len(groups) else 0))

    merged: Dict[int, Dict[str, Any]] = {}
    for group in groups:
        for hit in get_qdrant_client().search(
                vector, top_k=per_group,
                query_filter=_qdrant_filter(group, subject_id)) or []:
            cid = int(hit.get("chunk_id") or 0)
            if cid and (cid not in merged or hit["score"] > merged[cid]["score"]):
                merged[cid] = hit

    # 同分时按分数降序；跨组去重已在 merged 里完成
    ranked = sorted(merged.values(), key=lambda h: float(h.get("score") or 0), reverse=True)
    return ranked[:limit]


def _fetch_contents(chunk_ids: List[int]) -> Dict[int, str]:
    """按 chunk_id 从 MySQL 取正文（向量 payload 不存正文，这是设计约定）。"""
    if not chunk_ids:
        return {}
    import pymysql  # 局部导入：不用向量化的部署不会装它

    fmt = ",".join(["%s"] * len(chunk_ids))
    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(f"SELECT id, content FROM ai_chunk WHERE id IN ({fmt})", chunk_ids)
            return {row[0]: row[1] for row in cur.fetchall()}
    finally:
        conn.close()


def _ref_path(source_type: str, source_id: Optional[int]) -> str:
    """与 Java ``ChunkContext.refPath()`` 保持一致的跳转前缀。"""
    prefix = {
        "QUESTION": "/questions/",
        "RESOURCE": "/resources/",
        "KNOWLEDGE": "/knowledge/",
        "COURSEWARE": "/courseware/",
    }.get(source_type or "", "")
    return f"{prefix}{source_id}" if prefix and source_id else ""


# ---------------------------------------------------------------- 主流程
@router.post(
    "/v1/ask",
    response_model=AskResponse,
    summary="Router + Skill 统一问答（带判定过程与引用）",
    description=(
        "先用 bge 句向量判定意图（8 个 skill + 拒答/澄清），再按 skill 声明的"
        "source_types 检索，最后交给本地 7B 生成带 [n] 引用的答案。"
        "**事实类问题检索不到资料时直接拒答**，不编造。"
    ),
)
def ask(request: AskRequest) -> AskResponse:
    """执行一次完整问答。"""
    started = time.perf_counter()
    question = request.question.strip()

    # ① 路由
    try:
        if request.skill:
            forced = SkillName(request.skill)
            decision = RouterDecision(forced, 1.0, 1.0, [], "FORCED")
        else:
            decision = get_router().decide(question)
    except Exception as exc:  # noqa: BLE001 - 路由失败不能让问答挂掉
        logger.warning("[Ask] Router 不可用，退化为通用检索：%s", exc)
        decision = RouterDecision(SkillName.COURSE_QA, 0.0, 0.0, [], "ROUTER_UNAVAILABLE")

    base = dict(question=question, router=decision.to_dict(),
                requested_skill=decision.skill.value)

    # ② Wiki 热层优先：**在拒答之前**检查预编译页
    #    为什么放在这里：Router 的原型句覆盖的是课程/课件领域，像「言语理解与表达」
    #    这类行测模块题会被判成域外而直接拒答；而 Wiki 里恰好有这一页。
    #    热层是"已编译好的答案"，先看它、再谈能不能答，才符合"热层优先"的语义。
    settings = get_settings()
    if settings.wiki.enabled and settings.wiki.hot_enabled:
        hot = _wiki_hot(question, request.subject_id)
        if hot:
            body = (hot["content"] or hot["summary"] or "").strip()[: settings.wiki.hot_max_chars]
            return AskResponse(
                question=question,
                answer=f"{hot['title']}\n\n{body}",
                skill="WIKI_HOT",
                requested_skill=decision.skill.value,
                hot=True, hot_score=hot["score"], wiki_page_id=hot["id"],
                sources=[AskSource(index=1, chunk_id=0, title=hot["title"],
                                   source_type="WIKI", source_id=hot["id"],
                                   score=hot["score"], ref_path=f"/wiki/{hot['slug']}")],
                router={**decision.to_dict(), "hot": "HIT"},
                latency_ms=int((time.perf_counter() - started) * 1000),
            )

    # ② 终结型判定：不检索、不调模型
    if decision.skill is SkillName.REFUSE:
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=SkillName.REFUSE.value,
                           latency_ms=int((time.perf_counter() - started) * 1000))
    if decision.skill is SkillName.CLARIFY:
        return AskResponse(**base, answer="你的问题能再具体一点吗？比如「进程和线程的区别」。",
                           skill=SkillName.CLARIFY.value,
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ③ 解析 skill；未实现的回退到 COURSE_QA（如实标注）
    skill = get_skill(decision.skill)
    fallback = False
    if not skill.implemented:
        fallback = True
        skill = get_skill(SkillName.COURSE_QA)

    if not skill.needs_llm:
        return AskResponse(**base, answer=skill.template_reply or "",
                           skill=skill.name.value, fallback=fallback,
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑤ 检索（热层未命中 → RAG 冷层）
    hits = _retrieve(question, skill, request.top_k, request.subject_id)
    if not hits and skill.name in FACT_SKILLS:
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=skill.name.value, fallback=fallback,
                           router={**decision.to_dict(), "reason": "RETRIEVAL_EMPTY"},
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑥ 组装上下文（编号与 sources 严格一一对应）
    # 注意：检索封装返回的是 {"chunk_id", "score", "payload": {...}}，业务字段在 payload 里
    chunk_ids = [int(h.get("chunk_id") or 0) for h in hits]
    contents = _fetch_contents([cid for cid in chunk_ids if cid])
    sources: List[AskSource] = []
    contexts: List[str] = []
    for hit in hits:
        payload = hit.get("payload") or {}
        cid = int(hit.get("chunk_id") or payload.get("chunk_id") or 0)
        text = (contents.get(cid) or "").strip()
        if not text:
            continue  # 回表取不到正文（脏向量）→ 跳过，不占用引用编号
        contexts.append(text[: skill.max_context_chars])
        sources.append(AskSource(
            index=len(contexts), chunk_id=cid,
            title=str(payload.get("title") or ""),
            source_type=str(payload.get("source_type") or ""),
            source_id=payload.get("source_id"),
            score=round(float(hit.get("score") or 0.0), 4),
            page_no=payload.get("page_no"),
            ref_path=_ref_path(str(payload.get("source_type") or ""), payload.get("source_id")),
        ))

    if not contexts:
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=skill.name.value, fallback=fallback,
                           router={**decision.to_dict(), "reason": "HYDRATE_EMPTY"},
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑦ 生成（7B 只负责写，不做决策）
    prompt = question if not skill.answer_hint else f"{question}\n（输出要求：{skill.answer_hint}）"
    try:
        result = get_llm_client().chat(prompt, contexts, max_tokens=500)
        answer = result.get("answer", "")
        degraded = False
        reason = ""
    except LlmUnavailableError as exc:
        # 大模型不可用：返回抽取式摘要，接口仍 200（前端不至于白屏）
        answer = contexts[0][:200].strip()
        degraded = True
        reason = str(exc)[:120]
    except Exception as exc:  # noqa: BLE001
        answer = contexts[0][:200].strip()
        degraded = True
        reason = f"生成失败：{type(exc).__name__}"

    return AskResponse(
        **base, answer=answer, sources=sources, skill=skill.name.value,
        fallback=fallback, degraded=degraded, degrade_reason=reason,
        latency_ms=int((time.perf_counter() - started) * 1000),
    )
