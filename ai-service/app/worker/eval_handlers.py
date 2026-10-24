"""评估任务的 Redis Stream 处理器。"""

from __future__ import annotations

import logging
from typing import Any, Dict

from eval.agent_eval import run as run_agent_eval
from eval.retrieval_eval import run as run_retrieval_eval

from .handlers import ProgressReporter, register

logger = logging.getLogger(__name__)


@register("EVAL_RETRIEVAL")
async def handle_retrieval(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """检索侧评估（Golden Set → Recall@K / MRR）。"""
    logger.info("[Eval] 开始检索侧评估 job=%s payload=%s", job_id, payload)
    await progress(1)
    result = await run_retrieval_eval(payload.get("limit"), progress)
    await progress(100)
    return result


@register("EVAL_AGENT")
async def handle_agent(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """Agent 侧评估（合成任务集 → 规则判定）。"""
    logger.info("[Eval] 开始 Agent 侧评估 job=%s payload=%s", job_id, payload)
    await progress(1)
    result = await run_agent_eval(payload.get("limit"), progress)
    await progress(100)
    return result
