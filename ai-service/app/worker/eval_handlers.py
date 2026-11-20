"""评估任务的 Redis Stream 处理器。"""

from __future__ import annotations

import logging
from typing import Any, Dict

from eval.agent_eval import run as run_agent_eval
from eval.grading_eval import evaluate as run_grading_eval
from eval.refusal_eval import evaluate as run_refusal_eval
from eval.retrieval_eval import run as run_retrieval_eval
from eval.study_plan_eval import evaluate as run_study_plan_eval

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


@register("EVAL_REFUSAL")
async def handle_refusal(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """拒答侧评估（域外拒答率 / 域内误拒率）。"""
    logger.info("[Eval] 开始拒答侧评估 job=%s payload=%s", job_id, payload)
    await progress(1)
    result = await run_refusal_eval(payload.get("limit"), progress)
    await progress(100)
    return result


@register("EVAL_GRADING")
async def handle_grading(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """判分侧评估（等级一致率 / 得分率 MAE）。"""
    logger.info("[Eval] 开始判分侧评估 job=%s payload=%s", job_id, payload)
    await progress(1)
    result = await run_grading_eval(payload.get("limit"), progress)
    await progress(100)
    return result


@register("EVAL_STUDY_PLAN")
async def handle_study_plan(
    job_type: str,
    payload: Dict[str, Any],
    job_id: int,
    bridge: Any,
    progress: ProgressReporter,
) -> Dict[str, Any]:
    """学习计划评估（JSON 合规 / 题号零幻觉 / 降级率）。"""
    logger.info("[Eval] 开始学习计划评估 job=%s payload=%s", job_id, payload)
    await progress(1)
    result = await run_study_plan_eval(payload.get("limit"), progress)
    await progress(100)
    return result
