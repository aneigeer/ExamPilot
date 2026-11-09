"""阶段三安全收口测试 —— 逐条对应 ``docs/Agent阶段三接口契约.md`` §4「Python 验收断言」。

覆盖 ①-⑥ 六条 + 配置级预算 clamp：

* ① 未配令牌 / 未开匿名 → 401，body **恰 3 字段**、`code=5231`，响应头带 `X-Trace-Id`；
* ② `AGENT_ALLOW_ANONYMOUS=1` → 非 401；启动 **1 条** ERROR（含"生产禁止"），第二个请求不新增；
* ③ `maxSteps` 0/-1/13 → 400 + `5200`，文案含 `maxSteps` 与 `1..12`；未传 → 实际 6；传 3 → 实际 3；
* ④ `/v1/health` 的 `security` 三键齐备且均 `bool`，全文不含令牌值；
* ⑤ 带 `X-Trace-Id` 调用 → 响应头 / Python 日志 / `JavaBridge` 出站头三处同值（`httpx.MockTransport`）；
* ⑥ 不带该头 → 输出匹配 `^[0-9a-f]{16}$`。

设计原则：断言**契约**，不锁实现。全部走真实 FastAPI 路由（TestClient），
只有"不关心的下游"（Agent 执行器 / Java）用桩替换。
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional

import pytest

from app.agent.tools import AgentContext
from app.clients.java_bridge import INTERNAL_TOKEN_HEADER, JavaBridge
from app.config import AgentSettings, get_settings, reset_settings
from app.observability.trace import (
    TRACE_ID_HEADER,
    TRACE_ID_PATTERN,
    TraceIdLogFilter,
)
from app.routers import agent as agent_router

#: 合法 Agent 请求体（不依赖真实用户数据）
_AGENT_BODY: Dict[str, Any] = {
    "question": "我的薄弱知识点有哪些",
    "userId": 3,
    "sessionId": "s-sec",
}

#: 测试令牌（**不是**真实密钥）
_TEST_TOKEN = "test-token-0123456789"

#: 契约里的样例 traceId
_SAMPLE_TRACE = "0123456789abcdef"


# --------------------------------------------------------------------------------------
# 桩与夹具
# --------------------------------------------------------------------------------------


class _HarnessStub:
    """替换 ``_harness()``：记录是否进入执行（鉴权失败时**一次都不该**进入）。"""

    def __init__(self) -> None:
        self.contexts: List[AgentContext] = []

    async def run(self, ctx: AgentContext):
        self.contexts.append(ctx)
        yield {"event": "final", "data": {"answer": "ok", "generator": "stub",
                                         "degraded": True, "refused": False,
                                         "step_count": 1, "tool_calls": 0}}
        yield {"event": "done", "data": {"totalMs": 1}}

    async def collect(self, ctx: AgentContext) -> Dict[str, Any]:
        self.contexts.append(ctx)
        return {"events": [], "answer": "ok", "generator": "stub", "degraded": True,
                "refused": False, "step_count": 1, "tool_calls": 0}


@pytest.fixture()
def env(monkeypatch: pytest.MonkeyPatch):
    """设置环境变量并重建配置单例（模拟"部署时 .env 长什么样"）。

    用法：``env(AI_INTERNAL_TOKEN="x", AGENT_ALLOW_ANONYMOUS="0")``；
    传 ``None`` 表示删除该变量（回到"未配置"）。
    """

    def _apply(**kwargs: Optional[str]) -> None:
        for key, value in kwargs.items():
            if value is None:
                monkeypatch.delenv(key, raising=False)
            else:
                monkeypatch.setenv(key, value)
        reset_settings()

    yield _apply
    reset_settings()


@pytest.fixture(autouse=True)
def _reset_runtime_flags() -> None:
    """每个用例前重置"匿名放行只告警一次"的进程内 flag，避免用例互相污染。"""
    agent_router._reset_anonymous_warning()


@pytest.fixture()
def harness_stub(monkeypatch: pytest.MonkeyPatch) -> _HarnessStub:
    """把 Agent 执行器换成桩：鉴权用例只关心"有没有进执行"。"""
    stub = _HarnessStub()
    monkeypatch.setattr(agent_router, "_harness", lambda max_steps: stub)
    return stub


@pytest.fixture()
def patched_httpx(monkeypatch: pytest.MonkeyPatch) -> List[Any]:
    """把 ``httpx.AsyncClient`` 换成挂 ``MockTransport`` 的客户端，返回捕获到的请求列表。"""
    import httpx

    captured: List[Any] = []
    real_client = httpx.AsyncClient

    def _handler(request: "httpx.Request") -> "httpx.Response":
        captured.append(request)
        return httpx.Response(200, json={"code": 200, "message": "ok", "data": {}})

    def _factory(*args: Any, **kwargs: Any) -> "httpx.AsyncClient":
        kwargs["transport"] = httpx.MockTransport(_handler)
        return real_client(*args, **kwargs)

    monkeypatch.setattr(httpx, "AsyncClient", _factory)
    return captured


# --------------------------------------------------------------------------------------
# ① fail-closed：未配令牌 + 未开匿名 → 401 / 5231，body 恰 3 字段
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("path", ["/v1/agent/run", "/v1/agent/stream"])
def test_unconfigured_token_is_401_with_5231(client: Any, harness_stub: _HarnessStub,
                                            env: Any, path: str) -> None:
    """契约 ①：两个变量都不设时，无令牌请求必须 401 + code=5231。"""
    env(AI_INTERNAL_TOKEN=None, AGENT_ALLOW_ANONYMOUS=None)
    resp = client.post(path, json=_AGENT_BODY)

    assert resp.status_code == 401, f"{path} 应 401，实际 {resp.status_code}：{resp.text}"
    body = resp.json()
    # 契约：错误体保持 Python 现有 3 字段，不加 traceId 字段
    assert set(body.keys()) == {"code", "message", "degraded"}, f"字段集不符：{body}"
    assert body["code"] == 5231
    assert body["message"] == "内部接口未授权：X-Internal-Token 缺失或无效"
    assert body["degraded"] is False
    # 401 也要带 trace（契约 §1 第 2/6 条：任何分支都有 X-Trace-Id）
    assert TRACE_ID_PATTERN.match(resp.headers.get(TRACE_ID_HEADER, "")), resp.headers
    assert harness_stub.contexts == [], "鉴权失败却已进入 Agent 执行"


def test_wrong_token_is_401_with_same_body(client: Any, harness_stub: _HarnessStub,
                                          env: Any) -> None:
    """配了令牌后：不带/带错响应体**完全一致**（不给爆破者区分信息）。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")

    missing = client.post("/v1/agent/run", json=_AGENT_BODY)
    wrong = client.post("/v1/agent/run", json=_AGENT_BODY,
                        headers={INTERNAL_TOKEN_HEADER: "guessed"})
    assert missing.status_code == 401 and wrong.status_code == 401
    assert missing.json() == wrong.json()
    assert missing.json()["code"] == 5231
    assert harness_stub.contexts == []


def test_correct_token_passes(client: Any, harness_stub: _HarnessStub, env: Any) -> None:
    """反向对照：令牌正确 → 200（证明 401 确实由令牌校验产生）。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    resp = client.post("/v1/agent/run", json=_AGENT_BODY,
                       headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN})
    assert resp.status_code == 200, resp.text
    assert len(harness_stub.contexts) == 1


# --------------------------------------------------------------------------------------
# ② 匿名放行：非 401 + 启动仅 1 条 ERROR（第二个请求不新增）
# --------------------------------------------------------------------------------------


def test_anonymous_flag_allows_and_logs_error_once(
    client: Any, harness_stub: _HarnessStub, env: Any,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """契约 ②：`AGENT_ALLOW_ANONYMOUS=1` → 非 401；ERROR **只 1 条**且含"生产禁止"。"""
    with caplog.at_level(logging.WARNING):  # WARNING 也收，才能同时断言"只 1 条 ERROR"
        env(AI_INTERNAL_TOKEN="", AGENT_ALLOW_ANONYMOUS="1")
        get_settings()  # ← 等价于启动：首次构建配置时自检

        first = client.post("/v1/agent/run", json=_AGENT_BODY)
        second = client.post("/v1/agent/run", json=_AGENT_BODY)

    assert first.status_code != 401 and second.status_code != 401
    assert first.status_code == 200, first.text
    assert len(harness_stub.contexts) == 2

    errors = [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert len(errors) == 1, f"启动 ERROR 应恰好 1 条，实际 {len(errors)}：{[r.getMessage() for r in errors]}"
    assert "生产禁止" in errors[0].getMessage()
    assert "AGENT_ALLOW_ANONYMOUS" in errors[0].getMessage()

    # 运行期只 WARNING 一次，且绝不回显令牌值（此处未配置）
    warnings = [r for r in caplog.records
                if r.name == "app.routers.agent" and r.levelno == logging.WARNING]
    assert len(warnings) == 1, f"匿名放行 WARNING 应只 1 条，实际 {len(warnings)}"


# --------------------------------------------------------------------------------------
# ③ maxSteps 边界
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("bad", [0, -1, 13])
@pytest.mark.parametrize("path", ["/v1/agent/run", "/v1/agent/stream"])
def test_max_steps_out_of_range_is_400(client: Any, harness_stub: _HarnessStub,
                                      env: Any, path: str, bad: int) -> None:
    """契约 ③：0 / -1 / 13 → 400 + 5200，文案含 `maxSteps` 与 `1..12`。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    resp = client.post(path, json={**_AGENT_BODY, "maxSteps": bad},
                       headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN})

    assert resp.status_code == 400, f"maxSteps={bad} 应 400，实际 {resp.status_code}"
    body = resp.json()
    assert body["code"] == 5200, body
    assert "maxSteps" in body["message"], body["message"]
    assert "1..12" in body["message"], body["message"]
    assert harness_stub.contexts == [], "参数非法却已进入执行"


def test_max_steps_effective_values(env: Any) -> None:
    """契约 ③：未传 → 实际 6；传 3 → 实际 3；边界 1/12 也生效。

    回归点：``if max_steps_override:`` 会把 0 当"未传"静默回退成 6，
    调用方以为限制了步数其实没有 —— 这类静默降级在响应里完全看不出来。
    """
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    assert agent_router._harness(None).settings.max_steps == 6, "未传未回落到默认 6"
    assert agent_router._harness(3).settings.max_steps == 3
    assert agent_router._harness(1).settings.max_steps == 1
    assert agent_router._harness(12).settings.max_steps == 12


def test_harness_rejects_out_of_range_when_called_directly() -> None:
    """兜底：绕过 schema 直接调 ``_harness(0)`` 也要 400/5200，不静默回落默认值。"""
    from app.errors import ParamError

    for bad in (0, -1, 13):
        with pytest.raises(ParamError) as excinfo:
            agent_router._harness(bad)
        assert excinfo.value.code == 5200
        assert excinfo.value.http_status == 400


def test_tool_calls_config_is_clamped_at_startup(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """契约 ③（配置级）：`AGENT_MAX_TOOL_CALLS` 越上限 → 启动 clamp 到 8 + WARNING。

    `maxToolCalls` **不开放请求覆盖**（契约 §3 A8 表），所以这里测的是配置，
    且必须是"clamp 而不是 400"——运维配错不该让线上请求失败。
    """
    monkeypatch.setenv("AGENT_MAX_TOOL_CALLS", "99")
    reset_settings()
    with caplog.at_level(logging.WARNING):
        settings = get_settings()

    assert settings.agent.max_tool_calls == 8, settings.agent.max_tool_calls
    assert any("AGENT_MAX_TOOL_CALLS" in r.getMessage() and "clamp" in r.getMessage()
               for r in caplog.records), [r.getMessage() for r in caplog.records]


# --------------------------------------------------------------------------------------
# ④ /v1/health 的 security 三键
# --------------------------------------------------------------------------------------


def test_health_security_keys_are_bool_and_contract_named(client: Any, env: Any) -> None:
    """契约 ④：三键齐备、camelCase、均 `bool`，且全文不含令牌值。"""
    secret = "secret-token-should-never-leak"
    env(AI_INTERNAL_TOKEN=secret, AGENT_ALLOW_ANONYMOUS="0")
    resp = client.get("/v1/health")
    assert resp.status_code == 200, resp.text
    text = resp.text
    data = resp.json()

    assert "security" in data, f"/v1/health 缺少 security 段：{data}"
    security = data["security"]
    assert set(security.keys()) == {
        "internalTokenConfigured", "agentAuthRequired", "anonymousAllowed",
    }, f"契约三键不符：{security}"
    for key, value in security.items():
        assert isinstance(value, bool), f"{key} 不是布尔：{value!r}"

    assert security["internalTokenConfigured"] is True
    assert security["agentAuthRequired"] is True
    assert security["anonymousAllowed"] is False
    # 令牌值（或任何片段）都不得出现在响应里
    assert secret not in text
    assert secret[:8] not in text


def test_health_security_flags_when_token_absent_and_anonymous(client: Any, env: Any) -> None:
    """未配令牌 + 开匿名 → 不强制鉴权（`agentAuthRequired=false`）。"""
    env(AI_INTERNAL_TOKEN="", AGENT_ALLOW_ANONYMOUS="1")
    security = client.get("/v1/health").json()["security"]
    assert security == {
        "internalTokenConfigured": False,
        "agentAuthRequired": False,
        "anonymousAllowed": True,
    }


def test_health_security_not_part_of_status(client: Any, env: Any) -> None:
    """安全姿态**不参与** status 判定（鉴权不是"能力不可用"）。"""
    env(AI_INTERNAL_TOKEN="", AGENT_ALLOW_ANONYMOUS="0")
    data = client.get("/v1/health").json()
    assert data["status"] in ("ok", "degraded")
    assert data["security"]["agentAuthRequired"] is False


# --------------------------------------------------------------------------------------
# ⑤ / ⑥ traceId 贯通
# --------------------------------------------------------------------------------------


def test_trace_flows_to_header_logs_and_outbound(
    client: Any, env: Any, monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture, patched_httpx: List[Any],
) -> None:
    """契约 ⑤：入站头 → 响应头 / Python 日志 / JavaBridge 出站头三处同值。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")

    class _OutboundProbe:
        """在请求处理期间发起一次 Python → Java 内部调用（走 MockTransport）。"""

        async def run(self, ctx: AgentContext):
            yield {"event": "final", "data": {"answer": "ok"}}

        async def collect(self, ctx: AgentContext) -> Dict[str, Any]:
            bridge = JavaBridge(
                AgentSettings(internal_token=_TEST_TOKEN,
                              java_base_url="http://java.test/api")
            )
            await bridge.tool_post("/internal/agent/tools/probe", {"questionId": 1})
            logging.getLogger("app.test.probe").info("请求处理期间的业务日志")
            return {"events": [], "answer": "ok", "generator": "stub", "degraded": True,
                    "refused": False, "step_count": 1, "tool_calls": 0}

    monkeypatch.setattr(agent_router, "_harness", lambda max_steps: _OutboundProbe())
    caplog.handler.addFilter(TraceIdLogFilter())  # 让捕获的记录也带 trace_id

    with caplog.at_level(logging.INFO):
        resp = client.post("/v1/agent/run", json=_AGENT_BODY,
                           headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN,
                                    TRACE_ID_HEADER: _SAMPLE_TRACE})

    assert resp.status_code == 200, resp.text
    # 1) 响应头同值
    assert resp.headers.get(TRACE_ID_HEADER) == _SAMPLE_TRACE
    # 2) 出站头同值（httpx.MockTransport 断言，而不是只看 _headers()）
    assert patched_httpx, "没有捕获到出站请求"
    assert patched_httpx[0].headers.get(TRACE_ID_HEADER) == _SAMPLE_TRACE
    assert patched_httpx[0].headers.get(INTERNAL_TOKEN_HEADER) == _TEST_TOKEN
    # 3) Python 日志同值（Filter 注入的 trace_id）
    probe_records = [r for r in caplog.records if r.name == "app.test.probe"]
    assert probe_records, "业务日志未被捕获"
    assert probe_records[0].trace_id == _SAMPLE_TRACE


def test_trace_generated_when_absent(client: Any, env: Any) -> None:
    """契约 ⑥：不带 `X-Trace-Id` → 生成 16 位小写 hex。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    resp = client.post("/v1/agent/run", json=_AGENT_BODY,
                       headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN})
    trace_id = resp.headers.get(TRACE_ID_HEADER, "")
    assert re.match(r"^[0-9a-f]{16}$", trace_id), f"traceId 非法：{trace_id!r}"


def test_illegal_inbound_trace_is_discarded_and_regenerated(client: Any, env: Any) -> None:
    """非法入站值（含换行/大写/长度不符）必须丢弃重生成，且不报错（防日志注入）。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    for illegal in ("BAD-TRACE", "0123456789ABCDEF", "short", "0123456789abcdef0"):
        resp = client.post(
            "/v1/agent/run", json=_AGENT_BODY,
            headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN, TRACE_ID_HEADER: illegal},
        )
        assert resp.status_code == 200, resp.text
        generated = resp.headers.get(TRACE_ID_HEADER, "")
        assert generated != illegal, f"非法 trace 被原样透传：{illegal}"
        assert TRACE_ID_PATTERN.match(generated), generated


def test_java_bridge_omits_trace_header_when_absent() -> None:
    """契约 §1 第 8 条：**没有 trace 就不带**该头（由 Java Filter 兜底生成）。"""
    bridge = JavaBridge(AgentSettings(internal_token=_TEST_TOKEN))
    headers = bridge._headers()
    assert headers[INTERNAL_TOKEN_HEADER] == _TEST_TOKEN
    assert TRACE_ID_HEADER not in headers, f"无 trace 时不应自造：{headers}"


def test_meta_trace_id_comes_from_header_not_body(
    client: Any, env: Any, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """回归（QA TR-12）：**只带 ``X-Trace-Id`` 头、body 无 traceId 时，meta.traceId = 头值**。

    契约 §1 第 5 项："Python 读取**以头为准**，body 字段保留兼容"。
    曾经的实现只读 ``request.trace_id``，于是"只带头"的调用方（离线评估脚本、
    其它内部服务）拿到的 ``meta.traceId`` 是空串，与响应头不一致 → 链路断在 meta 这一环。
    """
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    captured: Dict[str, str] = {}

    class _CtxProbe:
        async def run(self, ctx: AgentContext):
            yield {"event": "final", "data": {"answer": "ok"}}

        async def collect(self, ctx: AgentContext) -> Dict[str, Any]:
            captured["trace_id"] = ctx.trace_id
            return {"events": [], "answer": "ok", "generator": "stub", "degraded": True,
                    "refused": False, "step_count": 1, "tool_calls": 0}

    monkeypatch.setattr(agent_router, "_harness", lambda max_steps: _CtxProbe())
    resp = client.post("/v1/agent/run", json=_AGENT_BODY,
                       headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN,
                                TRACE_ID_HEADER: "fedcba9876543210"})

    assert resp.status_code == 200, resp.text
    assert captured["trace_id"] == "fedcba9876543210", (
        f"meta.traceId 应取头值，实际 {captured['trace_id']!r}"
    )
    assert resp.headers.get(TRACE_ID_HEADER) == "fedcba9876543210"


def test_meta_trace_id_prefers_header_when_body_differs(
    client: Any, env: Any, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """头与 body 不一致时**以头为准**（body 仅兼容，不参与仲裁）。"""
    env(AI_INTERNAL_TOKEN=_TEST_TOKEN, AGENT_ALLOW_ANONYMOUS="0")
    captured: Dict[str, str] = {}

    class _CtxProbe:
        async def run(self, ctx: AgentContext):
            yield {"event": "final", "data": {"answer": "ok"}}

        async def collect(self, ctx: AgentContext) -> Dict[str, Any]:
            captured["trace_id"] = ctx.trace_id
            return {"events": [], "answer": "ok", "generator": "stub", "degraded": True,
                    "refused": False, "step_count": 1, "tool_calls": 0}

    monkeypatch.setattr(agent_router, "_harness", lambda max_steps: _CtxProbe())
    resp = client.post(
        "/v1/agent/run",
        json={**_AGENT_BODY, "traceId": "aaaaaaaaaaaaaaaa"},   # body 里是另一个值
        headers={INTERNAL_TOKEN_HEADER: _TEST_TOKEN,
                 TRACE_ID_HEADER: "0123456789abcdef"},
    )
    assert resp.status_code == 200, resp.text
    assert captured["trace_id"] == "0123456789abcdef"
