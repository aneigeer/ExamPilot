"""``/v1/route`` 接口测试（A1 契约）。

**全部用桩**：路由判定器由 ``_stub_router`` 替换，不加载 bge 模型、不连任何服务，
因此用例毫秒级且判定结果完全确定；真实准确率另由 ``tools/route_eval.py`` 在
带模型的进程里跑 ``eval/router_cases.csv`` 得出。
"""

from __future__ import annotations

from typing import Any, List

import pytest

from app.router.schemas import RouterDecision, SkillName
from app.routers import route as route_router


def _stub_router(monkeypatch: pytest.MonkeyPatch, decision: RouterDecision) -> None:
    """把 get_router() 换成返回固定判定的桩。"""

    class _StubRouter:
        def decide(self, question: str) -> RouterDecision:  # noqa: D401
            return decision

    monkeypatch.setattr(route_router, "get_router", lambda: _StubRouter())


def _body(question: str = "我的薄弱知识点有哪些") -> dict:
    return {"question": question}


# --------------------------------------------------------------------------------------
# 正常路由
# --------------------------------------------------------------------------------------

def test_route_returns_skill_and_source_types(client: Any,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    """ROUTED：skill 与 source_types 按注册表返回，degraded=false。"""
    _stub_router(monkeypatch, RouterDecision(SkillName.COURSEWARE_QA, 0.82, 0.11,
                                             [], "ROUTED"))
    resp = client.post("/v1/route", json=_body("Transformer 的注意力怎么算"))
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["skill"] == "COURSEWARE_QA"
    assert body["source_types"] == ["COURSEWARE"]
    assert body["confidence"] == pytest.approx(0.82)
    assert body["margin"] == pytest.approx(0.11)
    assert body["degraded"] is False
    assert body["reason"] == "ROUTED"


def test_route_smalltalk_has_empty_source_types(client: Any,
                                                monkeypatch: pytest.MonkeyPatch) -> None:
    """寒暄：source_types 为空（= 不过滤的约定值），仍不标 degraded。"""
    _stub_router(monkeypatch, RouterDecision(SkillName.SMALLTALK, 0.71, 0.2, [], "ROUTED"))
    body = client.post("/v1/route", json=_body("你好")).json()
    assert body["skill"] == "SMALLTALK"
    assert body["source_types"] == []
    assert body["degraded"] is False


# --------------------------------------------------------------------------------------
# REFUSE / CLARIFY 折叠（Java 白名单没有这两个值）
# --------------------------------------------------------------------------------------

def test_route_refuse_folds_to_default_skill(client: Any,
                                             monkeypatch: pytest.MonkeyPatch) -> None:
    """REFUSE 折叠为 COURSE_QA + degraded=true + reason=SIM_LOW（契约合法）。"""
    _stub_router(monkeypatch, RouterDecision(SkillName.REFUSE, 0.31, 0.05, [], "SIM_LOW"))
    body = client.post("/v1/route", json=_body("区块链智能合约怎么写")).json()
    assert body["skill"] == "COURSE_QA"
    assert body["degraded"] is True
    assert body["reason"] == "SIM_LOW"
    assert body["confidence"] == pytest.approx(0.31)


def test_route_clarify_folds_to_default_skill(client: Any,
                                              monkeypatch: pytest.MonkeyPatch) -> None:
    """CLARIFY 折叠为 COURSE_QA + degraded=true + reason=MARGIN_LOW。"""
    _stub_router(monkeypatch, RouterDecision(SkillName.CLARIFY, 0.66, 0.01, [], "MARGIN_LOW"))
    body = client.post("/v1/route", json=_body("这个怎么弄")).json()
    assert body["skill"] == "COURSE_QA"
    assert body["degraded"] is True
    assert body["reason"] == "MARGIN_LOW"


# --------------------------------------------------------------------------------------
# 失败一律 200 + 默认值
# --------------------------------------------------------------------------------------

def test_route_degrades_when_router_raises(client: Any,
                                          monkeypatch: pytest.MonkeyPatch) -> None:
    """判定器抛异常（如 embedding 不可用）→ **仍然 200** + 默认路由 + degraded。"""

    class _Boom:
        def decide(self, question: str) -> RouterDecision:
            raise RuntimeError("embedding down")

    monkeypatch.setattr(route_router, "get_router", lambda: _Boom())
    resp = client.post("/v1/route", json=_body("值传递和地址传递有什么区别"))
    assert resp.status_code == 200
    body = resp.json()
    assert body["skill"] == "COURSE_QA"
    assert body["degraded"] is True
    assert body["reason"] == "EMBEDDING_UNAVAILABLE"
    assert body["confidence"] == 0.0


def test_route_rejects_empty_question(client: Any) -> None:
    """缺 question / 空串 → 400（参数错误，不是 200 兜底场景）。"""
    assert client.post("/v1/route", json={"question": ""}).status_code == 400
    assert client.post("/v1/route", json={}).status_code == 400


# --------------------------------------------------------------------------------------
# 鉴权（与 /v1/agent/* 同一条内部链路）
# --------------------------------------------------------------------------------------

def test_route_requires_internal_token(client: Any,
                                       monkeypatch: pytest.MonkeyPatch) -> None:
    """配了令牌：不带/带错 → 401；带对 → 200。"""
    from app.routers import agent as agent_router

    monkeypatch.setattr(agent_router, "_internal_token", lambda: "s3cret")
    _stub_router(monkeypatch, RouterDecision(SkillName.COURSE_QA, 0.8, 0.2, [], "ROUTED"))

    assert client.post("/v1/route", json=_body()).status_code == 401
    assert client.post("/v1/route", json=_body(),
                       headers={"X-Internal-Token": "wrong"}).status_code == 401
    ok = client.post("/v1/route", json=_body(), headers={"X-Internal-Token": "s3cret"})
    assert ok.status_code == 200
    assert ok.json()["skill"] == "COURSE_QA"


# --------------------------------------------------------------------------------------
# 契约形状（字段名与 Java RouteResponse 逐字对齐）
# --------------------------------------------------------------------------------------

def test_route_response_field_names_match_java_contract(
        client: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    """响应必须是 snake_case 六字段，多一个少一个都会让 Java 解析漂移。"""
    _stub_router(monkeypatch, RouterDecision(SkillName.GRADING, 0.9, 0.3, [], "ROUTED"))
    body = client.post("/v1/route", json=_body("帮我看看这道简答题能得几分")).json()
    assert set(body.keys()) == {"skill", "source_types", "confidence", "margin",
                                "degraded", "reason"}
    assert body["skill"] in {
        "COURSE_QA", "COURSEWARE_QA", "EXERCISE_EXPLAIN", "RESOURCE_RECOMMEND",
        "SMALLTALK", "STUDY_PLAN", "WRONG_BOOK_REVIEW", "GRADING", "INTERVIEW",
    }


def test_route_skill_whitelist_never_leaks_refuse(client: Any,
                                                  monkeypatch: pytest.MonkeyPatch) -> None:
    """任何判定结果都不能把 REFUSE / CLARIFY 透出去（Java 会判契约漂移）。"""
    for skill, reason in ((SkillName.REFUSE, "SIM_LOW"), (SkillName.CLARIFY, "MARGIN_LOW")):
        _stub_router(monkeypatch, RouterDecision(skill, 0.4, 0.02, [], reason))
        body = client.post("/v1/route", json=_body("随便问问")).json()
        assert body["skill"] not in ("REFUSE", "CLARIFY")
        assert isinstance(body["source_types"], List) or isinstance(body["source_types"], list)
