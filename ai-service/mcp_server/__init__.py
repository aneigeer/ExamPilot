"""只读 MCP Server（阶段四 Connector/MCP 的 MVP）。

**与主服务的关系**：零耦合。本包不 import FastAPI 的 app 实例、不注册路由、
不监听端口 —— 它是被 MCP 客户端以子进程方式拉起的 stdio 服务，
只复用 ``app.clients.JavaBridge``（内部调用客户端）与 ``app.observability.trace``。

安全边界见 :mod:`mcp_server.server` 的模块 docstring 与 ``README.md``。
"""

from __future__ import annotations

__all__ = ["server", "runner"]
