"""工具长输出落盘（Artifact Offloading）。

单条工具观察超过阈值时，完整 JSON 写入
``<project>/runtime/agent_artifacts/<safe_trace_id>/<artifact_id>.json``，
模型上下文只保留预览 + artifact_id；需要细节时再调用只读工具 ``read_artifact``
按段读取。

安全边界：
- trace_id 先做白名单正则，非法值统一落到 ``default`` 目录；
- artifact_id 必须是 16 位小写十六进制，拒绝 ``..``、``/``、``\\``；
- 读取时同时校验 trace_id 归属，跨会话读不到别人的文件。
"""

from __future__ import annotations

import json
import re
import time
import uuid
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

TRACE_SAFE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
ARTIFACT_ID = re.compile(r"^[a-f0-9]{16}$")
DEFAULT_TRACE = "default"
TTL_SECONDS = 24 * 3600


def _root() -> Path:
    # ai-service/app/agent/artifact_store.py -> project_root/runtime/agent_artifacts
    return Path(__file__).resolve().parents[3] / "runtime" / "agent_artifacts"


def safe_trace(trace_id: Optional[str]) -> str:
    value = (trace_id or "").strip()
    return value if TRACE_SAFE.match(value) else DEFAULT_TRACE


class ArtifactStore:
    """按 trace 隔离的 JSON artifact 存储。"""

    def __init__(self, root: Optional[Path] = None, ttl_seconds: int = TTL_SECONDS) -> None:
        self.root = root or _root()
        self.ttl_seconds = ttl_seconds

    def save(self, data: Any, trace_id: Optional[str]) -> Tuple[str, int]:
        """写入完整数据，返回 (artifact_id, 字符数)。"""
        trace = safe_trace(trace_id)
        artifact_id = uuid.uuid4().hex[:16]
        directory = self.root / trace
        directory.mkdir(parents=True, exist_ok=True)
        text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
        (directory / f"{artifact_id}.json").write_text(text, encoding="utf-8")
        return artifact_id, len(text)

    def read(self, artifact_id: str, trace_id: Optional[str],
             offset: int = 0, limit: int = 1200) -> Dict[str, Any]:
        """按段读取；任何非法输入都返回 ``{ok: false, error}``，不抛异常。"""
        if not ARTIFACT_ID.match(artifact_id or ""):
            return {"ok": False, "error": "artifact_id 非法"}
        trace = safe_trace(trace_id)
        path = (self.root / trace / f"{artifact_id}.json").resolve()
        base = (self.root / trace).resolve()
        if base not in path.parents:
            return {"ok": False, "error": "路径越界"}
        if not path.exists():
            return {"ok": False, "error": "artifact 不存在或已过期"}
        text = path.read_text(encoding="utf-8")
        start = max(0, int(offset))
        size = max(1, min(4000, int(limit)))
        chunk = text[start:start + size]
        return {
            "ok": True,
            "artifact_id": artifact_id,
            "offset": start,
            "limit": size,
            "total": len(text),
            "next_offset": start + size if start + size < len(text) else None,
            "content": chunk,
        }

    def cleanup(self) -> int:
        """删除超过 TTL 的文件与空目录，返回删除数量。"""
        if not self.root.exists():
            return 0
        deadline = time.time() - self.ttl_seconds
        removed = 0
        for path in self.root.rglob("*.json"):
            try:
                if path.stat().st_mtime < deadline:
                    path.unlink()
                    removed += 1
            except OSError:
                continue
        for directory in sorted(self.root.rglob("*"), reverse=True):
            if directory.is_dir():
                try:
                    directory.rmdir()
                except OSError:
                    pass
        return removed
