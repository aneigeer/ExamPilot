"""可观测性支撑（traceId 贯通 / 日志注入）。

本包**不得** import 任何业务模块（中间件、客户端、日志三方都要用它，
反向依赖会造成循环 import）。
"""

from __future__ import annotations

__all__ = ["trace"]
