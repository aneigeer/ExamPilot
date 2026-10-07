"""Embedding 模型预热脚本。

**为什么需要**：首次推理要加载 ONNX 模型（约 1-3 秒），
如果使用时第一次提问才加载，用户会明显感知到延迟。
提前跑一次把模型载入操作系统页缓存，使用场景就是"热"的。

用法::

    cd ai-service
    python -m app.tools.warmup_embedding

期望输出::

    model=bge-small-zh-v1.5 dim=512 load_ms=812 warmed=ok

失败时**退出码为 1**并给出中文原因（模型缺失/依赖未装），便于主理人一眼看出问题。
"""

from __future__ import annotations

import sys

from ..clients.embedding_client import get_embedding_client
from ..config import get_settings
from ..errors import AiServiceError


def main() -> int:
    """执行预热。

    Returns:
        成功返回 ``0``；失败返回 ``1``。
    """
    settings = get_settings()
    print(f"[warmup] 模型目录：{settings.embedding.model_dir}")
    print(f"[warmup] 自动下载：{settings.embedding.auto_download}")
    client = get_embedding_client()
    try:
        print(client.warmup())
        return 0
    except AiServiceError as exc:
        print(f"[warmup] 失败 code={exc.code} message={exc.message}", file=sys.stderr)
        if exc.detail:
            print(f"[warmup] 详情：{exc.detail}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001
        print(f"[warmup] 失败：{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
