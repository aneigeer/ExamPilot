#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""题库导入流水线：两个资料目录 → question_source / question / ai_chunk。

数据来源
--------
A = C:/Users/15241/Desktop/112233    （面试知识点：634 md + 634 docx + 配图 + pdf）
B = D:/题库                           （四六级 / 国考 / 考研 真题、解析、词汇、模板、岗位表、听力）

阶段
----
register  遍历两目录全部文件，推断 exam_code / year / doc_kind / text_layer，写入
          question_source（uk_source_path + ON DUPLICATE KEY UPDATE，可重复运行）。
parse     按 question_source 的状态断点续跑：有文本层文件按规则切题 / 切块，
          扫描件只登记 OCR_QUEUED，绝不编造题目。
all       先 register 再 parse。

幂等 / 断点续跑
----------------
* register：uk_source_path 唯一键；内容 sha256 变化时才把 PARSED 重置为 PENDING。
* parse：只处理 PENDING / PARTIAL / FAILED，PARSED / SKIPPED / OCR_QUEUED 跳过；
  题目行按 (source, type, stem) 存在性判重，ai_chunk 按 uk_source_chunk 幂等。
* 单文件一个显式事务：题目 + 选项 + 答案 + 语料块 + 状态更新要么全提交要么全回滚。

依赖
----
仅用已装包：pypdf 5.1.0（PDF 文本层）、pymysql 1.4.6（写库）、paddleocr 未启用、
其余全部标准库（zipfile + XML 解析 docx，不依赖 python-docx / openpyxl）。
不联网、不调用任何大模型。口令从 ai-service/.env 的 DB_PASSWORD 读取，不硬编码。

用法
----
    # 干跑（不连库、不写库、不改原始资料；打印真实统计）
    python scripts/import_question_bank.py --stage all --root ALL --dry-run

    # 小样干跑
    python scripts/import_question_bank.py --stage all --root ALL --limit 20 --dry-run

    # 主 agent 执行（先建表，再分阶段灌库）
    #   mysql -uroot -p exam_prep < sql/migration-question-source.sql
    #   python scripts/import_question_bank.py --stage register --root ALL
    #   python scripts/import_question_bank.py --stage parse --root ALL
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

# 可选依赖：dry-run 不需要数据库，但仍允许只装了 pypdf 的环境跑统计
try:
    import pymysql
except ImportError:  # pragma: no cover
    pymysql = None  # type: ignore[assignment]

try:
    from pypdf import PdfReader
except ImportError:  # pragma: no cover
    PdfReader = None  # type: ignore[assignment]


# ======================================================================================
# 常量配置
# ======================================================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ROOT_A = Path("C:/Users/15241/Desktop/112233")
ROOT_B = Path("D:/题库")
ENV_FILE = PROJECT_ROOT / "ai-service" / ".env"
OUT_DIR = PROJECT_ROOT / "项目规划"

#: ai_chunk 的 source_type 合法值（见 backend/.../entity/AiChunk.java）：
#: QUESTION / RESOURCE / KNOWLEDGE / COURSEWARE。面试知识语料与课件同语义，用 COURSEWARE。
#: 为避免与 courseware.id 撞 uk_source_chunk(source_type, source_id, chunk_no)，
#: 本流水线统一把 question_source.id 加上这个偏移再作为 source_id / ref_id。
CHUNK_SOURCE_TYPE = "COURSEWARE"
CHUNK_REF_TYPE = "COURSEWARE"
CHUNK_SOURCE_OFFSET = 1_000_000

#: 切块参数（复用 ai-service/tools/ingest_courseware.py:230-296 的思路）
CHUNK_TARGET = 600
CHUNK_OVERLAP = 80
CHUNK_MIN_TAIL = 50

#: 单条批量 SQL 的行数
BATCH_ROWS = 200

#: 契约中「question 表新增列由另一 agent 负责迁移」的列，运行时动态探测，缺失即跳过
CONTRACT_QUESTION_COLS: Tuple[str, ...] = ("year", "exam_code", "paper_name", "origin_path")

#: exam_code 固定取值（未识别时为 None，不硬塞枚举外的值）
EXAM_LABEL: Dict[str, str] = {
    "CET4": "英语四级",
    "CET6": "英语六级",
    "GUOKAO": "国考行测",
    "GUOKAO_SHENLUN": "国考申论",
    "POSTGRAD_ENG1": "考研英语一",
    "POSTGRAD_ENG2": "考研英语二",
    "POSTGRAD_MATH1": "考研数学一",
    "POSTGRAD_MATH2": "考研数学二",
    "POSTGRAD_MATH3": "考研数学三",
    "INTERVIEW": "AI大模型面试",
}

#: exam_code → 写入 question 时使用的科目名（库中不存在则由脚本按名建科目）
SUBJECT_NAME: Dict[str, str] = {
    "CET4": "CET4 阅读",
    "CET6": "CET6 阅读",
    "GUOKAO": "国考行测",
    "GUOKAO_SHENLUN": "国考申论",
    "POSTGRAD_ENG1": "考研英语（一）",
    "POSTGRAD_ENG2": "考研英语（二）",
    "POSTGRAD_MATH1": "考研数学（一）",
    "POSTGRAD_MATH2": "考研数学（二）",
    "POSTGRAD_MATH3": "考研数学（三）",
    "INTERVIEW": "AI大模型面试",
}

#: 合法题型（backend QuestionType.java；ESSAY 当前 open=false，故面试问答统一用 SHORT_ANSWER）
QTYPE_SINGLE = "SINGLE_CHOICE"
QTYPE_SHORT = "SHORT_ANSWER"

#: 文本型扩展名（可抽正文），其余为二进制 / 图片 / 音频
TEXT_EXTS: Set[str] = {".md", ".txt", ".docx"}
IMAGE_EXTS: Set[str] = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg"}
ARCHIVE_EXTS: Set[str] = {".zip", ".rar", ".7z"}
TABLE_EXTS: Set[str] = {".xlsx", ".et"}
AUDIO_EXTS: Set[str] = {".mp3", ".wav", ".m4a", ".aac"}

#: 文件名去数字前缀（"001 154.对比学习相似度函数面试题" → "对比学习相似度函数面试题"）
TITLE_PREFIX_RE = re.compile(r"^\d{1,4}\s*[.、．,，\-_]\s*|^\d{1,4}\s+")
#: 年份：19xx / 20xx，兼容 "2022.06"、"2022年06月"、"2015.6-2026.6" 等变体
YEAR_RE = re.compile(r"(?<!\d)(19\d{2}|20\d{2})(?!\d)")
#: 题号起始（行首 1~3 位数字 + 中英文点/顿号）
QNO_RE = re.compile(r"(?m)^\s*(\d{1,3})\s*[.．、,，]\s*")
#: 选项标记：A. / A、 / A) / [A] / A： 等
OPT_RE = re.compile(r"(?<![A-Za-z0-9])(?:\[([A-D])\]|([A-D])\s*[.．、)）:：\]])")
#: 章节标题（"第一部分 ..."、"Section II ..."）用于截断题干尾巴
SECTION_RE = re.compile(r"(?m)^\s*(?:第[一二三四五六七八九十百]+部分|Section\s+[IVX]+|Part\s+[A-D])\b.*$")


# ======================================================================================
# 数据结构
# ======================================================================================


@dataclass
class Record:
    """一个原始文件的登记 / 解析记录。"""

    root: str                       # "A" / "B"
    path: Path
    rel: str                        # 相对根目录的路径（as_posix）
    id: int = 0                     # question_source.id；dry-run 用序号占位
    file_name: str = ""
    file_ext: str = ""
    file_size: int = 0
    sha256: str = ""
    exam_code: Optional[str] = None
    subject_name: Optional[str] = None
    year: Optional[int] = None
    paper_name: str = ""
    doc_kind: str = "OTHER"
    text_layer: str = "NATIVE"
    parse_status: str = "PENDING"
    note: str = ""
    question_count: int = 0
    chunk_count: int = 0
    _text: Optional[str] = field(default=None, repr=False)  # 本次进程内缓存的正文


@dataclass
class Stats:
    """统计累加器（dry-run 的数字全部来自这里）。"""

    by_root: Counter = field(default_factory=Counter)
    by_ext: Counter = field(default_factory=Counter)
    by_exam: Counter = field(default_factory=Counter)
    by_kind: Counter = field(default_factory=Counter)
    by_layer: Counter = field(default_factory=Counter)
    by_status: Counter = field(default_factory=Counter)
    parse_questions: int = 0            # 真题册切出的题目数
    parse_interview_questions: int = 0  # 面试 md 生成的问答题数
    parse_chunks: int = 0               # 写 ai_chunk 的块数
    parse_interview_chunks: int = 0     # 其中面试 md 的块数
    parse_answer_items: int = 0         # 答案解析册切出的题号解析条数
    parse_questions_with_answer: int = 0     # 切出的题目中带正确答案的条数（绝不编造）
    parse_questions_with_analysis: int = 0   # 切出的题目中回填了解析的条数
    parse_files_parsed: int = 0
    parse_files_partial: int = 0
    parse_files_skipped: int = 0
    parse_files_failed: int = 0
    parse_ocr_queued: int = 0
    ocr_queue: List[Tuple[str, str]] = field(default_factory=list)
    failures: List[Tuple[str, str]] = field(default_factory=list)
    skipped_notes: Counter = field(default_factory=Counter)


# ======================================================================================
# 基础工具
# ======================================================================================


def log(msg: str) -> None:
    print(msg, flush=True)


def md5_hex(text: str) -> str:
    """ai_chunk.content_hash 是 CHAR(32)，与既有脚本口径一致用 MD5。"""
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    """只读分块计算 SHA256，作为内容变化判据。"""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def clean_text(text: str) -> str:
    """统一空白，去掉全角空格 / 多余空行，保留段落结构。"""
    text = text.replace("\u3000", " ").replace("\u00a0", " ").replace("\r\n", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n\n", text)
    return text.strip()


def read_text_file(path: Path) -> str:
    """读 utf-8 / gbk 文本（md、txt）。"""
    raw = path.read_bytes()
    for enc in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def docx_text(path: Path) -> str:
    """docx = zip + word/document.xml，用标准库解析，不依赖 python-docx。"""
    with zipfile.ZipFile(str(path)) as zf:
        xml_bytes = zf.read("word/document.xml")
    root = ET.fromstring(xml_bytes)
    ns = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    paragraphs: List[str] = []
    for para in root.iter(ns + "p"):
        runs = [node.text or "" for node in para.iter(ns + "t")]
        paragraphs.append("".join(runs))
    return "\n".join(paragraphs)


PDF_WORKER_TIMEOUT = 120
#: PDF 全文缓存目录：scripts/pdf_text_cache.py 并行预抽取，parse 阶段直接读缓存。
#: 大合集 PDF（300+ 页）单进程抽一次要几十秒，串行解析 1000+ 个文件会拖到数小时。
PDF_CACHE_DIR = Path(os.environ.get("QB_PDF_CACHE", "runtime/pdf_text_cache"))


def _pdf_cache_path(path: Path) -> Path:
    key = hashlib.md5(str(path.resolve()).encode("utf-8")).hexdigest()
    return PDF_CACHE_DIR / (key + ".txt")


def _pdf_text_inproc(path: Path, max_pages: Optional[int] = None) -> Tuple[str, int]:
    """进程内抽取（仅用于子进程 worker 模式）。"""
    if PdfReader is None:
        raise RuntimeError("pypdf 未安装，无法抽取 PDF 文本层")
    reader = PdfReader(str(path))
    total = len(reader.pages)
    idxs = range(total) if max_pages is None else range(min(max_pages, total))
    parts: List[str] = []
    for i in idxs:
        try:
            parts.append(reader.pages[i].extract_text() or "")
        except Exception:
            parts.append("")
    return "\n".join(parts), total


def pdf_text(path: Path, max_pages: Optional[int] = None) -> Tuple[str, int]:
    """pypdf 抽文本，**隔离到子进程**执行；返回 (文本, 总页数)。

    2026-11-11 实测：个别畸形 PDF 会让 pypdf 在 C 层崩溃（进程 EXIT=139 段错误），
    进程内 try/except 拦不住，会拖垮整批 1500 个文件的解析。
    改为子进程抽取后，单个坏文件只让子进程退出，主进程记 PARTIAL 继续。
    结果通过临时文件回传，避免 pypdf 的告警日志污染 stdout。
    """
    if os.environ.get("QB_PDF_INPROC") == "1":
        return _pdf_text_inproc(path, max_pages)
    if PdfReader is None:
        raise RuntimeError("pypdf 未安装，无法抽取 PDF 文本层")

    # 全文缓存命中直接返回（parallel 预抽取脚本写入，parse 阶段省掉重复解析）
    if max_pages is None:
        cache = _pdf_cache_path(path)
        if cache.exists():
            try:
                with open(cache, "r", encoding="utf-8", errors="replace") as fh:
                    payload = fh.read()
                first, _, rest = payload.partition("\n")
                return rest, int(first.strip() or 0)
            except (OSError, ValueError):
                pass

    tmp_fd, tmp_name = tempfile.mkstemp(suffix=".txt", prefix="qb_pdf_")
    os.close(tmp_fd)
    cmd = [sys.executable, str(Path(__file__).resolve()),
           "--pdf-text-worker", str(path), "--pdf-out", tmp_name]
    if max_pages is not None:
        cmd += ["--pdf-max-pages", str(max_pages)]
    try:
        # stdout/stderr 必须丢弃而不是管道：pypdf 对畸形 PDF 会刷大量
        # 「Ignoring wrong pointing object」告警，管道写满 64KB 后父子互等死锁
        # （实测每文件卡满 180s 超时）。结果统一走 --pdf-out 临时文件。
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL,
                              timeout=PDF_WORKER_TIMEOUT)
        # 先读结果：个别 PDF 会在「写完文件后」于解释器退出阶段崩溃，
        # 此时 rc 非 0 但文本已经拿到，不应因此丢题。
        payload = ""
        if os.path.exists(tmp_name):
            with open(tmp_name, "r", encoding="utf-8", errors="replace") as fh:
                payload = fh.read()
        if proc.returncode != 0 and not payload:
            raise RuntimeError("PDF 子进程异常 rc=%s" % proc.returncode)
    except subprocess.TimeoutExpired:
        raise RuntimeError("PDF 抽取超时（>%ds）" % PDF_WORKER_TIMEOUT)
    finally:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
    first, _, rest = payload.partition("\n")
    try:
        total = int(first.strip() or 0)
    except ValueError:
        total = 0
    if max_pages is None and total > 0:
        try:
            PDF_CACHE_DIR.mkdir(parents=True, exist_ok=True)
            cache = _pdf_cache_path(path)
            tmp_cache = cache.with_suffix(".tmp")
            with open(tmp_cache, "w", encoding="utf-8", errors="replace") as fh:
                fh.write(payload)
            os.replace(tmp_cache, cache)
        except OSError:
            pass
    return rest, total


def probe_pdf_layer(path: Path) -> Tuple[str, str]:
    """抽前 3 页判文本层：>100 字符/页 TEXT，>20 SPARSE，否则 SCAN。

    返回 (text_layer, 诊断 note)。读取异常一律按 SCAN 处理 —— 宁可进 OCR 队列，
    也绝不把读不出的 PDF 当成有文本层去造题。
    """
    try:
        text, total = pdf_text(path, max_pages=3)
        pages = min(3, max(1, total))
        per_page = len(text.strip()) / pages
        if per_page > 100:
            return "TEXT", ""
        if per_page > 20:
            return "SPARSE", "每页字符数偏低，切题可能不完整"
        return "SCAN", "pypdf 前 3 页文本为空/极少，判定为扫描件，登记 OCR_QUEUED"
    except Exception as exc:
        return "SCAN", "pypdf 读取异常(%s)，按扫描件处理" % type(exc).__name__


def chunk_text(text: str, title: str) -> List[str]:
    """按段落聚合切块，每块首行加《标题》（复用 ingest_courseware.py:230-296）。"""
    body = clean_text(text)
    if not body:
        return []
    units: List[str] = []
    for paragraph in body.split("\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if len(paragraph) <= CHUNK_TARGET:
            units.append(paragraph)
            continue
        stride = max(1, CHUNK_TARGET - CHUNK_OVERLAP)
        for start in range(0, len(paragraph), stride):
            piece = paragraph[start:start + CHUNK_TARGET]
            if piece.strip():
                units.append(piece.strip())

    bodies: List[str] = []
    buffer = ""
    for unit in units:
        if not buffer:
            buffer = unit
        elif len(buffer) + 1 + len(unit) <= CHUNK_TARGET:
            buffer = f"{buffer}\n{unit}"
        else:
            bodies.append(buffer)
            tail = buffer[-CHUNK_OVERLAP:] if CHUNK_OVERLAP > 0 else ""
            buffer = f"{tail}\n{unit}" if tail else unit
    if buffer.strip():
        bodies.append(buffer)

    if len(bodies) >= 2 and len(bodies[-1]) < CHUNK_MIN_TAIL:
        bodies[-2] = f"{bodies[-2]}\n{bodies[-1]}"
        bodies.pop()

    prefix = f"《{title}》"
    return [f"{prefix}\n{b.strip()}" for b in bodies if b.strip()]


# ======================================================================================
# 路径规则：推断 exam_code / year / doc_kind / text_layer / parse_status
# ======================================================================================


_SET_RE = re.compile(r"第\s*([一二三四五六七八九十\d])\s*套")


def paper_set_token(name: str, parent: str = "") -> str:
    """提取套号（第1套/第一套），用于把答案册与**同套**试卷配对。

    2026-11-11 修正：CET / 考研英语同一考次有 3 套卷，题号相同但答案不同。
    原实现只按 (exam_code, year) 分组回填，会把第 1 套的答案套到第 2 套的题上，
    产生「看起来有答案、其实是错答案」的脏数据。改为套号分组后，
    拿不到同套答案的题目宁可留空（不参与自动判分），也不猜。
    """
    for source in (name or "", parent or ""):
        m = _SET_RE.search(source)
        if m:
            token = m.group(1)
            return {"一": "1", "二": "2", "三": "3", "四": "4", "五": "5"}.get(token, token)
    return ""


def guess_year(name: str, rel: str) -> Tuple[Optional[int], str]:
    """先从文件名、再从路径取 19xx/20xx 年份；多命中取起始年并标注。"""
    hits = YEAR_RE.findall(name)
    note = ""
    if not hits:
        hits = YEAR_RE.findall(rel)
    if not hits:
        return None, "年份不可解析"
    years = [int(h) for h in hits]
    if len(set(years)) > 1:
        note = "年份区间/多命中，取起始年 %d" % years[0]
    return years[0], note


def guess_exam_code(root: str, rel: str, name: str, parts: Sequence[str]) -> Tuple[Optional[str], str]:
    """按顶层目录 + 文件名关键词推断考试编码；无法细分级别时返回 None（不硬塞）。"""
    if root == "A":
        return "INTERVIEW", ""
    top = parts[0] if parts else ""
    full = rel
    if "答题卡" in name:
        return None, "数学一二三共用答题卡，exam_code 留空"
    if "申论" in full:
        return "GUOKAO_SHENLUN", ""
    if "国考" in full or "公务员" in full or "行测" in full:
        return "GUOKAO", ""
    if "考研数学" in top:
        if "数学二" in top:
            return "POSTGRAD_MATH2", ""
        if "数学三" in top:
            return "POSTGRAD_MATH3", ""
        return "POSTGRAD_MATH1", ""
    if "考研英语" in top or "英语（一）" in top or "英语（二）" in top:
        if "（二）" in top or "英语二" in top:
            return "POSTGRAD_ENG2", ""
        return "POSTGRAD_ENG1", ""
    # 四六级：文件名优先（"四六级" 合集目录里各册文件名仍带具体级别）
    if "六级" in name or "CET6" in name.upper():
        return "CET6", ""
    if "四级" in name or "CET4" in name.upper():
        return "CET4", ""
    if "六级" in full:
        return "CET6", ""
    if "四级" in full:
        return "CET4", ""
    return None, "四六级合集/通用资料，级别未细分，exam_code 留空"


def guess_doc_kind(root: str, rel: str, name: str, ext: str) -> str:
    """推断文档类别（PAPER/ANSWER/ANALYSIS/VOCAB/TEMPLATE/AUDIO/POST_TABLE/INTERVIEW_KB/OTHER）。"""
    if root == "A":
        if ext in TEXT_EXTS or ext == ".pdf":
            return "INTERVIEW_KB"
        return "OTHER"

    if ext in AUDIO_EXTS:
        return "AUDIO"
    if ext in TABLE_EXTS or (ext == ".xls" and ("岗位表" in rel or "职位表" in rel)):
        return "POST_TABLE"
    if ext in ARCHIVE_EXTS or ext in IMAGE_EXTS:
        return "OTHER"
    if "答题卡" in name:
        return "OTHER"
    if "词汇" in name or "词表" in name or "大纲词" in name or "核心词" in name or "单词" in name:
        return "VOCAB"
    if "模板" in name or "模版" in name or "句型" in name or "范文" in name:
        return "TEMPLATE"
    if "解析" in name:
        return "ANALYSIS"
    if "答案" in name:
        return "ANSWER"
    if "真题" in name or "试卷" in name or "试题" in name or "题册" in name:
        return "PAPER"
    return "OTHER"


def guess_topic(rel: str, name: str) -> str:
    """从所在目录名（或文件名）提取专题名，作为面试问答题的 tags。"""
    for seg in list(Path(rel).parts):
        m = re.search(r"--\s*([^\s/\\]+?篇)", seg)
        if m:
            return m.group(1)[:40]
    parts = list(Path(rel).parts)
    parent = parts[-2] if len(parts) >= 2 else ""
    topic = TITLE_PREFIX_RE.sub("", parent).strip()
    return (topic or "AI大模型")[:40]


def clean_title(name: str) -> str:
    """文件名去扩展名、去多层数字前缀，得到可读标题。"""
    stem = Path(name).stem
    for _ in range(3):
        new = TITLE_PREFIX_RE.sub("", stem, count=1).strip()
        if new == stem or not new:
            break
        stem = new
    return stem.strip() or Path(name).stem


def build_record(root: str, path: Path, root_path: Path) -> Record:
    """扫描阶段：构造一条 question_source 记录（含 sha256 与 PDF 文本层探测）。"""
    rel = path.relative_to(root_path).as_posix()
    parts = tuple(Path(rel).parts)
    name = path.name
    ext = path.suffix.lower()
    size = path.stat().st_size
    sha = sha256_file(path)

    exam_code, exam_note = guess_exam_code(root, rel, name, parts)
    year, year_note = (2026, "面试资料固定按 2026 版登记") if root == "A" else guess_year(name, rel)
    doc_kind = guess_doc_kind(root, rel, name, ext)

    notes: List[str] = [n for n in (exam_note, year_note) if n]
    layer_note = ""
    if ext == ".pdf":
        text_layer, layer_note = probe_pdf_layer(path)
    else:
        text_layer = "NATIVE"
    if layer_note:
        notes.append(layer_note)

    # ---- 登记阶段即定下 parse_status：跳过的类型绝不进入 parse 造题 ----
    status = "PENDING"
    if root == "A":
        if ext == ".md":
            pass  # 634 篇面试 md 由 parse 阶段做实
        elif ext == ".docx":
            status = "SKIPPED"
            notes.append("与 md 同内容的含图镜像；正文语料由 md 分支统一处理，"
                         "含图版本另由 ai-service/tools/ingest_courseware.py 负责，避免重复造题")
        elif text_layer == "SCAN":
            status = "OCR_QUEUED"
        elif ext in IMAGE_EXTS or ext in ARCHIVE_EXTS or ext in {".ppt", ".pptx", ".svg", ".gif"}:
            status = "SKIPPED"
            notes.append("配图 / 二进制课件资源，不做文本解析")
    else:
        if doc_kind == "AUDIO":
            status = "SKIPPED"
            notes.append("听力音频，无文本层，登记为 AUDIO 资源，不造题")
        elif doc_kind == "POST_TABLE":
            status = "SKIPPED"
            notes.append("国考岗位表 / 分数线（不是题目），按契约 SKIPPED")
        elif ext in ARCHIVE_EXTS:
            status = "SKIPPED"
            notes.append("压缩包未展开，按契约 SKIPPED")
        elif ext in IMAGE_EXTS:
            status = "SKIPPED"
            notes.append("图片资源，按契约 SKIPPED")
        elif text_layer == "SCAN":
            # 扫描件判定必须优先于 doc_kind 兜底：不论判成 PAPER/ANALYSIS/OTHER，
            # 只要 pypdf 抽不出文本层，一律登记 OCR_QUEUED，绝不进入造题链路。
            status = "OCR_QUEUED"
        elif ext in (".doc", ".xls"):
            status = "SKIPPED"
            notes.append("legacy .doc/.xls 二进制，无 zip/XML 文本层，按契约 SKIPPED")
        elif doc_kind == "OTHER" and any(k in name for k in ("答题卡", "说明", "提示")):
            status = "SKIPPED"
            notes.append("说明/提示/答题卡，非题目语料，按契约 SKIPPED")
        else:
            # 其余文本型文件（含 doc_kind=OTHER 的 pdf/docx/txt）交 parse 兜底写资料 chunk，
            # 保证「全量登记并尽可能结构化入库」，不落进 SKIPPED 黑洞。
            pass

    if status == "PENDING" and doc_kind == "INTERVIEW_KB" and ext == ".pdf":
        notes.append("面试资料 PDF 镜像，仅切资料 chunk，不强行造题")

    return Record(
        root=root,
        path=path,
        rel=rel,
        file_name=name,
        file_ext=ext,
        file_size=size,
        sha256=sha,
        exam_code=exam_code,
        subject_name=SUBJECT_NAME.get(exam_code or ""),
        year=year,
        paper_name=clean_title(name)[:300],
        doc_kind=doc_kind,
        text_layer=text_layer,
        parse_status=status,
        note="; ".join(notes)[:500],
    )


def collect_files(root_sel: str) -> List[Tuple[str, Path, Path]]:
    """返回 [(root标签, 绝对路径, 根目录)]，排序保证可复现。"""
    roots: List[Tuple[str, Path]] = []
    if root_sel in ("A", "ALL"):
        roots.append(("A", ROOT_A))
    if root_sel in ("B", "ALL"):
        roots.append(("B", ROOT_B))
    out: List[Tuple[str, Path, Path]] = []
    for label, root_path in roots:
        if not root_path.is_dir():
            raise FileNotFoundError("资料目录不存在：%s" % root_path)
        for p in sorted(root_path.rglob("*"), key=lambda x: x.as_posix()):
            if p.is_file():
                out.append((label, p, root_path))
    return out


# ======================================================================================
# 数据库
# ======================================================================================


def load_db_password() -> str:
    """口令只从环境变量或 ai-service/.env 的 DB_PASSWORD 读取，绝不硬编码。"""
    env_pw = os.environ.get("DB_PASSWORD")
    if env_pw:
        return env_pw
    if ENV_FILE.is_file():
        for line in ENV_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() == "DB_PASSWORD":
                return value.strip().strip('"').strip("'")
    return ""


def db_connect():  # type: ignore[no-untyped-def]
    if pymysql is None:
        raise RuntimeError("pymysql 未安装，无法写库（dry-run 不需要）")
    password = load_db_password()
    if not password:
        raise RuntimeError("未读取到 DB_PASSWORD（检查 ai-service/.env 或环境变量）")
    return pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=password,
        database=os.getenv("DB_NAME", "exam_prep"),
        charset="utf8mb4",
        autocommit=False,
    )


def table_columns(conn, table: str) -> Set[str]:
    """运行时探测表列 —— 契约列不存在就跳过，不因缺列整体失败。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s",
            (table,),
        )
        return {row[0] for row in cur.fetchall()}


def ensure_subject(conn, exam_code: Optional[str], subject_name: Optional[str]) -> Tuple[int, int]:
    """按 exam_code / 科目名找到或建立 exam_category + subject，返回 (subject_id, exam_category_id)。

    question.subject_id 非空约束要求必须有科目；subject 表 name 无唯一索引，
    故用 SELECT + INSERT 而非 ON DUPLICATE KEY UPDATE。
    """
    code = exam_code or "SCHOOL"
    name = subject_name or "未分类导入资料"
    label = EXAM_LABEL.get(exam_code or "", "导入资料")
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO exam_category (code, name, type, description, sort, status) "
            "VALUES (%s, %s, 'CERT', %s, %s, 1) "
            "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            (code, label, "题库导入流水线自动建立：%s" % label, 90),
        )
        cur.execute("SELECT id FROM exam_category WHERE code = %s LIMIT 1", (code,))
        row = cur.fetchone()
        category_id = int(row[0])

        cur.execute("SELECT id FROM subject WHERE name = %s LIMIT 1", (name,))
        row = cur.fetchone()
        if row:
            subject_id = int(row[0])
        else:
            cur.execute(
                "INSERT INTO subject (exam_category_id, name, description, sort, status) "
                "VALUES (%s, %s, %s, %s, 1)",
                (category_id, name, "题库导入流水线自动建立", 900),
            )
            subject_id = int(cur.lastrowid)
    return subject_id, category_id


# ======================================================================================
# register 阶段写库
# ======================================================================================

REGISTER_SQL = """
INSERT INTO question_source
  (source_path, file_name, file_ext, file_size, sha256, exam_code, subject_name,
   year, paper_name, doc_kind, text_layer, parse_status, note)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
ON DUPLICATE KEY UPDATE
  file_name = VALUES(file_name),
  file_ext = VALUES(file_ext),
  file_size = VALUES(file_size),
  sha256 = VALUES(sha256),
  exam_code = VALUES(exam_code),
  subject_name = VALUES(subject_name),
  year = VALUES(year),
  paper_name = VALUES(paper_name),
  doc_kind = VALUES(doc_kind),
  text_layer = VALUES(text_layer),
  note = VALUES(note),
  parse_status = IF(VALUES(sha256) <> `sha256`
                    AND `parse_status` IN ('PARSED','PARTIAL','FAILED'),
                    'PENDING', `parse_status`)
"""


def register_rows(conn, records: Sequence[Record]) -> int:
    """批量 upsert，返回提交行数。"""
    rows = [
        (
            r.path.as_posix(), r.file_name, r.file_ext, r.file_size, r.sha256,
            r.exam_code, r.subject_name, r.year, r.paper_name, r.doc_kind,
            r.text_layer, r.parse_status, r.note,
        )
        for r in records
    ]
    affected = 0
    with conn.cursor() as cur:
        for start in range(0, len(rows), BATCH_ROWS):
            batch = rows[start:start + BATCH_ROWS]
            cur.executemany(REGISTER_SQL, batch)
            affected += len(batch)
    conn.commit()
    return affected


# ======================================================================================
# 切题规则（稳健优先：抽不出就记 PARTIAL，绝不硬凑）
# ======================================================================================


def strip_page_noise(text: str) -> str:
    """去掉纯页码行、页眉页脚，避免题号误切。"""
    lines = [ln for ln in text.splitlines() if not re.fullmatch(r"\s*\d{1,3}\s*", ln)]
    return "\n".join(lines)


def split_questions(text: str) -> List[Tuple[int, str]]:
    """按行首题号切分题目块；跳过例题区，章节标题截断上一题尾巴。"""
    body = strip_page_noise(text)
    m = re.search(r"请开始答题|开始答题", body)
    if m:
        body = body[m.end():]
    matches = list(QNO_RE.finditer(body))
    out: List[Tuple[int, str]] = []
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        block = body[match.end():end]
        block = SECTION_RE.split(block)[0].strip()
        if len(block) >= 10:
            out.append((int(match.group(1)), block))
    return out


def extract_options(block: str) -> Optional[Tuple[str, List[Tuple[str, str]]]]:
    """在题目块里找连续 A→B→C→D 选项序列，返回 (题干, [(A..D, 内容)])。

    支持选项挤在同一行（国考行测真题常见）、分行、英文 [A] 形式。
    找不到完整 ABCD 序列返回 None —— 这类块多为阅读材料/完形填空，不硬凑成选择题。
    """
    marks: List[Tuple[int, int, str]] = []
    for m in OPT_RE.finditer(block):
        key = m.group(1) or m.group(2)
        marks.append((m.start(), m.end(), key))
    for i in range(len(marks) - 3):
        keys = [marks[i + j][2] for j in range(4)]
        if keys != ["A", "B", "C", "D"]:
            continue
        stem = block[:marks[i][0]].strip()
        if len(stem) < 8:
            continue
        options: List[Tuple[str, str]] = []
        for j in range(4):
            s = marks[i + j][1]
            e = marks[i + j + 1][0] if j < 3 else len(block)
            content = clean_text(block[s:e]).replace("\n", " ")[:480]
            if not content:
                break
            options.append((keys[j], content))
        if len(options) == 4:
            return clean_text(stem), options
    return None


def parse_answer_book(text: str) -> Dict[int, Tuple[Optional[str], str]]:
    """答案解析册按题号切分：{题号: (答案字母或 None, 解析文本)}。

    样本格式：``1.A【解析】这段话的主旨在于……`` / ``2.C【解析】……``
    """
    body = strip_page_noise(text)
    matches = list(QNO_RE.finditer(body))
    out: Dict[int, Tuple[Optional[str], str]] = {}
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        block = body[match.end():end]
        am = re.match(r"\s*([A-D])(?![A-Za-z])", block)
        answer = am.group(1) if am else None
        analysis = clean_text(block)[:2000]
        if analysis:
            out[int(match.group(1))] = (answer, analysis)
    return out


def interview_sections(raw: str) -> List[Tuple[str, str]]:
    """按 ## / ### 小节标题切分面试 md 正文，供生成 1~3 条追问。"""
    pieces = re.split(r"(?m)^(#{2,3}\s+.+)$", raw)
    sections: List[Tuple[str, str]] = []
    head = ""
    buf: List[str] = []
    for piece in pieces:
        hm = re.match(r"#{2,3}\s+(.+)", piece.strip())
        if hm:
            if head and buf:
                sections.append((head, clean_text("\n".join(buf))))
            head, buf = hm.group(1).strip(), []
        else:
            buf.append(piece)
    if head and buf:
        sections.append((head, clean_text("\n".join(buf))))
    return sections


def parse_interview_md(rec: Record, raw: str) -> Tuple[List[Dict[str, Any]], List[str], str]:
    """面试 md → (问答题列表, 切块列表, 状态)。

    * 占位页（<500B 或含「本篇在原知识库中为空文档」）→ SKIPPED，注明原因；
    * 有正文 → 1~3 条 SHORT_ANSWER 问答题 + 全文切块，块首加《标题》。
    """
    title_match = re.search(r"(?m)^#\s+(.+)$", raw)
    title_raw = title_match.group(1).strip() if title_match else clean_title(rec.file_name)
    title = TITLE_PREFIX_RE.sub("", title_raw, count=1).strip() or title_raw

    url_match = re.search(r"原文：\s*(\S+)", raw)
    origin = url_match.group(1) if url_match else ""

    body_lines: List[str] = []
    for line in raw.splitlines():
        s = line.strip()
        if s == "# " + title_raw:
            continue
        if s.startswith("> 原文") or s.startswith("> 更新") or s.startswith("> ⚠"):
            continue
        if s.startswith("> ") and "yuque.com" in s:
            continue
        body_lines.append(line)
    body = "\n".join(body_lines)
    body = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", body)   # markdown 图片
    body = re.sub(r"<[^>]+>", "", body)                 # HTML 标签
    body = clean_text(body)

    placeholder = (
        rec.file_size < 500
        or "本篇在原知识库中为空文档" in raw
        or len(body) < 100
    )
    if placeholder:
        return [], [], "SKIPPED"

    chunks = chunk_text(body, title)
    topic = guess_topic(rec.rel, rec.file_name)
    tags = f"AI大模型面试,{topic},模拟面试"[:255]
    source = f"2026年 AI大模型面试 · {rec.file_name}"[:100]
    analysis = ("原文出处：%s" % origin) if origin else "原文出处：图灵AI大模型面试核心点(2026版)"

    def reference(text: str, lo: int = 800, hi: int = 1500) -> str:
        ref = text[:hi]
        if len(ref) > lo:
            cut = max(ref.rfind("。", lo, hi), ref.rfind("\n", lo, hi))
            if cut > lo:
                ref = ref[:cut + 1]
        return ref.strip() or text[:lo]

    questions: List[Dict[str, Any]] = []
    questions.append({
        "stem": f"请简述「{title}」的核心内容与要点。",
        "reference": reference(body),
    })

    # 有小节标题才追加追问，最多 2 条；小节正文过短则不硬凑
    sections = interview_sections(raw)
    for head, section in sections[:2]:
        if len(section) < 80:
            continue
        questions.append({
            "stem": f"关于「{title}」，请说明：{head}？"[:1000],
            "reference": reference(section, 600, 1200),
        })

    return questions, chunks, "PARSED"


# ======================================================================================
# parse 阶段写库：question / option / answer / ai_chunk
# ======================================================================================


def insert_question(conn, q: Dict[str, Any], qcols: Set[str]) -> int:
    """写一道题 + 选项 + 答案；已存在则整体跳过。返回新增题目数（0 或 1）。

    显式事务由调用方包裹；question 与 option/answer 要么全写要么全不写。
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id FROM question WHERE source = %s AND type = %s AND stem = %s LIMIT 1",
            (q["source"], q["type"], q["stem"]),
        )
        if cur.fetchone():
            return 0

        base: Dict[str, Any] = {
            "subject_id": q["subject_id"],
            "type": q["type"],
            "stem": q["stem"],
            "material": q.get("material"),
            "difficulty": q.get("difficulty", 3),
            "source": q["source"],
            "tags": q.get("tags"),
            "analysis": q.get("analysis"),
            "score": q.get("score", 1),
            "parent_id": 0,
            "sort": q.get("sort", 0),
            "audit_status": "PENDING",
        }
        # 契约列：运行时探测，缺失就跳过，不因此整体失败
        for col in CONTRACT_QUESTION_COLS:
            if col in qcols and col in q and q[col] is not None:
                base[col] = q[col]

        cols = list(base.keys())
        placeholders = ",".join(["%s"] * len(cols))
        cur.execute(
            "INSERT INTO question (%s) VALUES (%s)" % (",".join("`%s`" % c for c in cols), placeholders),
            [base[c] for c in cols],
        )
        qid = int(cur.lastrowid)

        for key, content in q.get("options", []):
            cur.execute(
                "INSERT INTO question_option (question_id, option_key, content, sort, is_answer) "
                "VALUES (%s, %s, %s, %s, %s)",
                (qid, key, content, ord(key) - ord("A"), 1 if key == q.get("answer") else 0),
            )
        answer_content = {"reference": q.get("reference", ""), "keywords": q.get("keywords", [])}
        cur.execute(
            "INSERT INTO question_answer "
            "(question_id, answer_content, grading_points, case_sensitive) "
            "VALUES (%s, %s, %s, 0) "
            "ON DUPLICATE KEY UPDATE "
            " answer_content = VALUES(answer_content), grading_points = VALUES(grading_points)",
            (qid, json.dumps(answer_content, ensure_ascii=False),
             json.dumps(q.get("grading_points", []), ensure_ascii=False)),
        )
    return 1


def upsert_chunks(conn, rec: Record, chunks: Sequence[str], subject_id: int,
                  category_id: int, has_vector_col: bool) -> int:
    """批量写 ai_chunk，按 uk_source_chunk 幂等；返回块数（不是影响行数）。"""
    if not chunks:
        return 0
    src_id = CHUNK_SOURCE_OFFSET + rec.id
    cols = [
        "source_type", "source_id", "chunk_no", "title", "content",
        "exam_category_id", "subject_id", "knowledge_id", "difficulty",
        "ref_type", "ref_id", "page_no", "content_hash", "source_updated_at", "status",
    ]
    if has_vector_col:
        cols.append("vector_synced")
    placeholders = "(" + ",".join(["%s"] * len(cols)) + ")"
    now = datetime.now()
    rows: List[List[Any]] = []
    for chunk_no, content in enumerate(chunks):
        row: List[Any] = [
            CHUNK_SOURCE_TYPE, src_id, chunk_no, rec.paper_name[:200], content,
            category_id, subject_id, None, 3,
            CHUNK_REF_TYPE, src_id, None, md5_hex(content), now, "ACTIVE",
        ]
        if has_vector_col:
            row.append(0)
        rows.append(row)

    update = (
        "title = VALUES(title), content = VALUES(content), "
        "content_hash = VALUES(content_hash), source_updated_at = VALUES(source_updated_at), "
        "status = 'ACTIVE'" + (", vector_synced = 0" if has_vector_col else "")
    )
    with conn.cursor() as cur:
        for start in range(0, len(rows), BATCH_ROWS):
            batch = rows[start:start + BATCH_ROWS]
            # 每批拼一条多 VALUES 的 INSERT，避免 6000+ 次单行往返
            multi = ",".join([placeholders] * len(batch))
            cur.execute(
                "INSERT INTO ai_chunk (%s) VALUES %s ON DUPLICATE KEY UPDATE %s" % (
                    ",".join("`%s`" % c for c in cols), multi, update,
                ),
                [v for row in batch for v in row],
            )
    return len(chunks)


def update_source_status(conn, rec_id: int, status: str, qcount: int, ccount: int, note: str) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE question_source SET parse_status = %s, question_count = %s, "
            "chunk_count = %s, note = %s, updated_at = NOW() WHERE id = %s",
            (status, qcount, ccount, note[:500], rec_id),
        )


def mark_failed(conn, rec_id: int, reason: str) -> None:
    """失败单独提交，不打断全批；状态置 FAILED，下次可断点续跑重试。"""
    try:
        conn.begin()
        update_source_status(conn, rec_id, "FAILED", 0, 0, "解析失败：%s" % reason[:400])
        conn.commit()
    except Exception:
        conn.rollback()


# ======================================================================================
# 阶段实现
# ======================================================================================


def accumulate_register_stats(st: Stats, rec: Record) -> None:
    st.by_root[rec.root] += 1
    st.by_ext[rec.file_ext or "(无扩展名)"] += 1
    st.by_exam[rec.exam_code or "NULL(未识别)"] += 1
    st.by_kind[rec.doc_kind] += 1
    st.by_layer[rec.text_layer] += 1
    st.by_status[rec.parse_status] += 1
    if rec.parse_status == "OCR_QUEUED":
        st.parse_ocr_queued += 1
        st.ocr_queue.append((rec.path.as_posix(), rec.note))
    if rec.parse_status == "SKIPPED":
        st.skipped_notes[rec.note[:60]] += 1


def stage_register(cfg: argparse.Namespace, conn, st: Stats) -> List[Record]:
    """register：全量登记，逐文件容错，批量事务提交。"""
    files = collect_files(cfg.root)
    if cfg.limit:
        files = files[:cfg.limit]
    log("[register] 待登记文件数：%d（root=%s, limit=%s）" % (
        len(files), cfg.root, cfg.limit or "无"))

    records: List[Record] = []
    pending: List[Record] = []
    done = 0
    for label, path, root_path in files:
        try:
            rec = build_record(label, path, root_path)
            rec.id = len(records) + 1  # dry-run 占位；真实 id 由 DB 分配
            records.append(rec)
            accumulate_register_stats(st, rec)
            pending.append(rec)
            if not cfg.dry_run and len(pending) >= BATCH_ROWS:
                register_rows(conn, pending)
                pending = []
        except Exception as exc:
            reason = "%s: %s" % (type(exc).__name__, exc)
            st.failures.append((path.as_posix(), reason))
            log("[register] 单文件失败（跳过，继续）：%s -> %s" % (path.as_posix(), reason))
        done += 1
        if done % 1000 == 0:
            log("[register] 已扫描 %d / %d" % (done, len(files)))

    if not cfg.dry_run and pending:
        register_rows(conn, pending)

    log("[register] 完成：成功 %d，失败 %d，OCR_QUEUED %d" % (
        len(records), len(st.failures), st.parse_ocr_queued))
    return records


def extract_record_text(rec: Record) -> str:
    """按扩展名抽正文，进程内缓存，避免同一 PDF 抽两遍。"""
    if rec._text is not None:
        return rec._text
    text = ""
    if rec.file_ext == ".pdf":
        text, _ = pdf_text(rec.path)
    elif rec.file_ext == ".docx":
        text = docx_text(rec.path)
    elif rec.file_ext in (".md", ".txt"):
        text = read_text_file(rec.path)
    rec._text = clean_text(text)
    return rec._text


def make_source(rec: Record) -> str:
    label = EXAM_LABEL.get(rec.exam_code or "", rec.exam_code or "导入资料")
    prefix = "%d年 " % rec.year if rec.year else ""
    return f"{prefix}{label} · {rec.file_name}"[:100]


def process_record(cfg: argparse.Namespace, conn, rec: Record,
                   answer_map: Dict[int, Tuple[Optional[str], str]],
                   qcols: Set[str], has_vector_col: bool, st: Stats) -> None:
    """处理单个文件的 parse：一个显式事务包住全部写入。"""
    dry = cfg.dry_run

    # ---------- 面试 md：做实问答 + chunk ----------
    if rec.doc_kind == "INTERVIEW_KB" and rec.file_ext == ".md":
        raw = read_text_file(rec.path)
        questions, chunks, status = parse_interview_md(rec, raw)
        if status == "SKIPPED":
            rec.parse_status = "SKIPPED"
            rec.note = ("占位页/正文过短（仅标题+原文链接，或标注为空文档），"
                        "不生成问答题与 chunk")[:500]
            st.parse_files_skipped += 1
            st.skipped_notes[rec.note[:60]] += 1
            if not dry:
                conn.begin()
                update_source_status(conn, rec.id, rec.parse_status, 0, 0, rec.note)
                conn.commit()
            return

        qdicts: List[Dict[str, Any]] = []
        subject_id, category_id = (0, 0) if dry else ensure_subject(conn, rec.exam_code, rec.subject_name)
        for i, q in enumerate(questions):
            qdicts.append({
                "subject_id": subject_id,
                "type": QTYPE_SHORT,
                "stem": q["stem"],
                "source": make_source(rec),
                "tags": f"AI大模型面试,{guess_topic(rec.rel, rec.file_name)},模拟面试"[:255],
                "analysis": "原文出处：%s" % (re.search(r"原文：\s*(\S+)", raw).group(1)
                                            if re.search(r"原文：\s*(\S+)", raw)
                                            else "图灵AI大模型面试核心点(2026版)"),
                "difficulty": 3,
                "score": 5,
                "sort": i + 1,
                "reference": q["reference"],
                "keywords": [],
                "year": 2026,
                "exam_code": "INTERVIEW",
                "paper_name": rec.paper_name,
                "origin_path": rec.path.as_posix()[:1000],
            })

        if not dry:
            conn.begin()
            try:
                written = 0
                for q in qdicts:
                    written += insert_question(conn, q, qcols)
                upsert_chunks(conn, rec, chunks, subject_id, category_id, has_vector_col)
                update_source_status(conn, rec.id, "PARSED", written, len(chunks), rec.note)
                conn.commit()
            except Exception:
                conn.rollback()
                raise
        st.parse_interview_questions += len(qdicts)
        st.parse_questions += len(qdicts)
        st.parse_chunks += len(chunks)
        st.parse_interview_chunks += len(chunks)
        st.parse_files_parsed += 1
        rec.question_count, rec.chunk_count = len(qdicts), len(chunks)
        rec.parse_status = "PARSED"
        return

    # ---------- 答案解析册：按题号切解析，填 answer_map，解析文本切资料 chunk ----------
    if rec.doc_kind in ("ANSWER", "ANALYSIS"):
        text = extract_record_text(rec)
        items = parse_answer_book(text)
        for qno, value in items.items():
            answer_map.setdefault(qno, value)  # 同年多卷冲突时保留首个，不臆测
        chunks = chunk_text(text, rec.paper_name)
        if not dry:
            subject_id, category_id = ensure_subject(conn, rec.exam_code, rec.subject_name)
            conn.begin()
            try:
                upsert_chunks(conn, rec, chunks, subject_id, category_id, has_vector_col)
                note = (rec.note + "; 按题号切出 %d 条解析用于回填 question.analysis" % len(items))[:500]
                update_source_status(conn, rec.id, "PARSED" if items else "PARTIAL", 0, len(chunks), note)
                conn.commit()
            except Exception:
                conn.rollback()
                raise
        st.parse_answer_items += len(items)
        st.parse_chunks += len(chunks)
        st.parse_files_parsed += 1
        rec.chunk_count, rec.parse_status = len(chunks), ("PARSED" if items else "PARTIAL")
        return

    # ---------- 真题册：规则切单选题 ----------
    if rec.doc_kind == "PAPER" and rec.text_layer in ("TEXT", "SPARSE", "NATIVE"):
        text = extract_record_text(rec)
        blocks = split_questions(text)
        qdicts = []
        partial_reason = ""
        for qno, block in blocks:
            parsed = extract_options(block)
            if not parsed:
                continue
            stem, options = parsed
            answer, analysis = answer_map.get(qno, (None, ""))
            qdicts.append({
                "subject_id": 0,  # 非 dry-run 时下面统一解析
                "type": QTYPE_SINGLE,
                "stem": stem[:60000],
                "source": make_source(rec),
                "tags": f"{EXAM_LABEL.get(rec.exam_code or '', '真题')},真题,{rec.doc_kind}"[:255],
                "analysis": analysis or None,
                "difficulty": 3,
                "score": 1,
                "sort": qno,
                "options": options,
                "answer": answer,
                "year": rec.year,
                "exam_code": rec.exam_code,
                "paper_name": rec.paper_name,
                "origin_path": rec.path.as_posix()[:1000],
            })
        if not qdicts:
            partial_reason = "按题号切分后未找到完整 A/B/C/D 选项序列，未造题"
        elif not any(q["answer"] for q in qdicts):
            partial_reason = "切出题目但同组答案解析册未匹配到答案，is_answer 全为 0，不编造答案"

        if not dry:
            subject_id, category_id = ensure_subject(conn, rec.exam_code, rec.subject_name)
            for q in qdicts:
                q["subject_id"] = subject_id
            conn.begin()
            try:
                written = 0
                for q in qdicts:
                    written += insert_question(conn, q, qcols)
                status = "PARTIAL" if partial_reason else "PARSED"
                note = (rec.note + "; " + partial_reason if partial_reason else
                        rec.note + "; 切出 %d 道单选题" % written)[:500]
                update_source_status(conn, rec.id, status, written, 0, note)
                conn.commit()
            except Exception:
                conn.rollback()
                raise
        st.parse_questions += len(qdicts)
        st.parse_questions_with_answer += sum(1 for q in qdicts if q["answer"])
        st.parse_questions_with_analysis += sum(1 for q in qdicts if q["analysis"])
        rec.question_count = len(qdicts)
        if partial_reason:
            st.parse_files_partial += 1
            rec.parse_status = "PARTIAL"
            rec.note = (rec.note + "; " + partial_reason)[:500]
        else:
            st.parse_files_parsed += 1
            rec.parse_status = "PARSED"
        return

    # ---------- 词汇 / 模板 / 面试 PDF 镜像 / docx / txt：资料 chunk，不强行造题 ----------
    # 兜底覆盖所有「有文本层的文本型文件」，保证 PENDING 记录都有归宿，不落进 SKIPPED 黑洞。
    if rec.file_ext in (".pdf", ".docx", ".md", ".txt") and rec.text_layer != "SCAN":
        text = extract_record_text(rec)
        chunks = chunk_text(text, rec.paper_name)
        if not dry:
            subject_id, category_id = ensure_subject(conn, rec.exam_code, rec.subject_name)
            conn.begin()
            try:
                upsert_chunks(conn, rec, chunks, subject_id, category_id, has_vector_col)
                note = (rec.note + "; 登记为资料 chunk，不强行造题")[:500]
                update_source_status(conn, rec.id,
                                     "PARSED" if chunks else "PARTIAL",
                                     0, len(chunks), note)
                conn.commit()
            except Exception:
                conn.rollback()
                raise
        st.parse_chunks += len(chunks)
        rec.chunk_count, rec.parse_status = len(chunks), ("PARSED" if chunks else "PARTIAL")
        if chunks:
            st.parse_files_parsed += 1
        else:
            st.parse_files_partial += 1
        return

    # ---------- 其余：登记为 SKIPPED 并注明原因 ----------
    rec.parse_status = "SKIPPED"
    rec.note = (rec.note or "无可解析文本层/题型未覆盖，按契约跳过")[:500]
    st.parse_files_skipped += 1
    st.skipped_notes[rec.note[:60]] += 1
    if not dry:
        conn.begin()
        update_source_status(conn, rec.id, "SKIPPED", 0, 0, rec.note)
        conn.commit()


def load_parse_records(cfg: argparse.Namespace, conn, register_records: List[Record]) -> List[Record]:
    """parse 的输入：
    * dry-run：直接复用 register 阶段扫描出的记录（不连库、状态按规则重算）；
    * 正常：从 question_source 读 PENDING/PARTIAL/FAILED（断点续跑）。
    """
    if cfg.dry_run:
        # 单独跑 --stage parse --dry-run 时没有 register 结果，自己扫描一遍，
        # 保证「每阶段独立可重跑」；--stage all 则直接复用 register 的内存结果。
        if not register_records:
            files = collect_files(cfg.root)
            if cfg.limit:
                files = files[:cfg.limit]
            for i, (label, path, root_path) in enumerate(files, start=1):
                try:
                    rec = build_record(label, path, root_path)
                    rec.id = i
                    register_records.append(rec)  # type: ignore[union-attr]
                except Exception as exc:
                    log("[parse] 扫描失败（跳过）：%s -> %s" % (path.as_posix(), exc))
        out: List[Record] = []
        for i, r in enumerate(register_records, start=1):
            if r.parse_status in ("SKIPPED", "OCR_QUEUED"):
                continue
            r.id = i
            out.append(r)
        return out

    # --root 在非 dry-run 下同样生效：只取对应目录下的待解析行
    if cfg.root == "A":
        prefix = ROOT_A.as_posix() + "%"
    elif cfg.root == "B":
        prefix = ROOT_B.as_posix() + "%"
    else:
        prefix = "%"
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, source_path, file_name, file_ext, file_size, sha256, exam_code, "
            "subject_name, year, paper_name, doc_kind, text_layer, parse_status, note "
            "FROM question_source "
            "WHERE parse_status IN ('PENDING','PARTIAL','FAILED') "
            "  AND text_layer <> 'SCAN' "
            "  AND source_path LIKE %s "
            "ORDER BY id",
            (prefix,),
        )
        rows = cur.fetchall()
    out = []
    for row in rows:
        (rid, spath, fname, fext, fsize, sha, ecode, sname, year, pname,
         kind, layer, status, note) = row
        root = "A" if spath.startswith(ROOT_A.as_posix()) else "B"
        out.append(Record(
            root=root, path=Path(spath), rel="", id=int(rid), file_name=fname,
            file_ext=fext, file_size=int(fsize or 0), sha256=sha or "",
            exam_code=ecode, subject_name=sname, year=year, paper_name=pname,
            doc_kind=kind, text_layer=layer, parse_status=status, note=note or "",
        ))
    return out


def stage_parse(cfg: argparse.Namespace, conn, st: Stats,
                register_records: Sequence[Record], qcols: Set[str],
                has_vector_col: bool) -> None:
    """parse：按 (exam_code, year, 套号) 分组，先汇总答案解析，再逐文件切题/切块。"""
    records = load_parse_records(cfg, conn, register_records)
    if cfg.limit:
        records = records[:cfg.limit]
    log("[parse] 待解析文件数：%d（root=%s, limit=%s, dry-run=%s）" % (
        len(records), cfg.root, cfg.limit or "无", cfg.dry_run))

    groups: Dict[Tuple[Optional[str], Optional[int], str], List[Record]] = defaultdict(list)
    for rec in records:
        # 套号进分组键：同一年同一考试的多套卷，答案绝不能互相串用
        groups[(rec.exam_code, rec.year,
                paper_set_token(rec.file_name, rec.path.parent.name))].append(rec)

    done = 0
    for (ecode, year, set_no), group in sorted(
            groups.items(), key=lambda kv: (str(kv[0][0]), kv[0][1] or 0, kv[0][2])):
        answer_map: Dict[int, Tuple[Optional[str], str]] = {}
        # ① 先扫答案/解析册，把题号→(答案, 解析) 汇总到组内 answer_map。
        #    必须在处理 PAPER 之前完成，否则真题册先跑就拿不到解析回填。
        for rec in group:
            if rec.doc_kind in ("ANSWER", "ANALYSIS") and rec.text_layer != "SCAN":
                try:
                    text = extract_record_text(rec)
                    for qno, value in parse_answer_book(text).items():
                        answer_map.setdefault(qno, value)  # 同年多卷冲突保留首个，不臆测
                except Exception as exc:
                    st.failures.append((rec.path.as_posix(), "答案册抽文本失败：%s" % exc))
        # ② 统一处理（答案册的解析回填在同组内可见）
        for rec in group:
            try:
                process_record(cfg, conn, rec, answer_map, qcols, has_vector_col, st)
            except Exception as exc:
                reason = "%s: %s" % (type(exc).__name__, exc)
                st.failures.append((rec.path.as_posix(), reason))
                st.parse_files_failed += 1
                log("[parse] 单文件失败（跳过，继续）：%s -> %s" % (rec.path.as_posix(), reason))
                if not cfg.dry_run:
                    mark_failed(conn, rec.id, reason)
            done += 1
            if done % 500 == 0:
                log("[parse] 已处理 %d / %d" % (done, len(records)))

    log("[parse] 完成：PARSED=%d PARTIAL=%d SKIPPED=%d FAILED=%d" % (
        st.parse_files_parsed, st.parse_files_partial,
        st.parse_files_skipped, st.parse_files_failed))
    log("[parse] 题目合计=%d（其中面试问答=%d） chunk 合计=%d（其中面试 chunk=%d） 解析条数=%d" % (
        st.parse_questions, st.parse_interview_questions, st.parse_chunks,
        st.parse_interview_chunks, st.parse_answer_items))


# ======================================================================================
# 输出：OCR 队列 / 失败清单 / 统计
# ======================================================================================


def write_list_file(path: Path, header: str, rows: Iterable[Tuple[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        f.write(header)
        for item, note in rows:
            f.write("%s\t%s\n" % (item, note.replace("\t", " ").replace("\n", " ")))


def print_report(cfg: argparse.Namespace, st: Stats, qcols: Set[str],
                 has_vector_col: bool, ocr_path: Path, fail_path: Path) -> None:
    def dump(title: str, counter: Counter) -> None:
        log("\n-- %s --" % title)
        for key, cnt in counter.most_common():
            log("  %-28s %d" % (key, cnt))

    log("\n" + "=" * 72)
    log("题库导入流水线 dry-run 统计" if cfg.dry_run else "题库导入流水线执行统计")
    log("=" * 72)
    dump("按目录", st.by_root)
    dump("按扩展名", st.by_ext)
    dump("按 exam_code", st.by_exam)
    dump("按 doc_kind", st.by_kind)
    dump("按 text_layer", st.by_layer)
    dump("register 后 parse_status", st.by_status)
    log("\n-- 关键口径 --")
    log("  登记总数                : %d" % sum(st.by_root.values()))
    log("  可解析文本层(TEXT+SPARSE): %d" % (st.by_layer["TEXT"] + st.by_layer["SPARSE"]))
    log("  扫描件(SCAN)             : %d" % st.by_layer["SCAN"])
    log("  OCR 队列条数             : %d" % st.parse_ocr_queued)
    log("  register 跳过(SKIPPED)   : %d" % st.by_status["SKIPPED"])
    log("\n-- parse 产出（预估 / 实测按同一套规则计算）--")
    log("  题目总数                 : %d" % st.parse_questions)
    log("    国考/四六级/考研真题题 : %d" % (st.parse_questions - st.parse_interview_questions))
    log("    面试问答(SHORT_ANSWER) : %d" % st.parse_interview_questions)
    log("  ai_chunk 块数            : %d" % st.parse_chunks)
    log("    面试 md 块数           : %d" % st.parse_interview_chunks)
    log("  答案册切出解析条数       : %d" % st.parse_answer_items)
    log("  真题题中带正确答案       : %d（无答案一律不编造，is_answer=0）" % st.parse_questions_with_answer)
    log("  真题题中带解析回填       : %d" % st.parse_questions_with_analysis)
    log("  PARSED / PARTIAL / SKIPPED / FAILED 文件数 : %d / %d / %d / %d" % (
        st.parse_files_parsed, st.parse_files_partial,
        st.parse_files_skipped, st.parse_files_failed))
    log("\n-- 运行时契约列探测 --")
    if cfg.dry_run:
        log("  dry-run 未连库，未做实际列探测；正式执行时脚本按 information_schema 自动探测：")
        log("    CONTRACT_QUESTION_COLS = %s" % (list(CONTRACT_QUESTION_COLS),))
        log("    ai_chunk.vector_synced  = 运行时探测，缺失则不写该列")
    else:
        for col in CONTRACT_QUESTION_COLS:
            log("    question.%-12s : %s" % (col, "存在→写入" if col in qcols else "缺失→跳过"))
        log("    ai_chunk.vector_synced : %s" % ("存在→写 0" if has_vector_col else "缺失→跳过"))
    log("\n-- 输出文件 --")
    log("  OCR 待处理清单 : %s" % ocr_path.as_posix())
    log("  失败清单       : %s" % fail_path.as_posix())
    log("  失败条数       : %d" % len(st.failures))
    if st.skipped_notes:
        log("\n-- SKIPPED 原因 Top5 --")
        for note, cnt in st.skipped_notes.most_common(5):
            log("  %-60s %d" % (note[:60], cnt))
    log("=" * 72)


# ======================================================================================
# 主流程
# ======================================================================================


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="题库导入流水线（register / parse / all），可重跑、幂等、可断点续跑")
    parser.add_argument("--stage", choices=("register", "parse", "all"), default="all",
                        help="执行阶段；每阶段独立可重跑")
    parser.add_argument("--dry-run", action="store_true",
                        help="只扫描统计，不连库、不写库、不改原始资料")
    parser.add_argument("--limit", type=int, default=0,
                        help="每阶段最多处理的文件数（小样验证用）")
    parser.add_argument("--root", choices=("A", "B", "ALL"), default="ALL",
                        help="资料目录：A=面试资料，B=题库，ALL=两者")
    # 内部 worker 参数（供 pdf_text 子进程隔离用，不对用户暴露）
    parser.add_argument("--pdf-text-worker", default="", help=argparse.SUPPRESS)
    parser.add_argument("--pdf-out", default="", help=argparse.SUPPRESS)
    parser.add_argument("--pdf-max-pages", type=int, default=0, help=argparse.SUPPRESS)
    cfg = parser.parse_args(argv)

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    # ---- 子进程 worker：只抽一个 PDF，结果写文件，主进程读 ----
    if cfg.pdf_text_worker:
        try:
            text, total = _pdf_text_inproc(Path(cfg.pdf_text_worker),
                                           cfg.pdf_max_pages or None)
        except Exception as exc:  # noqa: BLE001
            sys.stderr.write("%s: %s\n" % (type(exc).__name__, exc))
            return 3
        with open(cfg.pdf_out, "w", encoding="utf-8", errors="replace") as fh:
            fh.write("%d\n" % total)
            fh.write(text)
        return 0

    date_tag = datetime.now().strftime("%Y-%m-%d")
    ocr_path = OUT_DIR / ("OCR队列_题库导入_%s.txt" % date_tag)
    fail_path = OUT_DIR / ("导入失败清单_题库导入_%s.txt" % date_tag)

    conn = None
    qcols: Set[str] = set()
    has_vector_col = False
    if not cfg.dry_run:
        try:
            conn = db_connect()
            qcols = table_columns(conn, "question")
            has_vector_col = "vector_synced" in table_columns(conn, "ai_chunk")
            log("[启动] 已连库；question 契约列探测：%s" % (
                ", ".join("%s=%s" % (c, "有" if c in qcols else "无") for c in CONTRACT_QUESTION_COLS)))
        except Exception as exc:
            log("[启动] 连库失败：%s" % exc)
            return 1

    st = Stats()
    register_records: List[Record] = []
    started = time.time()
    try:
        if cfg.stage in ("register", "all"):
            register_records = stage_register(cfg, conn, st)
        if cfg.stage in ("parse", "all"):
            stage_parse(cfg, conn, st, register_records, qcols, has_vector_col)
    finally:
        if conn is not None:
            conn.close()

    # OCR 队列 / 失败清单是纯文本登记产物（不是数据库、不是原始资料），dry-run 也生成
    write_list_file(
        ocr_path,
        "# 待 OCR 扫描件清单（严禁编造题目）\n"
        "# 生成：scripts/import_question_bank.py  dry-run=%s  stage=%s  root=%s\n"
        "# 格式：绝对路径<TAB>判定说明\n" % (cfg.dry_run, cfg.stage, cfg.root),
        st.ocr_queue,
    )
    write_list_file(
        fail_path,
        "# 逐文件失败清单（失败不中断全批，下次重跑自动重试 FAILED）\n"
        "# 格式：路径<TAB>失败原因\n",
        st.failures,
    )

    print_report(cfg, st, qcols, has_vector_col, ocr_path, fail_path)
    log("耗时：%.1f 秒" % (time.time() - started))
    return 0 if not st.failures else 1


if __name__ == "__main__":
    sys.exit(main())
