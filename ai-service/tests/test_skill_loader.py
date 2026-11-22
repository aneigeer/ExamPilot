"""声明式 SKILL.md 加载：索引、按需正文、缺失回退。"""

from __future__ import annotations

from pathlib import Path

from app.skills.base import Skill
from app.skills.loader import SkillLoader, parse_skill_md
from app.router.schemas import SkillName


def test_parse_frontmatter():
    doc = parse_skill_md(
        "---\nname: DEMO\ndescription: 演示\nallowed_tools: [a, b]\n"
        "source_types: [QUESTION]\n---\n正文要求\n第二行\n",
        "fallback",
    )
    assert doc is not None
    assert doc.name == "DEMO"
    assert doc.allowed_tools == ["a", "b"]
    assert doc.source_types == ["QUESTION"]
    assert "正文要求" in doc.body


def test_catalog_reads_real_skills():
    catalog = SkillLoader().catalog()
    names = {item["name"] for item in catalog}
    assert {"COURSE_QA", "WRONG_BOOK_REVIEW", "STUDY_PLAN"} <= names


def test_decorate_merges_body(tmp_path: Path):
    d = tmp_path / "demo"
    d.mkdir()
    (d / "SKILL.md").write_text(
        "---\nname: COURSE_QA\ndescription: x\n---\n技能正文约束\n", encoding="utf-8")
    loader = SkillLoader(tmp_path)
    skill = Skill(name=SkillName.COURSE_QA, answer_hint="原始要求")
    merged = loader.decorate(skill)
    assert "原始要求" in merged.answer_hint
    assert "技能正文约束" in merged.answer_hint


def test_missing_dir_falls_back(tmp_path: Path):
    loader = SkillLoader(tmp_path / "not-exists")
    skill = Skill(name=SkillName.COURSE_QA, answer_hint="原样")
    assert loader.decorate(skill) == skill
