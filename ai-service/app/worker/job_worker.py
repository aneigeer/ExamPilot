"""Redis Stream 消费者（asyncio）。

关键约束（均已实测/评审确认）：

1. **只用 ``redis.asyncio``**，阻塞读、ACK、claim、trim 全部 ``await``，
   不使用同步客户端，也不把 worker 塞进线程。
2. **每条 stream 独立 Redis 实例 + BlockingConnectionPool(max_connections=2)**：
   1 个连接只做阻塞 ``XREADGROUP``，另 1 个由管理协程跑 claim/trim；
   XACK 在阻塞读协程内执行（读命令返回后连接已归还 pool，再借还）。
3. **不使用 MAXLEN 裁剪**：``XTRIM MINID`` 以「最老 PENDING ID」为安全边界，
   绝不裁掉未 ACK 消息（被裁消息无法通过 XCLAIM 恢复）。
4. **XAUTOCLAIM 循环 cursor 直到 0-0**，单轮上限 1000 条；
   不支持时回退 ``XPENDING + XCLAIM``。
5. **管理协程异常退避**：连续失败 3 次后 60→120→240→600s，成功一次重置。
6. **终态以 Java 为准**：回调失败只记日志，消息照常 ACK（失败不无限重投）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
from typing import Any, Dict, List, Optional, Tuple

from ..clients.java_bridge import JavaBridge, JavaBridgeError
from ..config import JobsSettings
from .handlers import ProgressReporter, get_handler

logger = logging.getLogger(__name__)

_MAX_CLAIM_PER_ROUND = 1000
_CLAIM_BATCH = 100


def _consumer_name(settings: JobsSettings) -> str:
    if settings.consumer:
        return settings.consumer
    return f"{socket.gethostname()}-{os.getpid()}"


class StreamWorker:
    """单条 stream 的消费者（读循环 + 管理协程并行）。"""

    def __init__(
        self,
        settings: JobsSettings,
        stream: str,
        bridge: JavaBridge,
        *,
        label: str,
    ) -> None:
        self.settings = settings
        self.stream = stream
        self.bridge = bridge
        self.label = label
        self.consumer = _consumer_name(settings)
        self._task: Optional[asyncio.Task] = None
        self._stop = asyncio.Event()
        self._redis: Any = None
        self._pool: Any = None

    # ------------------------------------------------------------------ 生命周期

    async def run(self) -> None:
        """启动读循环与管理协程（作为一个整体任务被 lifespan 持有）。"""
        self._stop.clear()
        await asyncio.gather(
            self._read_loop(),
            self._manage_loop(),
            return_exceptions=True,
        )

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        await self._close()

    # ------------------------------------------------------------------ 连接

    async def _ensure_client(self) -> None:
        if self._redis is not None:
            return
        from redis.asyncio import Redis
        from redis.asyncio.connection import BlockingConnectionPool

        # 每条 stream 独立 pool：1 个阻塞读 + 1 个管理命令
        self._pool = BlockingConnectionPool.from_url(
            self.settings.redis_url,
            max_connections=2,
            decode_responses=True,
            timeout=5,
        )
        self._redis = Redis(connection_pool=self._pool)

    async def _close(self) -> None:
        if self._redis is not None:
            try:
                await self._redis.aclose()
            except Exception:  # noqa: BLE001
                pass
            self._redis = None
        if self._pool is not None:
            try:
                await self._pool.disconnect()
            except Exception:  # noqa: BLE001
                pass
            self._pool = None

    async def _ensure_group(self) -> None:
        from redis.exceptions import ResponseError

        await self._ensure_client()
        try:
            await self._redis.xgroup_create(self.stream, self.settings.group, id="0", mkstream=True)
            logger.info("[Worker:%s] 已创建消费者组 %s @ %s", self.label, self.settings.group, self.stream)
        except ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    # ------------------------------------------------------------------ 读循环

    async def _read_loop(self) -> None:
        while not self._stop.is_set():
            try:
                await self._ensure_group()
                break
            except Exception as exc:  # noqa: BLE001
                logger.warning("[Worker:%s] Redis 暂不可用，5s 后重试：%s", self.label, exc)
                await asyncio.sleep(5)

        while not self._stop.is_set():
            try:
                resp = await self._redis.xreadgroup(
                    self.settings.group,
                    self.consumer,
                    {self.stream: ">"},
                    count=1,
                    block=self.settings.block_ms,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                logger.warning("[Worker:%s] XREADGROUP 失败：%s", self.label, exc)
                await asyncio.sleep(3)
                continue
            if not resp:
                continue
            for _stream, messages in resp:
                for message_id, fields in messages:
                    await self._process(message_id, fields)

    async def _process(self, message_id: str, fields: Dict[str, str]) -> None:
        job_id = 0
        job_type = fields.get("type", "")
        try:
            job_id = int(fields.get("jobId", "0"))
            payload = json.loads(fields.get("payload") or "{}")
            handler = get_handler(job_type)
            if handler is None:
                raise RuntimeError(f"未知任务类型：{job_type}")

            async def report(percent: int) -> None:
                try:
                    await self.bridge.job_progress(job_id, percent)
                except JavaBridgeError as exc:
                    logger.warning("[Worker:%s] 进度回调失败 job=%s：%s", self.label, job_id, exc)

            logger.info("[Worker:%s] 开始任务 job=%s type=%s", self.label, job_id, job_type)
            result = await handler(job_type, payload, job_id, self.bridge, report)
            try:
                updated = await self.bridge.job_complete(job_id, result)
                logger.info("[Worker:%s] 任务完成 job=%s（Java 已接收）", self.label, job_id)
            except JavaBridgeError as exc:
                # 终态守卫可能导致 Java 返回业务错误；结果按约定丢弃
                logger.warning("[Worker:%s] 完成回调失败 job=%s：%s", self.label, job_id, exc)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.exception("[Worker:%s] 任务失败 job=%s type=%s", self.label, job_id, job_type)
            if job_id:
                try:
                    await self.bridge.job_fail(job_id, f"{type(exc).__name__}: {exc}")
                except JavaBridgeError as bridge_exc:
                    logger.warning("[Worker:%s] 失败回调失败 job=%s：%s", self.label, job_id, bridge_exc)
        finally:
            # 无论成功失败都 ACK：失败不无限重投（需要重跑请重新提交任务）
            try:
                await self._redis.xack(self.stream, self.settings.group, message_id)
            except Exception as exc:  # noqa: BLE001
                logger.warning("[Worker:%s] XACK 失败 id=%s：%s", self.label, message_id, exc)

    # ------------------------------------------------------------------ 管理协程

    async def _manage_loop(self) -> None:
        failures = 0
        while not self._stop.is_set():
            delay_ms = self.settings.trim_interval_ms
            try:
                await self._claim_pending()
                await self._trim_stream()
                failures = 0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                failures += 1
                logger.warning(
                    "[Worker:%s] 管理协程失败（第 %d 次）：%s", self.label, failures, exc
                )
                if failures >= 3:
                    delay_ms = min(600_000, self.settings.trim_interval_ms * (2 ** (failures - 2)))
            await asyncio.sleep(max(1, delay_ms) / 1000.0)

    async def _claim_pending(self) -> None:
        """回收超时未 ACK 的消息；XAUTOCLAIM 不可用时回退 XPENDING + XCLAIM。"""
        try:
            claimed = await self._claim_with_xautoclaim()
        except (AttributeError, TypeError):
            claimed = await self._claim_with_xpending()
        if claimed:
            logger.info("[Worker:%s] 回收 %d 条超时未 ACK 消息", self.label, claimed)

    async def _claim_with_xautoclaim(self) -> int:
        from redis.exceptions import ResponseError

        total = 0
        start: Any = "0-0"
        while total < _MAX_CLAIM_PER_ROUND and not self._stop.is_set():
            try:
                next_id, messages, _deleted = await self._redis.xautoclaim(
                    self.stream,
                    self.settings.group,
                    self.consumer,
                    min_idle_time=self.settings.claim_idle_ms,
                    start_id=start,
                    count=_CLAIM_BATCH,
                )
            except ResponseError:
                raise
            for message_id, fields in messages or []:
                await self._process(message_id, fields)
                total += 1
            start = next_id
            if str(next_id) == "0-0" or not messages:
                break
        return total

    async def _claim_with_xpending(self) -> int:
        total = 0
        pending = await self._redis.xpending_range(
            self.stream,
            self.settings.group,
            min="-",
            max="+",
            count=_MAX_CLAIM_PER_ROUND,
            idle=self.settings.claim_idle_ms,
        )
        ids: List[str] = [str(item["message_id"]) for item in pending]
        if not ids:
            return 0
        messages = await self._redis.xclaim(
            self.stream,
            self.settings.group,
            self.consumer,
            self.settings.claim_idle_ms,
            ids,
        )
        for message_id, fields in messages or []:
            await self._process(message_id, fields)
            total += 1
        return total

    async def _trim_stream(self) -> None:
        """XTRIM MINID：只裁已 ACK 区间，不碰未 ACK 消息。"""
        from redis.exceptions import ResponseError

        try:
            info = await self._redis.xinfo_stream(self.stream)
        except ResponseError:
            return
        last_id = info.get("last-generated-id")
        pending = await self._redis.xpending_range(
            self.stream, self.settings.group, min="-", max="+", count=1
        )
        safe_id = str(pending[0]["message_id"]) if pending else last_id
        if not safe_id:
            return
        try:
            await self._redis.xtrim(self.stream, minid=safe_id)
        except ResponseError:
            # 裁剪与并发写入竞争时可能失败，下轮再试即可
            return


async def start_workers(settings: JobsSettings, bridge: JavaBridge) -> List[asyncio.Task]:
    """按开关启动业务 / 评估 / Wiki 三个 worker（各自独立 Redis 实例）。"""
    tasks: List[asyncio.Task] = []
    if not (settings.worker_enabled or settings.eval_worker_enabled
            or settings.wiki_worker_enabled):
        logger.info("[Worker] 未启用任何异步任务消费者"
                    "（JOB_WORKER_ENABLED / EVAL_WORKER_ENABLED / WIKI_WORKER_ENABLED）")
        return tasks

    if settings.worker_enabled:
        worker = StreamWorker(settings, settings.stream, bridge, label="jobs")
        worker._task = asyncio.create_task(worker.run(), name="jobs-worker")
        tasks.append(worker._task)
        logger.info("[Worker] 业务任务消费者已启动：%s", settings.stream)

    if settings.eval_worker_enabled:
        worker = StreamWorker(settings, settings.eval_stream, bridge, label="eval")
        worker._task = asyncio.create_task(worker.run(), name="eval-worker")
        tasks.append(worker._task)
        logger.info("[Worker] 评估任务消费者已启动：%s", settings.eval_stream)

    if settings.wiki_worker_enabled:
        # 蒸馏是分钟级 LLM 长任务：独立 stream + 独立 Redis 实例/连接池，
        # 阻塞读不会和业务 worker 抢连接。
        worker = StreamWorker(settings, settings.wiki_stream, bridge, label="wiki")
        worker._task = asyncio.create_task(worker.run(), name="wiki-worker")
        tasks.append(worker._task)
        logger.info("[Worker] Wiki 蒸馏消费者已启动：%s", settings.wiki_stream)

    return tasks
