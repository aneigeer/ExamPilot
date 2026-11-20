"""``/v1/interview`` —— AI 面试（出题 / 评分 / 追问 / 复盘报告）。

## 与主问答链路的区别

``/v1/ask`` 是**无状态**问答；面试是**有状态多轮**：同一场会话要保持题目进度、
每题得分与是否已追问，最后一题答完产出复盘报告。所以单独一个路由 +
进程内会话表（``uuid4`` + 30 分钟 TTL，不建新表——面试是短时交互，
落库反而引入清理负担）。

## 出题来源铁律（**禁止凭空编题**）

2026-11-11 整改：旧实现从 ``courseware.title LIKE '%面试%'`` 圈课件、再用
Qdrant 向量检索，强依赖「面试课件标题」与「向量索引已推送」，两者任一缺失就
直接 503 —— 这正是用户实测「模拟面试没了」的根因（第8周 D-03 已修正为
「语料未入库/未索引」，不是 Qdrant 端口问题）。

现在改为**直接查 MySQL ``ai_chunk``**（634 篇面试 md 切块已写入，实测
``subject_id=211``「AI 大模型面试」有 2424 个 ACTIVE 块 / 156 个真实题目标题）：

1. 圈出「INTERVIEW 面试知识点」：``subject.name`` 含「面试」或
   ``exam_category.code='INTERVIEW'``（兼容后续新增类别）；
2. 在这些 ``ai_chunk`` 块里按**专题关键词**（topic，规范化后 LIKE 标题/正文）
   与**难度**（difficulty 列）检索，同标题限流避免一篇文档霸占全部参考块；
   **不依赖 Qdrant 向量索引是否已推送**，从根上消除 503；
3. 本地 3B 只负责「把资料改写成一道面试题 / 评分 / 点评 / 追问」，
   提示词明确禁止超出资料；
4. 每题必带 ``source``（标题 / 源文件 / 页码 / chunk_id），Java 与前端可回溯；
5. 3B 不可用或语料不足 → **降级**：直接拿资料里的真实题目行当题目
   （``degraded=true`` + ``degrade_reason`` 明确提示），**不 503、不空题**；
6. 3B 单模型下超时可控：单次 LLM 调用硬超时 ``INTERVIEW_LLM_TIMEOUT_SECONDS``
   （远小于 .env 的 90s），且一次 ``/answer`` 有 LLM 调用次数预算，
   追问与点评合并进同一次评分调用，不串行拖死 SSE。

## 调用方向（已核对 Java 侧）

``backend/.../AiInterviewService.java`` 是**纯透传代理**：``start/answer`` 直接
POST 到本服务的 ``/interview/start`` / ``/interview/answer``，出题、评分、报告
全在 Python 侧完成，Java 只做鉴权 / 归属 / 落库 / 错误翻译。
因此**直接改这两个既有路由即可，无需新增任何接口**（禁止造没人调用的接口）。
新增的 ``degrade_reason`` / ``difficulty`` 字段经 Java ``JsonNode`` 透传，
不会破坏既有契约。

## 评分可解释

* 先检索参考答案块，再把「题目 + 参考资料 + 学生作答」交给 3B，
  要求输出 ``{score, verdict, hit_points, miss_points, comment}`` 五元组；
* 3B 不可用 → 抽取式兜底：按「作答字符 bigram 被参考资料覆盖的比例」折算分数，
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

import contextvars
import json
import logging
import re
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Dict, Iterator, List, Optional, Sequence, Tuple

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
#: 单题送入 3B 的参考块数
CONTEXT_CHUNKS = 5
#: 单个参考块截断长度
CONTEXT_CHARS = 700

#: 面试链路单次 LLM 调用的**硬超时**（秒）。必须远小于 .env 的
#: ``LLM_TIMEOUT_SECONDS=90``：一次 /answer 可能串行多次调用，若每次都按 90s
#: 计时，本地 3B 卡顿时会把 SSE 拖死。20s 足够 3B 产出一题/一段评分。
INTERVIEW_LLM_TIMEOUT_SECONDS = 20.0
#: 单次 /interview/start 的 LLM 调用预算（只用于出题）
START_LLM_CALL_BUDGET = 1
#: 单次 /interview/answer 的 LLM 调用预算（评分 + 下一题出题共享此额度）。
#: 超过预算的调用直接抛 InterviewLlmError 走抽取式/模板降级，
#: 从结构上保证一次作答不会触发多次串行 LLM 调用。
ANSWER_LLM_CALL_BUDGET = 2
#: 同一标题最多取几块，避免一篇长文档霸占全部参考块（与 /v1/search 同口径）
MAX_CHUNKS_PER_TITLE = 2

#: 面试知识点科目判定：subject.name 含「面试」，或 exam_category.code='INTERVIEW'
INTERVIEW_SUBJECT_LIKE = "%面试%"
INTERVIEW_EXAM_CODE = "INTERVIEW"

# ---------------------------------------------------------------- 单次请求上下文
# 用 ContextVar 而不是给每个函数加参数：既有测试用 monkeypatch 替换
# ``_retrieve_context`` / ``_chat``（固定签名），加位置参数会让桩全部失效。
# ContextVar 在线程池里按请求隔离，生产实现读、桩不读，天然互不影响。

#: 本次请求的 LLM 调用上限（None = 不限，例如直接单测某个内部函数时）
_LLM_BUDGET: contextvars.ContextVar[Optional[int]] = contextvars.ContextVar(
    "interview_llm_budget", default=None
)
#: 本次请求已用掉的 LLM 调用次数
_LLM_USED: contextvars.ContextVar[int] = contextvars.ContextVar(
    "interview_llm_used", default=0
)
#: 本次面试会话的难度筛选（由 /start 写入，生产 _retrieve_context 读取）
_CURRENT_DIFFICULTY: contextvars.ContextVar[Optional[int]] = contextvars.ContextVar(
    "interview_difficulty", default=None
)


@contextmanager
def _llm_call_budget(limit: int) -> Iterator[None]:
    """给一次 HTTP 请求设定 LLM 调用次数上限（退出时复位，不污染其它请求）。"""
    budget_token = _LLM_BUDGET.set(limit)
    used_token = _LLM_USED.set(0)
    try:
        yield
    finally:
        _LLM_BUDGET.reset(budget_token)
        _LLM_USED.reset(used_token)


# ---------------------------------------------------------------- 请求/响应模型
class InterviewStartRequest(BaseModel):
    """开始一场面试。"""

    topic: str = Field(default="", max_length=60, description="专题名/关键词，可为空（由服务挑）")
    total: int = Field(default=3, ge=1, le=5, description="题目数量（1-5）")
    subject_id: Optional[int] = Field(default=None, description="限定科目（可选）")
    #: 难度选题（1-5，对应 ai_chunk.difficulty）；不传则不限难度
    difficulty: Optional[int] = Field(default=None, ge=1, le=5, description="难度筛选（可选）")


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
    degraded: bool = Field(default=False, description="题目是否因 3B 不可用/语料不足而降级")
    #: 降级原因（语料未就绪 / 3B 超时等），非降级时为空串
    degrade_reason: str = ""


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
    #: 结束原因：COMPLETED（答满 total）/ EARLY_EXHAUSTED（语料耗尽提前收尾）。
    #: 契约未列，属附加信息；客户端据此可提示"计划 N 题、实评 M 题"。
    finish_reason: str = ""
    #: 降级原因（3B 不可用 / 语料不足），非降级时为空串
    degrade_reason: str = ""


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
    difficulty: Optional[int] = None
    degrade_reason: str = ""
    questions: List[_Question] = field(default_factory=list)
    index: int = 0
    created_at: float = field(default_factory=time.time)
    touched_at: float = field(default_factory=time.time)
    degraded: bool = False
    #: 已用过的 chunk_id，避免连续多题撞同一段资料
    used_chunk_ids: List[int] = field(default_factory=list)

    def scored_count(self) -> int:
        """已评分的题目数（= 报告 per_question 的条数）。"""
        return len([q for q in self.questions if q.score is not None])


_SESSIONS: Dict[str, _Session] = {}
# A-02：改用可重入锁。interview_answer 会在外层持锁完成
# 「取会话 → 判状态 → 改状态 → 写回」，而 _get_session / _save_session 内部
# 各自也 with 同一把锁；非重入 Lock 会自锁死，RLock 保证重入安全。
_SESSIONS_LOCK = threading.RLock()


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
    """面试链路的 3B 调用失败（触发降级，不向上抛）。"""


def _chat(system: str, user: str, *, max_tokens: int = 600,
          temperature: float = 0.3) -> str:
    """调用本地 Ollama 3B 的 OpenAI 兼容端点。

    与 ``llm_client`` 的区别：面试需要**自定义 system**（面试官 / 评分官）
    且要求 JSON 输出，不能复用带引用约束的问答提示词。

    两道 3B 单模型保护（2026-11-11 整改）：
    * **单次调用硬超时** ``INTERVIEW_LLM_TIMEOUT_SECONDS``，不跟随 .env 的 90s；
    * **单次请求 LLM 调用预算**：超过 ``_LLM_BUDGET`` 直接抛错走降级，
      保证一次 /answer 不会串行发起多次 LLM 调用把 SSE 拖死。

    Raises:
        InterviewLlmError: 调用预算耗尽、未配置 Key、缺 httpx、超时或上游异常。
    """
    budget = _LLM_BUDGET.get()
    if budget is not None:
        used = _LLM_USED.get()
        if used >= budget:
            raise InterviewLlmError(
                f"单次面试请求 LLM 调用已达上限 {budget} 次，已降级为抽取式/模板"
            )
        _LLM_USED.set(used + 1)

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
    # 硬超时：3B 卡顿时绝不按 .env 的 90s 干等，超时即降级（抽取式/模板）。
    timeout = min(settings.timeout_seconds, INTERVIEW_LLM_TIMEOUT_SECONDS)
    try:
        # trust_env=False：本地 Ollama 调用必须绕过系统代理（见 llm_client 同款注释）
        with httpx.Client(timeout=timeout, trust_env=False) as client:
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
    """从模型输出中提取 JSON 对象（容忍代码块、前后废话与 3B 常见语法瑕疵）。

    2026-11-11 实测：本地 3B 生成的 JSON 经常踩这几类坑——
    中文引号没转义、字符串里裸换行、尾逗号、把数组写成半截。
    先做四轮修复，仍失败则按字段正则兜底，尽量不把「模型已作答」误判成降级。
    """
    match = _JSON_BLOCK.search(raw or "")
    if not match:
        raise InterviewLlmError("模型输出不含 JSON")
    text = match.group(0)

    candidates = [
        text,
        re.sub(r",\s*([}\]])", r"\1", text),                        # 去尾逗号
        text.replace("“", '\\"').replace("”", '\\"'),                # 中文引号 → 转义
        re.sub(r"(?<=[^\n])\n(?=[^\n]*\")", r"\\n", text),          # 字符串内裸换行
    ]
    for cand in candidates:
        try:
            data = json.loads(cand)
            if isinstance(data, dict):
                return data
        except json.JSONDecodeError:
            continue

    # 字段级兜底：score 必填，其余尽力而为
    data: Dict[str, Any] = {}
    m = re.search(r'"score"\s*:\s*(-?\d+(?:\.\d+)?)', text)
    if not m:
        raise InterviewLlmError("JSON 解析失败且无 score 字段")
    data["score"] = float(m.group(1))
    for key in ("verdict", "comment", "follow_up"):
        m = re.search(r'"%s"\s*:\s*"([^"\n]{0,200})"' % key, text)
        if m:
            data[key] = m.group(1)
    for key in ("hit_points", "miss_points"):
        m = re.search(r'"%s"\s*:\s*\[(.*?)\]' % key, text, re.S)
        if m:
            data[key] = re.findall(r'"([^"\n]{1,80})"', m.group(1))
    return data


# ---------------------------------------------------------------- 语料检索
#: 书名号标题（《详细说下RAG的流程》）——面试语料里通常就是这道题的题面
_BOOK_TITLE_RE = re.compile(r"^《(.{2,60})》\s*$", re.M)
#: 真实问句行：编号（1. / 一、 / •三、）开头或以「？」结尾（逐行匹配）
_QUESTION_LINE_RE = re.compile(
    r"^(?:[一二三四五六七八九十]+[.、．]|\d+[.、．]|[•*]\s*)?(.{4,60}[？?])\s*$", re.M
)


def _topic_keyword(topic: str) -> str:
    """把专题名规范化为检索核心词（``RAG篇`` → ``RAG``，``Transformer篇`` → ``Transformer``）。

    另一 agent 写入的专题名带「篇/专题/系列」后缀，而 ``ai_chunk.title`` 里是
    「RAG关键痛点和解决方案面试题」这类标题，直接 LIKE ``%RAG篇%`` 必然零命中。
    """
    keyword = (topic or "").strip()
    for suffix in ("面试题", "专题", "系列", "部分", "篇"):
        keyword = keyword.replace(suffix, "")
    return keyword.strip()


def _interview_subject_ids() -> List[int]:
    """圈出「INTERVIEW 面试知识点」对应的 ``subject.id`` 集合。

    双口径兼容另一 agent 的两种标记方式：``subject.name`` 含「面试」、
    或 ``exam_category.code='INTERVIEW'``。任一查询异常返回空列表，
    由上层走「明确降级」而非 500/503。
    """
    import pymysql

    try:
        conn = pymysql.connect(**db_config())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT DISTINCT s.id FROM subject s "
                    "LEFT JOIN exam_category e ON e.id = s.exam_category_id "
                    "WHERE s.name LIKE %s OR e.code = %s",
                    (INTERVIEW_SUBJECT_LIKE, INTERVIEW_EXAM_CODE),
                )
                return [int(row[0]) for row in cur.fetchall()]
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001 - 语料不可用时走降级，不让面试 500
        logger.warning("[Interview] 面试科目查询失败，走降级：%s", type(exc).__name__)
        return []


def _interview_courseware_ids(topic: str) -> List[int]:
    """INTERVIEW 面试语料**可用性探针**：返回命中的 ``ai_chunk.id``。

    保留函数名与 3 参数形态是为了兼容既有测试桩与 ``interview_start`` 调用点。
    旧实现查 ``courseware.title LIKE '%面试%'`` 且强依赖 Qdrant 索引，是 503 根因；
    现在直接查 ``ai_chunk``（subject 属面试类），可选按专题关键词收窄，
    主题收窄无命中时退回全量面试语料，保证出得了题。
    """
    import pymysql

    subject_ids = _interview_subject_ids()
    if not subject_ids:
        return []
    keyword = _topic_keyword(topic)
    fmt = ",".join(["%s"] * len(subject_ids))
    sql = (
        "SELECT id FROM ai_chunk WHERE status='ACTIVE' "
        "AND source_type='COURSEWARE' AND subject_id IN (" + fmt + ")"
    )
    params: List[Any] = list(subject_ids)
    if keyword:
        sql += " AND (title LIKE %s OR content LIKE %s)"
        params.extend([f"%{keyword}%", f"%{keyword}%"])
    sql += " LIMIT 200"
    try:
        conn = pymysql.connect(**db_config())
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                ids = [int(row[0]) for row in cur.fetchall()]
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001
        logger.warning("[Interview] 面试语料探针失败，走降级：%s", type(exc).__name__)
        return []
    if ids or not keyword:
        return ids
    # 专题收窄后为空 → 退回全部面试语料
    return _interview_courseware_ids("")


def _courseware_meta(ids: Sequence[int]) -> Dict[int, Dict[str, Any]]:
    """``ai_chunk.id`` → ``{title, source_file}``（出题来源要可回溯到源文件）。

    ``source_file`` 来自关联的 ``courseware`` 行；表缺失/关联不到时留空串，
    不影响出题（title + chunk_id 已足够回溯）。
    """
    if not ids:
        return {}
    import pymysql

    fmt = ",".join(["%s"] * len(ids))
    try:
        conn = pymysql.connect(**db_config())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT c.id, c.title, COALESCE(cw.source_file, '') "
                    "FROM ai_chunk c "
                    "LEFT JOIN courseware cw "
                    "  ON cw.id = c.source_id AND c.source_type = 'COURSEWARE' "
                    f"WHERE c.id IN ({fmt})",
                    list(ids))
                return {
                    int(r[0]): {"title": str(r[1] or ""), "source_file": str(r[2] or "")}
                    for r in cur.fetchall()
                }
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001 - 回溯信息取不到不影响出题
        logger.warning("[Interview] 来源元数据查询失败：%s", type(exc).__name__)
        return {}


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
    """从 ``ai_chunk`` 直查 INTERVIEW 面试知识点语料（**不依赖 Qdrant 向量索引**）。

    为什么不用向量：634 篇面试语料切块写入 ``ai_chunk`` 后，向量索引
    （``vector_synced``）不一定已推送；旧实现走 Qdrant 检索无命中就直接 503。
    ``ai_chunk`` 自带 ngram 全文索引与 ``title``，用关键词直查既能按专题/难度
    选题，又能在毫秒级返回，且天然绕开 503 根因。

    ``subject_id`` 参数保留以兼容既有调用/测试桩，但**不作为硬过滤**：Java 侧
    start 常传校内课程科目（如 5），硬过滤会把面试语料全部排除、重新制造空题。

    Returns:
        ``[{"chunk_id", "title", "source_id", "page_no", "content", "source_file"}]``；
        检索不到返回空列表（调用方负责明确降级）。
    """
    import pymysql

    subject_ids = _interview_subject_ids()
    if not subject_ids:
        return []
    keyword = _topic_keyword(topic)
    difficulty = _CURRENT_DIFFICULTY.get()
    fmt = ",".join(["%s"] * len(subject_ids))
    sql = (
        "SELECT c.id, c.title, c.source_id, c.page_no, c.content, c.difficulty, "
        "       COALESCE(cw.source_file, '') "
        "FROM ai_chunk c "
        "LEFT JOIN courseware cw "
        "  ON cw.id = c.source_id AND c.source_type = 'COURSEWARE' "
        "WHERE c.status = 'ACTIVE' AND c.source_type = 'COURSEWARE' "
        "  AND c.subject_id IN (" + fmt + ")"
    )
    params: List[Any] = list(subject_ids)
    if difficulty is not None:
        sql += " AND c.difficulty = %s"
        params.append(int(difficulty))
    if keyword:
        sql += " AND (c.title LIKE %s OR c.content LIKE %s)"
        params.extend([f"%{keyword}%", f"%{keyword}%"])
    # 候选池放大到 400（面试语料实测 2424 块），让「带真实问句」的块有机会
    # 进入排序；只取前 80 条时会先撞上按 id 顺序导入的章节封面页。
    sql += " LIMIT 400"
    try:
        conn = pymysql.connect(**db_config())
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                rows = cur.fetchall()
        finally:
            conn.close()
    except Exception as exc:  # noqa: BLE001 - 语料/库不可用时走降级，不 500
        logger.warning("[Interview] 面试语料检索失败，走降级：%s", type(exc).__name__)
        return []

    if not rows and keyword:
        # 专题收窄无命中 → 退回全量面试语料（keyword 为空时不再递归）
        logger.info("[Interview] 专题「%s」无命中，退回全量面试语料", topic)
        return _retrieve_context("", subject_id, exclude_ids)

    excluded = {int(i) for i in exclude_ids}
    scored: List[Tuple[int, int, Any]] = []
    for row in rows:
        chunk_id = int(row[0])
        if chunk_id in excluded:
            continue
        title = str(row[1] or "")
        content = str(row[4] or "")
        score = 0
        if keyword:
            if keyword in title:
                score += 5
            if keyword in content:
                score += 1
        # 含真实面试问句（「？」「1.」「一、」）的块优先出题；
        # 只有书名号题面的多半是文档封面，权重低；章节封面页再降权。
        has_ask = bool(_QUESTION_LINE_RE.search(content)) or any(
            _TOPIC_LINE.match(line.strip().strip("*•")) for line in content.splitlines()
        )
        if has_ask:
            score += 4
        elif _BOOK_TITLE_RE.search(content):
            score += 1
        if "专题" in title and not has_ask:
            score -= 2
        scored.append((score, chunk_id, row))
    scored.sort(key=lambda item: (item[0], item[1]), reverse=True)

    results: List[Dict[str, Any]] = []
    per_title: Dict[str, int] = {}
    for _score, chunk_id, row in scored:
        title = str(row[1] or "")
        if per_title.get(title, 0) >= MAX_CHUNKS_PER_TITLE:
            continue
        text = str(row[4] or "").strip()
        if not text:
            continue
        per_title[title] = per_title.get(title, 0) + 1
        results.append({
            "chunk_id": chunk_id,
            "title": title,
            "source_id": int(row[2] or 0),
            "page_no": row[3],
            "source_file": str(row[6] or ""),
            "content": text,
        })
        if len(results) >= CONTEXT_CHUNKS:
            break
    return results


# ---------------------------------------------------------------- 出题
_QUESTION_SYSTEM = (
    "你是 AI 大模型方向的面试官。硬性规则：\n"
    "1. 只依据【资料】出题，资料之外的知识点一律不许出现；\n"
    "2. 输出**一句**中文面试问题，不超过 60 字，不要给答案，不要编号，不要引号；\n"
    "3. 优先考察原理、对比、工程实践这类能追问的点，避免「是什么」的死记硬背。"
)

_TOPIC_LINE = re.compile(r"^\s*\d+[.、．]\s*(.{4,50})$")


def _fallback_question(topic: str, contexts: List[str]) -> str:
    """3B 不可用 / 调用预算耗尽时的降级出题：**直接取语料里的真实题目行**。

    优先级：书名号题面（《xxx》）→ 带「？」的问句行 → 编号话题行 → 通用兜底。
    前三类都来自 ``ai_chunk`` 的真实面试语料，绝不凭空编题；最后一类只在
    完全无语料时使用，且会由调用方标注 ``degraded=true`` + ``degrade_reason``。
    """
    for text in contexts:
        # ① 书名号题面：面试 md 的第一行通常就是这道题的题面
        book = _BOOK_TITLE_RE.search(text)
        if book:
            question = book.group(1).strip()
            if len(question) >= 4:
                return question if question.endswith(("？", "?")) else f"{question}？"
        for raw_line in text.splitlines():
            line = raw_line.strip().strip("*•").strip()
            # ② 真实问句行（以「？」结尾）
            ask = _QUESTION_LINE_RE.match(line)
            if ask:
                question = ask.group(1).strip()
                if len(question) >= 4:
                    return question
            # ③ 编号话题行（兼容既有语料格式与测试桩）
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
    "comment（一句评语，40 字内）、"
    "follow_up（针对遗漏要点的一句追问，不超过 40 字）；\n"
    "3. 作答与资料无关或明显错误 → score 低于 40；答到要点 → 70 以上；\n"
    "4. 不要因为表达口语化扣分，只看技术要点是否命中。\n"
    "5. 评语与追问都由你一次性给出，不要分段输出。"
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
    """抽取式评分兜底（无 3B 时）：按作答被资料覆盖的比例折算。

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
    if not contexts:
        # 降级会话（语料不足）没有参考资料：不调 3B，也不做抽取式重合度
        # （空参考会让 grounding_ratio 除零），直接给出明确的「未评分」结果。
        return {
            "score": 0, "verdict": "未评分",
            "hit_points": [], "miss_points": [],
            "comment": "面试语料未就绪，本题未做模型判分（降级会话）",
            "follow_up": "",
        }, True
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
            # 追问与点评合并进同一次评分调用（3B 单模型下不再单独发起一次 LLM）
            "follow_up": str(data.get("follow_up") or "").strip()[:80],
        }
        return result, False
    except (InterviewLlmError, ValueError, TypeError) as exc:
        logger.info("[Interview] 评分降级：%s", exc)
        degraded_result = _extractive_grade(answer, reference)
        degraded_result["follow_up"] = ""
        return degraded_result, True


# ---------------------------------------------------------------- 追问
def _make_follow_up(question: str, answer: str, misses: List[str],
                    model_follow_up: str = "") -> Tuple[str, bool]:
    """产出一条追问。返回 ``(追问, 是否降级)``。

    **不再单独发起一次 LLM 调用**：3B 生成的追问已合并进 ``_grade`` 同一次
    评分调用的 ``follow_up`` 字段（``_GRADE_SYSTEM`` 第 2/5 条）。这样一次
    ``/answer`` 最多两次 LLM（评分 + 下一题出题），不会串行拖死 SSE。
    模型没给 follow_up 或评分走了抽取式降级时，用确定性模板兜底（``degraded=True``）。

    Args:
        question: 当前面试题（保留参数以兼容既有调用点）。
        answer: 学生作答（保留参数以兼容既有调用点）。
        misses: 评分得到的遗漏要点。
        model_follow_up: 同一次评分调用里 3B 给出的追问。

    Returns:
        ``(追问文本, 是否非模型产出)``。
    """
    if model_follow_up and len(model_follow_up.strip()) >= 4:
        text = re.sub(r"^(追问|问题)[:：]\s*", "", model_follow_up.strip()).strip("\"'“”")
        if len(text) >= 4:
            return text.splitlines()[0].strip(), False
    logger.info("[Interview] 追问降级：评分未产出 follow_up，使用模板追问")
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
        suggestions.append("本次评分含降级环节（3B 不可用时的抽取式评分），结论需人工复核")
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
    """检索资料并生成一道题；检索不到返回 None（由调用方兜底）。

    难度通过 ContextVar 传给生产 ``_retrieve_context``，避免给该函数加位置参数
    （既有测试用固定 3 参数的 lambda 桩替换它）。
    """
    difficulty_token = _CURRENT_DIFFICULTY.set(session.difficulty)
    try:
        contexts = _retrieve_context(topic, subject_id, session.used_chunk_ids)
    finally:
        _CURRENT_DIFFICULTY.reset(difficulty_token)
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
#: 语料不足时的明确降级提示（写入 degrade_reason 与 source.title）
CORPUS_DEGRADE_REASON = (
    "面试语料未就绪：ai_chunk 中未检索到 INTERVIEW 面试知识点"
    "（subject.name 含「面试」或 exam_category.code=INTERVIEW），或 MySQL 不可用。"
    "本题为通用兜底题，未取自真实语料；请确认 634 篇面试语料已写入 ai_chunk 后重试。"
)


def _degraded_start(request: InterviewStartRequest, reason: str) -> InterviewStartResponse:
    """语料不可用时返回 **200 + 一道明确降级的题**，而不是 503 / 空题。

    2026-11-11 整改要求：模拟面试「语料不足时给出明确降级提示而不是 503 或空题」。
    旧实现在这里直接 503/5203，Java 透传后用户只看到「面试服务暂不可用」。
    现在如实返回一道通用兜底题（``_fallback_question`` 的既有设计内降级），
    并用 ``degraded=true`` + ``degrade_reason`` + ``source.title`` 三处标明降级，
    绝不假装是正常题目。会话仍可用（作答走抽取式降级评分）。
    """
    topic = (request.topic or "").strip() or "AI 大模型面试"
    question_text = _fallback_question(topic, [])
    session = _Session(
        session_id=str(uuid.uuid4()), topic=topic, total=int(request.total),
        subject_id=request.subject_id, difficulty=request.difficulty,
        degrade_reason=reason, degraded=True,
    )
    question = _Question(
        text=question_text,
        source={"title": "（降级：面试语料未就绪）", "source_file": "",
                "page_no": None, "chunk_id": 0},
        contexts=[], degraded=True,
    )
    session.questions.append(question)
    _save_session(session)
    logger.warning("[Interview] 会话 %s 以降级题目开始：%s", session.session_id, reason)
    return InterviewStartResponse(
        session_id=session.session_id, topic=topic, question_no=1,
        total=session.total, question=question_text,
        source=InterviewSource(**question.source), degraded=True,
        degrade_reason=reason,
    )


@router.post(
    "/v1/interview/start",
    response_model=InterviewStartResponse,
    summary="开始一场 AI 面试（出第一题）",
    description=(
        "优先从 ai_chunk 的 INTERVIEW 面试知识点（634 篇面试语料切块）按专题/难度检索组题，"
        "由本地 3B 改写成面试题；**每题带 source 可回溯**。3B 不可用或语料不足时"
        "返回 200 + 明确降级提示（degraded/degrade_reason），不再 503、不空题。"
        "会话保存在进程内（30 分钟 TTL），返回 session_id 供 /answer 使用。"
    ),
)
def interview_start(request: InterviewStartRequest) -> InterviewStartResponse:
    """开始面试（3B 单模型下只出 1 次 LLM；语料不足走明确降级，不再 503）。"""
    topic = (request.topic or "").strip()
    with _llm_call_budget(START_LLM_CALL_BUDGET):
        doc_ids = _interview_courseware_ids(topic)
        if not doc_ids:
            return _degraded_start(request, CORPUS_DEGRADE_REASON)
        if not topic:
            # 空主题：用第一条真实面试语料的标题当主题（真实存在、可回溯）
            metas = _courseware_meta(doc_ids[:1])
            topic = next(iter(metas.values()), {}).get("title", "AI 大模型面试").replace("面试题", "")

        session = _Session(
            session_id=str(uuid.uuid4()), topic=topic,
            total=int(request.total), subject_id=request.subject_id,
            difficulty=request.difficulty,
        )
        question = _build_question(session, topic, request.subject_id)
        if question is None:
            return _degraded_start(request, CORPUS_DEGRADE_REASON)
        reason = "3B 不可用，题目取自真实语料原文" if question.degraded else ""
        session.degrade_reason = reason
        session.questions.append(question)
        session.degraded = session.degraded or question.degraded
        _save_session(session)
        logger.info("[Interview] 会话 %s 开始 topic=%s total=%d difficulty=%s degraded=%s",
                    session.session_id, topic, session.total,
                    request.difficulty, question.degraded)
        return InterviewStartResponse(
            session_id=session.session_id, topic=topic, question_no=1,
            total=session.total, question=question.text,
            source=InterviewSource(**question.source), degraded=question.degraded,
            degrade_reason=reason,
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
    """提交作答（A-02：会话状态迁移整体在 ``_SESSIONS_LOCK`` 内完成）。

    「取会话 → 判状态 → 改状态 → 写回」必须由同一把锁保护，否则同一
    ``session_id`` 并发提交会重复评分、``session.index`` 跳变。
    锁内包含 ``_grade``（可能调用大模型）与懒生成下一题，因此不同会话
    会被串行化——属本轮登记的架构级已知限制，见 F3 修复记录 §③。
    """
    with _SESSIONS_LOCK:
        # 一次作答最多 ANSWER_LLM_CALL_BUDGET 次 LLM 调用（评分 + 下一题出题共享），
        # 超预算的调用由 _chat 直接抛错走抽取式/模板降级，避免 3B 串行拖死 SSE。
        with _llm_call_budget(ANSWER_LLM_CALL_BUDGET):
            return _interview_answer_locked(request)


def _interview_answer_locked(request: InterviewAnswerRequest) -> InterviewAnswerResponse:
    """提交作答（原实现；**调用方必须持有 ``_SESSIONS_LOCK``**）。"""
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
            # 题号报**实评题数**：提前收尾时 total(计划) 与实评数不同，报 total 会与
            # report.per_question 条数矛盾（Java 侧实测：question_no=5 但只评了 3 题）
            question_no=session.scored_count(), degraded=session.degraded,
            degrade_reason=session.degrade_reason,
            finish_reason=("COMPLETED" if session.index >= session.total
                           else "EARLY_EXHAUSTED"),
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
        # 3B 在同一轮评分里已产出追问（_GRADE_SYSTEM），不再单独调 LLM
        grade_follow_up = str(result.get("follow_up") or "")
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
                question.text, answer, question.miss_points, grade_follow_up)
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
    degrade_reason = session.degrade_reason or (
        "本次含降级环节（3B 不可用或语料不足），结论需人工复核"
        if session.degraded else ""
    )
    logger.info("[Interview] 会话 %s 第 %d 题 得分=%s 结束=%s",
                session_id, session.index + 1, score, finished)

    if finished:
        return InterviewAnswerResponse(
            session_id=session_id, finished=True, total=session.total,
            # 题号报**实评题数**（正常结束 = total；提前收尾 = 实评数），
            # 保证 question_no 与 report.per_question 条数永远自洽
            question_no=session.scored_count(),
            finish_reason=("COMPLETED" if session.index >= session.total
                           else "EARLY_EXHAUSTED"),
            score=score, verdict=verdict, degraded=session.degraded,
            degrade_reason=degrade_reason, report=report,
        )
    return InterviewAnswerResponse(
        session_id=session_id, finished=False,
        question_no=session.index + 1, total=session.total,
        score=score, verdict=verdict, hit_points=hit_points,
        miss_points=miss_points, reference=reference,
        follow_up=follow_up_text, next_question=next_question,
        is_follow_up=bool(question.pending_follow_up),
        degraded=question.degraded, degrade_reason=degrade_reason,
    )
