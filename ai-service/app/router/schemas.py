"""Router 的对外数据结构。

**为什么单独放一层**：Router 的判定结果要同时给三个地方用——
① 路由分派（选 skill）、② 接口返回（答辩时展示判定过程）、③ 日志与回归测试。
把结构定死在这里，避免每处各写一份 dict。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional


class SkillName(str, Enum):
    """已注册的 skill 名。

    取值即协议字段，**不要改名**（Java 侧与前端按字符串匹配）。
    """

    COURSE_QA = "COURSE_QA"                # 课程知识问答（题库 + 知识点）
    COURSEWARE_QA = "COURSEWARE_QA"        # 课件讲解（634 份技术资料）
    EXERCISE_EXPLAIN = "EXERCISE_EXPLAIN"  # 题目讲解（定位原题）
    RESOURCE_RECOMMEND = "RESOURCE_RECOMMEND"  # 资料推荐
    STUDY_PLAN = "STUDY_PLAN"              # 学习计划
    WRONG_BOOK_REVIEW = "WRONG_BOOK_REVIEW"  # 错题分析
    GRADING = "GRADING"                    # 主观题判分
    SMALLTALK = "SMALLTALK"                # 寒暄（模板直出，不调大模型）
    REFUSE = "REFUSE"                      # 域外拒答
    CLARIFY = "CLARIFY"                    # 意图歧义，反问


#: 事实类 skill：必须通过"检索有命中"这道闸门，否则拒答。
#: 依据：`docs/Router与Skill架构设计.md` B.3 第 ③ 条。
FACT_SKILLS = frozenset({
    SkillName.COURSE_QA,
    SkillName.COURSEWARE_QA,
    SkillName.EXERCISE_EXPLAIN,
})


@dataclass(frozen=True)
class RouterDecision:
    """一次路由判定的完整结果（不可变，便于日志与测试断言）。"""

    skill: SkillName
    sim: float
    margin: float
    #: Top3 候选，答辩时展示"为什么选它"
    candidates: List[dict] = field(default_factory=list)
    #: 判定原因，直接写进接口响应：SIM_LOW / MARGIN_LOW / ROUTED / FALLBACK
    reason: str = ""

    @property
    def is_terminal(self) -> bool:
        """是否已终结（不需要再检索/生成）。"""
        return self.skill in (SkillName.REFUSE, SkillName.CLARIFY)

    def to_dict(self) -> dict:
        return {
            "skill": self.skill.value,
            "sim": round(self.sim, 4),
            "margin": round(self.margin, 4),
            "reason": self.reason,
            "candidates": self.candidates,
        }
