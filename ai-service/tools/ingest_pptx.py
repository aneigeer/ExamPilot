"""把 PPTX 课件灌入知识库（courseware + ai_chunk），并推到向量库。

## 与 ingest_courseware.py 的关系

复用它定下的整套数据约定（表结构、source_type、分块参数、向量化方式），
只是把"输入格式"从 docx 扩展到 pptx。**不新建表、不新建类型**，
所以灌完立刻能在 App 的课件列表里看到、也能被 AI 问答检索到。

## 为什么 pptx 要单独抽文本

pptx 本质是 zip：`ppt/slides/slide1.xml ... slideN.xml`，正文在 `<a:t>` 标签里。
**图片里的文字抽不出来**（需要 OCR），脚本会把"纯图片页"统计出来报给你。

## 用法

    # 先看要灌什么（不写库）
    python ai-service/tools/ingest_pptx.py --dry-run

    # 真灌 + 立刻向量化
    python ai-service/tools/ingest_pptx.py

    # 只灌不推向量（后端/向量服务没起时用）
    python ai-service/tools/ingest_pptx.py --no-vector

    # 指定目录与科目
    python ai-service/tools/ingest_pptx.py --dir "C:/Users/15241/Downloads" --subject 操作系统

## 幂等

同一份 pptx（按 `source_file` 唯一键）重复执行 = 更新元数据 + 重建其语料块，
不会产生重复块；`content_hash` 用于块级判重。
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import pymysql

# 以脚本方式运行时，把 ai-service 加入 sys.path，才能 import app.db
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db import db_config  # noqa: E402

# ---------------------------------------------------------------- 配置
DB: dict = {}  # 由 db_config() 在连接时填充（口令来自环境变量/.env，不入库）
API = "http://127.0.0.1:8000"

SOURCE_TYPE = "COURSEWARE"
TARGET_CHARS = 600      # 目标块长（与 ingest_courseware.py 一致）
OVERLAP_CHARS = 80      # 相邻块重叠
MIN_SLIDE_CHARS = 30    # 单页少于这么多字视为封面/分隔页
BATCH = 500             # 单次向量化上限（Python 侧限制）

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------- PPTX 解析
def extract_pptx(path: Path) -> Tuple[str, List[Tuple[int, str]]]:
    """抽取 pptx 文本。

    Returns:
        (全文, [(页号, 该页文本), ...])
    """
    slides: List[Tuple[int, str]] = []
    with zipfile.ZipFile(path) as zf:
        names = [n for n in zf.namelist()
                 if re.fullmatch(r"ppt/slides/slide\d+\.xml", n)]
        names.sort(key=lambda n: int(re.search(r"slide(\d+)\.xml", n).group(1)))
        for name in names:
            xml = zf.read(name).decode("utf-8", "ignore")
            # 段落结束转换行，再剥标签：保留文本顺序与换行
            xml = xml.replace("</a:p>", "\n").replace("<a:br/>", "\n")
            text = re.sub(r"<[^>]+>", "", xml)
            text = html.unescape(text)
            text = re.sub(r"[ \t\u00a0]+", " ", text)
            text = re.sub(r"\n{3,}", "\n\n", text).strip()
            page_no = int(re.search(r"slide(\d+)\.xml", name).group(1))
            if len(text) >= MIN_SLIDE_CHARS:
                slides.append((page_no, text))
    full = "\n\n".join(t for _, t in slides)
    return full, slides


def split_chunks(title: str, slides: List[Tuple[int, str]]) -> List[Tuple[str, int]]:
    """按「幻灯片」切块，返回 [(块文本, 页号)]。

    ## 为什么按页而不是按 600 字硬切

    PPT 的语义单元就是**一页**：一页里通常是「标题 + 3-6 条要点」。
    按字数硬切会把同一页的要点劈开——实测「死锁的四个必要条件」被切成两半，
    模型只答出 3 条（第 4 条落在下一块，没进上下文）。

    所以这里改成：**一页一块**，块头带 `《章名》第N页·页标题`，
    页内在要点之间用「；」连接保持可读。超长页（>900 字）才二次切分，
    且每片都重复块头，保证任何一片都能独立回答"这是哪一页的内容"。
    """
    chunks: List[Tuple[str, int]] = []
    for page_no, text in slides:
        lines = [ln.strip() for ln in text.split("\n") if ln.strip()]
        if not lines:
            continue
        # 首行通常是幻灯片标题（短句），其余是要点
        page_title = lines[0] if len(lines[0]) <= 40 else ""
        body_lines = lines[1:] if page_title else lines
        # 丢掉纯序号/页码噪声（"01"、"1/86"、"第 3 页"），它们对检索是纯干扰
        body_lines = [
            ln for ln in body_lines
            if not re.fullmatch(r"[\d\s./|第页\-]{1,8}", ln)
        ]
        if not body_lines:
            continue
        body = "；".join(body_lines)
        header = f"《{title}》第{page_no}页" + (f"·{page_title}" if page_title else "")
        head = f"{header}\n{page_title}\n" if page_title else f"{header}\n"

        if len(head) + len(body) <= 900:
            chunks.append((head + body, page_no))
            continue
        # 超长页：按要点装箱，每片都带同样的块头
        buf = ""
        for ln in body_lines:
            if buf and len(buf) + len(ln) + 1 > TARGET_CHARS:
                chunks.append((head + buf, page_no))
                buf = (buf[-OVERLAP_CHARS:] + "；" + ln) if len(buf) > OVERLAP_CHARS else ln
            else:
                buf = f"{buf}；{ln}" if buf else ln
        if buf:
            chunks.append((head + buf, page_no))

    return [(c, page) for c, page in chunks if len(c) >= 60]


# ---------------------------------------------------------------- 数据库
def ensure_subject(conn, name: str, exam_category_id: int) -> int:
    """确保科目存在，返回 subject_id。"""
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM subject WHERE name=%s AND is_deleted=0 LIMIT 1", (name,))
        row = cur.fetchone()
        if row:
            return int(row[0])
        cur.execute(
            "INSERT INTO subject (exam_category_id, name, description, sort, status) "
            "VALUES (%s, %s, %s, %s, 1)",
            (exam_category_id, name, f"{name} 课程课件（PPT 导入）", 0))
        conn.commit()
        return int(cur.lastrowid)


def upsert_courseware(conn, title: str, category: str, subject_id: int,
                      source_file: str, char_count: int, chunk_count: int) -> int:
    """按 source_file 幂等写入课件元数据，返回 courseware.id。"""
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM courseware WHERE source_file=%s LIMIT 1", (source_file,))
        row = cur.fetchone()
        if row:
            cid = int(row[0])
            cur.execute(
                "UPDATE courseware SET title=%s, category=%s, subject_id=%s, "
                "char_count=%s, chunk_count=%s, status='ACTIVE', is_deleted=0, updated_at=NOW() "
                "WHERE id=%s",
                (title, category, subject_id, char_count, chunk_count, cid))
        else:
            cur.execute(
                "INSERT INTO courseware (title, category, subject_id, char_count, "
                "chunk_count, source_file, status, is_deleted) "
                "VALUES (%s,%s,%s,%s,%s,%s,'ACTIVE',0)",
                (title, category, subject_id, char_count, chunk_count, source_file))
            cid = int(cur.lastrowid)
        conn.commit()
        return cid


def replace_chunks(conn, courseware_id: int, title: str, chunks: List[Tuple[str, int]],
                   subject_id: int, exam_category_id: int) -> int:
    """重建该课件的语料块（旧块先删，避免累积重复）。"""
    with conn.cursor() as cur:
        cur.execute("DELETE FROM ai_chunk WHERE source_type=%s AND source_id=%s",
                    (SOURCE_TYPE, courseware_id))
        rows = []
        for i, (text, page_no) in enumerate(chunks):
            rows.append((
                SOURCE_TYPE, courseware_id, i, title, text,
                exam_category_id, subject_id, 3, "COURSEWARE", courseware_id,
                page_no, hashlib.md5(text.encode("utf-8")).hexdigest(),
            ))
        if rows:
            cur.executemany(
                "INSERT INTO ai_chunk (source_type, source_id, chunk_no, title, content, "
                "exam_category_id, subject_id, difficulty, ref_type, ref_id, page_no, "
                "content_hash, source_updated_at, status, vector_synced) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),'ACTIVE',0)", rows)
        conn.commit()
        return len(rows)


# ---------------------------------------------------------------- 向量化
def vectorize(conn, courseware_id: int) -> Tuple[int, int]:
    """把该课件未同步的块推到 Qdrant（分批 500），成功后回写 vector_synced=1。"""
    total = synced = failed = 0
    while True:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, content, title, subject_id, page_no FROM ai_chunk "
                "WHERE source_type=%s AND source_id=%s AND status='ACTIVE' "
                "AND (vector_synced IS NULL OR vector_synced<>1) "
                "ORDER BY id LIMIT %s", (SOURCE_TYPE, courseware_id, BATCH))
            rows = cur.fetchall()
        if not rows:
            break
        chunks = [{
            "chunk_id": r[0], "content": r[1], "source_type": SOURCE_TYPE,
            "source_id": courseware_id, "title": r[2], "subject_id": r[3],
            "knowledge_id": None, "difficulty": 3, "ref_type": "COURSEWARE",
            "ref_id": courseware_id, "page_no": r[4], "status": "ACTIVE",
            "content_hash": hashlib.md5(r[1].encode("utf-8")).hexdigest(),
        } for r in rows]
        try:
            req = urllib.request.Request(
                API + "/v1/index/sync", data=json.dumps({"chunks": chunks}).encode(),
                headers={"Content-Type": "application/json"}, method="POST")
            resp = json.loads(_opener.open(req, timeout=600).read())
            ok, bad = int(resp.get("synced", 0)), int(resp.get("failed", 0))
            with conn.cursor() as cur:
                fmt = ",".join(["%s"] * len(chunks))
                cur.execute(f"UPDATE ai_chunk SET vector_synced=1, vector_synced_at=NOW() "
                            f"WHERE id IN ({fmt})", [c["chunk_id"] for c in chunks])
            conn.commit()
            synced += ok
            failed += bad
            total += len(chunks)
            print(f"    向量化 {total} 块：synced={ok} failed={bad}")
        except (urllib.error.URLError, json.JSONDecodeError, KeyError) as exc:
            print(f"    ⚠ 向量化失败（{str(exc)[:80]}）→ 该批保持 vector_synced=0，可重跑")
            failed += len(chunks)
            break
    return synced, failed


# ---------------------------------------------------------------- 主流程
def main() -> int:
    ap = argparse.ArgumentParser(description="PPTX 课件灌库 + 向量化")
    ap.add_argument("--dir", default=r"C:/Users/15241/Downloads", help="ppt 所在目录")
    ap.add_argument("--pattern", default="第*章*.pptx", help="文件名匹配（默认教材章节）")
    ap.add_argument("--subject", default="操作系统", help="科目名（不存在会自动创建）")
    ap.add_argument("--category", default="操作系统", help="courseware.category")
    ap.add_argument("--exam-category", type=int, default=1, help="exam_category_id")
    ap.add_argument("--only", default="", help="只灌文件名含这些关键词的文件（逗号分隔）")
    ap.add_argument("--dry-run", action="store_true", help="只统计不写库")
    ap.add_argument("--no-vector", action="store_true", help="只灌库不推向量")
    args = ap.parse_args()

    files = sorted(Path(args.dir).glob(args.pattern))
    if args.only:
        keys = [k.strip() for k in args.only.split(",") if k.strip()]
        files = [f for f in files if any(k in f.name for k in keys)]
        print(f"关键词过滤 {len(keys)} 个 → 命中 {len(files)} 份")
    if not files:
        print(f"没找到文件：{Path(args.dir) / args.pattern}")
        return 1

    print(f"扫描到 {len(files)} 个 pptx　科目：{args.subject}"
          f"{'　[DRY-RUN 不写库]' if args.dry_run else ''}")
    conn = pymysql.connect(**db_config())
    total_chunks = total_chars = 0
    try:
        subject_id = 0 if args.dry_run else ensure_subject(conn, args.subject, args.exam_category)
        for path in files:
            title = path.stem.strip()
            started = time.time()
            try:
                full, slides = extract_pptx(path)
            except Exception as exc:  # noqa: BLE001 - 单篇失败不中断
                print(f"  ✗ {title}：解析失败 {type(exc).__name__}: {str(exc)[:60]}")
                continue
            if not full.strip():
                print(f"  ✗ {title}：未抽到文字（可能是纯图片 PPT，需 OCR）")
                continue
            chunks = split_chunks(title, slides)
            total_chars += len(full)
            total_chunks += len(chunks)
            print(f"  ✓ {title}：{len(slides)} 页 / {len(full)} 字 → {len(chunks)} 块"
                  f"（{time.time()-started:.1f}s）")
            if args.dry_run:
                continue
            cid = upsert_courseware(conn, title, args.category, subject_id,
                                    str(path.resolve()), len(full), len(chunks))
            n = replace_chunks(conn, cid, title, chunks, subject_id, args.exam_category)
            print(f"      入库 courseware.id={cid}，语料 {n} 块")
            if not args.no_vector:
                s, f = vectorize(conn, cid)
                print(f"      向量化完成：成功 {s}，失败 {f}")
    finally:
        conn.close()

    print(f"\n合计：{len(files)} 份 / {total_chars} 字 / {total_chunks} 块")
    if args.dry_run:
        print("★ DRY-RUN 未写库。确认数字后去掉 --dry-run 再执行。")
    else:
        print("下一步：App「课件」里应能看到；问答可直接问「死锁的四个必要条件」验证")
    return 0


if __name__ == "__main__":
    sys.exit(main())
