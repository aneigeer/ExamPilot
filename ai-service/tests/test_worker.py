"""Redis Stream worker 逻辑测试（stub 驱动，不依赖真实 Redis）。

说明：fakeredis 对 ``XAUTOCLAIM`` 支持不完整，因此这里用可编程 stub 验证
cursor 循环、回收上限、MINID 安全裁剪与 XACK 语义；真实 Redis 的集成验证
放在端到端验收里（见 README 验证记录）。
"""

from __future__ import annotations

import asyncio
from typing import Any, Dict, List, Optional, Tuple

import pytest

from app.clients.java_bridge import JavaBridgeError
from app.config import JobsSettings
from app.worker import handlers
from app.worker.job_worker import StreamWorker


class StubRedis:
    def __init__(self) -> None:
        self.autoclaim_pages: List[Tuple[str, List[Tuple[str, Dict[str, str]]], List[str]]] = []
        self.autoclaim_calls = 0
        self.pending: List[Dict[str, Any]] = []
        self.last_id = "10-0"
        self.trimmed: Optional[str] = None
        self.acked: List[str] = []
        self.xclaim_messages: List[Tuple[str, Dict[str, str]]] = []
        self.xclaim_calls = 0

    async def xautoclaim(self, *args: Any, **kwargs: Any):
        self.autoclaim_calls += 1
        if self.autoclaim_pages:
            return self.autoclaim_pages.pop(0)
        return "0-0", [], []

    async def xpending_range(self, *args: Any, **kwargs: Any):
        return self.pending

    async def xinfo_stream(self, *args: Any, **kwargs: Any):
        return {"last-generated-id": self.last_id}

    async def xtrim(self, stream: str, minid: str):
        self.trimmed = minid
        return 1

    async def xack(self, stream: str, group: str, message_id: str):
        self.acked.append(message_id)
        return 1

    async def xclaim(self, *args: Any, **kwargs: Any):
        self.xclaim_calls += 1
        return self.xclaim_messages


class StubBridge:
    def __init__(self, fail_complete: bool = False) -> None:
        self.fail_complete = fail_complete
        self.completed: List[Tuple[int, Any]] = []
        self.failed: List[Tuple[int, str]] = []
        self.progress: List[Tuple[int, int]] = []

    async def job_progress(self, job_id: int, percent: int) -> None:
        self.progress.append((job_id, percent))

    async def job_complete(self, job_id: int, result: Any) -> None:
        if self.fail_complete:
            raise JavaBridgeError("java down")
        self.completed.append((job_id, result))

    async def job_fail(self, job_id: int, error: str) -> None:
        self.failed.append((job_id, error))


def _worker(bridge: StubBridge) -> StreamWorker:
    worker = StreamWorker(JobsSettings(), "exam:jobs", bridge, label="test")
    worker._redis = StubRedis()  # type: ignore[assignment]
    return worker


def test_xautoclaim_loops_cursor_until_zero():
    bridge = StubBridge()
    worker = _worker(bridge)
    handlers._HANDLERS["TEST_NOOP"] = _noop_handler  # type: ignore[assignment]

    worker._redis.autoclaim_pages = [  # type: ignore[union-attr]
        ("5-0", [("1-0", {"jobId": "1", "type": "TEST_NOOP", "payload": "{}"})], []),
        ("9-0", [("2-0", {"jobId": "2", "type": "TEST_NOOP", "payload": "{}"})], []),
        ("0-0", [], []),
    ]
    claimed = asyncio.run(worker._claim_with_xautoclaim())
    assert claimed == 2
    assert worker._redis.autoclaim_calls == 3  # type: ignore[union-attr]
    assert worker._redis.acked == ["1-0", "2-0"]  # type: ignore[union-attr]


def test_trim_uses_oldest_pending_not_maxlen():
    bridge = StubBridge()
    worker = _worker(bridge)
    worker._redis.pending = [{"message_id": "7-0"}]  # type: ignore[union-attr]
    asyncio.run(worker._trim_stream())
    assert worker._redis.trimmed == "7-0"  # type: ignore[union-attr]


def test_trim_uses_last_generated_when_no_pending():
    bridge = StubBridge()
    worker = _worker(bridge)
    asyncio.run(worker._trim_stream())
    assert worker._redis.trimmed == "10-0"  # type: ignore[union-attr]


def test_process_acks_even_when_complete_callback_fails():
    bridge = StubBridge(fail_complete=True)
    worker = _worker(bridge)
    handlers._HANDLERS["TEST_NOOP"] = _noop_handler  # type: ignore[assignment]
    asyncio.run(
        worker._process("3-0", {"jobId": "3", "type": "TEST_NOOP", "payload": "{}"})
    )
    assert worker._redis.acked == ["3-0"]  # type: ignore[union-attr]
    assert bridge.failed == []


def test_unknown_type_reports_failure_and_acks():
    bridge = StubBridge()
    worker = _worker(bridge)
    asyncio.run(worker._process("4-0", {"jobId": "4", "type": "NOPE", "payload": "{}"}))
    assert worker._redis.acked == ["4-0"]  # type: ignore[union-attr]
    assert bridge.failed and bridge.failed[0][0] == 4


def test_claim_fallback_uses_xpending_and_xclaim():
    bridge = StubBridge()
    worker = _worker(bridge)
    worker._redis.pending = [{"message_id": "8-0"}]  # type: ignore[union-attr]
    worker._redis.xclaim_messages = [  # type: ignore[union-attr]
        ("8-0", {"jobId": "8", "type": "TEST_NOOP", "payload": "{}"})
    ]
    handlers._HANDLERS["TEST_NOOP"] = _noop_handler  # type: ignore[assignment]
    claimed = asyncio.run(worker._claim_with_xpending())
    assert claimed == 1
    assert worker._redis.xclaim_calls == 1  # type: ignore[union-attr]
    assert worker._redis.acked == ["8-0"]  # type: ignore[union-attr]


async def _noop_handler(
    job_type: str, payload: Dict[str, Any], job_id: int, bridge: Any, report: Any
) -> Dict[str, Any]:
    await report(50)
    return {"ok": True}
