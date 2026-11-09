"""``python -m mcp_server`` —— 只读 MCP Server 的 stdio 循环。

职责边界：本模块只做 **读一行 → 解析 → handle_request → 写一行**，
协议逻辑全在 :mod:`mcp_server.server`（纯函数，易于测试）。

**stdout 只允许出现 JSON-RPC 报文**：任何调试输出都必须走 stderr，
否则客户端会收到"非 JSON 行"并直接断连。因此这里不调用 ``print``，
而是显式写 ``stdout`` 并 ``flush``。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, Optional, TextIO

from app.clients.java_bridge import JavaBridge
from app.config import get_settings
from app.observability.trace import new_trace_id, set_trace_id

from .server import (
    INVALID_REQUEST,
    PARSE_ERROR,
    handle_request,
    parse_line,
    _ParseFailure,
    _error,
)

logger = logging.getLogger("mcp_server")


def configure_logging() -> None:
    """日志一律写 **stderr**（stdout 被 JSON-RPC 独占）。"""
    logging.basicConfig(
        level=logging.INFO,
        stream=sys.stderr,
        format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
        force=True,
    )


def resolve_user_id() -> Optional[int]:
    """从服务端配置读当前用户 ID（``MCP_USER_ID``），非法/缺失返回 ``None``。"""
    raw = os.environ.get("MCP_USER_ID", "").strip()
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        logger.error("[MCP] MCP_USER_ID 不是整数：%r（错题本工具将不可用）", raw)
        return None


def build_bridge() -> JavaBridge:
    """按配置构造 Java 内部接口客户端（令牌缺失时直接退出，fail-closed）。"""
    settings = get_settings()
    if not settings.agent.internal_token:
        logger.error(
            "[MCP] AI_INTERNAL_TOKEN 未配置：MCP 工具将全部调用失败（Java 返回 403/401）。"
            "拒绝启动 —— 只读工具也不该在没有身份凭证的前提下暴露。"
            "修复：在 ai-service/.env 写入与 Java app.ai.internal-token 同值的令牌。"
        )
        raise SystemExit(2)
    return JavaBridge(settings.agent)


def process_line(line: str, bridge: JavaBridge, user_id: Optional[int]) -> Optional[Dict[str, Any]]:
    """处理一行输入，返回响应 dict（通知/空行返回 ``None``）。

    Args:
        line: 原始输入行。
        bridge: Java 桥。
        user_id: 服务端配置的用户 ID。

    Returns:
        JSON-RPC 响应或 ``None``。
    """
    text = line.strip()
    if not text:
        return None
    payload = parse_line(text)
    if isinstance(payload, _ParseFailure):
        return _error(None, PARSE_ERROR, "JSON 解析失败")
    # 每条请求一个独立 traceId：Java 侧日志可直接串到这次 MCP 调用
    set_trace_id(new_trace_id())
    return asyncio.run(handle_request(payload, bridge, user_id))


def run_stdio(stdin: TextIO, stdout: TextIO, bridge: JavaBridge,
              user_id: Optional[int]) -> int:
    """stdio 主循环（阻塞直到 EOF）。

    Args:
        stdin: 输入流（生产是 ``sys.stdin``）。
        stdout: 输出流（生产是 ``sys.stdout``，只写 JSON-RPC 报文）。
        bridge: Java 桥。
        user_id: 服务端配置的用户 ID。

    Returns:
        进程退出码（``0`` 正常）。
    """
    logger.info("[MCP] exam-prep-mcp 已就绪（stdio，MCP 2024-11-05，只读三工具）")
    for line in stdin:
        response = process_line(line, bridge, user_id)
        if response is None:
            continue
        stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        stdout.flush()
    logger.info("[MCP] stdin 已关闭，退出")
    return 0


def main() -> int:
    """入口：配置日志 → 校验令牌 → 进入 stdio 循环。"""
    configure_logging()
    bridge = build_bridge()   # 令牌为空 → SystemExit(2)
    user_id = resolve_user_id()
    if user_id is None:
        logger.warning(
            "[MCP] 未配置 MCP_USER_ID：get_wrong_book 将返回 -32000（错题本需服务端指定用户）"
        )
    return run_stdio(sys.stdin, sys.stdout, bridge, user_id)


if __name__ == "__main__":  # pragma: no cover - 由 __main__ 转发
    raise SystemExit(main())
