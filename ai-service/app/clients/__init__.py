"""外部能力客户端。

每个客户端都遵守同一条铁律：

    **依赖缺失不抛裸异常，而是在 :meth:`xxx.ready` / :meth:`xxx.health` 上报告 ``ready=False``，
    由路由层转成 503 + 统一错误体，绝不让整个服务崩溃。**

- :mod:`app.clients.embedding_client` —— bge-small-zh-v1.5 ONNX INT8 本地推理
- :mod:`app.clients.qdrant_client` —— Qdrant 建集合 / 写入 / 检索
- :mod:`app.clients.llm_client` —— GLM（OpenAI 兼容协议）代理
"""

from __future__ import annotations

__all__ = ["embedding_client", "qdrant_client", "llm_client"]
