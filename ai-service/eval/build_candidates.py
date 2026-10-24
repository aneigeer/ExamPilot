"""Golden Set 构建工具（**不参与决定标答**）。

标注流程（必须按顺序）：

1. ``python eval/build_candidates.py --export``
   从 MySQL 导出候选 chunk 到 ``eval/candidates.csv``，供人阅读正文。
2. 人工读正文 → 写学生口吻问题 → 在 ``eval/golden_set.csv`` 填 ``expected_chunk_ids``。
3. ``python eval/build_candidates.py --convert``
   只做格式校验与 CSV → JSONL 转换。

禁止：先跑检索、把 top1 标成正确答案——那会让评估变成自证。

依赖：``pip install -r requirements-eval.txt``（仅标注/导出需要 pymysql）。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List

EVAL_DIR = Path(__file__).resolve().parent
CANDIDATES_CSV = EVAL_DIR / "candidates.csv"
GOLDEN_CSV = EVAL_DIR / "golden_set.csv"
GOLDEN_JSONL = EVAL_DIR / "golden_set.jsonl"


def _connect():
    try:
        import pymysql  # type: ignore[import-not-found]
    except ImportError:
        print("缺少 pymysql：请执行  pip install -r requirements-eval.txt", file=sys.stderr)
        raise SystemExit(2)
    return pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", "123456"),
        database=os.getenv("DB_NAME", "exam_prep"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def export_candidates(limit: int) -> None:
    """导出候选 chunk（只导出，不排序、不筛选“正确答案”）。"""
    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, source_type, source_id, knowledge_id, subject_id,
                       title, content, content_hash
                FROM ai_chunk
                WHERE status = 'ACTIVE'
                ORDER BY id
                LIMIT %s
                """,
                (limit,),
            )
            rows: List[Dict[str, Any]] = list(cur.fetchall())
    finally:
        conn.close()

    with CANDIDATES_CSV.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(
            fp,
            fieldnames=["id", "source_type", "source_id", "knowledge_id",
                        "subject_id", "title", "content", "content_hash"],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    print(f"已导出 {len(rows)} 条候选 → {CANDIDATES_CSV}")


def convert_golden() -> None:
    """校验 golden_set.csv 并转换为 JSONL（不做任何检索）。"""
    if not GOLDEN_CSV.exists():
        print(f"缺少标注文件：{GOLDEN_CSV}", file=sys.stderr)
        raise SystemExit(2)

    conn = _connect()
    valid_ids = set()
    ids_by_hash: Dict[str, List[int]] = {}
    hash_by_id: Dict[int, str] = {}
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, content_hash FROM ai_chunk WHERE status = 'ACTIVE'")
            for row in cur.fetchall():
                chunk_id = int(row["id"])
                valid_ids.add(chunk_id)
                content_hash = row.get("content_hash")
                if content_hash:
                    hash_by_id[chunk_id] = str(content_hash)
                    ids_by_hash.setdefault(str(content_hash), []).append(chunk_id)
    finally:
        conn.close()

    def expand_duplicates(ids: List[int]) -> List[int]:
        """同一正文的重复块（不同年份重复题）视为同一标答。

        这不是「决定标答」——标答仍由人工填写；这里只把**内容完全相同**的副本
        一并纳入，避免检索命中副本却被判 MISS。
        """
        expanded = set(ids)
        for chunk_id in ids:
            content_hash = hash_by_id.get(chunk_id)
            if content_hash:
                expanded.update(ids_by_hash.get(content_hash, []))
        return sorted(expanded)

    rows: List[Dict[str, Any]] = []
    errors: List[str] = []
    with GOLDEN_CSV.open("r", encoding="utf-8-sig", newline="") as fp:
        reader = csv.DictReader(fp)
        for line_no, row in enumerate(reader, start=2):
            case_id = (row.get("id") or "").strip()
            question = (row.get("question") or "").strip()
            expected_raw = (row.get("expected_chunk_ids") or "").strip()
            expect_refusal = (row.get("expect_refusal") or "").strip().lower() in ("1", "true", "yes")
            subject_id = (row.get("subject_id") or "").strip()
            source = (row.get("source") or "").strip()

            if not case_id or not question:
                errors.append(f"第 {line_no} 行：id/question 不能为空")
                continue
            ids: List[int] = []
            if expected_raw:
                try:
                    ids = [int(x) for x in expected_raw.replace("，", ",").split(",") if x.strip()]
                except ValueError:
                    errors.append(f"第 {line_no} 行：expected_chunk_ids 不是整数列表")
                    continue
            if not expect_refusal and not ids:
                errors.append(f"第 {line_no} 行：非拒答用例必须标注 expected_chunk_ids")
                continue
            missing = [i for i in ids if i not in valid_ids]
            if missing:
                errors.append(f"第 {line_no} 行：chunk 不存在或非 ACTIVE：{missing}")
                continue
            rows.append(
                {
                    "id": case_id,
                    "question": question,
                    "expected_chunk_ids": expand_duplicates(ids),
                    "subject_id": int(subject_id) if subject_id else None,
                    "expect_refusal": expect_refusal,
                    "source": source,
                }
            )

    if errors:
        print("标注校验失败：", file=sys.stderr)
        for error in errors[:30]:
            print(" -", error, file=sys.stderr)
        raise SystemExit(1)

    with GOLDEN_JSONL.open("w", encoding="utf-8") as fp:
        for row in rows:
            fp.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"已生成 {len(rows)} 条 → {GOLDEN_JSONL}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Golden Set 导出 / 转换（不参与标注决策）")
    parser.add_argument("--export", action="store_true", help="导出候选 chunk")
    parser.add_argument("--convert", action="store_true", help="校验 CSV 并转 JSONL")
    parser.add_argument("--limit", type=int, default=300, help="导出条数，默认 300")
    args = parser.parse_args()
    if args.export:
        export_candidates(args.limit)
    elif args.convert:
        convert_golden()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
