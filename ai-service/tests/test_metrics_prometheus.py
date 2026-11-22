"""Prometheus /metrics 指标端点单元测试。"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app
from app.routers.metrics import record_llm_tokens, record_tool_call, set_circuit_breaker_open


def test_prometheus_metrics_endpoint() -> None:
    client = TestClient(app)
    # 先发起一次健康检查，确保 HTTP 请求计数器有样本
    r_health = client.get("/v1/health")
    assert r_health.status_code == 200

    record_llm_tokens(prompt_tokens=120, completion_tokens=45)
    record_tool_call("search_knowledge", "success")
    set_circuit_breaker_open(False)

    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "text/plain" in resp.headers.get("content-type", "")
    body = resp.text
    assert "# TYPE ai_http_requests_total counter" in body
    assert 'ai_http_requests_total{path="/v1/health",status="200"}' in body
    assert 'ai_llm_tokens_total{type="prompt"}' in body
    assert 'ai_agent_tool_calls_total{tool="search_knowledge",status="success"}' in body
    assert "ai_circuit_breaker_open 0" in body
