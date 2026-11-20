"""评测结果写回 ai_eval_run（CLI 直跑场景）。

worker 触发的评测走 ``/jobs/{id}/complete`` 自动落库；当脚本独立运行时，
加 ``--write-back`` 即调用 Java 内部接口 ``POST /internal/ai/eval-runs`` 落一条运行记录。
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from app.clients.java_bridge import JavaBridge, JavaBridgeError
from app.config import get_settings

logger = logging.getLogger(__name__)


async def write_eval_run(
    layer: str,
    total: int,
    passed: int,
    metrics: Dict[str, Any],
    detail: List[Dict[str, Any]],
    report_path: Optional[str] = None,
    status: str = "SUCCESS",
    error: Optional[str] = None,
) -> Optional[int]:
    """写回一条评测运行记录，返回 ai_eval_run.id；失败只告警不抛。"""
    settings = get_settings()
    bridge = JavaBridge(settings.agent)
    payload = {
        "layer": layer,
        "status": status,
        "total": total,
        "passed": passed,
        "metrics": metrics,
        "detail": detail,
        "reportPath": report_path,
        "error": error,
    }
    try:
        data = await bridge.tool_post("/internal/ai/eval-runs", payload)
    except JavaBridgeError as exc:
        logger.warning("[EvalWriteBack] 写回失败 layer=%s：%s", layer, exc)
        return None
    if isinstance(data, dict):
        value = data.get("id")
        return int(value) if isinstance(value, (int, float)) else None
    return None
