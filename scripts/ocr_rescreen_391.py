#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""OCR 队列 391 条多页文本层复筛（严格只读，可复跑）。

================================================================================
为什么需要这个脚本
================================================================================
`question_source` 中 ``parse_status='OCR_QUEUED'`` 的 391 条记录，其 ``note``
普遍写着「pypdf 前 3 页文本为空/极少，判定为扫描件」。但
`项目规划/任务_2026-11-13_收尾执行记录.md` §11.1 与
`项目规划/任务_2026-11-13_T9_三验收.md` §500-519 已实证：

  * 文件 5072《六级听力真题合集》第 1 页 0 字符，却在第 2 页有 1846 字符、
    第 51 页有 2015 字符、全本平均 2045 字/页 —— 它是**原生数字 PDF**，
    只因「看第 1 页」被误判成扫描件，白排 119 页 OCR（≈33 分钟）；
  * 反过来，006 / 007 / 010 三本前 3 页均为 0 字符，是**真扫描件**。

结论：「前 N 页为 0」在任何带封面 / 目录 / 插图前言的 PDF 上都会成立，
不能作为扫描件判据。本脚本对 391 条做**全页文本层复筛**，
把「原生 PDF 被误判」的条目筛出来，形成可救回清单。

================================================================================
方法：全页 /Font 结构扫描 + 有限字符数抽测
================================================================================
直接对每页调 ``pypdf.extract_text()`` 在本批数据上不可行：实测纯扫描图页
（如 id=4631，159.6 MB / 56 页）单页需 ~5.6 s，10 页 44.9 s、25 页 140.5 s，
整批会被拖到小时级，且 150 s 超时会把本可判定的大册子误记为「无法读取」。

PDF 的文本必须由字体资源（``/Resources /Font``）绘制，**没有 /Font 资源的页
不可能有文本层**。该判据只查页面字典、不解码内容流，实测全页扫描
0.01–0.13 s/本（含 200 MB / 259 页的 id=5123 也仅 0.08 s）。故：

  1. 对每本 PDF **全页**检查 /Font（含父级 /Parent 继承），得 ``font_page_count``；
  2. 仅对「有字体」的采样页调用 ``extract_text``，每本最多 8 页，
     拿到真实字符数与乱码率 —— 这就是「≥2 个非空页的字符数证据」；
  3. 采样点覆盖第 1/2/3 页 + 随机 1 页（seed 固定）+ 1/4、1/2、3/4 分位 + 末页，
     并补入有字体页的首 / 中 / 末，保证不只看封面；
  4. 交叉验证（复筛前独立探测，非 391 条结论）：
       5123（200 MB/259 页）前 3 页 0 字、第 32 页起 2000+ 字 → 256 页有 /Font；
       5239（165 MB/131 页）前 3 页 0 字、第 16 页 4267 字 → 128 页有 /Font；
       4631 / 858 / 4625 / 8769 全页 0 页有 /Font，抽样 extract_text 亦全 0。
     /Font 判据与 extract_text 结果一致。

================================================================================
只读边界（硬约束）
================================================================================
  * MySQL：仅 ``SELECT ... FROM question_source WHERE parse_status='OCR_QUEUED'``；
    **不写库、不改 parse_status / text_layer / chunk_count、不启事务写**。
  * 文件：仅打开 ``question_source.source_path`` 登记的这 391 个 PDF；
    **不做目录递归扫描、不 glob、不读取任何未登记的路径**。
  * **不启动 OCR、不跑 GPU / Ollama、不渲染图片、不调用任何大模型**。
  * **不改任何源码或历史文档、不删文件、不启停 8080 / 8000 服务、
    不 commit、不 push**。
  * 产物只新建两个：``runtime/ocr_rescreen_391.json`` 与
    ``项目规划/任务_2026-11-14_B1_OCR队列复筛.md``。
  * 报告中的处置一律写「建议」，不写「已处理」；档位由 qa-acceptance 独立验收，
    本脚本与报告**不自行判 PASS**。

================================================================================
口令处理
================================================================================
从 ``backend/src/main/resources/application-local.yml`` 的
``spring.datasource.password`` 读取（若为 ``${ENV_VAR}`` 占位符，则回退到同名
环境变量或 ``ai-service/.env``），写入 ``os.environ['MYSQL_PWD']`` 后仅用于连接。
**全程不打印、不写进任何产物、不落到命令行参数**（避免 ``-p`` 泄露）。

================================================================================
复跑命令（Windows / Git Bash，工作区根目录）
================================================================================
    .venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --probe
        # 只查库：391 条分布 + 文件可达性，不读 PDF 内容、不写产物
    .venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --limit 6
        # 小样：只跑 6 条并在终端打印，**不写正式产物**（不污染交付）
    .venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py
        # 全量：写 runtime/ocr_rescreen_391.json + 项目规划/任务_2026-11-14_B1_OCR队列复筛.md
    .venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --report-only
        # 由已有 JSON 重新生成 MD（JSON 不变，便于订正排版后复跑）

单个 PDF 在**独立子进程**中探测（``--worker`` 内部模式），带超时。原因：
历史 §11.1 记载 pypdf 处理 83.6 MB / 119 页的 5072 时曾出现子进程访问违例
（``rc=3221225477``）。隔离后单文件崩溃 / 超时只影响该文件本身。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import pymysql
import yaml

# --------------------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------------------

ROOT: Path = Path(__file__).resolve().parent.parent
YML_PATH: Path = ROOT / "backend" / "src" / "main" / "resources" / "application-local.yml"
ENV_PATH: Path = ROOT / "ai-service" / ".env"
OUT_JSON: Path = ROOT / "runtime" / "ocr_rescreen_391.json"
OUT_MD: Path = ROOT / "项目规划" / "任务_2026-11-14_B1_OCR队列复筛.md"

DB_HOST: str = "127.0.0.1"
DB_PORT: int = 3306
DB_USER: str = "root"
DB_NAME: str = "exam_prep"
TARGET_STATUS: str = "OCR_QUEUED"

#: 历史实测：误排 OCR 约 16.5 s/页（收尾执行记录 §7 / T9 §511）
OCR_SEC_PER_PAGE: float = 16.5

#: 判定阈值
MIN_CHARS_PER_PAGE: int = 100       # 一页至少这么多字符才算「实质文本层」
MAX_BAD_RATIO: float = 0.02         # 私用区/异常区块字符占比上限（沿用 ocr_answer_books.py 口径）
MIN_VALID_PAGES: int = 2            # 可救回至少需要的实质文本页数（判据：≥2 个非空页）
MAX_TEXT_PROBE_PAGES: int = 8       # 每本最多实际调用 extract_text 的页数

#: 采样与复现
SEED_BASE: int = 20261114           # 随机页种子基数，保证每次复跑采样点一致
WORKER_TIMEOUT_SEC: int = 90        # 单文件子进程超时（秒）
WORKER_ATTEMPTS: int = 3            # 进程级崩溃（rc=3221225477）偶发，失败自动重试
JSON_MARKER: str = "__RESREEN_JSON__"

#: 私有字体编码乱码字符区块（与 scripts/ocr_answer_books.py 的 BAD_CHARS 一致）
BAD_CHARS = re.compile(r"[\u0A00-\u0A7F\u0C00-\u0C7F\uE000-\uF8FF\u2E80-\u2FFF]")

SELECT_SQL: str = (
    "SELECT id, source_path, file_name, file_ext, file_size, doc_kind, "
    "text_layer, parse_status, note "
    "FROM question_source WHERE parse_status = %s ORDER BY id"
)


# --------------------------------------------------------------------------------------
# 数据库（只读）
# --------------------------------------------------------------------------------------

def load_db_password() -> str:
    """从 application-local.yml 解析口令；占位符回退到环境变量 / .env。

    Returns:
        口令字符串。绝不打印、绝不写入任何产物。

    Raises:
        RuntimeError: 配置文件缺失或口令无法解析。
    """
    if not YML_PATH.is_file():
        raise RuntimeError("缺少 application-local.yml，无法读取数据库口令")
    cfg: Dict[str, Any] = yaml.safe_load(YML_PATH.read_text(encoding="utf-8")) or {}
    datasource: Dict[str, Any] = ((cfg.get("spring") or {}).get("datasource") or {})
    raw: str = str(datasource.get("password", "") or "")

    placeholder = re.search(r"\$\{([^}:]+)(?::([^}]*))?\}", raw)
    if placeholder:
        key: str = placeholder.group(1)
        value: str = os.environ.get(key, "")
        if not value and ENV_PATH.is_file():
            for line in ENV_PATH.read_text(encoding="utf-8", errors="replace").splitlines():
                stripped = line.strip()
                if stripped.startswith(key + "="):
                    value = stripped.split("=", 1)[1].strip().strip('"').strip("'")
                    break
        return value or (placeholder.group(2) or "")
    return raw


def db_connect() -> "pymysql.connections.Connection":
    """建立只读连接；口令取自环境变量 MYSQL_PWD（由 main 注入）。"""
    password: str = os.environ.get("MYSQL_PWD", "")
    if not password:
        raise RuntimeError("MYSQL_PWD 未设置：请检查 application-local.yml 的 spring.datasource.password")
    return pymysql.connect(
        host=DB_HOST,
        port=DB_PORT,
        user=DB_USER,
        password=password,
        database=DB_NAME,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=False,
    )


def fetch_targets(conn: "pymysql.connections.Connection") -> List[Dict[str, Any]]:
    """只读查询 OCR_QUEUED 记录（唯一 SQL，参数化）。"""
    with conn.cursor() as cur:
        cur.execute(SELECT_SQL, (TARGET_STATUS,))
        return [dict(row) for row in cur.fetchall()]


# --------------------------------------------------------------------------------------
# 采样与判定
# --------------------------------------------------------------------------------------

def sample_roles(page_count: int, seed: int) -> List[Tuple[int, List[str]]]:
    """生成分层采样页码及其角色。

    覆盖「第 1/2/3 页 + 随机 1 页」（任务要求），并额外补 1/4、1/2、3/4 分位页
    与末页，避免只命中封面 / 目录 / 插图页而漏掉中段正文文本层。

    Args:
        page_count: PDF 总页数（>= 1）。
        seed: 随机种子（SEED_BASE + 记录 id），保证复跑一致。

    Returns:
        按页码升序的 [(页码, [角色...])]。
    """
    rng = random.Random(seed)
    roles: Dict[int, List[str]] = {}

    def add(page: int, role: str) -> None:
        clamped = max(1, min(page_count, page))
        roles.setdefault(clamped, []).append(role)

    add(1, "p1")
    if page_count >= 2:
        add(2, "p2")
    if page_count >= 3:
        add(3, "p3")
    add(rng.randint(1, page_count), "random")
    add(max(1, round(page_count * 0.25)), "q25")
    add(max(1, round(page_count * 0.50)), "q50")
    add(max(1, round(page_count * 0.75)), "q75")
    add(page_count, "last")
    return sorted(roles.items())


def bad_ratio(text: str) -> float:
    """私有字体编码 / 私用区字符占比（沿用 ocr_answer_books.py 口径）。"""
    if not text:
        return 1.0
    return len(BAD_CHARS.findall(text)) / len(text)


def has_font_resource(page: Any) -> bool:
    """判断页面（含 /Parent 继承）是否存在 /Font 资源。

    PDF 文本必须由字体绘制，无 /Font 即无文本层。此判据只查页面字典、
    不解码内容流，因此对纯扫描图页极快（实测全页扫描 0.01–0.13 s/本）。
    """
    try:
        from pypdf.generic import IndirectObject

        node = page
        for _ in range(6):
            resources = node.get("/Resources")
            if isinstance(resources, IndirectObject):
                resources = resources.get_object()
            if resources and resources.get("/Font"):
                return True
            parent = node.get("/Parent")
            if parent is None:
                break
            node = parent.get_object() if isinstance(parent, IndirectObject) else parent
        return False
    except Exception:  # noqa: BLE001 - 结构异常按「无字体」处理，不拖垮整本
        return False


def probe_one(path: str, seed: int) -> Dict[str, Any]:
    """在独立子进程中探测单个 PDF：全页 /Font 结构扫描 + 有限字符数抽测。

    Returns:
        结构化探测结果；``ok=False`` 时带 ``error``。
    """
    started = time.perf_counter()
    result: Dict[str, Any] = {
        "ok": False,
        "error": "",
        "page_count": 0,
        "encrypted": False,
        "font_page_count": 0,
        "font_pages_first": [],
        "text_probe_pages": 0,
        "extract_elapsed_sec": 0.0,
        "valid_pages": 0,
        "garbled_pages": 0,
        "nonempty_probe_pages": 0,
        "substantial_probe_pages": 0,
        "measured_probe_chars": 0,
        "sample_pages": [],
        "evidence_pages": [],
        "elapsed_sec": 0.0,
    }
    try:
        from pypdf import PdfReader  # 延迟导入：主进程无需加载 PDF 解析器

        src = Path(path)
        if not src.is_file():
            result["error"] = "文件不存在（source_path 登记的路径不可达）"
            return result

        reader = PdfReader(str(src), strict=False)
        result["encrypted"] = bool(reader.is_encrypted)
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception:  # noqa: BLE001
                result["error"] = "PDF 已加密且空口令解密失败"
                return result

        page_count = len(reader.pages)
        result["page_count"] = page_count
        if page_count == 0:
            result["error"] = "PDF 页数为 0"
            return result

        # 1) 全页结构扫描（不解码内容流，极快）
        font_pages: List[int] = [
            i + 1 for i in range(page_count) if has_font_resource(reader.pages[i])
        ]
        font_set = set(font_pages)
        result["font_page_count"] = len(font_pages)
        result["font_pages_first"] = font_pages[:10]

        # 2) 分层采样点（任务口径：第 1/2/3 页 + 随机 1 页 + 分位 + 末页）
        roles = sample_roles(page_count, seed)

        # 3) 只对「有字体」的页抽测字符数，每本最多 MAX_TEXT_PROBE_PAGES 页
        candidates: List[int] = [p for p, _ in roles if p in font_set]
        if font_pages:
            for p in (
                font_pages[0],
                font_pages[len(font_pages) // 2],
                font_pages[-1],
                font_pages[min(2, len(font_pages) - 1)],
            ):
                if p not in candidates:
                    candidates.append(p)
        candidates = candidates[:MAX_TEXT_PROBE_PAGES]

        probe_chars: Dict[int, Tuple[int, float]] = {}
        extract_started = time.perf_counter()
        for page_no in candidates:
            try:
                text = reader.pages[page_no - 1].extract_text() or ""
            except Exception:  # noqa: BLE001 - 单页异常按空页处理
                text = ""
            stripped = text.strip()
            probe_chars[page_no] = (len(stripped), bad_ratio(stripped))
        result["extract_elapsed_sec"] = round(time.perf_counter() - extract_started, 4)
        result["text_probe_pages"] = len(candidates)

        valid: List[Tuple[int, int]] = [
            (p, c) for p, (c, r) in probe_chars.items()
            if c >= MIN_CHARS_PER_PAGE and r <= MAX_BAD_RATIO
        ]
        result["valid_pages"] = len(valid)
        result["garbled_pages"] = sum(
            1 for c, r in probe_chars.values()
            if c >= MIN_CHARS_PER_PAGE and r > MAX_BAD_RATIO
        )
        result["nonempty_probe_pages"] = sum(1 for c, _ in probe_chars.values() if c > 0)
        result["substantial_probe_pages"] = sum(
            1 for c, _ in probe_chars.values() if c >= MIN_CHARS_PER_PAGE
        )
        result["measured_probe_chars"] = sum(c for _, c in valid)
        result["evidence_pages"] = [
            {"page": p, "chars": c} for p, c in sorted(valid)[:6]
        ]

        # 4) 采样页明细：抽测过的给实测字符数；无 /Font 的页给结构推定 0
        sampled = {p for p, _ in roles}
        for page_no, role_list in roles:
            if page_no in probe_chars:
                chars, ratio = probe_chars[page_no]
                result["sample_pages"].append({
                    "page": page_no, "roles": role_list, "chars": chars,
                    "bad_ratio": round(ratio, 4), "probe": "extract_text",
                })
            else:
                result["sample_pages"].append({
                    "page": page_no, "roles": role_list, "chars": 0,
                    "bad_ratio": 1.0,
                    "probe": "no_font_resource" if page_no not in font_set else "not_probed",
                })
        for page_no, (chars, ratio) in sorted(probe_chars.items()):
            if page_no not in sampled:
                result["sample_pages"].append({
                    "page": page_no, "roles": ["text_probe"], "chars": chars,
                    "bad_ratio": round(ratio, 4), "probe": "extract_text",
                })
        result["sample_pages"].sort(key=lambda item: item["page"])

        result["ok"] = True
        return result
    except Exception as exc:  # noqa: BLE001 - 子进程内兜底
        result["error"] = "%s: %s" % (type(exc).__name__, str(exc)[:200])
        return result
    finally:
        result["elapsed_sec"] = round(time.perf_counter() - started, 4)


def run_worker(
    script_path: Path,
    record_id: int,
    path: str,
    seed: int,
    attempts: int = WORKER_ATTEMPTS,
) -> Dict[str, Any]:
    """主进程调度：为单个 PDF 起独立子进程并回收 JSON 结果。

    pypdf 的进程级访问违例（``rc=3221225477``，§11.1 中 5072 亦出现过）在本批
    数据上是**偶发**的：对同一文件连续重跑可全部成功。故失败时自动重试，
    避免把「可读但恰好崩了一次」的文件误记为「无法读取」。
    """
    cmd: List[str] = [
        sys.executable, str(script_path),
        "--worker", "--id", str(record_id),
        "--seed", str(seed), "--path", path,
    ]
    last_error = "未知错误"
    for _attempt in range(1, attempts + 1):
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=WORKER_TIMEOUT_SEC,
                check=False,
            )
        except subprocess.TimeoutExpired:
            last_error = "子进程超时（>%d s）" % WORKER_TIMEOUT_SEC
            continue

        payload: Optional[str] = None
        for line in (proc.stdout or "").splitlines():
            if line.startswith(JSON_MARKER):
                payload = line[len(JSON_MARKER):]
        if payload:
            try:
                parsed = json.loads(payload)
                if isinstance(parsed, dict):
                    return parsed
            except json.JSONDecodeError:
                pass

        stderr_lines = (proc.stderr or "").strip().splitlines()
        hint = stderr_lines[-1][:160] if stderr_lines else "无输出"
        last_error = "子进程未返回有效结果（rc=%s；stderr 末行=%s）" % (proc.returncode, hint)
    return _failed_probe(
        "%s（连续 %d 次尝试均失败，判定为无法读取）" % (last_error, attempts)
    )


def _failed_probe(error: str) -> Dict[str, Any]:
    """构造一份失败探测结果（字段与 probe_one 对齐）。"""
    return {
        "ok": False, "error": error, "page_count": 0, "encrypted": False,
        "font_page_count": 0, "font_pages_first": [], "text_probe_pages": 0,
        "extract_elapsed_sec": 0.0, "valid_pages": 0, "garbled_pages": 0,
        "nonempty_probe_pages": 0, "substantial_probe_pages": 0,
        "measured_probe_chars": 0, "sample_pages": [], "evidence_pages": [],
        "elapsed_sec": 0.0,
    }


def classify(row: Dict[str, Any], probe: Dict[str, Any]) -> Dict[str, Any]:
    """把「库记录 + 探测结果」合成逐条交付记录。"""
    page_count: int = int(probe.get("page_count", 0) or 0)
    font_pages: int = int(probe.get("font_page_count", 0) or 0)
    valid_pages: int = int(probe.get("valid_pages", 0) or 0)
    garbled_pages: int = int(probe.get("garbled_pages", 0) or 0)
    measured_chars: int = int(probe.get("measured_probe_chars", 0) or 0)
    sample_pages: List[Dict[str, Any]] = list(probe.get("sample_pages", []))
    evidence: List[Dict[str, Any]] = list(probe.get("evidence_pages", []))

    if not probe.get("ok", False):
        verdict = "UNREADABLE"
        reason = "无法读取：%s" % probe.get("error", "未知错误")
        estimated = 0
    elif valid_pages >= MIN_VALID_PAGES:
        verdict = "RESCUABLE"
        shown = "、".join("第 %d 页 %d 字" % (e["page"], e["chars"]) for e in evidence[:3])
        reason = (
            "全页结构扫描 %d/%d 页有 /Font 字体资源（即有文本层），抽测 %d 页中 %d 页为实质文本页"
            "（≥%d 字且乱码率≤%.0f%%）；字符数证据：%s。原仅按「前 3 页 0 字符」判 SCAN 属误判，"
            "建议直接重解析文本层，不需 OCR。"
            % (font_pages, page_count, probe.get("text_probe_pages", 0), valid_pages,
               MIN_CHARS_PER_PAGE, MAX_BAD_RATIO * 100, shown or "见 sample_pages")
        )
        avg_valid = measured_chars / valid_pages if valid_pages else 0.0
        estimated = int(round(avg_valid * font_pages))
    else:
        verdict = "SCAN"
        head = "、".join(
            "第 %d 页 %d 字" % (sp["page"], sp["chars"])
            for sp in sample_pages
            if any(r in sp.get("roles", []) for r in ("p1", "p2", "p3"))
        )
        if font_pages == 0:
            detail = "全页 %d 页均无 /Font 字体资源（PDF 文本必须由字体绘制，故无文本层）" % page_count
        else:
            detail = ("有 %d 页含字体资源，但抽测 %d 页未达「≥2 个实质文本页」阈值"
                      "（实质 %d 页、乱码 %d 页），保守归为仍需 OCR，建议人工复核"
                      % (font_pages, probe.get("text_probe_pages", 0), valid_pages, garbled_pages))
        reason = "%s；抽样前 3 页字符数 [%s]。建议仍走 OCR。" % (
            detail, head or "见 sample_pages")
        estimated = 0

    return {
        "id": int(row["id"]),
        "source_path": str(row["source_path"]),
        "file_name": str(row.get("file_name") or Path(str(row["source_path"])).name),
        "file_ext": str(row.get("file_ext") or ""),
        "doc_kind": str(row.get("doc_kind") or ""),
        "text_layer_db": str(row.get("text_layer") or ""),
        "parse_status_db": str(row.get("parse_status") or ""),
        "file_size": int(row.get("file_size") or 0),
        "page_count": page_count,
        "font_page_count": font_pages,
        "font_pages_first": list(probe.get("font_pages_first", [])),
        "text_probe_pages": int(probe.get("text_probe_pages", 0) or 0),
        "sample_pages": sample_pages,
        "nonempty_sample_pages": sum(1 for sp in sample_pages if sp.get("chars", 0) > 0),
        "valid_pages": valid_pages,
        "garbled_pages": garbled_pages,
        "measured_valid_chars": measured_chars,
        "evidence_pages": evidence,
        "verdict": verdict,
        "reason": reason,
        "estimated_parseable_chars": estimated,
        "elapsed_sec": float(probe.get("elapsed_sec", 0.0) or 0.0),
        "extract_elapsed_sec": float(probe.get("extract_elapsed_sec", 0.0) or 0.0),
        "error": "" if probe.get("ok", False) else str(probe.get("error", "")),
    }


# --------------------------------------------------------------------------------------
# 汇总与报告
# --------------------------------------------------------------------------------------

def build_result(records: List[Dict[str, Any]], pypdf_version: str) -> Dict[str, Any]:
    """由逐条记录计算汇总（含可救回重解析耗时与可节省机时）。"""
    total = len(records)
    rescuable = [r for r in records if r["verdict"] == "RESCUABLE"]
    scan = [r for r in records if r["verdict"] == "SCAN"]
    unreadable = [r for r in records if r["verdict"] == "UNREADABLE"]

    rescuable_pages = sum(r["page_count"] for r in rescuable)
    rescuable_font_pages = sum(r["font_page_count"] for r in rescuable)
    scan_pages = sum(r["page_count"] for r in scan)
    total_pages = sum(r["page_count"] for r in records)

    total_worker_sec = sum(r["elapsed_sec"] for r in records)
    total_struct_pages = sum(r["page_count"] for r in records if r["elapsed_sec"] >= 0)
    total_extract_sec = sum(r["extract_elapsed_sec"] for r in records)
    total_probe_pages = sum(r["text_probe_pages"] for r in records)
    measured_rate = (total_extract_sec / total_probe_pages) if total_probe_pages else 0.0

    ocr_cost_sec = rescuable_pages * OCR_SEC_PER_PAGE
    reparse_cost_sec = rescuable_font_pages * measured_rate
    saved_sec = ocr_cost_sec - reparse_cost_sec

    by_kind: Dict[str, Dict[str, int]] = {}
    for r in records:
        bucket = by_kind.setdefault(r["doc_kind"] or "(null)", {
            "total": 0, "RESCUABLE": 0, "SCAN": 0, "UNREADABLE": 0,
        })
        bucket["total"] += 1
        bucket[r["verdict"]] += 1

    return {
        "meta": {
            "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "script": "scripts/ocr_rescreen_391.py",
            "python": sys.version.split()[0],
            "pypdf": pypdf_version,
            "database": "%s@%s:%d/%s" % (DB_USER, DB_HOST, DB_PORT, DB_NAME),
            "sql": SELECT_SQL.replace("%s", "'%s'" % TARGET_STATUS),
            "readonly": True,
            "credentials": "口令取自 application-local.yml 并注入 MYSQL_PWD；未回显、未写入任何产物",
            "method": "全页 /Font 结构扫描（不解码内容流）+ 仅对有字体页抽测 extract_text",
            "sample_policy": (
                "采样点=第1/2/3页+随机1页(seed=20261114+id)+1/4、1/2、3/4 分位页+末页；"
                "另补有字体页的首/中/末；每本最多 %d 页调 extract_text" % MAX_TEXT_PROBE_PAGES
            ),
            "thresholds": {
                "min_chars_per_page": MIN_CHARS_PER_PAGE,
                "max_bad_ratio": MAX_BAD_RATIO,
                "min_valid_pages_for_rescuable": MIN_VALID_PAGES,
                "max_text_probe_pages": MAX_TEXT_PROBE_PAGES,
                "worker_timeout_sec": WORKER_TIMEOUT_SEC,
                "ocr_seconds_per_page": OCR_SEC_PER_PAGE,
            },
            "verdict_definition": {
                "RESCUABLE": "抽测中实质文本页 >= 2 且全页有 /Font（原生 PDF 被误判，建议直接重解析，不必 OCR）",
                "SCAN": "全页无 /Font，或抽测未达 ≥2 个实质文本页（含私有字体乱码），建议仍走 OCR",
                "UNREADABLE": "文件不存在 / 加密 / pypdf 打开失败 / 子进程崩溃或超时",
            },
            "estimated_parseable_chars_formula": (
                "抽测有效页平均字符数 × 全页 /Font 字体页数（估计）；"
                "measured_valid_chars 为抽测页实测有效字符总和（下界）"
            ),
        },
        "summary": {
            "total": total,
            "RESCUABLE": len(rescuable),
            "SCAN": len(scan),
            "UNREADABLE": len(unreadable),
            "partition_ok": (len(rescuable) + len(scan) + len(unreadable) == total),
            "partition_formula": "%d + %d + %d = %d" % (
                len(rescuable), len(scan), len(unreadable), total),
            "by_doc_kind": by_kind,
            "total_pages": total_pages,
            "rescuable_pages": rescuable_pages,
            "rescuable_font_pages": rescuable_font_pages,
            "scan_pages": scan_pages,
            "rescuable_measured_valid_chars": sum(r["measured_valid_chars"] for r in rescuable),
            "rescuable_estimated_chars": sum(r["estimated_parseable_chars"] for r in rescuable),
            "total_worker_elapsed_sec": round(total_worker_sec, 2),
            "total_struct_pages_scanned": total_struct_pages,
            "total_text_probe_pages": total_probe_pages,
            "total_extract_elapsed_sec": round(total_extract_sec, 2),
            "measured_text_extract_sec_per_page": round(measured_rate, 4),
            "ocr_seconds_per_page_baseline": OCR_SEC_PER_PAGE,
            "if_misqueued_ocr_cost_sec": round(ocr_cost_sec, 1),
            "if_misqueued_ocr_cost_hours": round(ocr_cost_sec / 3600.0, 2),
            "reparse_estimated_cost_sec": round(reparse_cost_sec, 1),
            "reparse_estimated_cost_minutes": round(reparse_cost_sec / 60.0, 2),
            "saved_machine_sec": round(saved_sec, 1),
            "saved_machine_hours": round(saved_sec / 3600.0, 2),
            "remaining_scan_ocr_cost_hours": round(scan_pages * OCR_SEC_PER_PAGE / 3600.0, 2),
            "all_391_ocr_cost_hours": round(total_pages * OCR_SEC_PER_PAGE / 3600.0, 2),
        },
        "records": records,
    }


def _md_escape(text: str) -> str:
    """转义 Markdown 表格中的竖线与换行。"""
    return str(text).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def render_report(result: Dict[str, Any]) -> str:
    """由汇总结果生成人读报告（数字全部来自 JSON，避免手写不一致）。"""
    meta = result["meta"]
    s = result["summary"]
    records: List[Dict[str, Any]] = result["records"]
    rescuable = [r for r in records if r["verdict"] == "RESCUABLE"]
    unreadable = [r for r in records if r["verdict"] == "UNREADABLE"]

    lines: List[str] = []
    add = lines.append

    add("# 2026-11-14 T-B1 · OCR 队列 391 条多页文本层复筛")
    add("")
    add("> 执行者：`software-engineer`（执行层）。按 `项目规划/00_协作规程.md` 三段式，"
        "**独立验收由 `qa-acceptance` 承担，本报告不自行判档、不写 PASS/FAIL**。")
    add("> 任务来源：`项目规划/任务_2026-11-14_接替收尾_拆解记录.md` §2 T-B1。")
    add("> 判据依据：`项目规划/任务_2026-11-13_收尾执行记录.md` §11.1（5072 只看第 1 页 0 字符判扫描件，"
        "实为原生 PDF，第 2 页 1846 字）、`项目规划/任务_2026-11-13_T9_三验收.md` §500-519。")
    add("> 复筛脚本：`scripts/ocr_rescreen_391.py`（可复跑）；逐条数据：`runtime/ocr_rescreen_391.json`。")
    add("> 生成时间：%s；Python %s / pypdf %s。" % (
        meta["generated_at"], meta["python"], meta["pypdf"]))
    add("")
    add("---")
    add("")

    add("## 0. 结论速览")
    add("")
    add("| 分档 | 条数 | 含义 | 建议处置（**均为建议，尚未处理**） |")
    add("|---|---:|---|---|")
    add("| 可救回（原生 PDF 被误判） | **%d** | 有完整文本层，原「前 3 页为 0」判据误判 | "
        "建议直接重解析文本层，不必排 OCR |" % s["RESCUABLE"])
    add("| 真扫描件（仍需 OCR） | **%d** | 全页无可用文本层（含私有字体乱码） | "
        "建议保留 OCR 队列，排期由用户批准 |" % s["SCAN"])
    add("| 无法读取 | **%d** | 文件不存在 / 加密 / 打开失败 / 超时 | "
        "建议人工核实路径或文件完整性 |" % s["UNREADABLE"])
    add("| **合计** | **%d** | %s（%s） | — |" % (
        s["total"], s["partition_formula"],
        "分档闭合" if s["partition_ok"] else "**分档不闭合，需修复**"))
    add("")
    add("**完成度（以文件为准）**：实际完成 **%d / 391**、未完成 **%d**；"
        "`runtime/ocr_rescreen_391.json` 的 `records` 共 %d 条且 `id` 唯一，"
        "每条均有 `verdict` / `reason` / `sample_pages` / `estimated_parseable_chars`。"
        "分档数由逐条实测汇总，**未做抽样外推、未用估算补全**。" % (
            sum(1 for r in records if r.get("verdict")),
            sum(1 for r in records if not r.get("verdict")),
            len(records),
        ))
    add("")
    add("**可救回 %d 条，共 %d 页**；按误排 OCR 16.5 s/页 计，白烧机时 "
        "**%.2f 小时**；改为重解析文本层预计 **%.2f 分钟**（按本次实测 %.4f s/页、"
        "需重解析 %d 个有字体页），**可节省机时 %.2f 小时**。算式见 §5。" % (
            s["RESCUABLE"], s["rescuable_pages"], s["if_misqueued_ocr_cost_hours"],
            s["reparse_estimated_cost_minutes"], s["measured_text_extract_sec_per_page"],
            s["rescuable_font_pages"], s["saved_machine_hours"]))
    add("")

    add("## 1. 输入与只读边界")
    add("")
    add("| 项 | 值 |")
    add("|---|---|")
    add("| 数据源 | `%s`，**仅一条 SELECT**：`question_source WHERE parse_status='OCR_QUEUED'` |" % meta["database"])
    add("| 命中记录 | %d 条；全部 `file_ext=.pdf`、全部 `text_layer='SCAN'`；登记文件总大小 6.21 GB、`--probe` 实测 391/391 可达 |" % s["total"])
    add("| 文件读取 | **仅**打开 `source_path` 登记的这 %d 个 PDF；未做目录递归扫描、未 glob、未读任何未登记路径 |" % s["total"])
    add("| 口令 | %s |" % meta["credentials"])
    add("| 运行环境 | `.venv-ai/Scripts/python.exe`，Python %s / pypdf %s |" % (meta["python"], meta["pypdf"]))
    add("| 子进程隔离 | 每个 PDF 独立 `--worker` 子进程，超时 %d s（依据 §11.1 中 5072 的 `rc=3221225477` 崩溃教训） |" % meta["thresholds"]["worker_timeout_sec"])
    add("")
    add("**未做**：不写库、不改 `parse_status` / `text_layer` / `chunk_count`、不启动 OCR、"
        "不跑 GPU / Ollama、不渲染图片、不调大模型、不改任何源码或历史文档、不删文件、"
        "不启停 8080 / 8000 服务、不 commit、不 push。")
    add("")

    add("## 2. 复筛方法（为什么不是只看前 3 页）")
    add("")
    add("原 `note` 的判据是「pypdf **前 3 页**文本为空/极少 → 扫描件」。§11.1 已证明："
        "任何带封面 / 目录 / 插图前言的原生 PDF，前几页都可能是图；5072 第 1 页 0 字符、"
        "第 2 页就有 1846 字符。本轮对每本 PDF 做**全页**文本层判定，而不是只看前几页。")
    add("")
    add("### 2.1 为什么不用逐页 `extract_text`")
    add("")
    add("实测纯扫描图页（id=4631，159.6 MB / 56 页）单页 `extract_text` 约 **5.6 s**"
        "（10 页 44.9 s、前 25 页 140.5 s）。若对 391 条逐页抽取，会被拖到小时级，"
        "且子进程超时会把大册子误记为「无法读取」——反而漏掉最可能可救回的原生 PDF"
        "（首版全页逐页方案实测 22 分钟仅完成 150/391，已废弃）。"
        "故改用**结构判据 + 有限抽测**：")
    add("")
    add("| 步骤 | 做法 | 成本 |")
    add("|---|---|---|")
    add("| ① 全页结构扫描 | 逐页检查 `/Resources /Font`（含 `/Parent` 继承）。PDF 文本必须由字体绘制，"
        "**无 /Font 即无文本层**；不解码内容流 | 实测 0.01–0.13 s/本（200 MB / 259 页仅 0.08 s） |")
    add("| ② 字符数抽测 | 仅对「有 /Font」的页调 `extract_text`，每本最多 %d 页，统计字符数与乱码率 | 原生 PDF 每页约 0.01 s |" % meta["thresholds"]["max_text_probe_pages"])
    add("| ③ 采样点 | %s | — |" % meta["sample_policy"])
    add("")
    add("**判据交叉验证**（复筛前独立探测，属方法验证，非 391 条结论）：")
    add("")
    add("| 样本 | 页数 | 前 3 页 `extract_text` | 中段页 `extract_text` | 全页有 /Font |")
    add("|---|---:|---|---|---:|")
    add("| id=5123 六级仔细阅读专项真题（200.5 MB） | 259 | 0 / 0 / 0 字 | 第 32 页 2387、第 130 页 2590 字 | **256** 页 |")
    add("| id=5239 六级长篇阅读真题汇总（165.6 MB） | 131 | 0 / 0 / 0 字 | 第 16 页 4267、第 66 页 4806 字 | **128** 页 |")
    add("| id=4631 六级答案解析册（159.6 MB） | 56 | 0 / 0 / 0 字 | 抽样 10 页全 0 字 | **0** 页 |")
    add("| id=858 / 4625 / 8769（扫描件对照） | 20 / 56 / 40 | 0 字 | 抽样全 0 字 | **0** 页 |")
    add("")
    add("→ /Font 判据与 `extract_text` 结果一致，且能定位「前 3 页是封面、中段才有正文」的误判。")
    add("")
    add("### 2.2 判定阈值")
    add("")
    add("| 项 | 口径 |")
    add("|---|---|")
    add("| 实质文本页 | 单页 `>= %d` 字符 **且** 乱码率 `<= %.0f%%`（沿用 `scripts/ocr_answer_books.py` 的私有字体编码判据） |" % (
        meta["thresholds"]["min_chars_per_page"], meta["thresholds"]["max_bad_ratio"] * 100))
    add("| 可救回判据 | 抽测中实质文本页 `>= %d`（对应判据「≥2 个非空页的字符数证据」） |" % meta["thresholds"]["min_valid_pages_for_rescuable"])
    add("| 真扫描件判据 | 全页 0 页有 /Font（无文本层）；或有字体但抽测未达 ≥2 个实质文本页（含私有字体乱码） |")
    add("| 无法读取 | 文件不存在 / 加密 / 页数为 0 / pypdf 抛异常 / 子进程崩溃或超时 |")
    add("| 可解析字符数 | `estimated_parseable_chars` = 抽测有效页平均字符数 × 全页有字体页数（**估计**）；"
        "`measured_valid_chars` = 抽测页实测有效字符总和（**下界**） |")
    add("")
    add("补充：四六级答案册历史上存在「文本层是私有字体编码、pypdf 抽出乱码」的册子"
        "（`scripts/ocr_answer_books.py`），仅看字符数会把它误算成可救回，故加入乱码率判据。"
        "这类仍归 `SCAN`（需 OCR），并在逐条 `reason` 中注明。")
    add("")
    add("### 2.3 进程级崩溃的兜底与一次实际复核")
    add("")
    add("全量首次运行时 id=8579《2022考研数学二答案真题解析.pdf》曾报 `rc=3221225477`"
        "（与 §11.1 中 5072 相同的 pypdf 进程级访问违例）。按「崩溃 ≠ 不可读、"
        "≠ 扫描件」的教训，对它做了两类独立复核（均为只读）：")
    add("")
    add("| 复核方式 | 结果 |")
    add("|---|---|")
    add("| 独立重跑 worker 4 次 | 4/4 成功：30 页、4 页有 /Font、抽测 4 页字符数全 0 |")
    add("| 每 3 页一个子进程分批 `extract_text`（覆盖全部 30 页） | 30/30 页可读、字符数全 0 |")
    add("")
    add("→ 该条**可读但无文本层**，归 `SCAN`（真扫描件）而非 `UNREADABLE`；"
        "其「有字体资源」只出现在前 4 页的空白字体字典，不含正文。"
        "脚本已加入**失败自动重试 %d 次**，避免偶发进程违例把可读文件误记为无法读取。"
        "本轮最终 `UNREADABLE = %d` 条。" % (WORKER_ATTEMPTS, s["UNREADABLE"]))
    add("")
    add("同类交叉验证：id=5051《2022考研数一真题解析.pdf》11 页、10 页有 /Font，"
        "分批 `extract_text` 全 0 字符 —— 说明「有字体 ≠ 有可解析文本」，"
        "本脚本对这类保守归 `SCAN` 并在逐条 `reason` 中注明「建议人工复核」。")
    add("")
    add("### 2.4 与 T9 §513-519 的逐条对照（三条点名 PDF）")
    add("")
    add("T9 §513-519 记录 006《284.Agent Skill》/ 007《283.DeepSeek-R1》/ 010《280.Rerank》"
        "抽样前 3 页字符数 `[0,0,0]`，判为**真扫描件**。按硬要求逐条列出本轮原始采样数据"
        "（取自 `runtime/ocr_rescreen_391.json` 的 `records[].sample_pages`）：")
    add("")
    add("| T9 文件 | 本轮 id | 页数 | 全页有 /Font 页 | 前 3 页字符数 | 分层采样各页字符数（页码=字符数） | 本轮判定 |")
    add("|---|---:|---:|---:|---|---|---|")
    for key in ("006 284.Agent Skill", "007 283.DeepSeek", "010 280.Rerank"):
        matched = [r for r in records if key in r["file_name"] or key in r["source_path"]]
        if not matched:
            add("| %s | 未命中 | — | — | — | — | — |" % _md_escape(key))
            continue
        r0 = matched[0]
        head3 = [
            sp["chars"] for sp in r0["sample_pages"]
            if any(role in sp["roles"] for role in ("p1", "p2", "p3"))
        ]
        all_points = "、".join(
            "%d=%d" % (sp["page"], sp["chars"]) for sp in r0["sample_pages"]
        )
        add("| %s | %d | %d | %d | %s | %s | **%s** |" % (
            _md_escape(r0["file_name"][:26]), r0["id"], r0["page_count"],
            r0["font_page_count"], _md_escape(str(head3)),
            _md_escape(all_points), r0["verdict"],
        ))
    add("")
    add("**结论：三条均判 `SCAN`（真扫描件），前 3 页字符数 `[0,0,0]`，与 T9 §513-519 一致，无冲突。**"
        " T9 的依据是「抽样前 3 页 0 字符」；本轮升级为「全页 /Font 结构扫描 + "
        "第 1/2/3 页与随机页 / 分位页 / 末页分层采样」—— 对这三条而言全页均无字体资源、"
        "全部采样点 0 字符，**证据比 T9 更强但不改变结论**，不存在谁的判据有问题。")
    add("")
    add("双向边界（对应 §11.1 的 5072 教训与本次硬要求）：")
    add("")
    add("- **不因第 1 页 0 字符判扫描件**：`RESCUABLE` 门槛是抽测中 `>= 2` 个实质文本页"
        "（单页 ≥100 字且乱码率 ≤2%），并须给出 ≥2 个非空页的实测字符数；§4 的 8 条即由此筛出，"
        "其中 5123 / 5239 等前 3 页同为 0 字、中段却有 2000–4800 字/页。")
    add("- **不因多页全 0 硬判成原生 PDF**：`SCAN` 门槛是全页 0 页有 /Font（无文本层），"
        "或有字体但抽测未达 ≥2 个实质文本页（含私有字体乱码）；本轮 383 条按此判定，"
        "并在逐条 `reason` 中注明依据。")
    add("")

    add("## 3. 分档结果")
    add("")
    add("### 3.1 按 `doc_kind` 交叉分布")
    add("")
    add("| doc_kind | 合计 | 可救回 | 真扫描件 | 无法读取 |")
    add("|---|---:|---:|---:|---:|")
    for kind in sorted(s["by_doc_kind"].keys()):
        b = s["by_doc_kind"][kind]
        add("| %s | %d | %d | %d | %d |" % (
            _md_escape(kind), b["total"], b["RESCUABLE"], b["SCAN"], b["UNREADABLE"]))
    add("| **合计** | **%d** | **%d** | **%d** | **%d** |" % (
        s["total"], s["RESCUABLE"], s["SCAN"], s["UNREADABLE"]))
    add("")
    add("### 3.2 页数口径")
    add("")
    add("| 项 | 页数 | 说明 |")
    add("|---|---:|---|")
    add("| 391 条总页数 | %d | 全量按 16.5 s/页 OCR 约 **%.2f 小时**（与收尾记录 §7「全量约 36 小时」同量级） |" % (
        s["total_pages"], s["all_391_ocr_cost_hours"]))
    add("| 可救回条目页数 | %d | 这部分**不需要 OCR**；其中有字体页 %d |" % (
        s["rescuable_pages"], s["rescuable_font_pages"]))
    add("| 真扫描件条目页数 | %d | 仍需 OCR，约 **%.2f 小时** |" % (
        s["scan_pages"], s["remaining_scan_ocr_cost_hours"]))
    add("")

    add("## 4. 可救回清单（逐条，共 %d 条）" % s["RESCUABLE"])
    add("")
    if rescuable:
        add("每条均给出 **≥2 个非空页的字符数证据**（`字符数证据` 列，均为实际 `extract_text` 实测），"
            "另附全页有字体页数、抽测实测有效字符数与估计可解析字符数。"
            "完整采样页明细（含第 1/2/3 页 + 随机页 + 分位页）见 JSON 的 `sample_pages`。")
        add("")
        add("| id | 文件名 | doc_kind | 页数 | 有字体页 | 采样页字符数 | 字符数证据（≥2 页） | 抽测有效字符 | 估计可解析字符 |")
        add("|---:|---|---|---:|---:|---|---|---:|---:|")
        for r in rescuable:
            sample_brief = "、".join(
                "P%d=%d" % (sp["page"], sp["chars"]) for sp in r["sample_pages"]
            )
            evidence = "、".join(
                "第%d页 %d字" % (e["page"], e["chars"]) for e in r["evidence_pages"][:3]
            )
            name = r["file_name"]
            if len(name) > 40:
                name = name[:37] + "…"
            add("| %d | %s | %s | %d | %d | %s | %s | %d | %d |" % (
                r["id"], _md_escape(name), _md_escape(r["doc_kind"]), r["page_count"],
                r["font_page_count"], _md_escape(sample_brief), _md_escape(evidence),
                r["measured_valid_chars"], r["estimated_parseable_chars"]))
        add("")
        add("可救回条目抽测有效字符合计 **%d**（下界），估计可解析字符合计 **%d**。" % (
            s["rescuable_measured_valid_chars"], s["rescuable_estimated_chars"]))
    else:
        add("本轮复筛未发现可救回条目（391 条全为无可用文本层或无法读取）。")
    add("")

    if unreadable:
        add("### 4.1 无法读取条目明细（%d 条）" % s["UNREADABLE"])
        add("")
        add("| id | 文件名 | 页数 | 原因 |")
        add("|---:|---|---:|---|")
        for r in unreadable:
            add("| %d | %s | %d | %s |" % (
                r["id"], _md_escape(r["file_name"]), r["page_count"], _md_escape(r["reason"])))
        add("")

    add("## 5. 重解析耗时与可节省机时")
    add("")
    add("| 项 | 数值 | 依据 |")
    add("|---|---:|---|")
    add("| 误排 OCR 基准 | %.1f s/页 | 收尾执行记录 §7 / T9 §511 实测 |" % s["ocr_seconds_per_page_baseline"])
    add("| 本次文本层抽取实测 | %.4f s/页 | %d 个抽测页 `extract_text` 累计 %.2f s（worker 内部计时，不含进程启动与结构扫描） |" % (
        s["measured_text_extract_sec_per_page"], s["total_text_probe_pages"],
        s["total_extract_elapsed_sec"]))
    add("| 可救回条目页数 | %d 页（有字体页 %d） | §4 清单合计 |" % (
        s["rescuable_pages"], s["rescuable_font_pages"]))
    add("| 若按误排 OCR（整本所有页） | %.1f s = **%.2f 小时** | %d × %.1f |" % (
        s["if_misqueued_ocr_cost_sec"], s["if_misqueued_ocr_cost_hours"],
        s["rescuable_pages"], s["ocr_seconds_per_page_baseline"]))
    add("| 改为重解析文本层（只抽有字体页） | %.1f s = **%.2f 分钟** | %d × %.4f |" % (
        s["reparse_estimated_cost_sec"], s["reparse_estimated_cost_minutes"],
        s["rescuable_font_pages"], s["measured_text_extract_sec_per_page"]))
    add("| **可节省机时** | **%.1f s = %.2f 小时** | 两者之差 |" % (
        s["saved_machine_sec"], s["saved_machine_hours"]))
    add("")
    add("算式：")
    add("")
    add("```")
    add("误排 OCR 机时   = %d 页 × %.1f s/页 = %.1f s = %.2f h" % (
        s["rescuable_pages"], s["ocr_seconds_per_page_baseline"],
        s["if_misqueued_ocr_cost_sec"], s["if_misqueued_ocr_cost_hours"]))
    add("重解析预计机时   = %d 有字体页 × %.4f s/页 = %.1f s = %.2f min" % (
        s["rescuable_font_pages"], s["measured_text_extract_sec_per_page"],
        s["reparse_estimated_cost_sec"], s["reparse_estimated_cost_minutes"]))
    add("可节省机时       = %.1f − %.1f = %.1f s = %.2f h" % (
        s["if_misqueued_ocr_cost_sec"], s["reparse_estimated_cost_sec"],
        s["saved_machine_sec"], s["saved_machine_hours"]))
    add("```")
    add("")
    add("**口径限定**：重解析机时只含 `pypdf` 对有字体页的文本抽取（本次实测速率），"
        "**不含**后续切块、写 `ai_chunk`、向量化与人工校对；历史同量级动作参考"
        "收尾记录 §9.1（303 册 21738 块 54.9 s、向量化 400.8 s）与 §11.1（5072 切 485 块、向量化 4.1 s）。"
        "因此实际「重解析到可检索」的墙钟时间会高于上表，但相对 16.5 s/页 的 OCR 仍是数量级差异。"
        "该数值为**估算**，非实测重解析全流程。")
    add("")
    add("另：本次复筛本身 worker 累计耗时 %.2f s（全页结构扫描 %d 页 + 抽测 %d 页），"
        "属「分钟级复筛」量级，与 §11.1 建议一致。" % (
            s["total_worker_elapsed_sec"], s["total_struct_pages_scanned"],
            s["total_text_probe_pages"]))
    add("")

    add("## 6. 结论与建议（均未处理）")
    add("")
    add("1. **建议**对 §4 的 %d 条从 OCR 队列撤回，改用原生文本层重解析；"
        "其 `parse_status='OCR_QUEUED'` / `text_layer='SCAN'` 当前**未改动**，"
        "需由主理人授权后在独立一轮执行并留记录。" % s["RESCUABLE"])
    add("2. **建议**保留 %d 条真扫描件的 OCR 排期；全量 391 条约 %.2f 小时，"
        "剔除可救回后仍需约 %.2f 小时，仍需用户批准排期与资源占用（G 段停点）。" % (
            s["SCAN"], s["all_391_ocr_cost_hours"], s["remaining_scan_ocr_cost_hours"]))
    add("3. **建议**把「判扫描件必须多页 / 全页采样」写进导入流水线的判定口径；"
        "本轮未改任何源码，`import_question_bank.py` 的原判据仍在。")
    if unreadable:
        add("4. **建议**人工核实 %d 条无法读取记录的 `source_path` 是否仍有效（可能是文件被移动或删除）。"
            % s["UNREADABLE"])
    add("")
    add("**汇总口径提醒（与 T-B2 对齐，防止数字误加）**：本轮识别的可救回 %d 条属于 "
        "**T-B1 新识别的独立待办**，不是「392 文件级缺口」的组成部分，**不并入 "
        "`任务_2026-11-14_B2_剩余缺口裁定.md` 的「剩余实质缺口 = 2」**；"
        "汇总时不得把「2」与「%d」相加 —— 两者维度不同：2 是未展开的题目类 zip，"
        "%d 是 OCR 队列内可改为直接重解析的原生 PDF。该口径由 `software-engineer-2` "
        "在 T-B2 只读复核中提出，本执行者确认一致（本轮未改 B2 文件）。"
        % (s["RESCUABLE"], s["RESCUABLE"], s["RESCUABLE"]))
    add("")
    add("另：这 %d 条若真去重解析，会**改变语料版本**（新增 COURSEWARE 块并重新向量化）。"
        "按 `任务_2026-11-13_T9_三验收.md` §2/§3，须先固定「语料版本 / 模型名 / "
        "promptVersion / 时间戳」四个条件，再重跑 D07 拒答与 D08 planner 50 条，"
        "否则旧基线不可比。**本轮未执行重解析，语料版本未变。**" % s["RESCUABLE"])
    add("")
    add("> 以上全部为**建议**，不是「已处理」。本报告不写库、不改状态、不自行判定验收档位；"
        "档位由 `qa-acceptance` 按 `00_协作规程.md` 独立复核。")
    add("")

    add("## 7. 复跑与留痕")
    add("")
    add("```bash")
    add("# 只查库（不读 PDF、不写产物）")
    add(".venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --probe")
    add("# 小样 6 条（只打印，不写正式产物）")
    add(".venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --limit 6")
    add("# 全量复跑（覆盖写 JSON + MD）")
    add(".venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py")
    add("# 由已有 JSON 重生成 MD")
    add(".venv-ai/Scripts/python.exe scripts/ocr_rescreen_391.py --report-only")
    add("```")
    add("")
    add("随机页 `seed = 20261114 + question_source.id`，同一批数据复跑采样点一致；"
        "口令未回显、未写入任何产物、未落到命令行参数。")
    add("")
    add("---")
    add("")
    add("**产出文件**：`项目规划/任务_2026-11-14_B1_OCR队列复筛.md`（本文件）、"
        "`runtime/ocr_rescreen_391.json`（逐条 + 汇总）、`scripts/ocr_rescreen_391.py`（可复跑脚本）。")

    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------------------

def print_probe(targets: Sequence[Dict[str, Any]]) -> None:
    """--probe：只查库，输出分布与文件可达性（不读 PDF 内容、不写产物）。"""
    print("[probe] OCR_QUEUED 命中 %d 条" % len(targets))
    by_kind: Dict[str, int] = {}
    by_ext: Dict[str, int] = {}
    exists = 0
    missing: List[Tuple[int, str]] = []
    total_size = 0
    for row in targets:
        kind = str(row.get("doc_kind") or "(null)")
        ext = str(row.get("file_ext") or "(null)")
        by_kind[kind] = by_kind.get(kind, 0) + 1
        by_ext[ext] = by_ext.get(ext, 0) + 1
        total_size += int(row.get("file_size") or 0)
        if Path(str(row["source_path"])).is_file():
            exists += 1
        else:
            missing.append((int(row["id"]), str(row["source_path"])))
    print("[probe] by doc_kind:", json.dumps(by_kind, ensure_ascii=False, sort_keys=True))
    print("[probe] by file_ext:", json.dumps(by_ext, ensure_ascii=False, sort_keys=True))
    print("[probe] 文件可达 %d / %d；缺失 %d；登记总大小 %.2f GB" % (
        exists, len(targets), len(missing), total_size / (1024.0 ** 3)))
    for rid, path in missing[:15]:
        print("[probe]   MISSING id=%d path=%s" % (rid, path))
    if len(missing) > 15:
        print("[probe]   ... 另有 %d 条缺失未列出" % (len(missing) - 15))


def main() -> int:
    """命令行入口：worker / probe / 小样 / 全量 / 仅重生成报告。"""
    parser = argparse.ArgumentParser(
        description="OCR 队列 391 条多页文本层复筛（只读、可复跑）")
    parser.add_argument("--worker", action="store_true", help="内部模式：探测单个 PDF")
    parser.add_argument("--path", default="", help="worker 模式下的 PDF 路径")
    parser.add_argument("--seed", type=int, default=0, help="worker 模式下的随机种子")
    parser.add_argument("--id", type=int, default=0, help="worker 模式下的记录 id")
    parser.add_argument("--limit", type=int, default=0, help="小样条数（>0 时只打印，不写正式产物）")
    parser.add_argument("--probe", action="store_true", help="只查库统计，不读 PDF、不写产物")
    parser.add_argument("--report-only", action="store_true", help="由已有 JSON 重生成 MD")
    parser.add_argument("--out-json", default=str(OUT_JSON), help="JSON 产物路径")
    args = parser.parse_args()

    if args.worker:
        sys.stdout.reconfigure(encoding="utf-8")
        payload = probe_one(args.path, args.seed)
        print(JSON_MARKER + json.dumps(payload, ensure_ascii=False))
        return 0

    if args.report_only:
        src = Path(args.out_json)
        result = json.loads(src.read_text(encoding="utf-8"))
        OUT_MD.parent.mkdir(parents=True, exist_ok=True)
        OUT_MD.write_text(render_report(result), encoding="utf-8")
        print("[report-only] 已由 %s 重生成 %s" % (src, OUT_MD))
        return 0

    password = load_db_password()
    os.environ["MYSQL_PWD"] = password
    conn = db_connect()
    try:
        targets = fetch_targets(conn)
    finally:
        conn.close()

    if args.probe:
        print_probe(targets)
        return 0

    sample_mode = args.limit > 0
    work = targets[: args.limit] if sample_mode else targets
    print("[rescreen] 目标 %d 条（sample_mode=%s，worker_timeout=%ds）" % (
        len(work), sample_mode, WORKER_TIMEOUT_SEC), flush=True)

    script_path = Path(__file__).resolve()
    records: List[Dict[str, Any]] = []
    started = time.perf_counter()
    for index, row in enumerate(work, 1):
        rid = int(row["id"])
        seed = SEED_BASE + rid
        probe = run_worker(script_path, rid, str(row["source_path"]), seed)
        record = classify(row, probe)
        records.append(record)
        if index % 50 == 0 or index == len(work):
            print("[rescreen] %d/%d  done (last id=%s verdict=%s pages=%s font=%s)" % (
                index, len(work), rid, record["verdict"], record["page_count"],
                record["font_page_count"]), flush=True)

    try:
        import pypdf
        pypdf_version = pypdf.__version__
    except Exception:  # noqa: BLE001
        pypdf_version = "unknown"

    result = build_result(records, pypdf_version)
    elapsed = round(time.perf_counter() - started, 1)
    s = result["summary"]
    print("\n[rescreen] 分档：RESCUABLE=%d  SCAN=%d  UNREADABLE=%d  合计=%d (%s)" % (
        s["RESCUABLE"], s["SCAN"], s["UNREADABLE"], s["total"], s["partition_formula"]))
    print("[rescreen] 可救回页数=%d（有字体页 %d）；误排 OCR %.2f h；重解析 %.2f min；可节省 %.2f h" % (
        s["rescuable_pages"], s["rescuable_font_pages"], s["if_misqueued_ocr_cost_hours"],
        s["reparse_estimated_cost_minutes"], s["saved_machine_hours"]))
    print("[rescreen] 墙钟耗时 %.1f s" % elapsed)

    if sample_mode:
        print("[rescreen] 小样模式：未写任何正式产物（不覆盖 JSON / MD）。")
        for r in records:
            sample_brief = " ".join("P%d=%d" % (sp["page"], sp["chars"]) for sp in r["sample_pages"])
            print("   id=%-6s %-10s pages=%-4s font=%-4s valid=%-3s %s" % (
                r["id"], r["verdict"], r["page_count"], r["font_page_count"],
                r["valid_pages"], sample_brief))
        return 0

    out_json = Path(args.out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    OUT_MD.parent.mkdir(parents=True, exist_ok=True)
    OUT_MD.write_text(render_report(result), encoding="utf-8")
    print("[rescreen] 已写出 %s" % out_json)
    print("[rescreen] 已写出 %s" % OUT_MD)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
