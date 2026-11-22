"""Prometheus 文本格式指标端点（零第三方强依赖，符合 exposition format 0.0.4）。"""

from __future__ import annotations

import threading
from collections import defaultdict
from typing import Dict, Tuple

from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

router = APIRouter(tags=["observability"])

_lock = threading.Lock()
_http_requests_total: Dict[Tuple[str, int], int] = defaultdict(int)
_http_duration_sum: Dict[str, float] = defaultdict(float)
_http_duration_count: Dict[str, int] = defaultdict(int)
_llm_tokens_total: Dict[str, int] = {"prompt": 0, "completion": 0}
_tool_calls_total: Dict[Tuple[str, str], int] = defaultdict(int)
_circuit_breaker_open: int = 0


def record_http_request(path: str, status_code: int, duration_seconds: float) -> None:
    """记录一次 HTTP 请求计数与耗时。"""
    norm_path = path.split("?")[0] or "/"
    with _lock:
        _http_requests_total[(norm_path, int(status_code))] += 1
        _http_duration_sum[norm_path] += max(0.0, float(duration_seconds))
        _http_duration_count[norm_path] += 1


def record_llm_tokens(prompt_tokens: int = 0, completion_tokens: int = 0) -> None:
    """累加 LLM Token 消耗。"""
    with _lock:
        _llm_tokens_total["prompt"] += max(0, int(prompt_tokens or 0))
        _llm_tokens_total["completion"] += max(0, int(completion_tokens or 0))


def record_tool_call(tool_name: str, status: str = "success") -> None:
    """记录 Agent 工具调用次数。"""
    safe_tool = (tool_name or "unknown").replace('"', "")
    safe_status = (status or "success").replace('"', "")
    with _lock:
        _tool_calls_total[(safe_tool, safe_status)] += 1


def set_circuit_breaker_open(is_open: bool) -> None:
    """更新熔断器状态指标（1=open, 0=closed）。"""
    global _circuit_breaker_open
    with _lock:
        _circuit_breaker_open = 1 if is_open else 0


@router.get("/metrics", response_class=PlainTextResponse, include_in_schema=True)
def prometheus_metrics() -> PlainTextResponse:
    """暴露 Prometheus 标准文本格式（0.0.4）指标。"""
    lines = [
        "# HELP ai_http_requests_total Total HTTP requests handled by ai-service",
        "# TYPE ai_http_requests_total counter",
    ]
    with _lock:
        for (path, status), count in sorted(_http_requests_total.items()):
            lines.append(f'ai_http_requests_total{{path="{path}",status="{status}"}} {count}')

        lines.append("# HELP ai_http_request_duration_seconds_sum Total HTTP latency in seconds")
        lines.append("# TYPE ai_http_request_duration_seconds_sum counter")
        for path, total_sec in sorted(_http_duration_sum.items()):
            lines.append(f'ai_http_request_duration_seconds_sum{{path="{path}"}} {total_sec:.6f}')

        lines.append("# HELP ai_http_request_duration_seconds_count Total HTTP request count for latency")
        lines.append("# TYPE ai_http_request_duration_seconds_count counter")
        for path, cnt in sorted(_http_duration_count.items()):
            lines.append(f'ai_http_request_duration_seconds_count{{path="{path}"}} {cnt}')

        lines.append("# HELP ai_llm_tokens_total Total LLM tokens processed by type")
        lines.append("# TYPE ai_llm_tokens_total counter")
        for kind, total_tok in sorted(_llm_tokens_total.items()):
            lines.append(f'ai_llm_tokens_total{{type="{kind}"}} {total_tok}')

        lines.append("# HELP ai_agent_tool_calls_total Total Agent tool invocations by tool and status")
        lines.append("# TYPE ai_agent_tool_calls_total counter")
        if _tool_calls_total:
            for (tool, status), cnt in sorted(_tool_calls_total.items()):
                lines.append(f'ai_agent_tool_calls_total{{tool="{tool}",status="{status}"}} {cnt}')
        else:
            lines.append('ai_agent_tool_calls_total{tool="none",status="idle"} 0')

        lines.append("# HELP ai_circuit_breaker_open Whether upstream circuit breaker is open (1=open, 0=closed)")
        lines.append("# TYPE ai_circuit_breaker_open gauge")
        lines.append(f"ai_circuit_breaker_open {_circuit_breaker_open}")

    body = "\n".join(lines) + "\n"
    return PlainTextResponse(
        content=body,
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )
