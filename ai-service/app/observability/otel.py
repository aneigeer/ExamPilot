"""OpenTelemetry GenAI 可选导出（零依赖、可降级）。

设计约束：
- **默认关闭**：``OTEL_ENABLED`` 未设置或为 false 时返回零开销 NoOp，不 import 任何 otel 包；
- **包未安装**：即使打开开关也静默降级为 NoOp，绝不让请求失败；
- **隐私**：只允许白名单属性，API Key / 原始问题 / 答案一律不写入 Span。
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from typing import Any, Dict, Iterator, Optional

logger = logging.getLogger(__name__)

#: 允许写入 Span 的属性前缀/键（白名单）
_ALLOWED_KEYS = {
    "gen_ai.system",
    "gen_ai.request.model",
    "gen_ai.response.model",
    "gen_ai.usage.input_tokens",
    "gen_ai.usage.output_tokens",
    "gen_ai.operation.name",
    "examprep.trace_id",
    "examprep.tool.name",
    "examprep.tool.risk",
    "examprep.run.status",
}
_FORBIDDEN_HINTS = ("key", "token=", "password", "secret", "authorization")


class _NoopSpan:
    """零开销 Span：所有方法都是空操作。"""

    def set_attribute(self, key: str, value: Any) -> None:  # noqa: D401
        return None

    def record_exception(self, exc: BaseException) -> None:
        return None

    def set_status_error(self, message: str = "") -> None:
        return None


_NOOP = _NoopSpan()


def _sanitize(attributes: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not attributes:
        return {}
    clean: Dict[str, Any] = {}
    for key, value in attributes.items():
        if key not in _ALLOWED_KEYS:
            continue
        lowered = str(key).lower()
        if any(hint in lowered for hint in _FORBIDDEN_HINTS):
            continue
        if isinstance(value, (str, int, float, bool)):
            clean[key] = value
    return clean


def enabled() -> bool:
    """是否真正启用了 OTel（开关打开 + 依赖可用 + 端点非空）。"""
    try:
        from ..config import get_settings

        agent = get_settings().agent
        if not getattr(agent, "otel_enabled", False):
            return False
        if not getattr(agent, "otel_endpoint", ""):
            return False
        import opentelemetry.trace  # noqa: F401
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: F401
            OTLPSpanExporter,
        )
        return True
    except Exception:  # noqa: BLE001 - 任何缺失都视为未启用
        return False


@contextmanager
def span(name: str, attributes: Optional[Dict[str, Any]] = None) -> Iterator[Any]:
    """创建 Span；未启用时返回 NoOp，调用方可无脑使用。"""
    if not enabled():
        yield _NOOP
        return
    try:
        from opentelemetry import trace

        tracer = trace.get_tracer("exam-prep-ai")
        with tracer.start_as_current_span(name) as current:
            for key, value in _sanitize(attributes).items():
                current.set_attribute(key, value)
            yield current
    except Exception as exc:  # noqa: BLE001 - 导出故障不能影响主链路
        logger.warning("[OTel] span %s 创建失败，降级 NoOp：%s", name, type(exc).__name__)
        yield _NOOP
