"""HTTP 路由层。

每个路由只做三件事：校验参数 → 调 client → 转换响应。
**所有异常都交给 :mod:`app.errors` 的全局处理器**，绝不 return 500 堆栈。
"""

from __future__ import annotations

__all__ = ["health", "embed", "search", "index", "ocr", "llm"]
