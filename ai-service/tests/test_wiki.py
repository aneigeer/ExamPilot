"""LLM Wiki 蒸馏与任务处理器测试。

覆盖三条关键约束：
1. 接地率能识别「格式正确但内容编造」的输出；
2. LLM 输出非 JSON / 引用非法 ID 时必须回退抽取式，而不是产出空任务；
3. WIKI_DISTILL 处理器只回传草稿（PENDING），失败单页不中断整批。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, Dict, List

import pytest

from app.config import Settings
from app.wiki.distiller import distill_cluster, grounding_ratio

CLUSTER: Dict[str, Any] = {
    "slug": "kp-5",
    "title": "函数",
    "subjectId": 5,
    "knowledgeId": 5,
    "chunks": [
        {
            "chunkId": 101,
            "title": "函数定义",
            "content": "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        },
        {
            "chunkId": 102,
            "title": "函数声明",
            "content": "函数声明告诉编译器函数的名称、返回类型和参数列表，定义则提供函数体实现。",
        },
        {
            "chunkId": 103,
            "title": "值传递",
            "content": "C 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        },
    ],
}

CATALOG = [{"slug": "kp-6", "title": "数组"}, {"slug": "kp-7", "title": "指针"}]


def test_grounding_ratio_detects_hallucination() -> None:
    source = "\n".join(item["content"] for item in CLUSTER["chunks"])
    copied = "函数是完成特定任务的独立代码块，通过函数名调用。"
    hallucinated = "The int type occupies 8 bytes and _Bool is a size specifier in C."
    assert grounding_ratio(copied, source) > 0.3
    assert grounding_ratio(hallucinated, source) < 0.05


def test_distill_accepts_grounded_json(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()
    payload = {
        "title": "函数基础",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用；C 语言参数默认按值传递。",
        "content": "## 函数定义\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。\n"
                   "## 参数传递\nC 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        "key_points": ["函数是独立代码块", "参数默认按值传递", "声明与定义分离"],
        "source_chunk_ids": [101, 103],
        "links": [{"to_slug": "kp-6", "relation": "RELATED", "weight": 0.6},
                  {"to_slug": "fake-page", "relation": "RELATED", "weight": 0.9}],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["title"] == "函数基础"
    assert draft["source_chunk_ids"] == [101, 103]
    # 不存在的 slug 必须被丢弃，避免悬空链接
    assert [item["to_slug"] for item in draft["links"]] == ["kp-6"]
    assert draft["quality_score"] > 0.5


def test_distill_falls_back_when_llm_ignores_json(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return "The int type occupies 8 bytes. _Bool is a size specifier."

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is True
    assert draft["model"] == "extractive-fallback"
    assert draft["source_chunk_ids"]
    assert all(chunk_id in {101, 102, 103} for chunk_id in draft["source_chunk_ids"])
    assert draft["summary"]


def test_distill_rejects_unknown_source_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()
    payload = {
        "title": "函数",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        "content": "## 函数\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        "key_points": ["函数是代码块", "可以接收参数"],
        "source_chunk_ids": [999],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is True
    assert draft["model"] == "extractive-fallback"


class WikiToolBridge:
    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        assert path == "/internal/agent/tools/wiki-search"
        return {
            "engine": "WIKI",
            "hits": [{
                "id": 1,
                "slug": "kp-27",
                "title": "言语理解与表达",
                "summary": "考察语句逻辑关系与词语选择。",
                "content": "## 考点\n" + "内容" * 400,
                "subjectId": 190,
                "knowledgeId": 27,
                "score": 0.78,
                "channel": "VECTOR",
            }],
        }

    async def tool_get(self, path: str, params: Any = None) -> Any:
        assert path == "/internal/agent/tools/wiki-page/1"
        return {
            "id": 1,
            "slug": "kp-27",
            "title": "言语理解与表达",
            "summary": "考察语句逻辑关系与词语选择。",
            "content": "## 考点\n正文",
            "keyPoints": ["逻辑关系", "词语选择"],
            "relatedPages": [{"id": 2, "title": "判断推理", "slug": "kp-29"}],
        }


def test_agent_wiki_tools_build_contexts() -> None:
    from app.agent.tools import AgentContext, ToolRegistry

    registry = ToolRegistry(WikiToolBridge())  # type: ignore[arg-type]
    ctx = AgentContext(question="言语理解考什么", subject_id=190)
    result = asyncio.run(registry.execute("search_wiki", {"query": "言语理解"}, ctx))
    assert result.ok
    assert result.data[0]["pageId"] == 1
    assert result.contexts[0]["sourceType"] == "WIKI"
    # 截断规则：正文最多 600 字符 + 固定标记
    assert len(result.contexts[0]["content"]) <= 600 + len("…[truncated]")

    page = asyncio.run(registry.execute("get_wiki_page", {"pageId": 1}, ctx))
    assert page.ok
    assert page.data["relatedPages"][0]["pageId"] == 2
    assert page.contexts[0]["refType"] == "WIKI"


class WikiBridge:
    def __init__(self) -> None:
        self.posted: List[Dict[str, Any]] = []
        self.progress: List[int] = []

    async def wiki_input(self, scope: str, subject_id: Any, limit: int, max_chunks: int) -> Dict[str, Any]:
        return {"clusters": [CLUSTER, dict(CLUSTER, slug="kp-6", title="数组")],
                "catalog": CATALOG}

    async def post_wiki_revision(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self.posted.append(payload)
        return {"revisionId": len(self.posted)}


def test_wiki_handler_posts_pending_drafts(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.worker import wiki_handlers

    async def fake_distill(cluster: Dict[str, Any], catalog: List[Dict[str, Any]],
                           settings: Any) -> Any:
        return {
            "title": cluster["title"],
            "summary": "函数是完成特定任务的独立代码块。",
            "content": "## 函数\n函数是完成特定任务的独立代码块。",
            "key_points": ["函数是代码块", "可以调用"],
            "source_chunk_ids": [101],
            "links": [],
            "quality_score": 0.5,
            "model": "fake",
        }, False

    async def report(percent: int) -> None:
        bridge.progress.append(percent)

    monkeypatch.setattr(wiki_handlers, "distill_cluster", fake_distill)
    bridge = WikiBridge()
    result = asyncio.run(wiki_handlers.handle_wiki_distill(
        "WIKI_DISTILL", {"scope": "ALL", "limit": 2, "maxChunks": 3}, 77, bridge, report))

    assert result["total"] == 2
    assert result["drafted"] == 2
    assert len(bridge.posted) == 2
    assert all(item["task_id"] == 77 for item in bridge.posted)
    assert bridge.posted[0]["slug"] == "kp-5"
    assert bridge.posted[1]["slug"] == "kp-6"
    assert bridge.progress[-1] == 99
