"""异步任务处理器注册表（Java 生产 → Redis Stream → Python 消费）。

Phase 4 会把 ``EVAL_RETRIEVAL`` / ``EVAL_AGENT`` 注册进来；本模块只提供注册与查找，
避免 worker 直接依赖具体业务实现。
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable, Dict, Optional

logger = logging.getLogger(__name__)

#: 进度回调：percent 为 0-100
ProgressReporter = Callable[[int], Awaitable[None]]

#: 任务处理器签名： (type, payload, job_id, bridge, report) -> result(dict)
JobHandler = Callable[[str, Dict[str, Any], int, Any, ProgressReporter], Awaitable[Dict[str, Any]]]

_HANDLERS: Dict[str, JobHandler] = {}


def register(job_type: str) -> Callable[[JobHandler], JobHandler]:
    """注册任务处理器（重复注册会覆盖，便于测试替换）。"""

    def decorator(fn: JobHandler) -> JobHandler:
        _HANDLERS[job_type] = fn
        return fn

    return decorator


def get_handler(job_type: str) -> Optional[JobHandler]:
    return _HANDLERS.get(job_type)


def registered_types() -> list[str]:
    return sorted(_HANDLERS.keys())
