"""/v1/ask 主流程测试（v4 整改 R04/R05/R06/R08 + 三 skill 实装）。

**全部用桩**，不依赖 MySQL / Qdrant / 模型 / 大模型 Key：

* Router / embedding / Qdrant 由 conftest 的 ``client`` 夹具注入桩
* ``_retrieve`` / ``_fetch_contents`` / ``_fetch_wiki_page`` / ``page_sources_active``
  按用例 monkeypatch——它们是纯函数边界，替换后整条 ask 链路与外部组件解耦
* LLM 不配 Key（天然走抽取式降级路径），需要"生成成功"的用例显式桩 ``get_llm_client``
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import pytest

from app.routers import ask as ask_module
from app.router.schemas import SkillName
from app.skills.registry import get_skill


# --------------------------------------------------------------------------------------
# 桩与工具
# --------------------------------------------------------------------------------------


def _hit(chunk_id: int, score: float, *, source_type: str = "COURSEWARE",
         source_id: int = 1, content_hash: str = "") -> Dict[str, Any]:
    """构造一条 ``_retrieve`` 风格的命中。"""
    payload: Dict[str, Any] = {
        "chunk_id": chunk_id, "source_type": source_type, "source_id": source_id,
    }
    if content_hash:
        payload["content_hash"] = content_hash
    return {"chunk_id": chunk_id, "score": score, "payload": payload}


class _StubLlm:
    """生成成功的 LLM 桩：原样返回预设答案（可含任意 [n]）。"""

    def __init__(self, answer: str) -> None:
        self.answer: str = answer

    def chat(self, *args: Any, **kwargs: Any) -> Dict[str, Any]:
        return {"answer": self.answer, "sources": [], "generator": "stub", "cost_ms": 1}


def _patch_retrieval(monkeypatch: pytest.MonkeyPatch,
                     hits: List[Dict[str, Any]],
                     contents: Optional[Dict[int, str]] = None) -> None:
    """让检索层返回固定结果，绕开真实的 Qdrant/MySQL。"""
    monkeypatch.setattr(ask_module, "_retrieve", lambda q, s, k, subj: list(hits))
    monkeypatch.setattr(ask_module, "_fetch_contents",
                        lambda ids: dict(contents or {h["chunk_id"]: f"正文{h['chunk_id']}" for h in hits}))


def _patch_hot(monkeypatch: pytest.MonkeyPatch, *, page: Optional[Dict[str, Any]],
               allow: bool = True, reason: str = "") -> None:
    """让热层返回固定结果：``page`` 非空即命中（真实 _wiki_hot 会回填 score）。"""
    def _fake_hot(question: str, subject_id: Optional[int]):
        if page is None:
            return None, (reason or "MISS")
        return {**page, "score": 0.9}, ""

    monkeypatch.setattr(ask_module, "_wiki_hot", _fake_hot)
    if page is not None:
        monkeypatch.setattr(ask_module, "page_sources_active", lambda raw: (allow, reason))


# --------------------------------------------------------------------------------------
# R05：引用校验
# --------------------------------------------------------------------------------------


class TestSanitizeCitations:
    """``_sanitize_citations`` 单元测试（R05）。"""

    def test_valid_citations_kept(self) -> None:
        answer, removed = ask_module._sanitize_citations("进程是[1]，线程是[2]。", 2)
        assert answer == "进程是[1]，线程是[2]。"
        assert removed == 0

    def test_out_of_range_dropped(self) -> None:
        answer, removed = ask_module._sanitize_citations("结论一[1]。结论二[9]。", 3)
        assert answer == "结论一[1]。结论二。"
        assert removed == 1

    def test_zero_and_negative_style_dropped(self) -> None:
        answer, removed = ask_module._sanitize_citations("引用[0]和[99]都非法[1]", 2)
        assert answer == "引用和都非法[1]"
        assert removed == 2

    def test_mixed_group_remapped(self) -> None:
        """组合引用 [1,9]：合法部分保留并重映射为 [1]。"""
        answer, removed = ask_module._sanitize_citations("两点结论[1,9]", 2)
        assert answer == "两点结论[1]"
        assert removed == 1

    def test_fully_invalid_group_removed(self) -> None:
        answer, removed = ask_module._sanitize_citations("编造引用[7,8]", 2)
        assert answer == "编造引用"
        assert removed == 2

    def test_empty_answer_passthrough(self) -> None:
        assert ask_module._sanitize_citations("", 3) == ("", 0)

    def test_no_sources_disables_check(self) -> None:
        """max_index=0（无真实来源）时不做任何改写。"""
        raw = "原文保留[1][2]"
        assert ask_module._sanitize_citations(raw, 0) == (raw, 0)

    def test_end_to_end_bad_citation_fixed(self, client: Any,
                                           monkeypatch: pytest.MonkeyPatch) -> None:
        """端到端：模型编造 [9]，响应里被剔除且 citation_fixed=1。"""
        _patch_retrieval(monkeypatch, [_hit(11, 0.9)])
        monkeypatch.setattr(ask_module, "get_llm_client", lambda: _StubLlm("结论[1]，补充[9]"))
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["answer"] == "结论[1]，补充"
        assert body["citation_fixed"] == 1


# --------------------------------------------------------------------------------------
# R06：降级状态机 meta
# --------------------------------------------------------------------------------------


class TestDegradeStateMachine:
    """``degrade_level`` / ``layers_hit`` / ``final_strategy``（R06）。"""

    def test_hot_hit_meta(self, client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
        _patch_hot(monkeypatch, page={"id": 9, "slug": "kp-1", "title": "进程与线程",
                                      "summary": "s", "content": "c", "source_chunk_ids": "[1]"})
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["hot"] is True
        assert body["final_strategy"] == "WIKI_HOT"
        assert body["layers_hit"] == ["ROUTER", "WIKI_HOT"]
        assert body["degrade_level"] == 0

    def test_template_meta(self, client: Any) -> None:
        resp = client.post("/v1/ask", json={"question": "你好", "skill": "SMALLTALK"})
        body = resp.json()
        assert body["final_strategy"] == "TEMPLATE"
        assert body["degrade_level"] == 0

    def test_retrieval_empty_refused_level3(self, client: Any,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
        _patch_retrieval(monkeypatch, [])
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["refused"] is True
        assert body["final_strategy"] == "REFUSED"
        assert body["degrade_level"] == 3
        assert body["router"]["reason"] == "RETRIEVAL_EMPTY"

    def test_llm_down_extractive_level2(self, client: Any,
                                        monkeypatch: pytest.MonkeyPatch) -> None:
        """LLM 未配 Key：走抽取式兜底，degrade_level=2 且 200。"""
        _patch_retrieval(monkeypatch, [_hit(11, 0.9, source_type="QUESTION", source_id=5)])
        resp = client.post("/v1/ask", json={"question": "这道题为什么选B", "skill": "EXERCISE_EXPLAIN"})
        body = resp.json()
        assert resp.status_code == 200
        assert body["degraded"] is True
        assert body["final_strategy"] == "RAG_EXTRACTIVE"
        assert body["degrade_level"] == 2
        assert body["layers_hit"][-2:] == ["LLM_7B", "EXTRACTIVE"]

    def test_rag_llm_full_path_meta(self, client: Any,
                                    monkeypatch: pytest.MonkeyPatch) -> None:
        _patch_retrieval(monkeypatch, [_hit(11, 0.9)])
        monkeypatch.setattr(ask_module, "get_llm_client", lambda: _StubLlm("结论[1]"))
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["final_strategy"] == "RAG_LLM"
        assert body["degrade_level"] == 0
        # 热层开启时即使未命中也会留下 WIKI_HOT 层记录（可观测性）
        assert body["layers_hit"] == ["ROUTER", "WIKI_HOT", "VECTOR_RAG", "LLM_7B"]


# --------------------------------------------------------------------------------------
# R04：热层来源在架校验
# --------------------------------------------------------------------------------------


class TestHotSourceGuard:
    """热层命中后回 MySQL 校验来源（R04）。"""

    _PAGE = {"id": 9, "slug": "kp-1", "title": "进程与线程", "summary": "s",
             "content": "预编译页正文", "source_chunk_ids": "[101,102]"}

    def test_hot_blocked_when_source_delisted(self, client: Any,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
        """任一来源失效 → 热层不放行（_wiki_hot 返回未命中），降级 RAG 冷层并给出原因。"""
        _patch_hot(monkeypatch, page=None, reason="SOURCE_DELISTED")
        _patch_retrieval(monkeypatch, [_hit(11, 0.9)])
        monkeypatch.setattr(ask_module, "get_llm_client", lambda: _StubLlm("冷层结论[1]"))
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["hot"] is False  # 没有走热层
        assert body["router"]["hot_reason"] == "SOURCE_DELISTED"
        assert body["final_strategy"] == "RAG_LLM"  # 冷层接住了
        assert "预编译页正文" not in body["answer"]

    def test_hot_blocked_when_check_unavailable(self, client: Any,
                                                monkeypatch: pytest.MonkeyPatch) -> None:
        """DB 不可用（fail-closed）：热层不放行。"""
        _patch_hot(monkeypatch, page=None, reason="SOURCE_CHECK_UNAVAILABLE")
        _patch_retrieval(monkeypatch, [])
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["router"]["hot_reason"] == "SOURCE_CHECK_UNAVAILABLE"
        assert body["hot"] is False

    def test_hot_served_when_sources_active(self, client: Any,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
        _patch_hot(monkeypatch, page=self._PAGE, allow=True)
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["hot"] is True
        assert body["final_strategy"] == "WIKI_HOT"

    def test_hot_miss_reason_surfaced(self, client: Any,
                                      monkeypatch: pytest.MonkeyPatch) -> None:
        """普通未命中也要带原因（可观测性），但不拦冷层。"""
        _patch_hot(monkeypatch, page=None, reason="LOW_SCORE")
        _patch_retrieval(monkeypatch, [])
        resp = client.post("/v1/ask", json={"question": "进程和线程的区别", "skill": "COURSE_QA"})
        body = resp.json()
        assert body["router"]["hot_reason"] == "LOW_SCORE"


# --------------------------------------------------------------------------------------
# R08：同来源去重
# --------------------------------------------------------------------------------------


class TestDedup:
    """``_drop_exact_duplicates`` / ``_drop_near_duplicates``（R08）。"""

    def test_exact_duplicate_by_hash(self) -> None:
        hits = [_hit(1, 0.9, content_hash="aaa"), _hit(2, 0.8, content_hash="aaa"),
                _hit(3, 0.7, content_hash="bbb")]
        kept = ask_module._drop_exact_duplicates(hits)
        assert [h["chunk_id"] for h in kept] == [1, 3]  # 保留最高分的 1，弃 2

    def test_no_hash_kept_all(self) -> None:
        hits = [_hit(1, 0.9), _hit(2, 0.8)]
        assert len(ask_module._drop_exact_duplicates(hits)) == 2

    def test_near_duplicate_same_source_dropped(self) -> None:
        """同来源内近重复正文（>85% bigram 重合）只留最高分。"""
        base = "进程是资源分配的基本单位，线程是调度的基本单位，两者有本质区别"
        dup = base + "。两者有本质区别"  # 高度重合的改写
        hits = [_hit(1, 0.9), _hit(2, 0.8)]
        contents = {1: base, 2: dup}
        kept = ask_module._drop_near_duplicates(hits, contents)
        assert [h["chunk_id"] for h in kept] == [1]

    def test_distinct_content_same_source_kept(self) -> None:
        """同来源的两段不同内容不去重（正常的多块引用）。"""
        hits = [_hit(1, 0.9), _hit(2, 0.8)]
        contents = {1: "进程拥有独立的地址空间", 2: "线程共享进程的地址空间"}
        assert len(ask_module._drop_near_duplicates(hits, contents)) == 2

    def test_identical_content_cross_source_kept(self) -> None:
        """跨来源相同内容不去重（课件引用原题属正常现象）。"""
        text = "进程拥有独立的地址空间，线程共享地址空间"
        hits = [_hit(1, 0.9, source_type="QUESTION", source_id=1),
                _hit(2, 0.8, source_type="COURSEWARE", source_id=2)]
        assert len(ask_module._drop_near_duplicates(hits, {1: text, 2: text})) == 2

    def test_empty_content_dropped(self) -> None:
        """回表取不到正文的脏向量在去重阶段即被剔除。"""
        hits = [_hit(1, 0.9), _hit(2, 0.8)]
        kept = ask_module._drop_near_duplicates(hits, {2: "只有二号有正文"})
        assert [h["chunk_id"] for h in kept] == [2]

    def test_end_to_end_dedup(self, client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
        """端到端：重复块不占引用编号，sources 只有去重后的条数。"""
        hits = [_hit(1, 0.9, content_hash="aaa"), _hit(2, 0.8, content_hash="aaa"),
                _hit(3, 0.7, content_hash="bbb")]
        contents = {1: "进程是资源分配的基本单位", 2: "进程是资源分配的基本单位", 3: "线程是调度的基本单位"}
        _patch_retrieval(monkeypatch, hits, contents)
        monkeypatch.setattr(ask_module, "get_llm_client", lambda: _StubLlm("结论[1][2]"))
        resp = client.post("/v1/ask", json={"question": "进程和线程", "skill": "COURSE_QA"})
        body = resp.json()
        assert [s["chunk_id"] for s in body["sources"]] == [1, 3]
        assert body["citation_fixed"] == 0  # 两条引用都合法


# --------------------------------------------------------------------------------------
# 三 skill 实装
# --------------------------------------------------------------------------------------


class TestNewSkills:
    """STUDY_PLAN / WRONG_BOOK_REVIEW / GRADING 不再 fallback（v4）。"""

    @pytest.mark.parametrize("name", ["STUDY_PLAN", "WRONG_BOOK_REVIEW", "GRADING"])
    def test_registry_implemented(self, name: str) -> None:
        skill = get_skill(SkillName(name))
        assert skill.implemented is True
        assert skill.needs_retrieval is True
        assert skill.needs_llm is True
        assert skill.source_types, "实装 skill 必须声明检索范围"

    @pytest.mark.parametrize("name", ["STUDY_PLAN", "WRONG_BOOK_REVIEW", "GRADING"])
    def test_no_fallback(self, client: Any, monkeypatch: pytest.MonkeyPatch, name: str) -> None:
        """强制指定新 skill 时不再回退 COURSE_QA（fallback=false）。"""
        _patch_retrieval(monkeypatch, [_hit(11, 0.9, source_type="QUESTION", source_id=5)])
        resp = client.post("/v1/ask", json={"question": "帮我安排复习", "skill": name})
        body = resp.json()
        assert resp.status_code == 200
        assert body["skill"] == name
        assert body["fallback"] is False

    def test_grading_extractive_when_no_llm(self, client: Any,
                                            monkeypatch: pytest.MonkeyPatch) -> None:
        """GRADING 无大模型 Key → 抽取式评估，且明确注明非模型判分。"""
        _patch_retrieval(monkeypatch, [_hit(11, 0.9, source_type="QUESTION", source_id=5)])
        resp = client.post("/v1/ask", json={"question": "帮我改这道简答题", "skill": "GRADING"})
        body = resp.json()
        assert body["degraded"] is True
        assert body["final_strategy"] == "RAG_EXTRACTIVE"
        assert "抽取式评估" in body["answer"]
        assert "人工复核" in body["answer"]
        assert "非模型判分" in body["answer"] or "仅供参考" in body["answer"]

    def test_grading_with_llm_uses_hint(self, client: Any,
                                        monkeypatch: pytest.MonkeyPatch) -> None:
        """GRADING 有大模型时按 skill 正常生成，answer_hint 生效。"""
        captured: Dict[str, Any] = {}

        class _CaptureLlm:
            def chat(self, prompt: str, contexts: List[str], **kwargs: Any) -> Dict[str, Any]:
                captured["prompt"] = prompt
                captured["contexts"] = contexts
                return {"answer": "总评：良[1]", "sources": [], "generator": "stub", "cost_ms": 1}

        _patch_retrieval(monkeypatch, [_hit(11, 0.9, source_type="QUESTION", source_id=5)])
        monkeypatch.setattr(ask_module, "get_llm_client", lambda: _CaptureLlm())
        resp = client.post("/v1/ask", json={"question": "帮我改这道简答题", "skill": "GRADING"})
        body = resp.json()
        assert body["answer"] == "总评：良[1]"
        assert body["final_strategy"] == "RAG_LLM"
        assert "输出要求" in captured["prompt"]  # answer_hint 拼进了用户消息

    def test_study_plan_uses_both_groups(self, client: Any) -> None:
        """STUDY_PLAN 检索范围覆盖课件 + 知识点两组。"""
        skill = get_skill(SkillName.STUDY_PLAN)
        assert set(skill.source_types) == {"COURSEWARE", "KNOWLEDGE"}
        assert len(skill.retrieval_groups) == 2

    def test_wrong_book_review_groups(self, client: Any) -> None:
        skill = get_skill(SkillName.WRONG_BOOK_REVIEW)
        assert set(skill.source_types) == {"QUESTION", "KNOWLEDGE"}
        assert set(skill.retrieval_groups[0]) == {"QUESTION"}


# --------------------------------------------------------------------------------------
# R04：_wiki_hot 内部逻辑（真实函数 + 桩依赖）
# --------------------------------------------------------------------------------------


class _StubEmbedQuery:
    """embedding 桩：只提供 encode_query。"""

    def encode_query(self, query: str) -> List[float]:
        return [0.0] * 512


class _StubQdrantWiki:
    """Qdrant 桩：只提供 search_wiki，返回固定命中。"""

    def __init__(self, hits: List[Dict[str, Any]]) -> None:
        self.hits: List[Dict[str, Any]] = hits

    def search_wiki(self, collection: str, vector: List[float], *,
                    top_k: int = 5, query_filter: Any = None) -> List[Dict[str, Any]]:
        return self.hits[:top_k]


_PAGE = {"id": 9, "slug": "kp-1", "title": "T", "summary": "s", "content": "c",
         "source_chunk_ids": "[101,102]"}


class TestWikiHotInternal:
    """直接测 ``_wiki_hot``：来源校验/阈值/异常的分支覆盖（R04）。"""

    def _patch(self, monkeypatch: pytest.MonkeyPatch, *,
               hits: List[Dict[str, Any]], page: Optional[Dict[str, Any]],
               allow: bool, reason: str) -> None:
        monkeypatch.setattr(ask_module, "get_embedding_client", lambda: _StubEmbedQuery())
        monkeypatch.setattr(ask_module, "get_qdrant_client",
                            lambda: _StubQdrantWiki(hits))
        monkeypatch.setattr(ask_module, "_fetch_wiki_page", lambda pid: page)
        monkeypatch.setattr(ask_module, "page_sources_active", lambda raw: (allow, reason))

    def test_hit_and_allowed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, hits=[{"page_id": 9, "score": 0.9, "payload": {}}],
                    page=dict(_PAGE), allow=True, reason="")
        page, reason = ask_module._wiki_hot("q", None)
        assert reason == ""
        assert page is not None and page["id"] == 9

    def test_delisted_blocked(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, hits=[{"page_id": 9, "score": 0.9, "payload": {}}],
                    page=dict(_PAGE), allow=False, reason="SOURCE_DELISTED")
        page, reason = ask_module._wiki_hot("q", None)
        assert page is None
        assert reason == "SOURCE_DELISTED"

    def test_check_unavailable_fail_closed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, hits=[{"page_id": 9, "score": 0.9, "payload": {}}],
                    page=dict(_PAGE), allow=False, reason="SOURCE_CHECK_UNAVAILABLE")
        page, reason = ask_module._wiki_hot("q", None)
        assert page is None
        assert reason == "SOURCE_CHECK_UNAVAILABLE"

    def test_low_score_miss(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self._patch(monkeypatch, hits=[{"page_id": 9, "score": 0.50, "payload": {}}],
                    page=dict(_PAGE), allow=True, reason="")
        page, reason = ask_module._wiki_hot("q", None)
        assert (page, reason) == (None, "LOW_SCORE")

    def test_qdrant_error_degrades(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """search_wiki 抛异常 → 安静降级，绝不向上抛。"""

        class _Boom:
            def search_wiki(self, *a: Any, **k: Any) -> List[Dict[str, Any]]:
                raise RuntimeError("boom")

        monkeypatch.setattr(ask_module, "get_embedding_client", lambda: _StubEmbedQuery())
        monkeypatch.setattr(ask_module, "get_qdrant_client", lambda: _Boom())
        page, reason = ask_module._wiki_hot("q", None)
        assert page is None
        assert reason == "ERROR"
