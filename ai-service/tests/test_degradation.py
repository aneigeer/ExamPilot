"""降级契约测试 —— 本套测试的**战略意义**。

对应设计方案 D.6 的"最坏情况：用户什么都不装"：

    Docker 停、FastAPI 不起、MinIO 不起、OCR 不装 →
    系统完整退化为 Java 单体 AI 形态，功能不减。

因此本文件验证的是**架构安全属性**而非业务功能：

1. 缺关键依赖时模块导入**不崩**（抛的是清晰 ImportError，不是把服务打崩）
2. 每个能力都有 :meth:`ready` / :meth:`is_available` 探针
3. 缺依赖 → 对应接口 503 + 统一错误体
4. **一个都不装时 ``/v1/health`` 仍然 200**
"""

from __future__ import annotations

import importlib
from typing import Any

import pytest


# --------------------------------------------------------------------------------------
# 模块导入行为
# --------------------------------------------------------------------------------------


def test_config_module_has_no_third_party_dependency() -> None:
    """``app.config`` 只用标准库 —— 配置层不能成为故障点。"""
    module = importlib.import_module("app.config")
    assert hasattr(module, "Settings")
    assert hasattr(module, "get_settings")


def test_errors_module_has_no_third_party_dependency() -> None:
    """``app.errors`` 只用标准库。"""
    module = importlib.import_module("app.errors")
    assert module.ErrorCode.CAPABILITY_UNAVAILABLE == 5201
    assert module.ErrorCode.LLM_UNAVAILABLE == 5209


def test_structure_parser_is_importable_without_ocr() -> None:
    """**关键**：结构化解析是纯 Python，没装 paddleocr 也能跑、能测。"""
    module = importlib.import_module("app.ocr.structure_parser")
    assert module.classify_block("1. 题干") == "stem"


def test_clients_import_without_optional_deps() -> None:
    """三个 client 模块导入时**不** import 重量级依赖（否则服务起不来）。

    这是"懒加载"的实现层保证：模块顶层只 import 标准库 + 本项目模块。
    """
    for name in (
        "app.clients.embedding_client",
        "app.clients.qdrant_client",
        "app.clients.llm_client",
    ):
        module = importlib.import_module(name)
        assert module is not None, f"{name} 导入失败"


def test_ocr_engine_module_imports_without_paddleocr() -> None:
    """``app.ocr.paddle_ocr_engine`` 导入时不需要 paddleocr 存在。"""
    module = importlib.import_module("app.ocr.paddle_ocr_engine")
    assert hasattr(module, "PaddleOcrEngine")


def test_embedding_client_import_error_is_actionable() -> None:
    """缺 onnxruntime / 模型文件时抛的错必须说清"怎么办"，而不是裸 ImportError。"""
    from pathlib import Path

    from app.clients.embedding_client import EmbeddingClient
    from app.config import EmbeddingSettings
    from app.errors import CapabilityUnavailableError

    # 指向不存在的模型目录 → 无论本机是否装了 onnxruntime/是否放了模型，都确定性触发 5201
    client = EmbeddingClient(EmbeddingSettings(models_dir=Path("Z:/不存在-模型目录")))
    with pytest.raises(CapabilityUnavailableError) as excinfo:
        client.ensure_loaded()
    assert excinfo.value.http_status == 503
    assert excinfo.value.degraded is True


# --------------------------------------------------------------------------------------
# 能力探针
# --------------------------------------------------------------------------------------


def test_embedding_reports_not_capable_without_model() -> None:
    """模型文件缺失时 ``capable/ready=False`` 且诊断可操作。"""
    from app.clients.embedding_client import EmbeddingClient
    from app.config import EmbeddingSettings

    client = EmbeddingClient(
        EmbeddingSettings(models_dir=__import__("pathlib").Path("Z:/不存在"))
    )
    assert client.capable is False
    assert client.loaded is False
    assert client.ready is False, "ready 语义 = capable，缺模型时必须为 False"
    detail = client.health_detail()
    # 提示必须说清「缺什么、放哪」，而不是一句"未就绪"
    assert "模型文件缺失" in detail or "不可用" in detail


def test_ocr_reports_not_available_without_paddleocr() -> None:
    """未装 paddleocr 时 ``is_available()=False``。"""
    from app.config import OcrSettings
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    engine = PaddleOcrEngine(OcrSettings(enabled=True))
    import importlib.util

    if importlib.util.find_spec("paddleocr") is None:
        assert engine.is_available() is False
    assert engine.loaded is False


def test_ocr_disabled_probe_returns_false() -> None:
    """OCR_ENABLED=false 时探针为 false。"""
    from app.config import OcrSettings
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    assert PaddleOcrEngine(OcrSettings(enabled=False)).is_available() is False


def test_llm_reports_not_ready_without_key() -> None:
    """未配置 Key 时 ``ready=False``。"""
    from app.clients.llm_client import LlmClient
    from app.config import LlmSettings

    assert LlmClient(LlmSettings(api_key="")).ready is False


# --------------------------------------------------------------------------------------
# 端到端降级
# --------------------------------------------------------------------------------------


def test_all_endpoints_degrade_gracefully(embedding_down: Any) -> None:
    """**核心用例**：依赖不可用时，所有能力接口都返回 503 而非 5xx 崩溃。

    逐个检查：HTTP 状态码是 503、错误体是 ``{code, message, degraded}``、
    ``degraded=True``、错误码在 52xx 段、消息里没有堆栈。
    用 embedding_down(disable_ocr=True) 显式制造「embedding + OCR 都不可用」，
    避免因本机已装依赖/已放模型而使断言失真。
    """
    cases = [
        ("POST", "/v1/embed", {"texts": ["测试"]}),
        ("POST", "/v1/search", {"query": "测试", "top_k": 3}),
        ("POST", "/v1/index/sync", {"chunks": [{"chunk_id": 1, "content": "内容"}]}),
        ("POST", "/v1/ocr", {"image_base64": "aGVsbG8="}),
        ("POST", "/v1/llm", {"question": "问题", "contexts": ["上下文"]}),
    ]
    with embedding_down(disable_ocr=True) as test_client:
        for method, path, payload in cases:
            response = test_client.request(method, path, json=payload)
            assert response.status_code == 503, (
                f"{path} 应返回 503，实际 {response.status_code}：{response.text}"
            )
            body = response.json()
            assert set(body.keys()) == {"code", "message", "degraded"}, (
                f"{path} 错误体结构不符：{body}"
            )
            assert body["degraded"] is True, f"{path} 未标记 degraded"
            assert 5200 <= body["code"] <= 5299, f"{path} 错误码越界：{body['code']}"
            assert "Traceback" not in body["message"], f"{path} 消息里含堆栈"


def test_health_is_200_even_with_zero_dependencies(
    embedding_down: Any, llm_unconfigured: Any
) -> None:
    """**安全底线**：依赖不可用时，``/v1/health`` 仍 200。

    没有这条，Java 侧连"探测 AI 服务是否可用"都做不到，降级链路的起点就断了。
    """
    with embedding_down() as test_client:
        response = test_client.get("/v1/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "degraded"
    assert data["embedding"]["ready"] is False
    assert data["llm"]["ready"] is False


def test_no_endpoint_returns_500(raw_client: Any) -> None:
    """全部接口在任何依赖状态下都**不返回 500**（500 = 不可预期崩溃 = 不可接受）。"""
    paths = [
        ("POST", "/v1/embed", {"texts": ["x"]}),
        ("POST", "/v1/search", {"query": "x"}),
        ("POST", "/v1/index/sync", {"chunks": [{"chunk_id": 1, "content": "x"}]}),
        ("POST", "/v1/index/rebuild", {}),
        ("POST", "/v1/ocr", {"image_base64": "aGk="}),
        ("POST", "/v1/llm", {"question": "x", "contexts": ["y"]}),
        ("GET", "/v1/index/status", None),
        ("GET", "/v1/health", None),
    ]
    for method, path, payload in paths:
        response = raw_client.request(method, path, json=payload)
        assert response.status_code != 500, f"{path} 返回了 500：{response.text}"
