"""``POST /v1/ocr`` —— 试卷 OCR 识别（懒加载 PaddleOCR）。

**降级语义（硬性要求）**：

* paddleocr 未安装 / 模型缺失 / ``OCR_ENABLED=false`` → **503 / 5205**
  → Java 侧 ``OcrService`` 返回"OCR 不可用，请手动录入"，**管理员可手工建题，功能不断**。
* 启动时**不加载** OCR 模型：健康检查只做 ``importlib.util.find_spec`` 探测。

**本接口不写题库**：只返回"文本块 + 块级置信度"。
识别结果永远进 ``question.audit_status='DRAFT'``，由管理员左右对照原图人工确认
（复用项目既有的 ``AuditService.approveQuestion`` 审核流，零新增机制）。

三级兜底（本服务负责 G1 的数据准备，G0/G2 由 Java 侧落地）：

* 块置信度 ``>= 0.90`` → Java 侧自动填充
* 块置信度 ``< 0.90`` → Java 侧留空并高亮，强制人工补全
* 题干置信度 ``< 0.75`` → Java 侧判为"需人工重录"，不入库
"""

from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import List, Optional, Tuple

from fastapi import APIRouter

from ..config import get_settings
from ..errors import (
    OcrStructureError,
    OcrUnavailableError,
    ParamError,
)
from ..ocr import structure_parser
from ..ocr.paddle_ocr_engine import PaddleOcrEngine, get_ocr_engine
from ..schemas import OcrBlock, OcrRequest, OcrResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ocr"])

#: 可交给 OCR 的图片扩展名
_IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tiff", ".tif"}


@router.post(
    "/v1/ocr",
    response_model=OcrResponse,
    summary="试卷 OCR 识别（PaddleOCR 懒加载）",
    description=(
        "输入图片路径或 base64，返回文本块列表（type=stem/option/answer）与块级置信度。"
        "**首次调用会加载模型（约 5-15 秒），之后单页约 1-3 秒**。"
        "未安装 paddleocr 时返回 503，Java 侧降级为人工录入，其余功能不受影响。"
    ),
)
def ocr(request: OcrRequest) -> OcrResponse:
    """识别试卷图片。

    Args:
        request: 图片来源（二选一）与题型提示。

    Returns:
        :class:`OcrResponse`，``blocks`` 已按行序排好并标注类型与置信度。

    Raises:
        app.errors.ParamError: 两个来源都没给（400）。
        app.errors.OcrUnavailableError: 未装依赖 / 文件不存在 / 图片解码失败（503，5205）。
        app.errors.OcrRecognizeFailedError: 识别过程出错（503，5206）。
        app.errors.OcrStructureError: PDF 无文本层等结构化失败（503，5207）。
    """
    if not request.has_source():
        raise ParamError("请提供 file_path 或 image_base64 其中之一")

    settings = get_settings()
    engine = get_ocr_engine()
    started = time.perf_counter()

    # —— ① 解析图片来源
    if request.file_path and request.file_path.strip():
        blocks, confidence = _recognize_path(engine, request.file_path.strip())
    else:
        blocks, confidence = engine.recognize_base64(request.image_base64 or "")

    # —— ② 结构化分块（纯 Python，零依赖，缺 paddleocr 也能单测）
    blocks = _structure_blocks(blocks, request.question_type_hint)

    took_ms = int((time.perf_counter() - started) * 1000)
    logger.info(
        "[OCR] blocks=%d confidence=%.3f took_ms=%d", len(blocks), confidence, took_ms
    )
    return OcrResponse(
        blocks=blocks,
        confidence=confidence,
        page_no=request.page_from,
        took_ms=took_ms,
        stem_confidence_threshold=settings.ocr.stem_confidence_threshold,
        need_manual_input=True,  # 恒为 true：识别结果永不自动入库
    )


def _recognize_path(engine: PaddleOcrEngine, file_path: str) -> Tuple[List[OcrBlock], float]:
    """按路径识别（图片直接识别，PDF 先判断有无文本层）。

    Args:
        engine: OCR 引擎。
        file_path: 文件路径。

    Returns:
        ``(blocks, confidence)``。

    Raises:
        OcrUnavailableError: 文件不存在或类型不支持（重试无用）。
        OcrStructureError: PDF 无文本层（需人工补录）。
    """
    path = Path(file_path)
    if not path.is_file():
        raise OcrUnavailableError(f"OCR 文件不存在：{file_path}", detail=file_path)

    suffix = path.suffix.lower()
    if suffix in _IMAGE_SUFFIXES:
        return engine.recognize_file(file_path)

    if suffix == ".pdf":
        # PDF：先用 pypdf 快速判断有无文本层（比 OCR 快一个数量级）
        from ..ocr import pdf_text

        if pdf_text.is_available() and pdf_text.has_text_layer(file_path):
            pages = pdf_text.extract_text(file_path, page_from=1, page_to=1)
            text = pages[0] if pages else ""
            if text.strip():
                return (
                    [
                        OcrBlock(
                            type=block_type,
                            text=line.strip(),
                            bbox=None,
                            confidence=1.0,
                        )
                        for block_type, line in _classify_lines(text)
                        if line.strip()
                    ],
                    1.0,
                )
        # 无文本层 = 扫描件 → 需先把 PDF 转成图片再走 OCR 通道。
        # 本服务不做 PDF 转图（需要 poppler 等系统级依赖，Windows 部署成本高），
        # 因此明确报结构化失败，由 Java 侧转图后重新提交，或直接人工补录。
        raise OcrStructureError(
            "PDF 无文本层（扫描件），本服务不做 PDF 转图；"
            "请由 Java 侧逐页转成图片后重新提交，或直接人工补录"
        )

    raise OcrUnavailableError(
        f"不支持的 OCR 文件类型：{suffix}（仅支持图片与 PDF）", detail=file_path
    )


def _classify_lines(text: str) -> List[Tuple[str, str]]:
    """把多行文本按行分类。

    Args:
        text: 整段文本。

    Returns:
        ``[(type, line), ...]``。
    """
    result: List[Tuple[str, str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            result.append((structure_parser.classify_block(stripped), stripped))
    return result


def _structure_blocks(blocks: List[OcrBlock], hint: Optional[str]) -> List[OcrBlock]:
    """给原始块标注类型（stem/option/answer），并对答案块补充归一化选项。

    Args:
        blocks: OCR 原始块（``type`` 均为 unknown）。
        hint: 题型提示。

    Returns:
        标注类型后的块列表；输入为空时返回空列表。
    """
    if not blocks:
        return []
    for block in blocks:
        block.type = structure_parser.classify_block(block.text, hint)
    return blocks
