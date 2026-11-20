# -*- coding: utf-8 -*-
"""四六级真题「两栏版面」重解析（pdftotext -layout）。

为什么需要
----------
四六级真题 PDF 是双栏排版，pypdf 按内容流抽文本会把左右栏交错，题号与选项错位，
导入脚本只能切出约 13 道/份（正常 50+）。实测 `pdftotext -layout` 能按栏还原，
同一份能切出 36 道完整题（含题干与 4 个选项）。

本脚本只补题、不改已有题：
* 只处理 CET4 / CET6 的 PDF 真题（TEXT/SPARSE 层）；
* 只收「题干 ≥15 字符 且 4 个选项齐全」的块（听力题无题干，跳过）；
* 以 `(exam_code, year, md5(stem))` 判重，已存在的一律跳过；
* 默认 dry-run，加 --apply 才写库。

用法
----
    .venv-ai\\Scripts\\python.exe scripts/reparse_cet_layout.py --dry-run
    .venv-ai\\Scripts\\python.exe scripts/reparse_cet_layout.py --apply
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_question_bank as qb  # noqa: E402

PDFTOTEXT = Path(r"D:\MiKTeX\miktex\bin\x64\pdftotext.exe")
ROOT = Path(__file__).resolve().parent.parent
QNO = re.compile(r"(?m)^\s*(\d{1,3})\s*[.．]\s+")
OPT = re.compile(r"([A-D])\)\s*")
NOISE = re.compile(r"(第\s*\d+\s*页|共\s*\d+\s*页|淘宝|店铺|微信号|关注)", re.I)

#: 试卷尾部「答案速查」等区域不再切题
STOP = re.compile(r"(?m)^\s*(参考答案|答案速查|答案与解析|【答案】)")


def layout_text(pdf: Path) -> str:
    tmp = Path(tempfile.mkdtemp(prefix="cet_", dir=str(ROOT / "runtime")))
    try:
        src = tmp / "in.pdf"
        shutil.copy(pdf, src)
        proc = subprocess.run([str(PDFTOTEXT), "-layout", "-enc", "UTF-8", str(src), "-"],
                              capture_output=True, timeout=300)
        return proc.stdout.decode("utf-8", "replace")
    finally:
        for child in tmp.rglob("*"):
            if child.is_file():
                child.unlink(missing_ok=True)
        tmp.rmdir()


def parse_blocks(text: str) -> list[tuple[int, str, dict[str, str]]]:
    cut = STOP.search(text)
    if cut:
        text = text[: cut.start()]
    marks = list(QNO.finditer(text))
    out = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        block = text[m.end():end]
        om = list(OPT.finditer(block))
        if len(om) < 4:
            continue
        stem = block[: om[0].start()].strip()
        stem = re.sub(r"\s+", " ", stem)
        if len(stem) < 15 or NOISE.search(stem):
            continue
        opts: dict[str, str] = {}
        for j, mm in enumerate(om[:4]):
            stop = om[j + 1].start() if j < 3 else len(block)
            content = re.sub(r"\s+", " ", block[mm.end():stop]).strip()
            opts.setdefault(mm.group(1), content[:480])
        if len(opts) != 4 or any(not v for v in opts.values()):
            continue
        out.append((int(m.group(1)), stem[:60000], opts))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="四六级两栏真题重解析")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    apply = args.apply and not args.dry_run

    conn = qb.db_connect()
    qcols = qb.table_columns(conn, "question")
    with conn.cursor() as cur:
        cur.execute("""
            SELECT id, source_path, file_name, exam_code, year
            FROM question_source
            WHERE exam_code IN ('CET4','CET6') AND doc_kind='PAPER'
              AND file_ext='.pdf' AND text_layer IN ('TEXT','SPARSE')
            ORDER BY year DESC, id
        """)
        sources = cur.fetchall()
    if args.limit:
        sources = sources[: args.limit]

    print(f"[load] 待处理试卷 {len(sources)} 份", flush=True)
    added_total = 0
    for idx, (sid, path, fname, ecode, year) in enumerate(sources, 1):
        try:
            text = layout_text(Path(path))
        except Exception as exc:  # noqa: BLE001
            print(f"  SKIP {fname[:44]} -> {type(exc).__name__}", flush=True)
            continue
        blocks = parse_blocks(text)
        if not blocks:
            continue

        with conn.cursor() as cur:
            cur.execute("SELECT MD5(stem) FROM question WHERE origin_path=%s AND is_deleted=0", (path,))
            existing = {r[0] for r in cur.fetchall()}
            cur.execute("SELECT subject_id FROM question WHERE exam_code=%s LIMIT 1", (ecode,))
            row = cur.fetchone()
            subject_id = row[0] if row else 0

        new_items = [b for b in blocks if hashlib.md5(b[1].encode("utf-8")).hexdigest() not in existing]
        print(f"[{idx}/{len(sources)}] {fname[:40]:42s} 版面块 {len(blocks):3d} 新增 {len(new_items):3d}",
              flush=True)
        if not apply or not new_items:
            added_total += len(new_items)
            continue

        for qno, stem, opts in new_items:
            q = {
                "subject_id": subject_id,
                "type": qb.QTYPE_SINGLE,
                "stem": stem,
                "source": f"{year}年 {qb.EXAM_LABEL.get(ecode, '真题')} · {fname}"[:100],
                "tags": f"{qb.EXAM_LABEL.get(ecode, '真题')},真题,PAPER"[:255],
                "analysis": None,
                "difficulty": 3,
                "score": 1,
                "sort": qno,
                "options": [(k, v) for k, v in sorted(opts.items())],
                "answer": None,
                "year": year,
                "exam_code": ecode,
                "paper_name": fname,
                "origin_path": path,
            }
            qb.insert_question(conn, q, qcols)
        conn.commit()
        added_total += len(new_items)

    print(f"\n[{'apply' if apply else 'dry-run'}] 新增题目 {added_total} 道")
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
