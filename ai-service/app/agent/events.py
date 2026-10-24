"""Agent SSE 事件序列化。

Python 只负责产出事件；Java 负责反序列化、重组、转发与落库。
每个事件固定为 ``event: <name>\\ndata: <json>\\n\\n``（data 单行，避免多行解析分歧）。
"""

from __future__ import annotations

import json
from typing import Any, Dict

EVENT_META = "meta"
EVENT_THOUGHT = "thought"
EVENT_TOOL_CALL = "tool_call"
EVENT_TOOL_RESULT = "tool_result"
EVENT_BUDGET = "budget_truncated"
EVENT_FINAL = "final"
EVENT_DONE = "done"
EVENT_ERROR = "error"


def event(name: str, data: Dict[str, Any]) -> Dict[str, Any]:
    """构造内部事件对象（供非流式接口复用）。"""
    return {"event": name, "data": data}


def sse(name: str, data: Dict[str, Any]) -> str:
    """序列化为 SSE 文本块（HTTP 流式接口使用）。"""
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {name}\ndata: {payload}\n\n"
