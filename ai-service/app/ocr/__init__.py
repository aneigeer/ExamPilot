"""OCR 子包：PaddleOCR 懒加载 + 题目结构化。

- :mod:`app.ocr.paddle_ocr_engine` —— PaddleOCR 封装（懒加载，缺依赖 503）
- :mod:`app.ocr.structure_parser` —— 题号/选项/题型正则解析（纯 Python，零依赖）
"""

from __future__ import annotations

__all__ = ["paddle_ocr_engine", "structure_parser"]
