"""OCR 文本结构化：把识别出的文本行归类为 题干 / 选项 / 答案。

**纯 Python + 正则，零第三方依赖** —— 这样即使 paddleocr 没装，
结构化逻辑依然可以被单元测试覆盖（``tests/test_ocr_structure.py``）。

映射到项目既有的 22 类题型枚举（``QuestionType``，**不新增题型**）：

============================  ==========================================
 识别特征                      映射题型
============================  ==========================================
 ``1.题干 A.xx B.xx C.xx D.xx``  ``SINGLE_CHOICE``
 含"多选"/"不定项"                ``MULTI_CHOICE``
 ``（）√ ×`` / A.正确 B.错误      ``TRUE_FALSE``
 题干含 ``____`` 下划线            ``FILL_BLANK``
 有背景材料 + 若干小题            ``READING``
 无选项无下划线的问句              ``SHORT_ANSWER``
 含代码特征 ``{}`` ``;`` ``#include``  ``PROGRAMMING``
============================  ==========================================
"""

from __future__ import annotations

import re
from typing import List, Optional, Tuple

#: 块类型常量（与 schemas.OcrBlock.type 对应）
TYPE_STEM = "stem"
TYPE_OPTION = "option"
TYPE_ANSWER = "answer"
TYPE_UNKNOWN = "unknown"

#: 题号行：``1.`` ``1、`` ``(1)`` ``第1题`` ``1．``（全角点）
_QUESTION_NO_PATTERN = re.compile(
    r"^\s*(?:第\s*)?[（(]?\s*(\d{1,3})\s*[)）]?\s*[.、．。:：]\s*(.*)$"
)
#: 题号后紧跟"题"字：``1、下列说法正确的是``
_QUESTION_NO_CN_PATTERN = re.compile(r"^\s*(\d{1,3})\s*[.、．。]?\s*题\s*[.、．。:：]?\s*(.*)$")
#: 选项行：``A.xx`` ``（B）xx`` ``C、xx``，支持全角字母
_OPTION_PATTERN = re.compile(r"^\s*[（(]?\s*([A-HＡ-Ｈa-h])\s*[)）.、．。:：]\s*(.+)$")
#: 答案行
_ANSWER_PATTERN = re.compile(
    r"^\s*(?:参考答案|正确答案|答案|答案[:：]|Answer)\s*[:：]?\s*(.+)$", re.IGNORECASE
)
#: 填空下划线
_BLANK_PATTERN = re.compile(r"_{2,}|＿{2,}")
#: 编程题特征
_CODE_PATTERN = re.compile(r"[{};]|#include\s*<|System\.out|printf\s*\(|def\s+\w+\s*\(|public\s+static")
#: 多选提示词
_MULTI_CHOICE_HINT = re.compile(r"多选|不定项|多项选择|多項")
#: 判断题提示词
_TRUE_FALSE_HINT = re.compile(r"[（(]\s*[)）]|√\s*[×xX]|判断正误|正确与否|A\.正确\s*B\.错误")


def classify_block(text: str, question_type_hint: Optional[str] = None) -> str:
    """判断单行文本的块类型。

    Args:
        text: 识别出的文本行。
        question_type_hint: 题型提示；给出时用于消歧（``TRUE_FALSE`` / ``FILL_BLANK`` 等）。

    Returns:
        ``stem`` / ``option`` / ``answer`` / ``unknown`` 之一。
    """
    stripped = (text or "").strip()
    if not stripped:
        return TYPE_UNKNOWN
    if _ANSWER_PATTERN.match(stripped):
        return TYPE_ANSWER
    if _OPTION_PATTERN.match(stripped) and not _QUESTION_NO_PATTERN.match(stripped):
        return TYPE_OPTION
    if question_type_hint in ("TRUE_FALSE", "FILL_BLANK", "PROGRAMMING", "SHORT_ANSWER"):
        return TYPE_STEM
    if _QUESTION_NO_PATTERN.match(stripped) or _QUESTION_NO_CN_PATTERN.match(stripped):
        return TYPE_STEM
    return TYPE_STEM if len(stripped) > 4 else TYPE_UNKNOWN


def split_question_number(text: str) -> Tuple[Optional[int], str]:
    """拆出题号与正文。

    Args:
        text: 原始文本行。

    Returns:
        ``(题号, 正文)``；无题号时题号为 ``None``。
    """
    stripped = (text or "").strip()
    match = _QUESTION_NO_CN_PATTERN.match(stripped) or _QUESTION_NO_PATTERN.match(stripped)
    if not match:
        return None, stripped
    return int(match.group(1)), match.group(2).strip()


def infer_question_type(stem: str, options: List[str]) -> Optional[str]:
    """推断题型（映射到项目既有枚举，不新增）。

    Args:
        stem: 题干正文。
        options: 选项文本列表（已去掉 ``A.`` 前缀）。

    Returns:
        题型枚举字符串；无法判定时返回 ``None``（由 Java 侧人工选择）。
    """
    text = (stem or "").strip()
    if not text:
        return None
    if _CODE_PATTERN.search(text):
        return "PROGRAMMING"
    if _BLANK_PATTERN.search(text):
        return "FILL_BLANK"
    if options:
        joined = " ".join(options)
        if _MULTI_CHOICE_HINT.search(text) or _MULTI_CHOICE_HINT.search(joined):
            return "MULTI_CHOICE"
        if _TRUE_FALSE_HINT.search(text) or _TRUE_FALSE_HINT.search(joined):
            return "TRUE_FALSE"
        if len(options) <= 2 and ("正确" in joined or "错误" in joined):
            return "TRUE_FALSE"
        if len(options) >= 2:
            return "SINGLE_CHOICE"
        return None
    if _TRUE_FALSE_HINT.search(text):
        return "TRUE_FALSE"
    if text.endswith(("？", "?", "。")) or "什么" in text or "如何" in text or "简述" in text:
        return "SHORT_ANSWER"
    return None


def normalize_options(raw_options: List[str]) -> List[Tuple[str, str]]:
    """把选项文本规范化为 ``(标签, 内容)`` 列表。

    Args:
        raw_options: 形如 ``["A. 苹果", "（B）香蕉"]`` 的原始列表。

    Returns:
        ``[("A", "苹果"), ("B", "香蕉"), ...]``。
    """
    normalized: List[Tuple[str, str]] = []
    for item in raw_options:
        match = _OPTION_PATTERN.match((item or "").strip())
        if match:
            normalized.append((match.group(1).upper().replace("Ａ", "A").replace("Ｂ", "B"), match.group(2).strip()))
        else:
            normalized.append((chr(ord("A") + len(normalized)), (item or "").strip()))
    return normalized
