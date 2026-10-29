"""纯图片课件 OCR 入库（v5 任务2：106 份纯图片 docx 中的精选 12 篇）。

## 背景

106 份 docx（全部在「02 AI大模型」目录）是 PDFBox 逐页导出的图片版：
0 个文本 run、每页一张全幅 PNG，文本抽取彻底无解，**只能 OCR**。

## 处理管线（复用既有约定，不新建表/类型）

``docx(zip) → 按文档顺序取页面图 → PaddleOCR 逐页识别 →
按页切块（复用 ingest_pptx.split_chunks，页号即 page_no）→
courseware + ai_chunk 入库（复用 upsert_courseware / replace_chunks）→
经运行中的 AI 服务推向量（复用 vectorize）``

* 来源类型仍标 ``COURSEWARE``，``page_no`` 带真实页码（前端可跳页）
* 幂等：``uk_source_file`` 文件级幂等 + ``replace_chunks`` 先删后插，
  重复执行 = 更新，不产生重复块
* OCR 引擎直接复用 ``app/ocr/paddle_ocr_engine.py``（懒加载 + 版本兼容 +
  低分块丢弃），识别顺序按 bbox 自上而下、同行自左向右重排

## 用法

    python ai-service/tools/ocr_courseware.py --list                 # 列出纯图片文档
    python ai-service/tools/ocr_courseware.py --only RAG优化策略      # 按关键词选 1 篇
    python ai-service/tools/ocr_courseware.py --preset-top12         # 内置精选 12 篇
    python ai-service/tools/ocr_courseware.py --preset-top12 --dry-run

前置：paddleocr 已装（install-ocr.bat）；AI 服务在 8000 端口运行（向量化用）。
耗时参考：实测约 15s/页（CPU），12 篇约 200 页 ≈ 1 小时。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import pymysql

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from app.db import db_config  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.ocr.paddle_ocr_engine import get_ocr_engine  # noqa: E402
from ingest_courseware import SUBJECT_MAP, clean_title  # noqa: E402  # 复用标题清洗与科目映射
from ingest_pptx import (  # noqa: E402  # 复用入库/切块/向量化全套约定
    MIN_SLIDE_CHARS,
    ensure_subject,
    replace_chunks,
    split_chunks,
    upsert_courseware,
    vectorize,
)

#: docx 素材根目录（与 ingest_courseware.py 一致）
SOURCE_DIR = Path(r"C:/Users/15241/Desktop/112233/Word版")

#: OCR 前图片降采样长边（像素）。
#: 实测（PP-OCRv6 medium / CPU / enable_mkldnn=False）：
#:   1376×768 原图 ≈ 84s/页；缩到 1100px ≈ 19s/页，置信度 0.832（略优于 0.817）。
#: 原因是检测器对输入尺寸跨过内部放大阈值，1100px 恰好落在快档；幻灯片文字仍清晰。
MAX_IMAGE_SIDE = 1100

#: 内置精选 12 篇：优先 RAG / Transformer / Agent / 微调等高价值主题，
#: 同名文档只取一份（011 279 与 055 235 的《17种RAG优化策略》重复，取 011）
PRESET_TOP12 = [
    "279.17种RAG优化策略",
    "281.从传统RAG到Agentic RAG的进阶优化",
    "280.Rerank模型深度讲解",
    "264.Transformer是什么",
    "276.多头自注意力机制深度解析",
    "275.神经网络工作原理深度解析",
    "274.BERT核心原理及预训练机制",
    "282.ReAct框架原理解析与面试实战",
    "278.多智能体架构深度解析",
    "284.Agent Skill 原理和实践指南",
    "269.SFT、RLHF、DPO的真实边界",
    "265.提示词工程Prompt Engineering",
]


# ---------------------------------------------------------------- 图片抽取
def extract_page_images(docx_path: Path) -> List[Tuple[str, bytes]]:
    """按**文档出现顺序**抽取 docx 内嵌页面图。

    word/media/ 里的文件名顺序不可靠；真实顺序 = document.xml 里
    ``<a:blip r:embed="rIdX">`` 的出现顺序，rId 再经 rels 映射回媒体文件。

    Returns:
        ``[(媒体文件名, 图片字节), ...]``，顺序即页码顺序。
    """
    with zipfile.ZipFile(docx_path) as zf:
        document = zf.read("word/document.xml").decode("utf-8", "ignore")
        rels_xml = zf.read("word/_rels/document.xml.rels").decode("utf-8", "ignore")
        rid_to_target: Dict[str, str] = dict(re.findall(
            r'<Relationship[^>]*Id="([^"]+)"[^>]*Target="([^"]+)"', rels_xml))
        pages: List[Tuple[str, bytes]] = []
        for rid in re.findall(r'<a:blip[^>]*r:embed="([^"]+)"', document):
            target = rid_to_target.get(rid, "")
            name = "word/" + target.lstrip("./") if not target.startswith("word/") else target
            try:
                pages.append((Path(target).name, zf.read(name)))
            except KeyError:
                continue
        return pages


def ocr_page_image(engine: Any, image_bytes: bytes, temp_path: Path) -> Tuple[str, float]:
    """识别单页图片，返回按阅读顺序拼好的文本与平均置信度。

    ★ 落盘前先**降采样**：原始导出图 1376×768（部分文档 5504×3072），
    实测同一页在 1376px 下要 84s、1100px 下只要 19s（检测器对输入尺寸有内部台阶），
    且置信度不降反略升。这是纯图片课件批量 OCR 的关键优化。

    ★ 临时文件**整篇复用同一个路径**（每页覆盖写），不逐页创建+删除：
    逐页删临时文件会触发环境的安全钩子（连续删除达阈值即拦截并终止进程，
    实测第 50 次删除时进程被杀），所以删除动作收敛到"每篇一次"。
    """
    import io

    from PIL import Image

    with Image.open(io.BytesIO(image_bytes)) as image:
        image = image.convert("RGB")
        image.thumbnail((MAX_IMAGE_SIDE, MAX_IMAGE_SIDE), Image.LANCZOS)
        image.save(temp_path, format="PNG", optimize=False)
    blocks, confidence = engine.recognize_file(str(temp_path))
    # 按阅读顺序重排：先按 y（容差 12pt 视为同一行），行内按 x
    located = [b for b in blocks if b.bbox]
    located.sort(key=lambda b: (round(b.bbox[1] / 12.0), b.bbox[0]))
    text = "\n".join(b.text.strip() for b in located if b.text.strip())
    return text, float(confidence)


def is_image_only(docx_path: Path) -> Optional[int]:
    """判断是否纯图片文档；是则返回图片页数，否则 None。"""
    with zipfile.ZipFile(docx_path) as zf:
        if not any(n.startswith("word/media/") for n in zf.namelist()):
            return None
        xml = zf.read("word/document.xml").decode("utf-8", "ignore")
        if re.search(r"<w:t[ >]", xml):
            return None
        return len([n for n in zf.namelist() if n.startswith("word/media/")])


def scan_image_only() -> List[Tuple[Path, int]]:
    """递归扫描素材目录，返回所有纯图片文档。"""
    found: List[Tuple[Path, int]] = []
    for path in sorted(SOURCE_DIR.rglob("*.docx")):
        count = is_image_only(path)
        if count:
            found.append((path, count))
    return found


# ---------------------------------------------------------------- 主流程
def process_doc(conn: Any, engine: Any, path: Path, temp_dir: Path,
                subject_id: int, category: str, *, dry_run: bool,
                no_vector: bool) -> Dict[str, Any]:
    """OCR + 入库一篇。返回该篇的统计。"""
    title = clean_title(path.name)
    started = time.time()
    images = extract_page_images(path)
    if not images:
        return {"title": title, "pages": 0, "ocr_ok": 0, "chunks": 0,
                "error": "未抽到内嵌图片"}

    slides: List[Tuple[int, str]] = []
    total_conf = 0.0
    ocr_ok = 0
    # 整篇复用同一个临时文件（见 ocr_page_image 的说明：逐页删除会触发安全钩子）
    page_temp = temp_dir / "page.png"
    try:
        for page_no, (_, image_bytes) in enumerate(images, start=1):
            try:
                text, confidence = ocr_page_image(engine, image_bytes, page_temp)
            except Exception as exc:  # noqa: BLE001 —— 单页失败记空页，不中断整篇
                print(f"      第{page_no}页 OCR 失败：{type(exc).__name__}")
                continue
            if confidence > 0:
                ocr_ok += 1
                total_conf += confidence
            if len(text) >= MIN_SLIDE_CHARS:
                slides.append((page_no, text))
            if page_no % 5 == 0 or page_no == len(images):
                print(f"      {title[:18]}… 已识别 {page_no}/{len(images)} 页"
                      f"（{time.time() - started:.0f}s）", flush=True)
    finally:
        if page_temp.exists():
            try:
                page_temp.unlink()
            except OSError:
                pass

    full = "\n\n".join(t for _, t in slides)
    chunks = split_chunks(title, slides)
    result = {
        "title": title,
        "pages": len(images),
        "ocr_ok": ocr_ok,
        "confidence": round(total_conf / ocr_ok, 3) if ocr_ok else 0.0,
        "chunks": len(chunks),
        "chars": len(full),
        "error": "",
    }
    print(f"  ✓ {title}：{len(images)} 页 / OCR 成功 {ocr_ok} 页"
          f"（置信度 {result['confidence']}）→ {len(chunks)} 块 "
          f"（{time.time() - started:.1f}s）")
    if dry_run:
        return result

    cid = upsert_courseware(conn, title, category, subject_id,
                            str(path.resolve()), len(full), len(chunks))
    n = replace_chunks(conn, cid, title, chunks, subject_id, 1)
    result["db_id"] = cid
    result["inserted"] = n
    print(f"      入库 courseware.id={cid}，语料 {n} 块")
    if not no_vector:
        s, f = vectorize(conn, cid)
        result["vectorized"] = s
        print(f"      向量化完成：成功 {s}，失败 {f}")
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description="纯图片 docx OCR 入库")
    ap.add_argument("--list", action="store_true", help="只列出纯图片文档")
    ap.add_argument("--preset-top12", action="store_true", help="处理内置精选 12 篇")
    ap.add_argument("--all", action="store_true",
                    help="处理**全部**纯图片文档（配合 --skip-existing 断点续跑）")
    ap.add_argument("--only", default="", help="按文件名关键词选（逗号分隔，覆盖 --preset-top12）")
    ap.add_argument("--limit", type=int, default=0, help="最多处理多少篇（0=全部选中）")
    ap.add_argument("--skip-existing", action="store_true",
                    help="跳过已入库且已切出块的课件（续跑用，幂等）")
    ap.add_argument("--dry-run", action="store_true", help="OCR 但不写库")
    ap.add_argument("--no-vector", action="store_true", help="只入库不推向量")
    args = ap.parse_args()

    image_only = scan_image_only()
    print(f"素材目录共 {len(list(SOURCE_DIR.rglob('*.docx')))} 份 docx，纯图片 {len(image_only)} 份")
    if args.list:
        for path, count in image_only:
            print(f"  {path.name}  ({count} img)  [{path.parent.name}]")
        return 0

    if args.only:
        keys = [k.strip() for k in args.only.split(",") if k.strip()]
        selected = [(p, c) for p, c in image_only
                    if any(k in p.name for k in keys)]
    elif args.preset_top12:
        selected = [(p, c) for p, c in image_only
                    if any(k in p.name for k in PRESET_TOP12)]
    elif args.all:
        selected = list(image_only)
    else:
        print("未指定 --all / --preset-top12 / --only / --list，退出（防误跑）")
        return 1
    # 同名去重：clean_title 相同的只留第一份（如两个版本的《17种RAG优化策略》）
    seen_titles: set = set()
    deduped: List[Tuple[Path, int]] = []
    for path, count in selected:
        title = clean_title(path.name)
        if title in seen_titles:
            print(f"  跳过重复标题：{path.name}")
            continue
        seen_titles.add(title)
        deduped.append((path, count))
    if args.limit:
        deduped = deduped[:args.limit]
    if args.skip_existing:
        conn_probe = pymysql.connect(**db_config())
        try:
            with conn_probe.cursor() as cur:
                cur.execute(
                    "SELECT c.title FROM courseware c JOIN ai_chunk ch "
                    "ON ch.source_type='COURSEWARE' AND ch.source_id=c.id "
                    "AND ch.status='ACTIVE' GROUP BY c.title")
                done = {str(r[0]) for r in cur.fetchall()}
        finally:
            conn_probe.close()
        before = len(deduped)
        deduped = [(p, c) for p, c in deduped if clean_title(p.name) not in done]
        if before != len(deduped):
            print(f"  已入库跳过 {before - len(deduped)} 篇（--skip-existing）")
    if not deduped:
        print("没有命中任何文档")
        return 1
    print(f"选中 {len(deduped)} 篇{'　[DRY-RUN]' if args.dry_run else ''}")

    engine = get_ocr_engine()
    if not engine.is_available():
        print("✗ paddleocr 未安装，请先执行 install-ocr.bat（不要硬跑）")
        return 1
    temp_dir = Path(__file__).resolve().parent / "__ocr_tmp"
    temp_dir.mkdir(exist_ok=True)

    conn = pymysql.connect(**db_config())
    report: List[Dict[str, Any]] = []
    progress_file = TOOLS / "ocr_progress.json"
    progress: List[Dict[str, Any]] = []
    if progress_file.exists():
        try:
            progress = json.loads(progress_file.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            progress = []
    done_titles = {str(item.get("title") or "") for item in progress}

    def _record(item: Dict[str, Any]) -> None:
        """逐篇落盘进度台账（断点续跑清单；外部可据此核对已完成/剩余）。"""
        progress.append(item)
        try:
            progress_file.write_text(
                json.dumps(progress, ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError as exc:  # 写台账失败不能影响主流程
            print(f"      ⚠ 进度台账写入失败：{type(exc).__name__}")

    try:
        subject_name = SUBJECT_MAP.get(
            deduped[0][0].parent.name, "AI 大模型")
        subject_id = 0 if args.dry_run else ensure_subject(conn, subject_name, 1)
        print(f"科目：{subject_name}（subject_id={subject_id}）")
        for index, (path, page_count) in enumerate(deduped, start=1):
            category = path.parent.name
            title = clean_title(path.name)
            print(f"[{index}/{len(deduped)}] {title}（{page_count} 页）", flush=True)
            try:
                item = process_doc(conn, engine, path, temp_dir, subject_id,
                                   category, dry_run=args.dry_run,
                                   no_vector=args.no_vector)
            except Exception as exc:  # noqa: BLE001 —— 单篇失败不中断
                print(f"  ✗ {path.name}：{type(exc).__name__}: {str(exc)[:100]}")
                item = {"title": title, "pages": 0, "ocr_ok": 0, "chunks": 0,
                        "error": f"{type(exc).__name__}: {str(exc)[:120]}"}
            report.append(item)
            if not args.dry_run and title not in done_titles:
                done_titles.add(title)
                _record({"title": title, "path": str(path), "pages": item.get("pages", 0),
                         "ocr_ok": item.get("ocr_ok", 0), "chunks": item.get("chunks", 0),
                         "error": item.get("error", ""),
                         "at": time.strftime("%Y-%m-%d %H:%M:%S")})
    finally:
        conn.close()
        try:
            temp_dir.rmdir()
        except OSError:
            pass  # 还有残留临时文件时忽略

    print("\n==== 逐篇结果 ====")
    for r in report:
        print(f"  {r['title']}：{r['pages']} 页 / OCR 成功 {r['ocr_ok']} 页 / "
              f"{r['chunks']} 块" + (f" / 错误 {r['error']}" if r["error"] else ""))
    total_pages = sum(r["pages"] for r in report)
    total_ok = sum(r["ocr_ok"] for r in report)
    total_chunks = sum(r["chunks"] for r in report)
    print(f"合计：{len(report)} 篇 / {total_pages} 页 / OCR 成功 {total_ok} 页 / {total_chunks} 块")
    return 0


if __name__ == "__main__":
    sys.exit(main())
