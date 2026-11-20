# -*- coding: utf-8 -*-
"""四六级答案册 OCR：把字体编码损坏 / 扫描版解析册转成可解析文本。

背景
----
`D:\\题库` 里四六级的解析册有两类不可用：
  1. 扫描件（无文本层）；
  2. 文本层是**私有字体编码**，pypdf 抽出来是乱码（如「ਤਹਸ਼ੋਾ…」）。
这两类只能渲染成图片再做 OCR。本脚本用 pdftoppm 渲染 + PaddleOCR 识别，
结果缓存到 `runtime/ocr_text/<md5>.txt`，供 `backfill_answers.py` 直接读取。

之所以并行：单页 OCR 实测 45s（PP-OCRv6 medium / CPU），419 页串行要 5 小时。
4 进程 + 120dpi 可压到半小时级。

用法
----
    .venv-ai\\Scripts\\python.exe scripts/ocr_answer_books.py --limit-pages 2   # 测速
    .venv-ai\\Scripts\\python.exe scripts/ocr_answer_books.py                    # 全量
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pymysql  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OCR_TEXT_DIR = ROOT / "runtime" / "ocr_text"
PDFTOPPM = Path(r"D:\MiKTeX\miktex\bin\x64\pdftoppm.exe")
AI_SERVICE = ROOT / "ai-service"

BAD_CHARS = re.compile(r"[\u0A00-\u0A7F\u0C00-\u0C7F\uE000-\uF8FF\u2E80-\u2FFF]")
_ENGINE = None


def _init_worker() -> None:
    global _ENGINE
    os.chdir(AI_SERVICE)
    if str(AI_SERVICE) not in sys.path:
        sys.path.insert(0, str(AI_SERVICE))
    from app.config import get_settings  # noqa: WPS433
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine  # noqa: WPS433

    _ENGINE = PaddleOcrEngine(get_settings().ocr)
    _ENGINE.ensure_loaded()


def _ocr_image(path: str) -> str:
    blocks, _ms = _ENGINE.recognize_file(path)
    return "\n".join(b.text for b in blocks)


def bad_ratio(text: str) -> float:
    if not text:
        return 1.0
    return len(BAD_CHARS.findall(text)) / len(text)


def load_password() -> str:
    env = ROOT / "ai-service" / ".env"
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("DB_PASSWORD="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("ai-service/.env 缺少 DB_PASSWORD")


def targets() -> list[str]:
    """四六级答案/解析册里文本层已损坏的 PDF。"""
    import import_question_bank as qb  # noqa: WPS433

    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                           password=load_password(), database="exam_prep",
                           charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT source_path, year FROM question_source
                WHERE exam_code IN ('CET4','CET6')
                  AND doc_kind IN ('ANSWER','ANALYSIS')
                  AND file_ext='.pdf'
                ORDER BY year DESC, source_path
            """)
            rows = [(r[1], r[0]) for r in cur.fetchall()]
    finally:
        conn.close()

    out = []
    for _year, path in rows:
        if cache_path(path).exists():
            continue
        try:
            text, _ = qb.pdf_text(Path(path))
        except Exception:
            continue
        if bad_ratio(text) > 0.02:
            out.append(path)
    return out


def cache_path(pdf_path: str) -> Path:
    key = hashlib.md5(str(Path(pdf_path)).encode("utf-8")).hexdigest()
    return OCR_TEXT_DIR / (key + ".txt")


def ocr_one(pdf_path: str, dpi: int, pool: "ProcessPoolExecutor",
            limit_pages: int = 0) -> tuple[str, int, float]:
    started = time.time()
    OCR_TEXT_DIR.mkdir(parents=True, exist_ok=True)
    tmp_dir = Path(tempfile.mkdtemp(prefix="ocr_", dir=str(ROOT / "runtime")))
    try:
        src = tmp_dir / "in.pdf"
        shutil.copy(pdf_path, src)
        cmd = [str(PDFTOPPM), "-png", "-r", str(dpi)]
        if limit_pages:
            cmd += ["-f", "1", "-l", str(limit_pages)]
        cmd += [str(src), str(tmp_dir / "pg")]
        subprocess.run(cmd, capture_output=True, timeout=1800)

        images = sorted(tmp_dir.glob("pg*.png"))
        if not images:
            return "", 0, 0.0
        # 复用全局进程池：模型只在首次加载一次（单文件建池会每份重载 4×20s）
        texts = list(pool.map(_ocr_image, [str(i) for i in images]))
        body = "\n\n".join(t for t in texts if t.strip())
        target = cache_path(pdf_path)
        target.write_text(body, encoding="utf-8")
        return body, len(images), time.time() - started
    finally:
        # 不在这里删除临时图片：环境的安全策略按「单轮累计删除文件数」计数，
        # 超过 50 个就中断整批任务（实测 EXIT=1）。临时图约 160 MB，
        # 全部跑完后由人工/单独一轮清理，优先保证 OCR 不中断。
        pass


def main() -> int:
    ap = argparse.ArgumentParser(description="四六级答案册 OCR")
    ap.add_argument("--dpi", type=int, default=120)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--limit-files", type=int, default=0)
    ap.add_argument("--limit-pages", type=int, default=0)
    args = ap.parse_args()

    files = targets()
    if args.limit_files:
        files = files[: args.limit_files]
    print(f"[ocr] 待处理 {len(files)} 份（dpi={args.dpi}, workers={args.workers}）", flush=True)
    total_pages = 0
    started = time.time()
    with ProcessPoolExecutor(max_workers=args.workers,
                             initializer=_init_worker) as pool:
        for idx, path in enumerate(files, 1):
            try:
                text, pages, cost = ocr_one(path, args.dpi, pool, args.limit_pages)
                total_pages += pages
                print(f"[{idx}/{len(files)}] {pages:3d}页 {cost:6.1f}s chars={len(text):7d} "
                      f"{Path(path).name[:44]}", flush=True)
            except Exception as exc:  # noqa: BLE001
                print(f"[{idx}/{len(files)}] FAIL {Path(path).name[:44]} -> "
                      f"{type(exc).__name__}: {exc}", flush=True)
    print(f"[ocr] 完成：{total_pages} 页，耗时 {(time.time()-started)/60:.1f} 分钟", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
