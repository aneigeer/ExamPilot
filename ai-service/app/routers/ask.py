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
  （GRADING 特殊：返回**抽取式评估**，见 ``_extractive_grading``）

## 降级状态机（v4 R06，响应 meta 三字段）

内容产出的 5 层链路：**Wiki热层 → 向量RAG → ngram → 7B生成 → 抽取式兜底**。
其中 ngram 在 Java 侧（Python 检索为空拒答后由 Java 接手），Python 只能如实报告
自己这一段。每次响应都带：

* ``layers_hit``：实际走过的层，按顺序，如 ``["ROUTER", "WIKI_HOT"]``
* ``final_strategy``：最终内容策略 —— ``WIKI_HOT / RAG_LLM / RAG_EXTRACTIVE /
  TEMPLATE / REFUSED / CLARIFY``
* ``degrade_level``：降级严重度（取链路上**最差**一环）：
  ``0`` 未降级（热层命中 / RAG+7B 全链路 / 模板与拒答这类设计内行为）
  → ``1`` 路由降级（Router 不可用，退化为 COURSE_QA 通用检索）
  → ``2`` 生成降级（7B 不可用，抽取式兜底）
  → ``3`` 检索未产出内容（RETRIEVAL_EMPTY / HYDRATE_EMPTY，Java 可接 ngram 层）

## 引用校验（v4 R05）

7B 偶尔会编造越界的 ``[n]``（比如只给了 3 条资料却写 [5]）。生成后统一过
``_sanitize_citations``：越界/非法编号**剔除**，组合引用（如 [1,9]）保留合法部分
重映射为 [1]；剔除数写入 ``citation_fixed``。

## 热层来源校验（v4 R04）

热层命中后**回 MySQL 校验** ``wiki_page.source_chunk_ids`` 对应块是否仍 ACTIVE
（见 ``app/wiki/source_guard.py``）；任一来源已下架 → 热层放行失败，
安静降级 RAG 冷层，原因记入 ``router.hot_reason``。

## 同来源去重（v4 R08）

``_retrieve`` 合并排序阶段：跨组按 chunk_id 去重之外，再按 ``content_hash``
剔除完全重复块（保留最高分）；回表取到正文后，同一来源内 bigram 相似度过高的
近重复块也只保留分数最高的一条，避免同一页内容霸占 top-K。
"""

from __future__ import annotations

import logging
import re
import time
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from fastapi import APIRouter
from pydantic import BaseModel, Field, field_validator

from ..clients.embedding_client import get_embedding_client
from ..clients.llm_client import LlmUnavailableError, get_llm_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..router.router import get_router
from ..router.schemas import FACT_SKILLS, RouterDecision, SkillName
from ..skills.base import Skill
from ..skills.registry import get_skill
from ..wiki.source_guard import page_sources_active
from ..wiki.distiller import grounding_ratio

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

    @field_validator("question")
    @classmethod
    def _question_not_blank(cls, value: str) -> str:
        """A-04：与 SearchRequest / LlmRequest / AgentRequest 一致，拒绝全空白问题。

        Field(min_length=1) 只挡空串，全空格仍能通过后被 ``.strip()`` 成空串，
        进而带着空问题进入 Router / 检索 / LLM；这里统一在模型层拦下。
        """
        if not value or not value.strip():
            raise ValueError("question 不能为空")
        return value.strip()


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
    """问答响应（含 Router 判定过程与降级状态机，演示用）。"""

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
    #: 降级严重度：0 未降级 / 1 路由降级 / 2 生成降级（抽取式）/ 3 检索未产出内容
    degrade_level: int = 0
    #: 实际走过的层（按顺序），如 ["ROUTER", "WIKI_HOT"]（v4 R06）
    layers_hit: List[str] = Field(default_factory=list)
    #: 最终内容策略：WIKI_HOT / RAG_LLM / RAG_EXTRACTIVE / TEMPLATE / REFUSED / CLARIFY
    final_strategy: str = ""
    #: 生成后引用校验剔除的非法 [n] 编号个数（v4 R05）
    citation_fixed: int = 0
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


def _wiki_hot(question: str, subject_id: Optional[int]) -> Tuple[Optional[Dict[str, Any]], str]:
    """Wiki 热层检索：命中预编译页则返回 ``(page, "")``，未命中返回 ``(None, 原因)``。

    四道保险，任何一道不满足都安静降级，**绝不让热层故障拖垮问答**：
    ① 配置关闭 / 集合为空 → 未命中
    ② 向量或 Qdrant 异常 → 未命中（热层是可选项，不是依赖）
    ③ 最高分低于阈值 → 未命中（宁可不命中，也不拿弱相关页糊弄）
    ④ **来源已下架（R04）→ 未命中**：命中后回 MySQL 校验
       ``wiki_page.source_chunk_ids`` 是否全部仍 ACTIVE；任一失效就不放行
       （fail-closed），原因 ``SOURCE_DELISTED`` / ``SOURCE_CHECK_UNAVAILABLE``。
    """
    try:
        settings = get_settings().wiki
        if not settings.enabled or not settings.hot_enabled:
            return None, "DISABLED"
        vector = get_embedding_client().encode_query(question)
        filters = ({"must": [{"key": "subject_id", "match": {"value": int(subject_id)}}]}
                   if subject_id is not None else None)
        hits = get_qdrant_client().search_wiki(
            settings.collection, vector, top_k=1, query_filter=filters)
        if not hits:
            return None, "MISS"
        top = hits[0]
        if float(top.get("score") or 0.0) < settings.hot_threshold:
            return None, "LOW_SCORE"
        page_id = int(top.get("page_id") or 0)
        if page_id <= 0:
            return None, "MISS"
        page = _fetch_wiki_page(page_id)
        if not page:
            return None, "MISS"
        # R04：预编译页的来源可能已被下架（payload/页面状态都不会自动同步），
        # 放行前必须回 MySQL 实时校验；校验不过 → 视同未命中，走 RAG 冷层
        allow, reason = page_sources_active(page.get("source_chunk_ids"))
        if not allow:
            logger.info("[Ask] Wiki 热层拦截（%s）：page_id=%s", reason, page_id)
            return None, reason
        page["score"] = round(float(top.get("score") or 0.0), 4)
        return page, ""
    except Exception as exc:  # noqa: BLE001 - 热层任何异常都只是降级
        # A-06：依赖故障（MySQL / Qdrant）不能与正常 MISS 一样只打 INFO，
        # 否则运维无法从日志区分「热层没命中」与「热层挂了」。
        logger.warning("[Ask] Wiki 热层异常降级（A-06）：%s", str(exc)[:80])
        return None, "ERROR"


def _fetch_wiki_page(page_id: int) -> Optional[Dict[str, Any]]:
    """按 id 取已发布的 wiki 页（只认 PUBLISHED，草稿不对外）。

    同时取 ``source_chunk_ids``：热层放行前要拿它回 MySQL 校验来源在架状态（R04）。
    """
    import pymysql

    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, slug, title, summary, content, source_chunk_ids FROM wiki_page "
                "WHERE id=%s AND status='PUBLISHED'", (page_id,))
            row = cur.fetchone()
            if not row:
                return None
            return {"id": row[0], "slug": row[1], "title": row[2],
                    "summary": row[3], "content": row[4], "source_chunk_ids": row[5]}
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
        # 2026-11-11：候选池放大 4 倍再做重排。原先只取 per_group 条，
        # 重排无从发挥；实测「什么是Claude Code」正确内容排在第 20 名进不来。
        for hit in get_qdrant_client().search(
                vector, top_k=per_group * 4,
                query_filter=_qdrant_filter(group, subject_id)) or []:
            cid = int(hit.get("chunk_id") or 0)
            if cid and (cid not in merged or hit["score"] > merged[cid]["score"]):
                merged[cid] = hit

    # 同分时按分数降序；跨组去重已在 merged 里完成
    ranked = sorted(merged.values(), key=lambda h: float(h.get("score") or 0), reverse=True)
    # R08：同来源的完全重复块（content_hash 相同）只保留分数最高的一条。
    # 近重复（同页改写、重叠切片）在回表拿到正文后由 _drop_near_duplicates 处理。
    ranked = _drop_exact_duplicates(ranked)
    # 2026-11-11：与 /v1/search 同口径重排（实体词命中标题加分）+ 单文档限流。
    # 否则长文档（如某篇切出 366 块）会霸占配额，3B 拿到无关材料就会编答案。
    from .search import _MAX_CHUNKS_PER_DOC, _rerank_score  # 局部导入避免循环依赖
    ranked.sort(key=lambda h: _rerank_score(question, float(h.get("score") or 0), h.get("payload") or {}),
                reverse=True)
    capped: List[Dict[str, Any]] = []
    per_doc: Dict[Any, int] = {}
    for hit in ranked:
        if len(capped) >= limit:
            break
        payload_hit = hit.get("payload") or {}
        doc_key = payload_hit.get("source_id") or payload_hit.get("ref_id") or hit.get("chunk_id")
        if per_doc.get(doc_key, 0) >= _MAX_CHUNKS_PER_DOC:
            continue
        per_doc[doc_key] = per_doc.get(doc_key, 0) + 1
        capped.append(hit)
    return capped


def _drop_exact_duplicates(hits: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """按 ``content_hash`` 剔除完全重复块（入参须已按分数降序，保留先出现的）。

    Qdrant payload 里有写入时的 ``content_hash``；两条 hash 相同的块正文必然一致，
    同时进上下文只会浪费 prompt 预算、并让引用编号指向重复内容。

    Args:
        hits: 按分数降序的命中列表。

    Returns:
        去重后的命中列表（顺序与相对分数不变）。
    """
    seen_hash: Set[str] = set()
    result: List[Dict[str, Any]] = []
    for hit in hits:
        payload = hit.get("payload") or {}
        content_hash = str(payload.get("content_hash") or "")
        if content_hash:
            if content_hash in seen_hash:
                logger.debug("[Ask] R08 去重：content_hash=%s 重复块被剔除", content_hash[:16])
                continue
            seen_hash.add(content_hash)
        result.append(hit)
    return result


#: 近重复判定阈值：同一来源内两块正文的字符 bigram Jaccard 相似度超过该值视为重复。
#: 0.85 起点偏保守——正常的相邻切片因 80 字重叠只会到 0.1~0.3，不会误伤；
#: 复读/改写型重复通常 > 0.9，取 0.85 兼顾召回与安全。
NEAR_DUPLICATE_THRESHOLD = 0.85


def _text_bigrams(text: str) -> Set[str]:
    """字符 bigram 集合（与 distiller 的接地校验同一套口径）。"""
    compact = re.sub(r"\s+", "", text or "")
    return {compact[i:i + 2] for i in range(len(compact) - 1) if compact[i:i + 2].strip()}


def _drop_near_duplicates(
    hits: List[Dict[str, Any]], contents: Dict[int, str]
) -> List[Dict[str, Any]]:
    """同来源近重复块去重（R08 第二阶段）：保留分数最高的一条。

    只在**同一来源**（source_type + source_id 相同）内部比较：跨来源出现相同内容
    （如课件引用了原题）是正常现象，不该去重。取不到正文的块在这里先剔除——
    它们在组装上下文时本来就会被跳过。

    Args:
        hits: 按分数降序的命中列表。
        contents: ``chunk_id -> 正文``（已回表）。

    Returns:
        去重后的命中列表。
    """
    kept: List[Dict[str, Any]] = []
    # source_key -> 已保留块的 bigram 集合列表（同来源内两两比较）
    seen: Dict[Tuple[str, Any], List[Set[str]]] = {}
    for hit in hits:
        cid = int(hit.get("chunk_id") or 0)
        text = (contents.get(cid) or "").strip()
        if not text:
            continue  # 脏向量（回表无正文），交给组装阶段的既有逻辑兜底
        payload = hit.get("payload") or {}
        source_key = (str(payload.get("source_type") or ""), payload.get("source_id"))
        grams = _text_bigrams(text)
        is_dup = False
        for other in seen.get(source_key, []):
            union = grams | other
            if union and len(grams & other) / len(union) >= NEAR_DUPLICATE_THRESHOLD:
                is_dup = True
                break
        if is_dup:
            logger.debug("[Ask] R08 去重：chunk_id=%s 与同来源高分组块近重复", cid)
            continue
        seen.setdefault(source_key, []).append(grams)
        kept.append(hit)
    return kept


#: 答案中引用标注的正则：[1] [1,2] [1][2]（与 llm_client 同一套口径）
_CITATION_PATTERN = re.compile(r"\[(\d+(?:\s*[,，]\s*\d+)*)\]")


def _sanitize_citations(answer: str, max_index: int) -> Tuple[str, int]:
    """校验答案中的 ``[n]`` 引用是否真实对应来源（R05），无效编号剔除/重映射。

    规则：
    * 编号在 ``1..max_index`` 内 → 保留（max_index 即真实来源条数）；
    * 越界 / 非数字编号 → 剔除；
    * 组合引用（如 ``[1,9]``）→ 保留合法部分，重映射为 ``[1]``；
      全部非法 → 整个标注删除。

    Args:
        answer: 模型生成的答案。
        max_index: 真实来源条数（sources 与上下文一一对应）。

    Returns:
        ``(清洗后的答案, 剔除的非法编号个数)``。
    """
    if not answer or max_index <= 0:
        return answer, 0
    removed = 0

    def _replace(match: "re.Match[str]") -> str:
        nonlocal removed
        tokens = [token.strip() for token in re.split(r"[,，]", match.group(1))]
        valid = [token for token in tokens
                 if token.isdigit() and 1 <= int(token) <= max_index]
        removed += len(tokens) - len(valid)
        if not valid:
            return ""
        return "[" + ",".join(valid) + "]"

    return _CITATION_PATTERN.sub(_replace, answer), removed


def _extractive_grading(question: str, contexts: List[str]) -> str:
    """GRADING 的抽取式评估兜底（无大模型 Key 时的降级产物）。

    必须明确告知：**这不是模型判分**，只是"学生作答与资料要点的字符重合度"参考，
    需人工复核。重合度复用蒸馏器的 ``grounding_ratio``（同一套 bigram 口径，
    数值可与 Wiki 质量分互相对照）。
    """
    header = (
        "【抽取式评估】当前未配置大模型（LLM_API_KEY 为空），无法生成判分结论；"
        "以下为作答与题库参考答案的重合度参考，请人工复核："
    )
    best_text = ""
    best_ratio = 0.0
    for context in contexts:
        ratio = grounding_ratio(question, context)
        if ratio >= best_ratio:
            best_ratio = ratio
            best_text = context
    lines = [header, f"作答与参考资料的重合度约 {best_ratio:.0%}（仅供参考，非得分）。"]
    if best_text:
        lines.append(f"资料中的参考要点：{best_text[:200].strip()}")
    return "\n".join(lines)


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

    # 降级状态机（R06）：layers 按序记录走过的层；level 取链路上最差一环
    layers: List[str] = []
    degrade_level = 0
    degrade_reasons: List[str] = []

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
        degrade_level = max(degrade_level, 1)
        degrade_reasons.append(f"Router 不可用（{type(exc).__name__}），已退化为通用检索")
    layers.append("ROUTER")

    base = dict(question=question, router=decision.to_dict(),
                requested_skill=decision.skill.value)

    # ② Wiki 热层优先：**在拒答之前**检查预编译页
    #    为什么放在这里：Router 的原型句覆盖的是课程/课件领域，像「言语理解与表达」
    #    这类行测模块题会被判成域外而直接拒答；而 Wiki 里恰好有这一页。
    #    热层是"已编译好的答案"，先看它、再谈能不能答，才符合"热层优先"的语义。
    settings = get_settings()
    if settings.wiki.enabled and settings.wiki.hot_enabled:
        layers.append("WIKI_HOT")
        hot, hot_reason = _wiki_hot(question, request.subject_id)
        if hot:
            body = (hot["content"] or hot["summary"] or "").strip()[: settings.wiki.hot_max_chars]
            return AskResponse(
                question=question,
                answer=f"{hot['title']}\n\n{body}",
                skill="WIKI_HOT",
                requested_skill=decision.skill.value,
                layers_hit=layers, final_strategy="WIKI_HOT",
                degrade_level=degrade_level, degrade_reason="；".join(degrade_reasons),
                hot=True, hot_score=hot["score"], wiki_page_id=hot["id"],
                sources=[AskSource(index=1, chunk_id=0, title=hot["title"],
                                   source_type="WIKI", source_id=hot["id"],
                                   score=hot["score"], ref_path=f"/wiki/{hot['slug']}")],
                router={**decision.to_dict(), "hot": "HIT"},
                latency_ms=int((time.perf_counter() - started) * 1000),
            )
        # 未命中也要把原因带给前端/Java：来源下架（SOURCE_DELISTED）与普通未命中不同
        base["router"] = {**decision.to_dict(), "hot": "MISS", "hot_reason": hot_reason}

    # ② 终结型判定：不检索、不调模型
    if decision.skill is SkillName.REFUSE:
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=SkillName.REFUSE.value,
                           layers_hit=layers, final_strategy="REFUSED",
                           degrade_level=degrade_level,
                           degrade_reason="；".join(degrade_reasons),
                           latency_ms=int((time.perf_counter() - started) * 1000))
    if decision.skill is SkillName.CLARIFY:
        return AskResponse(**base, answer="你的问题能再具体一点吗？比如「进程和线程的区别」。",
                           skill=SkillName.CLARIFY.value,
                           layers_hit=layers, final_strategy="CLARIFY",
                           degrade_level=degrade_level,
                           degrade_reason="；".join(degrade_reasons),
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
                           layers_hit=layers, final_strategy="TEMPLATE",
                           degrade_level=degrade_level,
                           degrade_reason="；".join(degrade_reasons),
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑤ 检索（热层未命中 → RAG 冷层）
    layers.append("VECTOR_RAG")
    hits = _retrieve(question, skill, request.top_k, request.subject_id)
    if not hits and skill.name in FACT_SKILLS:
        # 检索闸门拒答：degrade_level=3 表示"内容链路未产出"（Java 侧可接 ngram 层）
        base["router"] = {**base["router"], "reason": "RETRIEVAL_EMPTY"}
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=skill.name.value, fallback=fallback,
                           layers_hit=layers, final_strategy="REFUSED",
                           degrade_level=3,
                           degrade_reason="；".join(
                               [*degrade_reasons, "向量检索无命中（Java 侧可接 ngram 层）"]),
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑥ 组装上下文（编号与 sources 严格一一对应）
    # 注意：检索封装返回的是 {"chunk_id", "score", "payload": {...}}，业务字段在 payload 里
    chunk_ids = [int(h.get("chunk_id") or 0) for h in hits]
    contents = _fetch_contents([cid for cid in chunk_ids if cid])
    # R08 第二阶段：回表后按同来源近重复去重（保留最高分），再编号
    hits = _drop_near_duplicates(hits, contents)
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
        base["router"] = {**base["router"], "reason": "HYDRATE_EMPTY"}
        return AskResponse(**base, answer="未找到相关资料", refused=True,
                           skill=skill.name.value, fallback=fallback,
                           layers_hit=layers, final_strategy="REFUSED",
                           degrade_level=3,
                           degrade_reason="；".join(
                               [*degrade_reasons, "检索命中但正文回表为空（向量索引含脏数据）"]),
                           latency_ms=int((time.perf_counter() - started) * 1000))

    # ⑦ 生成（7B 只负责写，不做决策）
    prompt = question if not skill.answer_hint else f"{question}\n（输出要求：{skill.answer_hint}）"
    try:
        result = get_llm_client().chat(prompt, contexts, max_tokens=500)
        # R05：引用校验——越界/非法 [n] 剔除，组合引用重映射为合法部分
        answer, citation_fixed = _sanitize_citations(
            result.get("answer", ""), len(sources))
        layers.append("LLM_7B")
        degraded = False
        reason = "；".join(degrade_reasons)
        strategy = "RAG_LLM"
    except LlmUnavailableError as exc:
        layers.extend(["LLM_7B", "EXTRACTIVE"])
        degrade_level = max(degrade_level, 2)
        if skill.name is SkillName.GRADING:
            # 判分场景的兜底不是"摘一段"而是"抽取式评估"（明确注明非模型判分）
            answer = _extractive_grading(question, contexts)
            reason = "；".join([*degrade_reasons, "未配置大模型，已用抽取式评估兜底"])
            strategy = "RAG_EXTRACTIVE"
        else:
            # 大模型不可用：返回抽取式摘要，接口仍 200（前端不至于白屏）
            answer = contexts[0][:200].strip()
            reason = "；".join([*degrade_reasons, str(exc)[:120]])
            strategy = "RAG_EXTRACTIVE"
        degraded = True
        citation_fixed = 0  # 兜底答案没有 [n]，谈不上校验
    except Exception as exc:  # noqa: BLE001 —— A-01：保留兜底，但不再静默伪装成正常降级
        # A-01：这里捕获的是**编程错误 / 未预期异常**，不是「大模型能力不可用」
        # （后者已在上面的 except LlmUnavailableError 单独处理）。
        # 仍保留 HTTP 200 + 抽取式兜底，是因为 /v1/ask 的既有契约是「永不 5xx」，
        # Java 侧 AiQaService 依赖 degraded / degrade_reason / layers_hit 决策降级；
        # 直接改 503 会让该降级分支失效（破坏契约，需联调才能改）。
        # 但必须：① 留完整堆栈 ② 标记 UNEXPECTED_ERROR 层 ③ 把异常类型与消息透出，
        # 使运维可定位、Java 侧可区分「正常能力降级」与「链路 bug」。
        logger.exception(
            "[Ask] 生成链路未预期异常（A-01，非能力降级）question=%s type=%s",
            question[:80], type(exc).__name__,
        )
        layers.extend(["LLM_7B", "EXTRACTIVE", "UNEXPECTED_ERROR"])
        answer = contexts[0][:200].strip()
        degraded = True
        degrade_level = max(degrade_level, 2)
        reason = "；".join([
            *degrade_reasons,
            f"生成链路未预期异常：{type(exc).__name__}: {str(exc)[:120]}",
        ])
        strategy = "RAG_EXTRACTIVE"
        citation_fixed = 0

    return AskResponse(
        **base, answer=answer, sources=sources, skill=skill.name.value,
        fallback=fallback, degraded=degraded, degrade_reason=reason,
        degrade_level=degrade_level, layers_hit=layers, final_strategy=strategy,
        citation_fixed=citation_fixed,
        latency_ms=int((time.perf_counter() - started) * 1000),
    )
