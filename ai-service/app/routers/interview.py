"""``/v1/interview`` —— AI 面试（出题 / 评分 / 追问 / 复盘报告）。

## 与主问答链路的区别

``/v1/ask`` 是**无状态**问答；面试是**有状态多轮**：同一场会话要保持题目进度、
每题得分与是否已追问，最后一题答完产出复盘报告。所以单独一个路由 +
进程内会话表（``uuid4`` + 30 分钟 TTL，不建新表——面试是短时交互，
落库反而引入清理负担）。

## 出题来源铁律（**禁止凭空编题**）

1. 先从 MySQL 圈出「面试类」课件（``courseware.title LIKE '%面试%'``，68 份）；
2. 用 bge 句向量在**这些课件的块**里检索（Qdrant filter：source_type=COURSEWARE
   + source_id ∈ 面试课件集合），得到与该主题最相关的 5 块；
3. 7B 只负责「把资料改写成一道面试题」，提示词明确禁止超出资料；
4. 每题必带 ``source``（标题 / 源文件 / 页码），Java 与前端可回溯；
5. 7B 不可用 → **降级**：直接拿资料里的话题行当题目（``degraded=true``），
   不编题、不空题。

## 评分可解释

* 先检索参考答案块，再把「题目 + 参考资料 + 学生作答」交给 7B，
  要求输出 ``{score, verdict, hit_points, miss_points, comment}`` 五元组；
* 7B 不可用 → 抽取式兜底：按「作答字符 bigram 被参考资料覆盖的比例」折算分数，
  命中/遗漏要点取参考资料中重合最高/最低的句子，**并明确标注非模型判分**；
* 追问：作答 < 30 字 或 得分 < 60 → 同一题内追问一次，追问作答与原作答
  **合并后重新评分**（不是两次分数取平均，避免靠追问刷分）。
  **最后一题不追问**——追问会把"答满 total 题"拉长成 total+1 轮，
  前端按题号计进度会以为还有题（Java 侧实测踩到过）；分数不受影响。
  追问轮次的响应带 ``is_follow_up=true`` 且题号不前进，便于前端区分"追问"与"下一题"。

## 会话状态

``_SESSIONS: Dict[str, _Session]`` + ``threading.Lock``；每次访问顺带清理过期会话。
会话不存在/过期 → ``404 {"detail": "SESSION_NOT_FOUND"}``。
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

from fastapi import APIRouter
from pydantic import BaseModel, Field

from ..clients.embedding_client import get_embedding_client
from ..clients.qdrant_client import get_qdrant_client
from ..config import get_settings
from ..db import db_config
from ..wiki.distiller import grounding_ratio

logger = logging.getLogger(__name__)

router = APIRouter(tags=["interview"])

#: 会话 TTL（秒）：面试是短时交互，30 分钟足够覆盖一场 5 题会话
SESSION_TTL_SECONDS = 30 * 60
#: 作答短于该字数视为「太简单」，触发追问
FOLLOW_UP_MIN_ANSWER_CHARS = 30
#: 得分低于该值触发追问
FOLLOW_UP_SCORE_THRESHOLD = 60
#: 单题最多追问次数（契约要求：同一题内最多 1 次）
MAX_FOLLOW_UPS = 1
#: 单题送入 7B 的参考块数
CONTEXT_CHUNKS = 5
#: 单个参考块截断长度
CONTEXT_CHARS = 700


# ---------------------------------------------------------------- 请求/响应模型
class InterviewStartRequest(BaseModel):
    """开始一场面试。"""

    topic: str = Field(default="", max_length=60, description="主题，可为空（由服务挑）")
    total: int = Field(default=3, ge=1, le=5, description="题目数量（1-5）")
    subject_id: Optional[int] = Field(default=None, description="限定科目（可选）")


class InterviewSource(BaseModel):
    """出题来源（可回溯）。"""

    title: str = ""
    source_file: str = ""
    page_no: Optional[int] = None
    chunk_id: int = 0


class InterviewStartResponse(BaseModel):
    """开始面试的响应。"""

    session_id: str
    topic: str
    question_no: int
    total: int
    question: str
    source: InterviewSource
    degraded: bool = Field(default=False, description="题目是否因 7B 不可用而降级")


class InterviewAnswerRequest(BaseModel):
    """作答。"""

    session_id: str = Field(min_length=1, description="start 返回的会话 id")
    answer: str = Field(min_length=1, max_length=4000, description="作答文本")


class InterviewAnswerResponse(BaseModel):
    """作答的响应：未结束时带本题评分 + 下一题；结束返回复盘报告。"""

    session_id: str
    finished: bool = False
    # 未结束字段
    question_no: int = 0
    total: int = 0
    score: Optional[int] = None
    verdict: str = ""
    hit_points: List[str] = Field(default_factory=list)
    miss_points: List[str] = Field(default_factory=list)
    reference: str = ""
    follow_up: str = ""
    #: 仅当 ``next_question`` 是**同一题的追问**时为 True（题号不前进、作答会并入本题评分）。
    #: 契约未列该字段，属向后兼容的附加信息：前端可用它把追问渲染成"追问"而非"下一题"。
    is_follow_up: bool = False
    next_question: str = ""
    degraded: bool = False
    # 结束字段
    report: Optional[Dict[str, Any]] = None


# ---------------------------------------------------------------- 内部状态
@dataclass
class _Question:
    """一道题的完整状态。"""

    text: str
    source: Dict[str, Any]
    contexts: List[str]
    chunk_id: int = 0
    #: 首次作答原文（追问合并时使用）
    first_answer: str = ""
    score: Optional[int] = None
    verdict: str = ""
    comment: str = ""
    hit_points: List[str] = field(default_factory=list)
    miss_points: List[str] = field(default_factory=list)
    follow_up: str = ""
    follow_ups_used: int = 0
    #: True 表示下一轮作答是本题的追问，需要合并评分
    pending_follow_up: bool = False
    degraded: bool = False


@dataclass
class _Session:
    """一场面试会话。"""

    session_id: str
    topic: str
    total: int
    subject_id: Optional[int]
    questions: List[_Question] = field(default_factory=list)
    index: int = 0
    created_at: float = field(default_factory=time.time)
    touched_at: float = field(default_factory=time.time)
    degraded: bool = False
    #: 已用过的 chunk_id，避免连续多题撞同一段资料
    used_chunk_ids: List[int] = field(default_factory=list)


_SESSIONS: Dict[str, _Session] = {}
_SESSIONS_LOCK = threading.Lock()


def _cleanup_sessions() -> None:
    """清理过期会话（每次访问顺带执行，无需后台线程）。"""
    now = time.time()
    expired = [sid for sid, s in _SESSIONS.items()
               if now - s.touched_at > SESSION_TTL_SECONDS]
    for sid in expired:
        _SESSIONS.pop(sid, None)


def _get_session(session_id: str) -> Optional[_Session]:
    """取会话并刷新活跃时间；过期视同不存在。"""
    with _SESSIONS_LOCK:
        _cleanup_sessions()
        session = _SESSIONS.get(session_id)
        if session is None:
            return None
        session.touched_at = time.time()
        return session


def _save_session(session: _Session) -> None:
    """写入/更新会话（调用方持有 session 引用即可，这里只刷新时间戳）。"""
    with _SESSIONS_LOCK:
        session.touched_at = time.time()
        _SESSIONS[session.session_id] = session


def reset_sessions() -> None:
    """清空全部会话（仅供测试使用）。"""
    with _SESSIONS_LOCK:
        _SESSIONS.clear()


# ---------------------------------------------------------------- LLM 调用
class InterviewLlmError(Exception):
    """面试链路的 7B 调用失败（触发降级，不向上抛）。"""


def _chat(system: str, user: str, *, max_tokens: int = 600,
          temperature: float = 0.3) -> str:
    """调用 OpenAI 兼容端点（Ollama / 云端同一套协议）。

    与 ``llm_client`` 的区别：面试需要**自定义 system**（面试官 / 评分官）
    且要求 JSON 输出，不能复用带引用约束的问答提示词。

    Raises:
        InterviewLlmError: 未配置 Key、缺 httpx、超时或上游异常。
    """
    settings = get_settings().llm
    if not settings.api_key:
        raise InterviewLlmError("未配置 LLM_API_KEY，已降级为抽取式")
    try:
        import httpx  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover
        raise InterviewLlmError("未安装 httpx，已降级为抽取式") from exc

    payload = {
        "model": settings.model,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    headers = {"Authorization": f"Bearer {settings.api_key}",
               "Content-Type": "application/json"}
    try:
        # trust_env=False：本地 Ollama 调用必须绕过系统代理（见 llm_client 同款注释）
        with httpx.Client(timeout=settings.timeout_seconds, trust_env=False) as client:
            response = client.post(f"{settings.base_url}/chat/completions",
                                   json=payload, headers=headers)
    except Exception as exc:  # noqa: BLE001
        raise InterviewLlmError(f"大模型调用失败：{type(exc).__name__}") from exc
    if response.status_code != 200:
        raise InterviewLlmError(f"大模型返回 HTTP {response.status_code}")
    try:
        content = response.json()["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise InterviewLlmError("大模型响应格式异常") from exc
    if not isinstance(content, str) or not content.strip():
        raise InterviewLlmError("大模型返回空内容")
    return content.strip()


_JSON_BLOCK = re.compile(r"\{[\s\S]*\}")


def _parse_json(raw: str) -> Dict[str, Any]:
    """从模型输出中提取 JSON 对象（容忍代码块与前后废话）。"""
    match = _JSON_BLOCK.search(raw or "")
    if not match:
        raise InterviewLlmError("模型输出不含 JSON")
    try:
        data = json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise InterviewLlmError(f"JSON 解析失败：{exc}") from exc
    if not isinstance(data, dict):
        raise InterviewLlmError("模型输出不是 JSON 对象")
    return data


# ---------------------------------------------------------------- 语料检索
def _interview_courseware_ids(topic: str) -> List[int]:
    """圈出「面试类」课件 id（可选按主题再收窄）。"""
    import pymysql

    sql = "SELECT id FROM courseware WHERE is_deleted=0 AND title LIKE %s"
    params: List[Any] = ["%面试%"]
    if topic:
        sql += " AND title LIKE %s"
        params.append(f"%{topic}%")
    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            ids = [int(row[0]) for row in cur.fetchall()]
    finally:
        conn.close()
    if ids or not topic:
        return ids
    # 主题收窄后为空 → 退回全部面试课件，保证出得了题
    return _interview_courseware_ids("")


def _courseware_meta(ids: Sequence[int]) -> Dict[int, Dict[str, Any]]:
    """课件 id → {title, source_file}（出题来源要可回溯到源文件）。"""
    if not ids:
        return {}
    import pymysql

    fmt = ",".join(["%s"] * len(ids))
    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"SELECT id, title, source_file FROM courseware WHERE id IN ({fmt})",
                list(ids))
            return {int(r[0]): {"title": str(r[1] or ""), "source_file": str(r[2] or "")}
                    for r in cur.fetchall()}
    finally:
        conn.close()


def _fetch_contents(chunk_ids: Sequence[int]) -> Dict[int, str]:
    """按 chunk_id 取正文（payload 不存正文，这是全局约定）。"""
    if not chunk_ids:
        return {}
    import pymysql

    fmt = ",".join(["%s"] * len(chunk_ids))
    conn = pymysql.connect(**db_config())
    try:
        with conn.cursor() as cur:
            cur.execute(
                f"SELECT id, content FROM ai_chunk WHERE id IN ({fmt}) AND status='ACTIVE'",
                list(chunk_ids))
            return {int(r[0]): str(r[1] or "") for r in cur.fetchall()}
    finally:
        conn.close()


def _retrieve_context(topic: str, subject_id: Optional[int],
                      exclude_ids: Sequence[int]) -> List[Dict[str, Any]]:
    """检索出题/评分用的参考块。

    Returns:
        ``[{"chunk_id", "title", "source_id", "page_no", "content", "source_file"}]``；
        检索不到返回空列表（调用方负责降级或报错）。
    """
    doc_ids = _interview_courseware_ids(topic)
    if not doc_ids:
        return []
    query = topic.strip() or "AI 大模型 面试 常见问题"
    vector = get_embedding_client().encode_query(query)
    must: List[Dict[str, Any]] = [
        {"key": "status", "match": {"value": "ACTIVE"}},
        {"key": "source_type", "match": {"value": "COURSEWARE"}},
        {"key": "source_id", "match": {"any": doc_ids}},
    ]
    if subject_id is not None:
        must.append({"key": "subject_id", "match": {"value": int(subject_id)}})
    hits = get_qdrant_client().search(vector, top_k=CONTEXT_CHUNKS * 3,
                                      query_filter={"must": must}) or []
    excluded = set(int(i) for i in exclude_ids)
    picked = [h for h in hits if int(h.get("chunk_id") or 0) not in excluded]
    picked = picked[:CONTEXT_CHUNKS]
    contents = _fetch_contents([int(h.get("chunk_id") or 0) for h in picked])
    metas = _courseware_meta([int((h.get("payload") or {}).get("source_id") or 0)
                              for h in picked])
    results: List[Dict[str, Any]] = []
    for hit in picked:
        payload = hit.get("payload") or {}
        chunk_id = int(hit.get("chunk_id") or 0)
        text = (contents.get(chunk_id) or "").strip()
        if not chunk_id or not text:
            continue
        source_id = int(payload.get("source_id") or 0)
        meta = metas.get(source_id, {})
        results.append({
            "chunk_id": chunk_id,
            "title": str(payload.get("title") or meta.get("title") or ""),
            "source_id": source_id,
            "page_no": payload.get("page_no"),
            "source_file": meta.get("source_file", ""),
            "content": text,
        })
    return results


# ---------------------------------------------------------------- 出题
_QUESTION_SYSTEM = (
    "你是 AI 大模型方向的面试官。硬性规则：\n"
    "1. 只依据【资料】出题，资料之外的知识点一律不许出现；\n"
    "2. 输出**一句**中文面试问题，不超过 60 字，不要给答案，不要编号，不要引号；\n"
    "3. 优先考察原理、对比、工程实践这类能追问的点，避免「是什么」的死记硬背。"
)

_TOPIC_LINE = re.compile(r"^\s*\d+[.、]\s*(.{4,50})$")


def _fallback_question(topic: str, contexts: List[str]) -> str:
    """7B 不可用时的降级出题：直接用资料里的话题行，绝不编题。"""
    for text in contexts:
        for line in text.splitlines():
            line = line.strip().strip("*").strip()
            match = _TOPIC_LINE.match(line)
            if match:
                topic_text = match.group(1).strip("？?。 ")
                return f"请说明「{topic_text}」的核心概念，以及在真实项目中怎么用。"
    return f"请结合项目经验谈谈你对「{topic or '大模型'}」的理解。"


def _generate_question(topic: str, contexts: List[str]) -> Tuple[str, bool]:
    """生成一道面试题。返回 ``(题目, 是否降级)``。"""
    blocks = "\n\n".join(f"【资料{i}】{text[:CONTEXT_CHARS]}"
                         for i, text in enumerate(contexts, start=1))
    user = (f"【主题】{topic or 'AI 大模型'}\n【资料】\n{blocks}\n\n"
            "请据此出**一道**面试题，只输出题目本身。")
    try:
        question = _chat(_QUESTION_SYSTEM, user, max_tokens=120, temperature=0.5)
        # 去掉模型可能带上的编号/引号，保持契约里的纯题目
        question = re.sub(r"^(题目|问题)[:：]\s*", "", question.strip()).strip("\"'“”")
        if len(question) < 6:
            raise InterviewLlmError("题目过短")
        return question.splitlines()[0].strip(), False
    except InterviewLlmError as exc:
        logger.info("[Interview] 出题降级：%s", exc)
        return _fallback_question(topic, contexts), True


# ---------------------------------------------------------------- 评分
_GRADE_SYSTEM = (
    "你是 AI 大模型面试的评分官。只依据【参考资料】判断作答质量，遵守：\n"
    "1. 只输出一个 JSON 对象，不要 Markdown 代码块、不要解释；\n"
    "2. 字段：score（0-100 整数）、verdict（正确/部分正确/不正确）、"
    "hit_points（作答命中的要点数组）、miss_points（遗漏的要点数组）、"
    "comment（一句评语，40 字内）；\n"
    "3. 作答与资料无关或明显错误 → score 低于 40；答到要点 → 70 以上；\n"
    "4. 不要因为表达口语化扣分，只看技术要点是否命中。"
)

_SENTENCE_SPLIT = re.compile(r"[。；;\n]+")


def _sentence_hits(answer: str, reference: str, *, want_low: bool,
                   limit: int) -> List[str]:
    """抽取式兜底：找出参考资料里与作答重合最高（或最低）的句子。"""
    sentences = [s.strip() for s in _SENTENCE_SPLIT.split(reference) if len(s.strip()) >= 8]
    if not sentences:
        return []
    scored = [(grounding_ratio(sentence, answer), sentence) for sentence in sentences]
    scored.sort(key=lambda item: item[0], reverse=not want_low)
    if want_low:
        # 遗漏要点：只保留确实没被覆盖的句子
        scored = [item for item in scored if item[0] < 0.35]
    else:
        scored = [item for item in scored if item[0] > 0.2]
    return [item[1][:60] for item in scored[:limit]]


def _extractive_grade(answer: str, reference: str) -> Dict[str, Any]:
    """抽取式评分兜底（无 7B 时）：按作答被资料覆盖的比例折算。

    口径与 Wiki 蒸馏的接地校验同源（``grounding_ratio``），便于横向对照；
    分数刻意封顶 80 并标注「非模型判分」——抽取式只能证明"提到了资料里的词"，
    证明不了"理解正确"。
    """
    ratio = grounding_ratio(answer, reference)
    length_bonus = min(10, len(answer.strip()) // 30)
    score = int(round(min(1.0, ratio / 0.35) * 70)) + length_bonus
    score = max(0, min(80, score))
    if score >= 70:
        verdict = "部分正确"
    elif score >= 45:
        verdict = "部分正确"
    else:
        verdict = "不正确"
    hits = _sentence_hits(answer, reference, want_low=False, limit=3)
    misses = _sentence_hits(answer, reference, want_low=True, limit=2)
    return {
        "score": score,
        "verdict": verdict,
        "hit_points": hits,
        "miss_points": misses,
        "comment": f"抽取式评估：与资料要点重合度约 {ratio:.0%}（非模型判分，需人工复核）",
    }


def _grade(question: str, answer: str,
           contexts: List[str]) -> Tuple[Dict[str, Any], bool]:
    """评分。返回 ``(评分结果, 是否降级)``。"""
    reference = "\n".join(contexts)[: CONTEXT_CHARS * len(contexts)]
    blocks = "\n\n".join(f"【资料{i}】{text[:CONTEXT_CHARS]}"
                         for i, text in enumerate(contexts, start=1))
    user = (f"【面试题】{question}\n\n【参考资料】\n{blocks}\n\n"
            f"【学生作答】\n{answer}\n\n请输出评分 JSON。")
    try:
        raw = _chat(_GRADE_SYSTEM, user, max_tokens=600, temperature=0.2)
        data = _parse_json(raw)
        score = int(float(data.get("score", 0)))
        result = {
            "score": max(0, min(100, score)),
            "verdict": str(data.get("verdict") or ("部分正确" if score >= 60 else "不正确")),
            "hit_points": [str(x)[:80] for x in (data.get("hit_points") or [])][:5],
            "miss_points": [str(x)[:80] for x in (data.get("miss_points") or [])][:5],
            "comment": str(data.get("comment") or "")[:120],
        }
        return result, False
    except (InterviewLlmError, ValueError, TypeError) as exc:
        logger.info("[Interview] 评分降级：%s", exc)
        return _extractive_grade(answer, reference), True


# ---------------------------------------------------------------- 追问
_FOLLOW_UP_SYSTEM = (
    "你是 AI 大模型面试官。根据学生的作答与遗漏要点，提出**一句**追问，"
    "不超过 40 字，只输出问题本身，不要解释、不要编号。"
)


def _make_follow_up(question: str, answer: str, misses: List[str]) -> Tuple[str, bool]:
    """生成一条追问。返回 ``(追问, 是否降级)``。"""
    miss_text = "；".join(misses) or "（未提取到具体遗漏点）"
    user = (f"【面试题】{question}\n【学生作答】{answer[:500]}\n"
            f"【遗漏要点】{miss_text}\n请提出一句追问。")
    try:
        text = _chat(_FOLLOW_UP_SYSTEM, user, max_tokens=80, temperature=0.5)
        text = re.sub(r"^(追问|问题)[:：]\s*", "", text.strip()).strip("\"'“”")
        if len(text) < 4:
            raise InterviewLlmError("追问过短")
        return text.splitlines()[0].strip(), False
    except InterviewLlmError as exc:
        logger.info("[Interview] 追问降级：%s", exc)
        if misses:
            return f"关于「{misses[0][:30]}」，你能展开说说吗？", True
        return "能结合一个具体项目场景再说明一下吗？", True


# ---------------------------------------------------------------- 报告
def _level_of(score: int) -> str:
    """总分档位（与前端展示约定一致）。"""
    if score >= 85:
        return "优秀"
    if score >= 75:
        return "中上"
    if score >= 60:
        return "中等"
    return "待提升"


def _dedupe(items: Sequence[str], limit: int = 3) -> List[str]:
    """去重且保序，最多 limit 条。"""
    result: List[str] = []
    for item in items:
        text = str(item).strip()
        if text and text not in result:
            result.append(text)
    return result[:limit]


def _build_report(session: _Session) -> Dict[str, Any]:
    """汇总复盘报告。"""
    scored = [q for q in session.questions if q.score is not None]
    total_score = int(round(sum(q.score or 0 for q in scored) / len(scored))) if scored else 0
    strengths = _dedupe([p for q in scored for p in q.hit_points])
    weaknesses = _dedupe([p for q in scored for p in q.miss_points])
    suggestions: List[str] = []
    if weaknesses:
        suggestions.append("优先补齐：" + "；".join(weaknesses[:2]))
    low = [q for q in scored if (q.score or 0) < 60]
    if low:
        suggestions.append(f"有 {len(low)} 道题低于 60 分，建议回到对应课件重读并复述一遍")
    if session.degraded:
        suggestions.append("本次评分含降级环节（7B 不可用时的抽取式评分），结论需人工复核")
    if not suggestions:
        suggestions.append("保持当前节奏，继续按主题刷面试题并录音自评")
    return {
        "total_score": total_score,
        "level": _level_of(total_score),
        "per_question": [
            {"question": q.text, "score": q.score or 0,
             "comment": q.comment or "未评分"}
            for q in session.questions
        ],
        "strengths": strengths or ["作答基本完整"],
        "weaknesses": weaknesses,
        "suggestions": suggestions,
        "degraded": session.degraded,
    }


# ---------------------------------------------------------------- 会话推进
def _build_question(session: _Session, topic: str,
                    subject_id: Optional[int]) -> Optional[_Question]:
    """检索资料并生成一道题；检索不到返回 None（由调用方兜底）。"""
    contexts = _retrieve_context(topic, subject_id, session.used_chunk_ids)
    if not contexts:
        return None
    texts = [c["content"][:CONTEXT_CHARS] for c in contexts]
    question_text, degraded = _generate_question(topic, texts)
    first = contexts[0]
    session.used_chunk_ids.extend(c["chunk_id"] for c in contexts)
    return _Question(
        text=question_text,
        source={
            "title": first["title"],
            "source_file": first["source_file"],
            "page_no": first["page_no"],
            "chunk_id": first["chunk_id"],
        },
        contexts=texts,
        chunk_id=first["chunk_id"],
        degraded=degraded,
    )


# ---------------------------------------------------------------- 接口
@router.post(
    "/v1/interview/start",
    response_model=InterviewStartResponse,
    summary="开始一场 AI 面试（出第一题）",
    description=(
        "从「面试类」课件（title 含「面试」的 68 份）中检索与主题最相关的语料，"
        "由本地 7B 改写成面试题；**每题带 source 可回溯**，检索不到就降级用资料原文提问。"
        "会话保存在进程内（30 分钟 TTL），返回 session_id 供 /answer 使用。"
    ),
)
def interview_start(request: InterviewStartRequest) -> InterviewStartResponse:
    """开始面试。"""
    topic = (request.topic or "").strip()
    doc_ids = _interview_courseware_ids(topic)
    if not doc_ids:
        # 没有任何「面试类」语料：直接 503 语义（不是 500，也不编题）
        from fastapi.responses import JSONResponse

        return JSONResponse(status_code=503, content={
            "code": 5203,
            "message": "面试语料不可用：未找到 title 含「面试」的课件或向量索引未就绪，"
                       "请先灌库并推送向量",
            "degraded": True,
        })  # type: ignore[return-value]
    if not topic:
        # 空主题：用第一份面试课件的标题当主题（真实存在、可回溯）
        metas = _courseware_meta(doc_ids[:1])
        topic = next(iter(metas.values()), {}).get("title", "AI 大模型面试").replace("面试题", "")

    session = _Session(session_id=str(uuid.uuid4()), topic=topic,
                       total=int(request.total), subject_id=request.subject_id)
    question = _build_question(session, topic, request.subject_id)
    if question is None:
        from fastapi.responses import JSONResponse

        return JSONResponse(status_code=503, content={
            "code": 5203,
            "message": "面试语料不可用：向量检索无命中，请确认 Qdrant 已推送面试课件",
            "degraded": True,
        })  # type: ignore[return-value]
    session.questions.append(question)
    session.degraded = session.degraded or question.degraded
    _save_session(session)
    logger.info("[Interview] 会话 %s 开始 topic=%s total=%d", session.session_id, topic, session.total)
    return InterviewStartResponse(
        session_id=session.session_id, topic=topic, question_no=1,
        total=session.total, question=question.text,
        source=InterviewSource(**question.source), degraded=question.degraded,
    )


@router.post(
    "/v1/interview/answer",
    response_model=InterviewAnswerResponse,
    summary="提交作答（评分 / 追问 / 下一题 / 复盘报告）",
    description=(
        "对当前题评分并推进会话。作答过短或低于 60 分时同一题内追问一次，"
        "追问作答与原作答合并后重新评分。最后一题答完返回 finished=true + report。"
        "会话不存在或超过 30 分钟 → 404 SESSION_NOT_FOUND。"
    ),
)
def interview_answer(request: InterviewAnswerRequest) -> InterviewAnswerResponse:
    """提交作答。"""
    from fastapi.responses import JSONResponse

    session = _get_session(request.session_id)
    if session is None:
        return JSONResponse(status_code=404,
                            content={"detail": "SESSION_NOT_FOUND"})  # type: ignore[return-value]

    answer = request.answer.strip()
    # 会话已结束（含重复提交）：幂等返回报告，不要落到 questions[index] 越界 500
    if session.index >= session.total or session.index >= len(session.questions):
        logger.info("[Interview] 会话 %s 已结束，重复提交按幂等处理", session.session_id)
        return InterviewAnswerResponse(
            session_id=session.session_id, finished=True, total=session.total,
            question_no=session.total, degraded=session.degraded,
            report=_build_report(session),
        )
    question = session.questions[session.index]

    if question.pending_follow_up:
        # 追问作答：与原作答合并后**重新评分**，不取两次平均（避免刷分）
        merged = f"{question.first_answer}\n（追问补充）{answer}"
        result, degraded = _grade(question.text, merged, question.contexts)
        question.score = result["score"]
        question.verdict = result["verdict"]
        question.hit_points = result["hit_points"]
        question.miss_points = result["miss_points"]
        question.comment = result["comment"]
        question.degraded = question.degraded or degraded
        session.degraded = session.degraded or degraded
        question.pending_follow_up = False
        session.index += 1
        session_id, finished = session.session_id, session.index >= session.total
        follow_up_text = ""
        # 追问后的响应同样要带参考答案要点（Java 侧展示口径与首答一致）
        reference = question.contexts[0][:300] if question.contexts else ""
        score, verdict = question.score, question.verdict
        hit_points, miss_points = question.hit_points, question.miss_points
    else:
        question.first_answer = answer
        result, degraded = _grade(question.text, answer, question.contexts)
        question.score = result["score"]
        question.verdict = result["verdict"]
        question.hit_points = result["hit_points"]
        question.miss_points = result["miss_points"]
        question.comment = result["comment"]
        question.degraded = question.degraded or degraded
        session.degraded = session.degraded or degraded
        session_id = session.session_id
        score, verdict = question.score, question.verdict
        hit_points, miss_points = question.hit_points, question.miss_points
        reference = question.contexts[0][:300] if question.contexts else ""
        follow_up_text = ""
        finished = False
        # ★ 最后一题不再追问：追问会把"答满 total 题"拉长成 total+1 轮，
        #   前端按 question_no 计进度时会以为还有题（Java 侧实测踩到：
        #   第 3 题答完仍 finished=false，第 4 次作答才结束）。分数不会因此丢失——
        #   最后一题只是少了追问补充的机会，报告仍按本题得分计入。
        is_last_question = session.index == session.total - 1
        too_thin = (len(answer) < FOLLOW_UP_MIN_ANSWER_CHARS
                    or (score or 0) < FOLLOW_UP_SCORE_THRESHOLD)
        if too_thin and not is_last_question and question.follow_ups_used < MAX_FOLLOW_UPS:
            follow_up_text, follow_degraded = _make_follow_up(
                question.text, answer, question.miss_points)
            question.follow_up = follow_up_text
            question.follow_ups_used += 1
            question.pending_follow_up = True
            session.degraded = session.degraded or follow_degraded
        else:
            session.index += 1
            finished = session.index >= session.total

    # 还差题就现场生成下一题（懒生成：start 只等一题，答题时补下一题）
    next_question = ""
    if not finished and not question.pending_follow_up:
        while len(session.questions) <= session.index:
            upcoming = _build_question(session, session.topic, session.subject_id)
            if upcoming is None:
                break
            session.questions.append(upcoming)
            session.degraded = session.degraded or upcoming.degraded
        if session.index < len(session.questions):
            next_question = session.questions[session.index].text
        else:
            # 检索不出下一题（语料耗尽）：**提前收尾出报告**，不能返回
            # finished=false 却给空的 next_question——那会让前端卡在无题可答的状态
            finished = True
            logger.info("[Interview] 会话 %s 无可用下一题，提前收尾", session_id)
    elif question.pending_follow_up:
        next_question = question.follow_up

    report = _build_report(session) if finished else None
    _save_session(session)
    logger.info("[Interview] 会话 %s 第 %d 题 得分=%s 结束=%s",
                session_id, session.index + 1, score, finished)

    if finished:
        return InterviewAnswerResponse(
            session_id=session_id, finished=True, total=session.total,
            # 结束时也回报题号：原来默认 0，Java 侧日志/进度条会误读
            question_no=session.total,
            score=score, verdict=verdict, degraded=session.degraded, report=report,
        )
    return InterviewAnswerResponse(
        session_id=session_id, finished=False,
        question_no=session.index + 1, total=session.total,
        score=score, verdict=verdict, hit_points=hit_points,
        miss_points=miss_points, reference=reference,
        follow_up=follow_up_text, next_question=next_question,
        is_follow_up=bool(question.pending_follow_up),
        degraded=question.degraded,
    )
