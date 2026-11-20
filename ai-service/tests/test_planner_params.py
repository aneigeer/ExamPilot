"""planner 参数链路的回归测试（对应 2026-11-13 五十条评测的 5 次 ``ok=false``）。

覆盖两类缺陷，都不改工具语义与返回结构：

1. **解析层不丢参数**（``planner._extract_arguments``）：
   小模型把参数写在 ``args`` / 字符串化 / 平铺顶层时，旧实现一律退化成 ``{}``，
   在下游表现为「空参 PARAM_ERROR」，但真实原因是解析层丢了字段。
2. **参数错误可回喂**（``tools._argument_hint``）：
   失败信息除抽象约束外必须给出「必填参数 + 一次合法调用写法」，
   否则模型下一轮只能瞎猜（评测里出现过 ``questionId=123456`` 这类编造）。
"""

from __future__ import annotations

import asyncio
from typing import Any, Dict, List, Optional

import pytest

from app.agent.planner import parse_decision
from app.agent.tools import TOOL_SPECS, AgentContext, ToolRegistry, _validate_arguments
from app.agent.tools import ArgumentError


def test_arguments_under_alias_key_are_kept() -> None:
    """模型把 ``arguments`` 写成 ``args``：参数不能用空。"""
    raw = '{"thought":"t","action":"tool","tool":"get_question","args":{"questionId":2}}'
    decision = parse_decision(raw)
    assert decision.tool == "get_question"
    assert decision.arguments == {"questionId": 2}


def test_stringified_arguments_are_decoded() -> None:
    """模型把参数序列化成字符串：必须解码，而不是静默丢成 ``{}``。"""
    raw = (
        '{"thought":"t","action":"tool","tool":"search_knowledge",'
        '"arguments":"{\\"query\\": \\"控制错觉\\", \\"topK\\": 5}"}'
    )
    decision = parse_decision(raw)
    assert decision.arguments == {"query": "控制错觉", "topK": 5}


def test_flattened_arguments_are_salvaged_without_leaking_control_keys() -> None:
    """参数平铺在决策对象顶层：只回收 schema 声明过的键，不混入 thought/action。"""
    raw = (
        '{"thought":"t","action":"tool","tool":"get_question",'
        '"questionId":12,"topK":99}'
    )
    decision = parse_decision(raw)
    # topK 不是 get_question 声明的参数，不得被当成调用参数
    assert decision.arguments == {"questionId": 12}


def test_empty_arguments_stay_empty_so_validation_can_report_it() -> None:
    """模型确实没给参数时保持 ``{}``——由校验层显式报错，而不是在这里猜默认值。"""
    decision = parse_decision('{"thought":"t","action":"tool","tool":"get_wiki_page"}')
    assert decision.arguments == {}


def test_param_error_message_carries_required_args_and_example() -> None:
    """参数错误回喂：仍以「参数错误」开头（T02 契约），并附必填项与正确写法。"""
    with pytest.raises(ArgumentError) as excinfo:
        _validate_arguments("get_wiki_page", {"query": "自然语言处理与LLM面试题", "topK": 3})
    message = str(excinfo.value)
    assert "pageId" in message
    assert "必填参数：pageId" in message
    assert '正确写法：{"pageId"' in message


def test_tool_specs_declare_required_and_example_for_id_tools() -> None:
    """schema 自身要说清必填与键名，否则模型只能按直觉猜（pageId vs query）。"""
    specs: Dict[str, Dict[str, Any]] = {str(spec["name"]): spec for spec in TOOL_SPECS}
    assert specs["get_wiki_page"]["required"] == ["pageId"]
    assert specs["get_question"]["required"] == ["questionId"]
    assert specs["search_knowledge"]["required"] == ["query"]
    # 每个必填参数都必须在 arguments 里有对应说明，且说明里带「必填」
    for name, spec in specs.items():
        for key in spec.get("required") or []:
            assert key in spec["arguments"], f"{name} 缺少 {key} 的参数说明"
            assert "必填" in str(spec["arguments"][key]), f"{name}.{key} 未标注必填"
    # 工具无必填参数时不编造 required
    assert "required" not in specs["get_study_plan"]


class _RecordingBridge:
    """只记录调用的 Java 桩：用来证明非法参数**不会**打到 Java。"""

    def __init__(self) -> None:
        self.calls: List[Dict[str, Any]] = []

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        self.calls.append({"method": "GET", "path": path, "params": dict(params or {})})
        return {}

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        self.calls.append({"method": "POST", "path": path, "payload": dict(payload)})
        return {}


def test_empty_arguments_fail_fast_without_touching_java() -> None:
    """空参 → ``ok=False``、summary 以「参数错误」开头、且一次 Java 都不访问。"""
    bridge = _RecordingBridge()
    ctx = AgentContext(question="测试", user_id=3)
    result = asyncio.run(ToolRegistry(bridge).execute("get_question", {}, ctx))  # type: ignore[arg-type]
    assert result.ok is False
    assert str(result.summary).startswith("参数错误")
    assert "questionId" in str(result.summary)
    assert bridge.calls == []
