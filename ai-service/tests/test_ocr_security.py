"""D15 / A-03：``POST /v1/ocr`` 安全收口测试。

覆盖两条独立闸门：

* **鉴权**（``require_internal_token_if_configured``）：配了 ``AI_INTERNAL_TOKEN``
  后，缺失/错误令牌 → **401 / 5231**（fail-open 语义本身不在本文件断言范围）。
* **路径白名单**（``_allowed_roots`` / ``_assert_allowed_path``）：越界 → **400 / 5200**，
  且 message 标明「A-03 白名单」；白名单内不得被 5200 误杀。

**不跑真实推理**：全部用例把 ``OCR_ENABLED`` 设为 false，引擎在
``ensure_loaded`` 处即返回 5205，白名单校验发生在此之前。
"""

from __future__ import annotations

import base64
from contextlib import contextmanager
from typing import Any, Callable, Iterator

import pytest

#: 1x1 像素 PNG（最小合法图片）
_TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

_UNAUTHORIZED_MSG = "内部接口未授权：X-Internal-Token 缺失或无效"


@pytest.fixture()
def configure(monkeypatch: pytest.MonkeyPatch) -> Iterator[Callable[..., None]]:
    """按用例设置环境变量并重置配置/引擎单例（测试结束再重置一次）。

    Args:
        monkeypatch: pytest 夹具（自动回滚环境变量）。

    Yields:
        ``_apply(**env)``：写入环境变量后 ``reset_settings()`` + ``reset_ocr_engine()``。
    """
    from app.config import reset_settings
    from app.ocr.paddle_ocr_engine import reset_ocr_engine

    def _apply(**env: str) -> None:
        for key, value in env.items():
            monkeypatch.setenv(key, value)
        reset_settings()
        reset_ocr_engine()

    try:
        yield _apply
    finally:
        reset_settings()
        reset_ocr_engine()


@contextmanager
def _client() -> Iterator[Any]:
    """构造不替换 embedding/qdrant 桩的轻量 ``TestClient``（OCR 路径不触达二者）。

    Yields:
        ``fastapi.testclient.TestClient``。
    """
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


# --------------------------------------------------------------------------------------
# 鉴权：配了令牌后必须 401 / 5231
# --------------------------------------------------------------------------------------


def test_ocr_requires_token_when_configured(configure: Callable[..., None]) -> None:
    """配了令牌但没带 ``X-Internal-Token`` → 401 且 code=5231。"""
    configure(AI_INTERNAL_TOKEN="s3cret", OCR_ENABLED="false")
    with _client() as client:
        resp = client.post("/v1/ocr", json={"image_base64": "AAAA"})
    assert resp.status_code == 401, f"应 401，实际 {resp.status_code}：{resp.text}"
    body = resp.json()
    assert body["code"] == 5231, f"错误码应为 5231，实际 {body.get('code')}"
    assert body["message"] == _UNAUTHORIZED_MSG
    assert "degraded" in body


def test_ocr_rejects_wrong_token(configure: Callable[..., None]) -> None:
    """配了令牌但带错令牌 → 401。"""
    configure(AI_INTERNAL_TOKEN="s3cret", OCR_ENABLED="false")
    with _client() as client:
        resp = client.post(
            "/v1/ocr",
            json={"image_base64": "AAAA"},
            headers={"X-Internal-Token": "wrong"},
        )
    assert resp.status_code == 401, f"应 401，实际 {resp.status_code}：{resp.text}"
    assert resp.json()["code"] == 5231


# --------------------------------------------------------------------------------------
# 路径白名单：越界 400/5200，界内不得误杀
# --------------------------------------------------------------------------------------


def test_ocr_rejects_path_outside_allowlist(
    configure: Callable[..., None], tmp_path: Any
) -> None:
    """白名单外的已存在图片 → 400 且 code=5200，message 含「A-03 白名单」。"""
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    target = outside / "escape.png"
    target.write_bytes(_TINY_PNG)

    configure(OCR_ENABLED="false", OCR_ALLOWED_ROOTS=str(allowed))
    with _client() as client:
        resp = client.post("/v1/ocr", json={"file_path": str(target)})

    assert resp.status_code == 400, f"应 400，实际 {resp.status_code}：{resp.text}"
    body = resp.json()
    assert body["code"] == 5200, f"错误码应为 5200，实际 {body.get('code')}"
    assert "A-03 白名单" in body["message"], body["message"]


def test_ocr_accepts_path_inside_allowlist(
    configure: Callable[..., None], tmp_path: Any
) -> None:
    """白名单内的图片不得被判 5200（OCR 关闭时落到 503/5205 属正常降级）。"""
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    target = allowed / "page.png"
    target.write_bytes(_TINY_PNG)

    configure(OCR_ENABLED="false", OCR_ALLOWED_ROOTS=str(allowed))
    with _client() as client:
        resp = client.post("/v1/ocr", json={"file_path": str(target)})

    body = resp.json()
    assert body.get("code") != 5200, f"白名单内被误杀：{resp.status_code} {resp.text}"
    assert resp.status_code == 503, f"OCR 关闭时应 503，实际 {resp.status_code}：{resp.text}"
