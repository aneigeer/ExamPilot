"""``python -m mcp_server`` 入口。

只读 MCP Server：与主 FastAPI 进程完全解耦（独立进程、独立 stdio），
详见 :mod:`mcp_server.server` 与 ``mcp_server/README.md``。
"""

from __future__ import annotations

from .server import PROTOCOL_VERSION, SERVER_INFO, TOOLS
from .runner import main

__all__ = ["main", "PROTOCOL_VERSION", "SERVER_INFO", "TOOLS", "__version__"]

#: 供 ``python -m mcp_server --version`` 之类的简单探测使用
__version__ = SERVER_INFO["version"]

if __name__ == "__main__":
    raise SystemExit(main())
