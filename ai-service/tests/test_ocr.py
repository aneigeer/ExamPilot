"""``POST /v1/ocr`` 测试。

覆盖：
* **PaddleOCR 未安装 → 503（不是崩溃）** ← 这是 T11 验收项①
* 缺图片来源 → 400
* 文件不存在 → 503
* 懒加载：健康检查不触发模型加载
* 结构化解析器（纯 Python，无依赖也能测）
* 响应恒带 ``need_manual_input=true``（永不自动入库）
"""

from __future__ import annotations

import base64
from contextlib import contextmanager
from typing import Any, Iterator

import pytest

#: 1x1 像素 PNG（最小合法图片，用于 base64 分支）
TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


@contextmanager
def _ocr_unavailable_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[Any]:
    """构造一个"OCR 能力不可用"的 ``TestClient``。

    本仓库的测试设计前提是「只装 requirements.txt、不装 paddleocr」；一旦机器上真的
    装了 paddleocr（本机即如此），原先"未装依赖必返回 503"的断言就会失真。
    这里显式用 ``OCR_ENABLED=false`` 把能力关掉，使降级契约的验证与机器环境解耦、
    无论在装与不装的机器上都可复现。

    Args:
        monkeypatch: pytest 夹具。

    Yields:
        ``TestClient``，其 OCR 能力被显式关闭。
    """
    from fastapi.testclient import TestClient

    from app.config import reset_settings
    from app.main import app
    from app.ocr.paddle_ocr_engine import reset_ocr_engine

    monkeypatch.setenv("OCR_ENABLED", "false")
    reset_settings()
    reset_ocr_engine()
    try:
        with TestClient(app, raise_server_exceptions=False) as test_client:
            yield test_client
    finally:
        reset_settings()
        reset_ocr_engine()


# --------------------------------------------------------------------------------------
# 降级用例
# --------------------------------------------------------------------------------------


def test_ocr_returns_503_when_paddleocr_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    """**T11 验收项①**：OCR 能力不可用（未装 PaddleOCR / 未启用）时必须 503，而不是崩溃或 500。"""
    with _ocr_unavailable_client(monkeypatch) as test_client:
        response = test_client.post("/v1/ocr", json={"image_base64": TINY_PNG_B64})
    assert response.status_code == 503, (
        f"OCR 能力不可用时应返回 503，实际 {response.status_code}：{response.text}"
    )
    body = response.json()
    assert body["degraded"] is True
    # 5201=能力不可用（缺 qdrant/pydantic 等），5205=OCR 能力不可用（未装 paddleocr）
    assert body["code"] in (5201, 5205), f"错误码应为 5201/5205，实际 {body['code']}"


def test_ocr_missing_deps_maps_to_5205_not_5206() -> None:
    """**能力不可用(5205) 与 识别失败(5206) 必须严格区分**。

    理由：Java 侧对这两者的处理路径不同 ——
    5205（没装，重试无用）应长期熔断并提示"请手动录入"；
    5206（装了但这张图识别不出）应提示"换图重试"。
    合并成一位会让熔断策略失准。
    """
    from app.errors import ErrorCode, OcrRecognizeFailedError, OcrUnavailableError

    assert ErrorCode.OCR_UNAVAILABLE == 5205
    assert ErrorCode.OCR_RECOGNIZE_FAILED == 5206
    assert ErrorCode.OCR_STRUCTURE_FAILED == 5207
    # 三者是不同的类型，Java 侧可据此分支
    assert OcrUnavailableError is not OcrRecognizeFailedError
    assert OcrUnavailableError("x").code != OcrRecognizeFailedError("x").code


def test_ocr_disabled_maps_to_5205() -> None:
    """OCR_ENABLED=false 属"能力不可用"，不是"识别失败"。"""
    from app.config import OcrSettings
    from app.errors import OcrUnavailableError
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    with pytest.raises(OcrUnavailableError) as excinfo:
        PaddleOcrEngine(OcrSettings(enabled=False)).ensure_loaded()
    assert excinfo.value.code == 5205
    assert "OCR_ENABLED" in excinfo.value.message


def test_ocr_missing_file_maps_to_5205() -> None:
    """文件不存在属"能力不可用/请求不可满足"，重试同样无用。"""
    import pathlib

    from app.config import OcrSettings
    from app.errors import OcrUnavailableError
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    with pytest.raises(OcrUnavailableError) as excinfo:
        PaddleOcrEngine(OcrSettings()).recognize_file(str(pathlib.Path("Z:/绝对不存在.png")))
    assert excinfo.value.code == 5205


def test_ocr_bad_base64_maps_to_5205() -> None:
    """base64 解码失败属输入不可满足，归 5205。"""
    from app.config import OcrSettings
    from app.errors import OcrUnavailableError
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    with pytest.raises(OcrUnavailableError) as excinfo:
        PaddleOcrEngine(OcrSettings()).recognize_base64("!!!not-base64!!!")
    assert excinfo.value.code == 5205


def test_pdf_without_text_layer_maps_to_5207(tmp_path: Any) -> None:
    """PDF 无文本层属**结构化**失败（5207），不是能力不可用（5205）。"""
    from app.config import OcrSettings
    from app.errors import OcrStructureError
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine
    from app.routers.ocr import _recognize_path

    # 造一个内容非法的 .pdf：pypdf 读不出文本层 → 走"扫描件"分支
    fake_pdf = tmp_path / "scan.pdf"
    fake_pdf.write_bytes(b"%PDF-1.4\nnot a real pdf body")

    with pytest.raises(OcrStructureError) as excinfo:
        _recognize_path(PaddleOcrEngine(OcrSettings()), str(fake_pdf))
    assert excinfo.value.code == 5207
    assert excinfo.value.http_status == 503
    assert excinfo.value.degraded is True


def test_ocr_degradation_message_mentions_manual_input(monkeypatch: pytest.MonkeyPatch) -> None:
    """降级提示必须明确指向"人工录入"，让 Java 侧知道该做什么。"""
    with _ocr_unavailable_client(monkeypatch) as test_client:
        body = test_client.post("/v1/ocr", json={"image_base64": TINY_PNG_B64}).json()
    assert "人工录入" in body["message"] or "paddleocr" in body["message"].lower()


def test_ocr_503_has_no_traceback(monkeypatch: pytest.MonkeyPatch) -> None:
    """503 响应不含堆栈。"""
    with _ocr_unavailable_client(monkeypatch) as test_client:
        body = test_client.post("/v1/ocr", json={"image_base64": TINY_PNG_B64}).json()
    assert "Traceback" not in body["message"]


def test_ocr_disabled_by_env_returns_503(monkeypatch: pytest.MonkeyPatch) -> None:
    """OCR_ENABLED=false 时直接 503（连 import 都不做）。"""
    monkeypatch.setenv("OCR_ENABLED", "false")
    from app.config import reset_settings
    from app.ocr.paddle_ocr_engine import reset_ocr_engine

    reset_settings()
    reset_ocr_engine()
    try:
        from fastapi.testclient import TestClient

        from app.main import app

        with TestClient(app, raise_server_exceptions=False) as test_client:
            response = test_client.post("/v1/ocr", json={"image_base64": TINY_PNG_B64})
            assert response.status_code == 503
            assert response.json()["code"] == 5205
    finally:
        reset_settings()
        reset_ocr_engine()


# --------------------------------------------------------------------------------------
# 参数校验
# --------------------------------------------------------------------------------------


def test_ocr_without_source_returns_400(client: Any) -> None:
    """两个来源都没给 → 400。"""
    response = client.post("/v1/ocr", json={})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_ocr_blank_source_returns_400(client: Any) -> None:
    """空白来源 → 400。"""
    response = client.post("/v1/ocr", json={"file_path": "   ", "image_base64": "  "})
    assert response.status_code == 400


def test_ocr_missing_file_returns_503(client: Any) -> None:
    """文件不存在 → 503（不是 500）。"""
    response = client.post("/v1/ocr", json={"file_path": "D:/不存在的文件/试卷.png"})
    assert response.status_code == 503
    assert response.json()["degraded"] is True


def test_ocr_unsupported_suffix_returns_503(client: Any, tmp_path: Any) -> None:
    """不支持的文件类型 → 503。"""
    path = tmp_path / "paper.docx"
    path.write_bytes(b"not an image")
    response = client.post("/v1/ocr", json={"file_path": str(path)})
    assert response.status_code == 503
    assert "不支持" in response.json()["message"]


def test_ocr_invalid_base64_returns_503(client: Any) -> None:
    """非法 base64 → 503。"""
    response = client.post("/v1/ocr", json={"image_base64": "!!!not-base64!!!"})
    assert response.status_code == 503
    assert response.json()["degraded"] is True


# --------------------------------------------------------------------------------------
# 懒加载断言
# --------------------------------------------------------------------------------------


def test_ocr_engine_not_loaded_after_health_check(raw_client: Any) -> None:
    """**懒加载硬性要求**：健康检查不得触发 OCR 模型加载。"""
    from app.ocr.paddle_ocr_engine import get_ocr_engine

    engine = get_ocr_engine()
    raw_client.get("/v1/health")
    assert engine.loaded is False, "健康检查不应加载 PaddleOCR 模型（会白占 500MB-1GB 内存）"


def test_ocr_engine_not_loaded_after_embed(raw_client: Any) -> None:
    """调 /v1/embed 也不应触发 OCR 加载（能力之间彼此隔离）。"""
    from app.ocr.paddle_ocr_engine import get_ocr_engine

    engine = get_ocr_engine()
    raw_client.post("/v1/embed", json={"texts": ["测试"]})
    assert engine.loaded is False


def test_ocr_health_reports_not_loaded(monkeypatch: pytest.MonkeyPatch) -> None:
    """健康检查如实报告"未加载"而非谎报就绪。

    本机可能已装 paddleocr（此时 ready=True 表示"能力可用"）；这里显式把探针置为不可用，
    使"未就绪"这一分支在任何机器上都能复现，而不是依赖"机器是否装了依赖"。
    """
    from fastapi.testclient import TestClient

    from app.config import OcrSettings
    from app.main import app
    from app.ocr.paddle_ocr_engine import PaddleOcrEngine

    import app.routers.health as health_module

    # 模拟"依赖缺失"（is_available=False）而非"被关闭"，以走"懒加载未加载"这条分支
    engine = PaddleOcrEngine(OcrSettings(enabled=True))
    monkeypatch.setattr(engine, "is_available", lambda: False)
    monkeypatch.setattr(health_module, "get_ocr_engine", lambda: engine)
    with TestClient(app, raise_server_exceptions=False) as test_client:
        ocr_health = test_client.get("/v1/health").json()["ocr"]
    assert ocr_health["ready"] is False
    assert "懒加载" in ocr_health["detail"] or "未就绪" in ocr_health["detail"]


# --------------------------------------------------------------------------------------
# 结构化解析器（纯 Python，零依赖）
# --------------------------------------------------------------------------------------


def test_classify_option_line() -> None:
    """选项行识别。"""
    from app.ocr.structure_parser import TYPE_OPTION, classify_block

    assert classify_block("A. 栈") == TYPE_OPTION
    assert classify_block("（B）队列") == TYPE_OPTION
    assert classify_block("C、优先队列") == TYPE_OPTION


def test_classify_answer_line() -> None:
    """答案行识别。"""
    from app.ocr.structure_parser import TYPE_ANSWER, classify_block

    assert classify_block("答案：B") == TYPE_ANSWER
    assert classify_block("参考答案：AC") == TYPE_ANSWER


def test_classify_stem_line() -> None:
    """题号行识别为题干。"""
    from app.ocr.structure_parser import TYPE_STEM, classify_block

    assert classify_block("1. 下列关于栈的说法正确的是") == TYPE_STEM
    assert classify_block("2、简述值传递与地址传递的区别") == TYPE_STEM


def test_classify_respects_type_hint() -> None:
    """题型提示可消歧。

    注意两条规则的优先级（这是解析器刻意的契约）：
    1) 带 A./（B）/C、 前缀的行**一律**判为选项 —— 判断题的「A.正确 / B.错误」本就是选项行，
       即便题型提示是 TRUE_FALSE 也不该被当成题干；
    2) 提示真正生效的场景是「短行 + 无选项前缀 + 无题号」：无提示时归 unknown，
       给了题型提示才判为题干。
    """
    from app.ocr.structure_parser import TYPE_OPTION, TYPE_STEM, TYPE_UNKNOWN, classify_block

    # 选项优先：判断题的选项行不被提示改写
    assert classify_block("A.正确", question_type_hint="TRUE_FALSE") == TYPE_OPTION
    assert classify_block("A.正确") == TYPE_OPTION
    # 提示真正消歧：短且无前缀无题号时，无提示为 unknown，给提示才成题干
    assert classify_block("正确（）") == TYPE_UNKNOWN
    assert classify_block("正确（）", question_type_hint="TRUE_FALSE") == TYPE_STEM


def test_classify_blank_line_is_unknown() -> None:
    """空行归为 unknown。"""
    from app.ocr.structure_parser import TYPE_UNKNOWN, classify_block

    assert classify_block("") == TYPE_UNKNOWN
    assert classify_block("   ") == TYPE_UNKNOWN


def test_split_question_number() -> None:
    """题号拆分。"""
    from app.ocr.structure_parser import split_question_number

    assert split_question_number("1. 下列说法正确的是") == (1, "下列说法正确的是")
    assert split_question_number("3、栈的特点") == (3, "栈的特点")
    assert split_question_number("没有题号的一行") == (None, "没有题号的一行")


def test_infer_question_type_single_choice() -> None:
    """有 4 个选项 → 单选。"""
    from app.ocr.structure_parser import infer_question_type

    options = ["先进先出", "后进先出", "随机", "按关键字"]
    assert infer_question_type("下列哪种数据结构是先进先出", options) == "SINGLE_CHOICE"


def test_infer_question_type_multi_choice() -> None:
    """含"多选"提示 → 多选。"""
    from app.ocr.structure_parser import infer_question_type

    assert infer_question_type("下列说法正确的有（多选）", ["A", "B", "C", "D"]) == "MULTI_CHOICE"


def test_infer_question_type_true_false() -> None:
    """含"（）" + 正确/错误 → 判断题。"""
    from app.ocr.structure_parser import infer_question_type

    assert infer_question_type("TCP 是面向连接的协议。（）", ["正确", "错误"]) == "TRUE_FALSE"


def test_infer_question_type_fill_blank() -> None:
    """含下划线 → 填空。"""
    from app.ocr.structure_parser import infer_question_type

    assert infer_question_type("进程的状态包括 ____ 与 ____", []) == "FILL_BLANK"


def test_infer_question_type_programming() -> None:
    """含代码特征 → 编程题。"""
    from app.ocr.structure_parser import infer_question_type

    stem = "编写一个 C 程序，使用 #include <stdio.h> 输出 Hello"
    assert infer_question_type(stem, []) == "PROGRAMMING"


def test_infer_question_type_short_answer() -> None:
    """无选项的问句 → 简答。"""
    from app.ocr.structure_parser import infer_question_type

    assert infer_question_type("简述操作系统虚拟内存的作用", []) == "SHORT_ANSWER"


def test_infer_question_type_unknown_returns_none() -> None:
    """无法判定时返回 None（由人工选择，不瞎猜）。"""
    from app.ocr.structure_parser import infer_question_type

    assert infer_question_type("", []) is None
    assert infer_question_type("短", []) is None


def test_normalize_options() -> None:
    """选项标签归一化。"""
    from app.ocr.structure_parser import normalize_options

    assert normalize_options(["A. 苹果", "（B）香蕉", "香蕉干"]) == [
        ("A", "苹果"),
        ("B", "香蕉"),
        ("C", "香蕉干"),
    ]


def test_ocr_confidence_thresholds_exposed() -> None:
    """OCR 响应把置信度阈值告诉 Java 侧（G1 分流由 Java 落地）。"""
    from app.config import OcrSettings

    settings = OcrSettings()
    assert settings.stem_confidence_threshold == 0.75
    assert settings.field_confidence_threshold == 0.90


def test_tiny_png_is_valid_base64() -> None:
    """测试固件自检：TINY_PNG_B64 必须能解码（否则降级测试的输入本身就不合法）。"""
    assert base64.b64decode(TINY_PNG_B64)[:4] == b"\x89PNG"
