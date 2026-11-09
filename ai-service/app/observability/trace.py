"""TraceId 贯通（**Python 侧唯一来源**）。

对应契约 ``docs/Agent阶段三接口契约.md`` §1：

* 载体：请求/响应头 ``X-Trace-Id``，值 ``^[0-9a-f]{16}$``；
* 优先级：合法入站头 > 当前 contextvar > 新生成；**入站值非法则丢弃重生成，不报错**
  （防日志注入）；
* 日志：``logging.Filter`` 给每条 record 注入 ``trace_id``，格式里用
  ``[%(trace_id)s]``；**禁止**用 ``request.state`` 取 trace（并发下会串号）。

为什么单独一个模块：中间件、``JavaBridge``、日志 Filter 三处都要读 trace，
放进任一方都会造成循环 import（``main`` → routers → ``java_bridge`` → ``main``）。
本模块**零业务依赖**，谁都 import 得起。

.. note::

   ``main.TRACE_ID_HEADER`` 是本常量的再导出（契约要求这个名字存在），
   字符串只在下面定义一次，不要新增第三个副本。
"""

from __future__ import annotations

import contextvars
import logging
import re
import uuid

#: TraceId 头名（与 Java ``TraceContext.HEADER`` 必须逐字相等）
TRACE_ID_HEADER = "X-Trace-Id"

#: 合法 traceId：16 位小写十六进制（与 Java ``UUID...substring(0,16)`` 一致）
TRACE_ID_PATTERN = re.compile(r"^[0-9a-f]{16}$")

#: 日志里"没有 trace"时的占位符（用 ``-`` 而非空串，便于 grep 对齐列）
EMPTY_TRACE_ID = "-"

#: 当前请求/任务的 traceId。默认空串 = "没有"，由调用方决定是否生成。
_CURRENT_TRACE_ID: contextvars.ContextVar[str] = contextvars.ContextVar(
    "ai_service_trace_id", default=""
)


def new_trace_id() -> str:
    """生成一个合法 traceId（16 位小写十六进制）。"""
    return uuid.uuid4().hex[:16]


def is_valid_trace_id(value: object) -> bool:
    """判断是否为合法 traceId（``str`` + 16 位小写 hex）。"""
    return isinstance(value, str) and bool(TRACE_ID_PATTERN.match(value))


def normalize_trace_id(value: object) -> str:
    """把入站值规范成可用 traceId：合法则原样返回，否则返回空串。

    Args:
        value: 头部带来的原始值（可能是 ``None`` / 非法字符串 / 超长串）。

    Returns:
        合法的 16 位 hex；非法或缺失时返回 ``""``（由调用方重新生成）。
    """
    return value if is_valid_trace_id(value) else ""


def get_trace_id() -> str:
    """当前上下文的 traceId（没有则返回空串）。"""
    return _CURRENT_TRACE_ID.get() or ""


def set_trace_id(trace_id: str) -> contextvars.Token:
    """把 traceId 绑定到当前上下文。

    Args:
        trace_id: 待绑定的值；空串表示"清除"。

    Returns:
        contextvar Token，供 :func:`reset_trace_id` 还原。
    """
    return _CURRENT_TRACE_ID.set(trace_id or "")


def reset_trace_id(token: contextvars.Token) -> None:
    """还原 :func:`set_trace_id` 的绑定（跨上下文 reset 不抛异常）。"""
    try:
        _CURRENT_TRACE_ID.reset(token)
    except (ValueError, RuntimeError):  # pragma: no cover - 仅跨上下文 reset 触发
        logging.getLogger(__name__).debug("[Trace] reset 失败（跨上下文），忽略")


class TraceIdLogFilter(logging.Filter):
    """给每条日志 record 注入 ``trace_id``，供格式 ``[%(trace_id)s]`` 使用。

    用 Filter 而不是 LoggerAdapter：业务代码里 50 多处 ``logger.xxx`` 不用改，
    也能覆盖第三方库（httpx/uvicorn）打进来的记录 —— 只是它们默认没有值，
    这里统一填 ``-``，格式永远不会因缺字段而报 "Logging error"。
    """

    def filter(self, record: logging.LogRecord) -> bool:
        """始终返回 ``True``（只补属性，不过滤任何日志）。"""
        if not getattr(record, "trace_id", ""):
            record.trace_id = get_trace_id() or EMPTY_TRACE_ID
        return True
