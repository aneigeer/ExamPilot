"""``POST /v1/llm`` 测试。

覆盖：
* **未配置 LLM_API_KEY → 503**（Java 侧走抽取式）← 关键降级契约
* 503 响应含统一错误体与 52xx 错误码
* 本服务**绝不**返回 ``generator=extractive``（降级决策权只在 Java 侧）
* 请求体组装与引用标注提取（纯函数，无需网络）
* 配置了 Key 但上游不可达 → 503（不是 500）
"""

from __future__ import annotations

from typing import Any

import pytest

# --------------------------------------------------------------------------------------
# 降级用例
# --------------------------------------------------------------------------------------


def test_llm_returns_503_without_api_key(client: Any) -> None:
    """**关键降级契约**：未配置 Key 时必须 503，Java 侧据此走抽取式。"""
    response = client.post(
        "/v1/llm", json={"question": "什么是值传递", "contexts": ["值传递不会改变实参"]}
    )
    assert response.status_code == 503, (
        f"未配置 Key 应返回 503，实际 {response.status_code}：{response.text}"
    )
    body = response.json()
    assert body["code"] == 5209
    assert body["degraded"] is True


def test_llm_503_message_points_to_extractive(client: Any) -> None:
    """降级提示必须明确指向"抽取式兜底"。"""
    body = client.post(
        "/v1/llm", json={"question": "问题", "contexts": ["上下文"]}
    ).json()
    assert "抽取式" in body["message"]


def test_llm_503_never_leaks_key_value(monkeypatch: pytest.MonkeyPatch) -> None:
    """503 响应只提到变量名 ``LLM_API_KEY``，绝不回显 Key 的值。"""
    from app.clients import llm_client as llm_module
    from app.config import get_settings, reset_settings

    reset_settings()
    settings = get_settings()
    object.__setattr__(settings.llm, "api_key", "sk-do-not-leak-me")
    llm_module.reset_llm_client()
    try:
        from fastapi.testclient import TestClient

        from app.main import app

        with TestClient(app, raise_server_exceptions=False) as test_client:
            body = test_client.post(
                "/v1/llm", json={"question": "问题", "contexts": ["上下文"]}
            ).json()
            # 安全红线：响应绝不回显 Key 的值
            assert "sk-do-not-leak-me" not in body["message"]
            # 且必须给出可执行的降级指引（变量名或"抽取式"）
            assert "LLM_API_KEY" in body["message"] or "抽取式" in body["message"]
    finally:
        object.__setattr__(settings.llm, "api_key", "")
        llm_module.reset_llm_client()
        reset_settings()


def test_llm_empty_question_returns_400(client: Any) -> None:
    """空问题 → 400。"""
    response = client.post("/v1/llm", json={"question": "  ", "contexts": ["x"]})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_llm_upstream_failure_returns_503(monkeypatch: pytest.MonkeyPatch) -> None:
    """配置了 Key 但上游不可达 → 503（不是 500）。"""
    from app.clients import llm_client as llm_module
    from app.config import LlmSettings, get_settings, reset_settings

    reset_settings()
    settings = get_settings()
    object.__setattr__(settings.llm, "api_key", "test-key-should-never-be-real")
    llm_module.reset_llm_client()
    try:
        from fastapi.testclient import TestClient

        from app.main import app

        with TestClient(app, raise_server_exceptions=False) as test_client:
            # 把 base_url 指向一个必然连不通的地址，触发上游异常
            object.__setattr__(settings.llm, "base_url", "http://127.0.0.1:59999/v4")
            object.__setattr__(settings.llm, "timeout_seconds", 1.0)
            response = test_client.post(
                "/v1/llm", json={"question": "问题", "contexts": ["上下文"]}
            )
            assert response.status_code == 503, (
                f"上游不可达应返回 503，实际 {response.status_code}：{response.text}"
            )
            assert response.json()["degraded"] is True
    finally:
        object.__setattr__(settings.llm, "api_key", "")
        object.__setattr__(settings.llm, "base_url", "https://open.bigmodel.cn/api/paas/v4")
        llm_module.reset_llm_client()
        reset_settings()


def test_llm_health_reports_not_configured(raw_client: Any, llm_unconfigured: Any) -> None:
    """健康检查如实报告大模型未配置（显式清空 Key，与机器环境解耦）。"""
    llm_health = raw_client.get("/v1/health").json()["llm"]
    assert llm_health["ready"] is False
    assert "LLM_API_KEY" in llm_health["detail"]


# --------------------------------------------------------------------------------------
# 纯函数单测（无需网络/Key）
# --------------------------------------------------------------------------------------


def test_extract_sources_from_citations() -> None:
    """从答案的 [n] 标注提取溯源。"""
    from app.clients.llm_client import extract_sources

    contexts = ["上下文一", "上下文二", "上下文三"]
    answer = "栈是后进先出[1]，队列是先进先出[2]。两者都受限[1][2]。"
    sources = extract_sources(answer, contexts)
    assert [item["index"] for item in sources] == [1, 2]
    assert sources[0]["snippet"] == "上下文一"


def test_extract_sources_dedupes() -> None:
    """重复引用去重。"""
    from app.clients.llm_client import extract_sources

    sources = extract_sources("甲[1] 乙[1] 丙[1]", ["a", "b"])
    assert len(sources) == 1


def test_extract_sources_supports_comma_separated() -> None:
    """支持 ``[1,2]`` 形式。"""
    from app.clients.llm_client import extract_sources

    sources = extract_sources("甲[1,2]", ["a", "b", "c"])
    assert [item["index"] for item in sources] == [1, 2]


def test_extract_sources_ignores_out_of_range() -> None:
    """越界编号被忽略（防幻觉：不因为模型编造 [9] 就崩）。"""
    from app.clients.llm_client import extract_sources

    assert extract_sources("甲[9]", ["a"]) == []


def test_extract_sources_empty_when_no_citation() -> None:
    """无引用标注时返回空 list（不是 None）。"""
    from app.clients.llm_client import extract_sources

    assert extract_sources("没有任何引用", ["a"]) == []


def test_extract_sources_empty_contexts() -> None:
    """无上下文时返回空 list。"""
    from app.clients.llm_client import extract_sources

    assert extract_sources("甲[1]", []) == []


def test_build_payload_includes_instruction_and_numbering() -> None:
    """请求体必须含检索前缀编号（供答案 [n] 引用对齐）。"""
    from app.clients.llm_client import LlmClient
    from app.config import LlmSettings

    client = LlmClient(LlmSettings())
    payload = client._build_payload("什么是栈", ["栈的特点", "队列的特点"], None, None)
    user_message = payload["messages"][-1]["content"]
    assert "[1] 栈的特点" in user_message
    assert "[2] 队列的特点" in user_message
    assert payload["stream"] is False


def test_build_payload_without_context_raises() -> None:
    """无上下文时抛 5209（拒答场景不该调大模型）。"""
    from app.clients.llm_client import LlmClient
    from app.config import LlmSettings
    from app.errors import LlmUnavailableError

    client = LlmClient(LlmSettings())
    with pytest.raises(LlmUnavailableError) as excinfo:
        client._build_payload("问题", [], None, None)
    assert excinfo.value.code == 5209


def test_generator_name_defaults_to_glm() -> None:
    """默认生成器标识为 glm（Java 侧按此区分生成器）。"""
    from app.clients.llm_client import LlmClient
    from app.config import LlmSettings

    assert LlmClient(LlmSettings()).settings.generator_name == "glm"


def test_llm_settings_api_key_empty_by_default() -> None:
    """默认无 Key（安全默认：开箱即用抽取式，不误扣费）。"""
    from app.clients.llm_client import LlmClient
    from app.config import LlmSettings

    settings = LlmSettings()
    assert settings.api_key == ""
    assert LlmClient(settings).ready is False


def test_safe_dump_never_leaks_key() -> None:
    """启动日志的配置摘要不含 Key 内容。"""
    from app.config import Settings

    settings = Settings.from_env()
    object.__setattr__(settings.llm, "api_key", "super-secret-key-value")
    dumped = str(settings.safe_dump())
    assert "super-secret-key-value" not in dumped
    assert "api_key_set" in dumped
