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
    # 课程知识 = 题库 + 知识点 + **教材/课件**（课件就是教材，课程问题当然该查它）
    retrieval_groups=(("QUESTION", "KNOWLEDGE"), ("COURSEWARE",)),
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
    retrieval_groups=(("COURSEWARE",),),
)

#: 题目讲解：只查题目块
EXERCISE_EXPLAIN = Skill(
    name=SkillName.EXERCISE_EXPLAIN,
    source_types=("QUESTION",),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="先点明正确答案，再逐步说明为什么其他选项不对。",
    retrieval_groups=(("QUESTION",),),
)

#: 资料推荐：资料 + 课件都能推
RESOURCE_RECOMMEND = Skill(
    name=SkillName.RESOURCE_RECOMMEND,
    source_types=("RESOURCE", "COURSEWARE"),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint="列出 2-3 条推荐，每条一句话说明适合什么阶段看。",
    retrieval_groups=(("RESOURCE",), ("COURSEWARE",)),
)

#: 学习计划（v4 实装）：基于课件章节 + 知识点排计划。
#: 不依赖 Java 学情接口——ask 通道没有用户身份，计划基于"科目内容有什么"，
#: 用户个人进度由前端在提问时拼进 question 文本。
STUDY_PLAN = Skill(
    name=SkillName.STUDY_PLAN,
    source_types=("COURSEWARE", "KNOWLEDGE"),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint=(
        "输出一份可执行的学习计划：按阶段/天数列出要看的章节与要做的练习，"
        "每项用 [n] 标注依据的资料；资料未覆盖的部分明确写「资料未涉及，需自行补充」，"
        "不要编造章节名。"
    ),
    max_contexts=5,
    # 分组召回：课件给章节骨架，知识点给考点粒度，两类都要有配额
    retrieval_groups=(("COURSEWARE",), ("KNOWLEDGE",)),
)

#: 错题分析（v4 实装）：ask 通道没有用户的错题数据，只能基于题库做**通用**归纳。
#: 提示词强制声明这一点，防止 7B 虚构"你的错题记录"。
WRONG_BOOK_REVIEW = Skill(
    name=SkillName.WRONG_BOOK_REVIEW,
    source_types=("QUESTION", "KNOWLEDGE"),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint=(
        "基于给定题目归纳易错点与复习建议，逐条注明来源 [n]；"
        "开头先说明这是基于题库的通用归纳（本通道没有接入你的个人错题数据），"
        "不要虚构用户的成绩或错题记录。"
    ),
    max_contexts=5,
    retrieval_groups=(("QUESTION",), ("KNOWLEDGE",)),
)

#: 主观题判分（v4 实装）：
#: * 有大模型 → 按「总评 → 扣分点 → 修改建议」结构化输出；
#: * **无大模型 Key → 走抽取式评估**（``routers/ask.py::_extractive_grading``）：
#:   输出作答与题库参考答案的字符重合度参考，并明确注明"非模型判分，需人工复核"。
GRADING = Skill(
    name=SkillName.GRADING,
    source_types=("QUESTION",),
    needs_retrieval=True,
    needs_llm=True,
    system_prompt=BASE_CONSTRAINTS,
    answer_hint=(
        "按「总评（优/良/中/差）→ 扣分点 → 修改建议」输出，每个扣分点注明依据 [n]；"
        "资料中找不到该题参考答案时，明确说明只能给出要点重合参考，不能判分。"
    ),
    max_contexts=5,
    retrieval_groups=(("QUESTION",),),
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
