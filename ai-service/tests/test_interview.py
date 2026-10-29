"""AI 面试接口测试（start / answer / 追问 / 结束报告 / 降级 / 会话不存在）。

**全部用桩**：MySQL 语料圈选与 Qdrant 检索由 ``_retrieve_context`` 桩替代，
7B 由 ``_chat`` 桩替代，测试不依赖数据库、向量库与 Ollama。
"""

from __future__ import annotations

import json
from typing import Any, Dict, List

import pytest

from app.routers import interview as iv


# --------------------------------------------------------------------------------------
# 桩
# --------------------------------------------------------------------------------------

_CORPUS: List[Dict[str, Any]] = [
    {"chunk_id": 101, "title": "17种RAG优化策略", "source_id": 38, "page_no": None,
     "source_file": "02 AI大模型/011 279.17种RAG优化策略.docx",
     "content": "1. 召回质量差的原因\n"
                "2. 分块粒度不合理会切断语义，导致召回片段缺上下文。\n"
                "3. 缺少 rerank 会让粗排结果直接进上下文。\n"
                "4. 查询改写可以缓解口语化提问。\n"
                "5. 混合检索（BM25 + 向量）能覆盖专有名词场景。"},
    {"chunk_id": 102, "title": "RAG关键痛点和解决方案面试题", "source_id": 67, "page_no": 3,
     "source_file": "01 AI大模型面试系列/018 RAG关键痛点.docx",
     "content": "5. RAG 的瓶颈在检索而非生成。\n"
                "6. 评估要分开看召回率和精确率，端到端看忠实度。\n"
                "7. rerank 的效果用 NDCG@10 与人工标注对照评估。"},
]


def _stub_corpus(monkeypatch: pytest.MonkeyPatch) -> None:
    """让语料圈选与检索返回固定内容（不连 MySQL / Qdrant）。"""
    monkeypatch.setattr(iv, "_interview_courseware_ids", lambda topic: [38, 67])
    monkeypatch.setattr(
        iv, "_retrieve_context",
        lambda topic, subject_id, exclude_ids: [
            c for c in _CORPUS if c["chunk_id"] not in set(exclude_ids)
        ] or _CORPUS)


def _stub_llm(monkeypatch: pytest.MonkeyPatch, *, score: int = 72) -> None:
    """7B 桩：按 system 提示词分流（出题 / 评分 / 追问）。"""
    def _chat(system: str, user: str, **kwargs: Any) -> str:
        if "评分官" in system:
            return json.dumps({
                "score": score, "verdict": "部分正确",
                "hit_points": ["提到了召回"], "miss_points": ["没提 chunk 切分粒度"],
                "comment": "要点基本到位，细节不足",
            }, ensure_ascii=False)
        if "追问" in system:
            return "那你会怎么评估 rerank 的效果？"
        return "请说说 RAG 中召回质量差有哪些常见原因，你会怎么优化？"

    monkeypatch.setattr(iv, "_chat", _chat)


def _stub_llm_down(monkeypatch: pytest.MonkeyPatch) -> None:
    """7B 不可用：所有调用抛 InterviewLlmError，触发全链路降级。"""
    def _chat(system: str, user: str, **kwargs: Any) -> str:
        raise iv.InterviewLlmError("stub: llm down")

    monkeypatch.setattr(iv, "_chat", _chat)


@pytest.fixture(autouse=True)
def _clean_sessions() -> Any:
    """每个用例前后清空会话表，避免串场。"""
    iv.reset_sessions()
    yield
    iv.reset_sessions()


# --------------------------------------------------------------------------------------
# start
# --------------------------------------------------------------------------------------

def test_start_returns_first_question(client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """正常出题：带 source 可回溯，question_no=1。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch)
    resp = client.post("/v1/interview/start", json={"topic": "RAG", "total": 3})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["topic"] == "RAG"
    assert body["question_no"] == 1 and body["total"] == 3
    assert body["question"]
    assert body["source"]["title"] == "17种RAG优化策略"
    assert body["source"]["source_file"].endswith(".docx")
    assert body["session_id"]


def test_start_with_empty_topic_picks_real_topic(
        client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """空主题：从面试课件里挑，topic 不为空。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch)
    monkeypatch.setattr(iv, "_courseware_meta",
                        lambda ids: {38: {"title": "Prompting相关面试题", "source_file": "x.docx"}})
    body = client.post("/v1/interview/start", json={"topic": "", "total": 1}).json()
    assert body["topic"] == "Prompting相关"


def test_start_rejects_bad_total(client: Any) -> None:
    """total 越界（0 或 6）→ 400。"""
    assert client.post("/v1/interview/start", json={"topic": "RAG", "total": 0}).status_code == 400
    assert client.post("/v1/interview/start", json={"topic": "RAG", "total": 6}).status_code == 400


def test_start_degrades_when_llm_down(client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """7B 不可用：仍出题（用资料话题行），degraded=true。"""
    _stub_corpus(monkeypatch)
    _stub_llm_down(monkeypatch)
    body = client.post("/v1/interview/start", json={"topic": "RAG", "total": 1}).json()
    assert body["degraded"] is True
    assert "召回质量差的原因" in body["question"]  # 来自资料原文，未编造


def test_start_without_corpus_returns_503(client: Any,
                                         monkeypatch: pytest.MonkeyPatch) -> None:
    """没有面试语料 → 503 语义（不编题）。"""
    monkeypatch.setattr(iv, "_interview_courseware_ids", lambda topic: [])
    resp = client.post("/v1/interview/start", json={"topic": "RAG"})
    assert resp.status_code == 503
    assert resp.json()["degraded"] is True


# --------------------------------------------------------------------------------------
# answer
# --------------------------------------------------------------------------------------

def _start(client: Any, total: int = 1) -> Dict[str, Any]:
    return client.post("/v1/interview/start",
                       json={"topic": "RAG", "total": total}).json()


def test_answer_scores_and_finishes(client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """单题会话：作答 → 评分 + 结束报告。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch, score=72)
    start = _start(client, total=1)
    body = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"],
        "answer": "召回差常见原因是分块粒度不合理，也会因为没有 rerank，"
                  "另外查询改写和混合检索都能缓解。",
    }).json()
    assert body["finished"] is True
    assert body["report"]["total_score"] == 72
    assert body["report"]["level"] == "中等"
    assert body["report"]["per_question"][0]["score"] == 72
    assert body["report"]["degraded"] is False


def test_answer_follow_up_then_next_question(client: Any,
                                             monkeypatch: pytest.MonkeyPatch) -> None:
    """作答过短 → 追问一次，question_no 不变；追问作答后进入下一题。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch, score=55)
    start = _start(client, total=2)
    first = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"], "answer": "分块太大",
    }).json()
    assert first["finished"] is False
    assert first["follow_up"]
    assert first["next_question"] == first["follow_up"]  # 追问就是下一句要展示的话
    assert first["question_no"] == 1  # 还在同一题

    second = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"],
        "answer": "我会用 NDCG@10 加上人工标注对照来评估 rerank 的效果。",
    }).json()
    assert second["finished"] is False
    assert second["question_no"] == 2
    assert second["next_question"]


def test_answer_follow_up_merged_regrades(client: Any,
                                          monkeypatch: pytest.MonkeyPatch) -> None:
    """追问作答与原作答**合并后重新评分**（不是两次取平均）。"""
    _stub_corpus(monkeypatch)
    calls: List[str] = []

    def _chat(system: str, user: str, **kwargs: Any) -> str:
        if "评分官" in system:
            calls.append(user)
            merged = "追问补充" in user
            return json.dumps({"score": 88 if merged else 40, "verdict": "正确",
                               "hit_points": ["提到了 rerank"], "miss_points": [],
                               "comment": "ok"}, ensure_ascii=False)
        if "追问" in system:
            return "那 rerank 效果怎么评估？"
        return "召回差有哪些原因？"

    _stub_corpus(monkeypatch)
    monkeypatch.setattr(iv, "_chat", _chat)
    start = _start(client, total=1)
    client.post("/v1/interview/answer", json={"session_id": start["session_id"],
                                              "answer": "不知道"})
    body = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"], "answer": "用 NDCG 评估"}).json()
    assert body["report"]["total_score"] == 88  # 合并后的分数，而非 (40+88)/2
    assert len(calls) == 2
    assert "（追问补充）" in calls[1]


def test_answer_unknown_session_returns_404(client: Any) -> None:
    """会话不存在/过期 → 404 SESSION_NOT_FOUND。"""
    resp = client.post("/v1/interview/answer",
                       json={"session_id": "no-such-session", "answer": "随便答"})
    assert resp.status_code == 404
    assert resp.json()["detail"] == "SESSION_NOT_FOUND"


def test_answer_degrades_when_llm_down(client: Any,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    """7B 不可用：抽取式评分 + degraded 标注，且明确非模型判分。"""
    _stub_corpus(monkeypatch)
    _stub_llm_down(monkeypatch)
    start = _start(client, total=1)
    body = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"],
        "answer": "分块粒度不合理会切断语义，缺少 rerank 也会让粗排结果进上下文。",
    }).json()
    assert body["finished"] is True
    assert body["degraded"] is True
    assert body["report"]["degraded"] is True
    assert any("人工复核" in s for s in body["report"]["suggestions"])


def test_three_question_session_report_shape(client: Any,
                                             monkeypatch: pytest.MonkeyPatch) -> None:
    """3 题完整会话：报告字段齐全、分数为三题平均。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch, score=80)
    start = _start(client, total=3)
    body: Dict[str, Any] = {}
    for _ in range(3):
        body = client.post("/v1/interview/answer", json={
            "session_id": start["session_id"],
            "answer": "召回差的原因包括分块粒度不合理、缺少 rerank、未做查询改写，"
                      "我会用混合检索加 rerank，并用 NDCG@10 评估效果。",
        }).json()
    assert body["finished"] is True
    report = body["report"]
    assert report["total_score"] == 80
    assert report["level"] == "中上"
    assert len(report["per_question"]) == 3
    for key in ("strengths", "weaknesses", "suggestions", "degraded"):
        assert key in report


def test_session_ttl_expiry_returns_404(client: Any,
                                        monkeypatch: pytest.MonkeyPatch) -> None:
    """超过 TTL 的会话被清理 → 404。"""
    _stub_corpus(monkeypatch)
    _stub_llm(monkeypatch)
    start = _start(client, total=1)
    with iv._SESSIONS_LOCK:
        iv._SESSIONS[start["session_id"]].touched_at -= (iv.SESSION_TTL_SECONDS + 5)
    resp = client.post("/v1/interview/answer", json={
        "session_id": start["session_id"], "answer": "回答"})
    assert resp.status_code == 404
    assert resp.json()["detail"] == "SESSION_NOT_FOUND"


# --------------------------------------------------------------------------------------
# skill 体系接入
# --------------------------------------------------------------------------------------

def test_interview_skill_registered() -> None:
    """registry 里注册了 INTERVIEW，且 /v1/ask 明确引导到面试接口。"""
    from app.router.schemas import SkillName
    from app.skills.registry import get_skill

    skill = get_skill(SkillName.INTERVIEW)
    assert skill.implemented is True
    assert skill.needs_llm is False
    assert "/v1/interview/start" in (skill.template_reply or "")


def test_interview_prototypes_present() -> None:
    """原型句 6-8 条，能表达"练面试"意图。"""
    from app.router.prototypes import PROTOTYPES
    from app.router.schemas import SkillName

    sentences = PROTOTYPES[SkillName.INTERVIEW]
    assert 6 <= len(sentences) <= 8
    assert any("模拟" in s for s in sentences)
