# -*- coding: utf-8 -*-
"""题库 PDF 全文并行预抽取（只写缓存，不改原始资料、不写业务表）。

背景
----
`import_question_bank.py --stage parse` 是单进程串行抽 PDF 的。实测 1000+ 个
真题 PDF 里有一批 200~400 页的「合集」，单个就要几十秒，串行会拖到数小时。

本脚本用线程池并发地起 pypdf 子进程，把全文写进 `runtime/pdf_text_cache/`；
之后 parse 阶段 `pdf_text()` 命中缓存直接返回，解析只剩规则切分与写库。

用法
----
    .venv-ai\\Scripts\\python.exe scripts/pdf_text_cache.py --workers 4
    .venv-ai\\Scripts\\python.exe scripts/pdf_text_cache.py --all   # 含已解析文件
"""

from __future__ import annotations

import argparse
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_question_bank as qb  # noqa: E402

_LOCK = threading.Lock()
_DONE = 0
_TOTAL = 0
_FAIL: list[tuple[str, str]] = []


def _load_password() -> str:
    env = Path(__file__).resolve().parent.parent / "ai-service" / ".env"
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("DB_PASSWORD="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("ai-service/.env 缺少 DB_PASSWORD")


def _candidates(include_all: bool) -> list[str]:
    import pymysql

    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                           password=_load_password(), database="exam_prep",
                           charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            if include_all:
                cur.execute("SELECT source_path FROM question_source WHERE file_ext='.pdf'")
            else:
                cur.execute("SELECT source_path FROM question_source "
                            "WHERE file_ext='.pdf' AND parse_status='PENDING'")
            return [row[0] for row in cur.fetchall()]
    finally:
        conn.close()


def _work(path_str: str) -> tuple[str, str]:
    global _DONE
    path = Path(path_str)
    cache = qb._pdf_cache_path(path)
    if cache.exists():
        with _LOCK:
            _DONE += 1
        return "cached", path_str
    try:
        text, total = qb.pdf_text(path)          # 内部子进程 + 自动写缓存
        status = "ok" if total > 0 else "empty"
    except Exception as exc:                      # noqa: BLE001
        status = "fail"
        with _LOCK:
            _FAIL.append((path_str, f"{type(exc).__name__}: {exc}"))
    with _LOCK:
        _DONE += 1
        if _DONE % 25 == 0 or _DONE == _TOTAL:
            print(f"[cache] {_DONE}/{_TOTAL}  最近={status}  {path.name[:60]}", flush=True)
    return status, path_str


def main() -> int:
    global _TOTAL
    parser = argparse.ArgumentParser(description="并行预抽取题库 PDF 文本到缓存")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--all", action="store_true", help="包含已解析状态的 PDF")
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    qb.PDF_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    paths = _candidates(args.all)
    if args.limit:
        paths = paths[: args.limit]
    _TOTAL = len(paths)
    if not paths:
        print("[cache] 没有待抽取的 PDF")
        return 0

    print(f"[cache] 待抽取 {_TOTAL} 个 PDF，并发 {args.workers}，"
          f"缓存目录 {qb.PDF_CACHE_DIR}", flush=True)
    started = time.time()
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(_work, p) for p in paths]
        for fut in as_completed(futures):
            fut.result()

    elapsed = time.time() - started
    cached = sum(1 for p in paths if qb._pdf_cache_path(Path(p)).exists())
    print(f"[cache] 完成：{cached}/{_TOTAL} 有缓存，失败 {len(_FAIL)}，"
          f"耗时 {elapsed:.1f}s", flush=True)
    for path, reason in _FAIL[:20]:
        print(f"  FAIL {Path(path).name[:60]} -> {reason}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
