"""阶段四波次一 —— ``maxToolCalls`` 配置级预算约束（阶段二 A8 收尾）。

契约 ``docs/Agent阶段三接口契约.md`` §3 A8：``maxToolCalls`` **不开放请求覆盖**，
只有配置项 ``AGENT_MAX_TOOL_CALLS``（默认 4，上限 8）。

越界处理是 **启动时 clamp + WARNING**，不是返回 400：
这是运维把环境变量写错的场景，让线上请求失败只会让用户看到"AI 不可用"，
而真正的错因（配置）反而被埋在请求日志里。

覆盖：
* ``9``（超上限）→ 8
* ``0``（低于下限）→ 1
* ``4``（正常）→ 原样不变，且**不打** WARNING（否则告警会被日常噪音淹没）
* 生效值出现在启动日志里（``log_security_posture``）
* ``AgentRequest`` 上**没有** ``max_tool_calls`` 字段（防有人偷偷加回来）
"""

from __future__ import annotations

import logging

import pytest

from app.config import get_settings, reset_settings
from app.schemas import AgentRequest


@pytest.fixture()
def budget_env(monkeypatch: pytest.MonkeyPatch):
    """设置 ``AGENT_MAX_TOOL_CALLS`` 并重建配置单例。"""

    def _apply(value: str) -> None:
        monkeypatch.setenv("AGENT_MAX_TOOL_CALLS", value)
        reset_settings()

    yield _apply
    reset_settings()


@pytest.mark.parametrize(
    ("configured", "expected"),
    [("9", 8), ("99", 8), ("0", 1), ("-3", 1), ("4", 4), ("1", 1), ("8", 8)],
)
def test_tool_calls_is_clamped(budget_env, configured: str, expected: int) -> None:
    """越界配置被 clamp 到 [1, 8]，范围内的值原样保留。"""
    budget_env(configured)
    settings = get_settings()
    assert settings.agent.max_tool_calls == expected, (
        f"AGENT_MAX_TOOL_CALLS={configured} 应生效为 {expected}，"
        f"实际 {settings.agent.max_tool_calls}"
    )


@pytest.mark.parametrize(("configured", "expected"), [("9", 8), ("0", 1)])
def test_clamp_logs_warning(budget_env, caplog: pytest.LogCaptureFixture,
                            configured: str, expected: int) -> None:
    """越界必须留 WARNING（含配置值与收敛值），否则"预算被改小"无人知晓。"""
    budget_env(configured)
    with caplog.at_level(logging.WARNING):
        get_settings()

    messages = [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING]
    joined = " ".join(messages)
    assert "AGENT_MAX_TOOL_CALLS" in joined, messages
    assert "clamp" in joined, messages
    assert str(expected) in joined, f"WARNING 未给出收敛后的值：{joined}"


def test_in_range_config_does_not_warn(budget_env, caplog: pytest.LogCaptureFixture) -> None:
    """范围内的配置**不得**产生预算类 WARNING：告警一旦日常化就没人看了。"""
    budget_env("4")
    with caplog.at_level(logging.WARNING):
        settings = get_settings()

    assert settings.agent.max_tool_calls == 4
    # 只筛"预算"口径的告警（启动时的"未配置令牌"是本机环境噪音，与预算无关）
    budget_warnings = [r.getMessage() for r in caplog.records
                       if "AGENT_MAX_TOOL_CALLS" in r.getMessage()]
    assert not budget_warnings, budget_warnings


def test_effective_budget_is_logged(budget_env, caplog: pytest.LogCaptureFixture) -> None:
    """启动日志要打**实际生效值**（clamp 之后），便于事后核对线上到底是多少。"""
    budget_env("99")
    with caplog.at_level(logging.INFO):
        settings = get_settings()

    messages = " ".join(r.getMessage() for r in caplog.records)
    assert "max_tool_calls=8" in messages, messages
    assert settings.agent.max_tool_calls == 8


def test_request_model_has_no_tool_calls_field() -> None:
    """契约红线：``maxToolCalls`` 不开放请求覆盖，schema 里不能有这个字段。"""
    fields = set(AgentRequest.model_fields.keys())
    assert "max_tool_calls" not in fields, f"AgentRequest 出现了非法字段：{fields}"
    assert "max_steps" in fields, "maxSteps 仍必须是可覆盖的请求字段"


def test_zero_max_steps_still_rejected_while_tool_calls_clamped(budget_env) -> None:
    """两者语义不同：``maxSteps=0`` 是**请求参数错误**（400），配置越界只是 clamp。"""
    budget_env("99")
    assert get_settings().agent.max_tool_calls == 8
    with pytest.raises(ValueError):
        AgentRequest(question="我的计划", maxSteps=0)
