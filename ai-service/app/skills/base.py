"""Skill 的统一定义。

一个 skill = **检索范围** + **提示词骨架** + **是否需要调用工具**。

设计原则（对应 `docs/Router与Skill架构设计.md` C 节）：
* 7B 只负责"把给定材料写成答案"，**不做决策**——检索范围和边界都在这里用代码定死
* 每个 skill 必须声明 `source_types`，**避免课件把课程题挤出召回**
  （实测：不过滤时「进程 线程」top-5 全是课件）
* 事实类 skill 必须带引用：提示词里强制 `[n]`，代码侧校验，资料不足就说不知道
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Sequence

from ..router.schemas import SkillName


@dataclass(frozen=True)
class Skill:
    """skill 定义（不可变）。

    Attributes:
        name: skill 标识，与 Router 输出一致。
        source_types: 检索时限定 `ai_chunk.source_type`；空表示不检索。
        needs_retrieval: 是否需要先检索。`True` 且无命中 → 拒答（事实类闸门）。
        needs_llm: 是否需要大模型。`False` 表示模板直出（如寒暄），延迟 < 50ms。
        system_prompt: 系统提示词（只含角色与硬约束）。
        answer_hint: 追加到用户消息末尾的输出要求（比塞进 system 更稳，7B 更听话）。
        template_reply: `needs_llm=False` 时的固定回复。
        implemented: 是否已实现。`False` 时降级到 COURSE_QA 检索路径，
                     并在响应里 **如实标注** `fallback=True`，不假装完成。
        max_contexts: 送入提示词的块数上限（7B 上下文小，宁少勿多）。
        max_context_chars: 单个块截断长度，控制总 prompt 预算（约 4×块数 KB）。
    """

    name: SkillName
    source_types: Sequence[str] = field(default_factory=tuple)
    needs_retrieval: bool = True
    needs_llm: bool = True
    system_prompt: str = ""
    answer_hint: str = ""
    template_reply: Optional[str] = None
    implemented: bool = True
    max_contexts: int = 5
    max_context_chars: int = 700


# ---------------------------------------------------------------- 公共提示词
#: 所有走大模型的 skill 共用这套硬约束。改这里等于改所有 skill，谨慎。
BASE_CONSTRAINTS = (
    "你是一名高校备考助教。必须遵守以下硬约束：\n"
    "1. 只依据给定的【资料】回答，不使用资料之外的任何知识；\n"
    "2. 每句结论后用 [n] 标注所依据资料的编号，编号必须来自【资料】；\n"
    "3. 资料不足以回答时，直接说「根据现有资料无法回答」，不要推测、不要编造；\n"
    "4. 不输出与问题无关的寒暄，不使用 Markdown 标题，答案控制在 300 字以内；\n"
    "5. 使用简体中文，术语保持原样。"
)


def build_messages(skill: Skill, question: str, contexts: List[str]) -> tuple[str, str]:
    """拼 (system, user)。

    `contexts` 已按 `[1]...[n]` 顺序排好，调用方负责编号与来源一一对应。
    """
    blocks = "\n\n".join(
        f"【资料{i}】{text}" for i, text in enumerate(contexts, start=1)
    ) or "（无资料）"
    user = f"【资料】\n{blocks}\n\n【问题】{question}"
    if skill.answer_hint:
        user += f"\n\n【输出要求】{skill.answer_hint}"
    return skill.system_prompt or BASE_CONSTRAINTS, user
