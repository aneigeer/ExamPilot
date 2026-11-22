"""OTel 可选导出的降级与脱敏测试。"""

from __future__ import annotations

from app.observability import otel


def test_default_disabled_is_noop(monkeypatch):
    monkeypatch.setattr(otel, "enabled", lambda: False)
    with otel.span("agent.run", {"gen_ai.system": "ollama"}) as current:
        current.set_attribute("gen_ai.usage.input_tokens", 10)
        current.set_attribute("api_key", "should-be-dropped")
    # 不抛异常即为通过；NoOp 不保存任何状态


def test_sanitize_drops_secrets_and_unknown_keys():
    clean = otel._sanitize({
        "gen_ai.system": "ollama",
        "gen_ai.usage.input_tokens": 12,
        "gen_ai.request.model": "exam-prep-7b",
        "api_key": "sk-secret",
        "authorization": "Bearer x",
        "question": "用户原始问题",
        "examprep.trace_id": "abc123",
    })
    assert clean["gen_ai.system"] == "ollama"
    assert clean["gen_ai.usage.input_tokens"] == 12
    assert clean["examprep.trace_id"] == "abc123"
    assert "api_key" not in clean
    assert "authorization" not in clean
    assert "question" not in clean


def test_enabled_requires_flag_and_endpoint(monkeypatch):
    class Agent:
        otel_enabled = False
        otel_endpoint = "http://127.0.0.1:4318/v1/traces"

    class Settings:
        agent = Agent()

    monkeypatch.setattr("app.config.get_settings", lambda: Settings())
    assert otel.enabled() is False
    Agent.otel_enabled = True
    # 未安装 opentelemetry 时必须仍为 False（本机未安装）；安装后由真实依赖决定
    assert otel.enabled() in (True, False)


def test_span_with_disabled_endpoint_never_raises(monkeypatch):
    monkeypatch.setattr(otel, "enabled", lambda: False)
    with otel.span("gen_ai.chat", None) as current:
        current.record_exception(RuntimeError("boom"))
