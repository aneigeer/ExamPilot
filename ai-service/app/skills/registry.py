"""Skill 注册表：`SkillName → Skill`。

**新增一个 skill 只需要改这个文件 + 在 `router/prototypes.py` 加 5-8 条原型句，
不需要动 `routers/ask.py`** —— 这是 Router+Skill 架构的核心收益（设计文档 T-R2 验收项）。

`implemented=False` 的 skill 表示"已能路由、但业务逻辑尚未实现"：
ask 接口会降级到通用课程检索，并在响应里 `fallback=true` 如实标注，
**绝不假装已实现**。
"""

from __future__ import annotations

from typing import Dict

from ..router.schemas import SkillName
from .base import BASE_CONSTRAINTS, Skill

#: 课程问答：题库 + 知识点。最常用路径，作为其他未实现 skill 的降级目标。
COURSE_QA = Skill(
    name=SkillName.COURSE_QA,
    source_types=("QUESTION", "KNOWLEDGE"),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="先给结论，再解释原因；涉及代码时给出关键语句。",
    max_contexts=5,
)

#: 课件讲解：只查 COURSEWARE，**与课程题彻底隔离**
#: （实测不加隔离时「进程 线程」top-5 全是课件，课程题被完全挤掉）
COURSEWARE_QA = Skill(
    name=SkillName.COURSEWARE_QA,
    source_types=("COURSEWARE",),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="按「是什么 → 怎么做 → 注意什么」组织；面试类问题补一句考察点。",
    max_contexts=5,
    max_context_chars=800,
)

#: 题目讲解：只查题目块
EXERCISE_EXPLAIN = Skill(
    name=SkillName.EXERCISE_EXPLAIN,
    source_types=("QUESTION",),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="先点明正确答案，再逐步说明为什么其他选项不对。",
)

#: 资料推荐：资料 + 课件都能推
RESOURCE_RECOMMEND = Skill(
    name=SkillName.RESOURCE_RECOMMEND,
    source_types=("RESOURCE", "COURSEWARE"),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="列出 2-3 条推荐，每条一句话说明适合什么阶段看。",
)

#: 学习计划：依赖学情数据（错题/画像），需要调 Java 内部接口 —— 尚未实现
STUDY_PLAN = Skill(
    name=SkillName.STUDY_PLAN,
    source_types=(),
    needs_retrieval=False,
    needs_llm=False,
    implemented=False,
)

#: 错题分析：多步工具编排（AgentHarness），尚未接入 Router
WRONG_BOOK_REVIEW = Skill(
    name=SkillName.WRONG_BOOK_REVIEW,
    source_types=(),
    needs_retrieval=False,
    needs_llm=False,
    implemented=False,
)

#: 主观题判分：结构化 JSON 输出，尚未接入
GRADING = Skill(
    name=SkillName.GRADING,
    source_types=(),
    needs_retrieval=False,
    needs_llm=False,
    implemented=False,
)

#: 寒暄：模板直出，**不调大模型**（实测 < 50ms），避免"你好"也等 5 秒
SMALLTALK = Skill(
    name=SkillName.SMALLTALK,
    source_types=(),
    needs_retrieval=False,
    needs_llm=False,
    template_reply=(
        "你好，我是备考助教。可以问我：\n"
        "· 课程知识点（如「进程和线程的区别」）\n"
        "· 课件内容（如「17 种 RAG 优化策略」）\n"
        "· 题目讲解、资料推荐、复习计划"
    ),
)

SKILLS: Dict[SkillName, Skill] = {
    skill.name: skill
    for skill in (
        COURSE_QA,
        COURSEWARE_QA,
        EXERCISE_EXPLAIN,
        RESOURCE_RECOMMEND,
        STUDY_PLAN,
        WRONG_BOOK_REVIEW,
        GRADING,
        SMALLTALK,
    )
}


def get_skill(name: SkillName) -> Skill:
    """取 skill 定义；未注册视为编码错误（新增 skill 必须同时进注册表）。"""
    try:
        return SKILLS[name]
    except KeyError as exc:  # pragma: no cover - 防御性
        raise RuntimeError(f"skill 未注册：{name}") from exc
