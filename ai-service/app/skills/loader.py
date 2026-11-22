"""声明式 SKILL.md 渐进加载。

启动时只读 frontmatter 建目录索引（name + description），命中所选技能后再读正文，
避免把所有技能正文塞进规划提示词。

目录缺失 / frontmatter 非法时**无缝回退**到注册表里的硬编码定义，不抛异常。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

SKILLS_DIR = Path(__file__).resolve().parents[2] / "skills"
_FRONTMATTER = re.compile(r"^---\s*\n(.*?)\n---\s*\n(.*)$", re.S)


@dataclass(frozen=True)
class SkillDoc:
    name: str
    description: str
    allowed_tools: List[str]
    source_types: List[str]
    body: str


def _parse_list(raw: str) -> List[str]:
    value = raw.strip().strip("[]")
    if not value:
        return []
    return [item.strip().strip("'\"") for item in value.split(",") if item.strip()]


def parse_skill_md(text: str, fallback_name: str = "") -> Optional[SkillDoc]:
    match = _FRONTMATTER.match(text)
    if not match:
        return None
    meta: Dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip()
    name = meta.get("name") or fallback_name
    if not name:
        return None
    return SkillDoc(
        name=name,
        description=meta.get("description", ""),
        allowed_tools=_parse_list(meta.get("allowed_tools", "")),
        source_types=_parse_list(meta.get("source_types", "")),
        body=match.group(2).strip(),
    )


class SkillLoader:
    """目录索引 + 按需正文加载（带进程内缓存）。"""

    def __init__(self, directory: Optional[Path] = None) -> None:
        self.directory = directory or SKILLS_DIR
        self._index: Dict[str, SkillDoc] = {}
        self._loaded = False

    def index(self) -> Dict[str, SkillDoc]:
        if self._loaded:
            return self._index
        self._loaded = True
        if not self.directory.exists():
            return self._index
        for path in sorted(self.directory.glob("*/SKILL.md")):
            try:
                doc = parse_skill_md(path.read_text(encoding="utf-8"), path.parent.name)
                if doc:
                    self._index[doc.name] = doc
            except OSError as exc:  # pragma: no cover - 权限/编码异常
                logger.warning("[SkillLoader] 读取 %s 失败：%s", path, exc)
        return self._index

    def catalog(self) -> List[Dict[str, str]]:
        return [
            {"name": doc.name, "description": doc.description}
            for doc in self.index().values()
        ]

    def body(self, name: str) -> str:
        doc = self.index().get(name)
        return doc.body if doc else ""

    def decorate(self, skill):
        """命中所选技能时把正文并入 answer_hint；目录缺失时原样返回。"""
        doc = self.index().get(getattr(skill.name, "value", str(skill.name)))
        if doc is None or not doc.body:
            return skill
        merged = (skill.answer_hint + "\n\n" + doc.body).strip()
        return replace(skill, answer_hint=merged)


_LOADER = SkillLoader()


def loader() -> SkillLoader:
    return _LOADER
