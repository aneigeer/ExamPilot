"""生成 Agent 侧合成任务集（模板实例化，不依赖检索结果）。

任务类型固定四类：
1. 薄弱点出题： get_weak_knowledge → search_knowledge
2. 知识点讲解： search_knowledge
3. 今日计划：   get_study_plan
4. 错题复盘：   get_wrong_book

标答是「期望工具序列 + 必需 token + 禁止工具」，由规则判定，不依赖大模型打分。
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, List

EVAL_DIR = Path(__file__).resolve().parent
OUT = EVAL_DIR / "agent_tasks.jsonl"
EVAL_USER_ID = int(os.getenv("EVAL_USER_ID", "3"))


def load_knowledge_points() -> List[Dict[str, Any]]:
    import pymysql  # type: ignore[import-not-found]

    conn = pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", "123456"),
        database=os.getenv("DB_NAME", "exam_prep"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, name, subject_id FROM knowledge_point ORDER BY id")
            return list(cur.fetchall())
    finally:
        conn.close()


def build(kps: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    tasks: List[Dict[str, Any]] = []
    for kp in kps:
        kid = int(kp["id"])
        name = str(kp["name"])
        subject = kp.get("subject_id")
        profile = {"weakKnowledgeIds": [kid], "subjectId": subject, "userId": EVAL_USER_ID}
        tasks.append(
            {
                "id": f"weak-{kid}",
                "goal": f"针对我薄弱的「{name}」知识点，帮我找一些练习",
                "profile": profile,
                "expected_tools": ["get_weak_knowledge", "search_knowledge"],
                "required_answer_tokens": [],
                "forbidden_tools": ["get_study_plan"],
                "max_steps": 4,
            }
        )
        tasks.append(
            {
                "id": f"explain-{kid}",
                "goal": f"帮我讲解一下「{name}」这个知识点",
                "profile": profile,
                "expected_tools": ["search_knowledge"],
                "required_answer_tokens": [name],
                "forbidden_tools": ["get_wrong_book", "get_study_plan"],
                "max_steps": 3,
            }
        )
        tasks.append(
            {
                "id": f"plan-{kid}",
                "goal": "看看我今天的备考计划安排",
                "profile": profile,
                "expected_tools": ["get_study_plan"],
                "required_answer_tokens": ["计划"],
                "forbidden_tools": ["search_knowledge"],
                "max_steps": 3,
            }
        )
        tasks.append(
            {
                "id": f"wrong-{kid}",
                "goal": "帮我复盘一下最近的错题",
                "profile": profile,
                "expected_tools": ["get_wrong_book"],
                "required_answer_tokens": ["错题"],
                "forbidden_tools": ["get_study_plan"],
                "max_steps": 3,
            }
        )
    return tasks


def main() -> None:
    kps = load_knowledge_points()
    tasks = build(kps)
    with OUT.open("w", encoding="utf-8") as fp:
        for task in tasks:
            fp.write(json.dumps(task, ensure_ascii=False) + "\n")
    print(f"已生成 {len(tasks)} 条 Agent 任务 → {OUT}")


if __name__ == "__main__":
    main()
