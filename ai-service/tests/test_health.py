"""``GET /v1/health`` 测试。

覆盖：
* 正常返回 200 与四份能力状态
* 缺依赖时**依然返回 200**（服务能起是关键安全属性）
* Swagger 文档可访问（使用加分项）
* TraceId 透传
* 统一错误体结构
"""

from __future__ import annotations

from typing import Any


def test_health_returns_200(client: Any) -> None:
    """健康检查恒返回 200（哪怕能力全不可用）。"""
    response = client.get("/v1/health")
    assert response.status_code == 200, response.text


def test_health_contains_all_components(client: Any) -> None:
    """响应必须含 embedding / qdrant / ocr / llm 四项能力。"""
    data = client.get("/v1/health").json()
    for key in ("status", "embedding", "qdrant", "ocr", "llm", "version"):
        assert key in data, f"响应缺少字段 {key}：{data}"


def test_health_status_is_ok_or_degraded(client: Any) -> None:
    """status 只能是 ok / degraded 两种取值。"""
    data = client.get("/v1/health").json()
    assert data["status"] in ("ok", "degraded")


def test_health_embedding_shape(client: Any) -> None:
    """embedding 子结构含 ready 与 dim。"""
    embedding = client.get("/v1/health").json()["embedding"]
    assert "ready" in embedding
    assert "dim" in embedding
    assert isinstance(embedding["ready"], bool)


def test_health_ready_flag_is_bool(client: Any) -> None:
    """各能力 ready 必须是布尔值（Java 侧按布尔反序列化）。"""
    data = client.get("/v1/health").json()
    for key in ("qdrant", "ocr", "llm"):
        assert isinstance(data[key]["ready"], bool), f"{key}.ready 不是布尔值"


def test_health_llm_not_ready_without_key(client: Any) -> None:
    """未配置 LLM_API_KEY 时 llm.ready 必须为 false（而不是报错）。"""
    data = client.get("/v1/health").json()
    assert data["llm"]["ready"] is False
    assert "LLM_API_KEY" in data["llm"]["detail"]


def test_health_survives_missing_optional_deps(raw_client: Any) -> None:
    """**核心安全属性**：不替换任何桩、依赖可能缺失时，健康检查仍必须 200。

    这是 Java 侧降级链路成立的前提 —— 服务起不来就谈不上降级。
    """
    response = raw_client.get("/v1/health")
    assert response.status_code == 200, (
        f"缺依赖时健康检查应仍返回 200，实际 {response.status_code}：{response.text}"
    )
    data = response.json()
    assert data["embedding"]["ready"] is False, "本机未放模型，embedding 应为未就绪"
    assert data["embedding"]["dim"] == 0, "未就绪时 dim 应为 0"
    assert data["status"] == "degraded"


def test_health_detail_has_no_traceback(raw_client: Any) -> None:
    """诊断信息里绝不能出现 Python 堆栈（防泄露实现细节）。"""
    data = raw_client.get("/v1/health").json()
    for key in ("embedding", "qdrant", "ocr", "llm"):
        detail = data[key]["detail"]
        assert "Traceback" not in detail
        assert "File \"" not in detail


def test_root_endpoint(client: Any) -> None:
    """根路径返回基本信息。"""
    data = client.get("/").json()
    assert data["health"] == "/v1/health"
    assert data["docs"] == "/docs"


def test_swagger_available(client: Any) -> None:
    """Swagger 文档可访问（使用加分项）。"""
    response = client.get("/docs")
    assert response.status_code == 200
    assert "swagger" in response.text.lower()


def test_openjson_lists_all_endpoints(client: Any) -> None:
    """OpenAPI 文档列出全部 7 个接口。"""
    paths = client.get("/openapi.json").json()["paths"]
    for path in (
        "/v1/health",
        "/v1/embed",
        "/v1/search",
        "/v1/ocr",
        "/v1/llm",
        "/v1/index/sync",
        "/v1/index/rebuild",
        "/v1/index/status",
    ):
        assert path in paths, f"OpenAPI 缺少接口 {path}"


def test_trace_id_header_echoed(client: Any) -> None:
    """上游传入的 X-Trace-Id 会被透传回响应头（与 Java traceId 串联）。"""
    response = client.get("/v1/health", headers={"X-Trace-Id": "trace-abc-123"})
    assert response.headers.get("X-Trace-Id") == "trace-abc-123"


def test_trace_id_generated_when_absent(client: Any) -> None:
    """未传 TraceId 时自动生成。"""
    response = client.get("/v1/health")
    assert response.headers.get("X-Trace-Id")


def test_unknown_path_returns_unified_error_body(client: Any) -> None:
    """未知路径也返回统一错误体，而不是 FastAPI 默认结构。"""
    response = client.get("/v1/not-exist")
    assert response.status_code == 404
    body = response.json()
    assert set(body.keys()) == {"code", "message", "degraded"}
    assert body["code"] == 5200


def test_error_code_range_is_52xx(client: Any) -> None:
    """错误码必须落在 52xx 段（避开 Java 已占用的 5001-5007）。"""
    body = client.get("/v1/not-exist").json()
    assert 5200 <= body["code"] <= 5299, f"错误码越界：{body['code']}"
