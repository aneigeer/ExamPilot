"""大 PDF 文本抽取（pypdf，纯 Python 轻量）。

**定位分工（很关键）**：

* Java 侧 **PDFBox** 抽不动的文件 = **扫描件**（整页是图片，没有文本层）→ 交给 **OCR**。
* 本模块处理的是"文本层存在但 PDFBox 抽出来是乱码/为空"的边缘情况，
  以及需要快速预判"这个 PDF 到底有没有文本层"的场景。

因此调用顺序应该是：
    ① Java PDFBox 抽文本 → 有文本直接用
    ② 没文本 → Java 调 ``/v1/ocr``（PaddleOCR 走图片流程）

本模块提供 ``has_text_layer`` 供排障与 ``/v1/ocr`` 的 ``file_path`` 分支做前置判断：
若 PDF 存在文本层，优先直接抽文本而不是走 OCR（快一个数量级）。
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Optional

from ..errors import OcrStructureError

logger = logging.getLogger(__name__)


def is_available() -> bool:
    """``pypdf`` 是否已安装（不 import，仅探测）。

    Returns:
        已安装返回 ``True``。
    """
    import importlib.util

    return importlib.util.find_spec("pypdf") is not None


def extract_text(
    file_path: str, *, page_from: int = 1, page_to: Optional[int] = None
) -> List[str]:
    """抽取 PDF 文本，按页返回。

    Args:
        file_path: PDF 路径。
        page_from: 起始页（1 起）。
        page_to: 结束页（含）；``None`` 表示到末页。

    Returns:
        与请求页范围对应的文本列表。

    Raises:
        OcrStructureError: pypdf 未安装、文件不存在或读取失败（调用方应改走 OCR 通道）。
    """
    try:
        from pypdf import PdfReader  # type: ignore[import-not-found]  # noqa: PLC0415
    except ImportError as exc:
        raise OcrStructureError(
            "PDF 文本抽取不可用：未安装 pypdf；如为扫描件请改用 OCR 通道",
            detail="pypdf missing",
        ) from exc

    path = Path(file_path)
    if not path.is_file():
        raise OcrStructureError(f"PDF 文件不存在：{file_path}", detail=file_path)
    try:
        reader = PdfReader(str(path))
        total: int = len(reader.pages)
        start: int = max(1, page_from)
        end: int = total if page_to is None else min(page_to, total)
        pages: List[str] = []
        for index in range(start - 1, end):
            pages.append(reader.pages[index].extract_text() or "")
        return pages
    except Exception as exc:  # noqa: BLE001
        raise OcrStructureError(
            f"PDF 文本抽取失败：{type(exc).__name__}", detail=str(exc)
        ) from exc


def has_text_layer(file_path: str, *, probe_pages: int = 3) -> bool:
    """判断 PDF 前若干页是否存在文本层（用于区分"文本 PDF"与"扫描件"）。

    Args:
        file_path: PDF 路径。
        probe_pages: 探测页数。

    Returns:
        有文本层返回 ``True``；pypdf 未装或读取失败返回 ``False``（调用方降级到 OCR）。
    """
    if not is_available():
        return False
    try:
        pages = extract_text(file_path, page_from=1, page_to=probe_pages)
    except OcrStructureError:
        return False
    return any((page or "").strip() for page in pages)
