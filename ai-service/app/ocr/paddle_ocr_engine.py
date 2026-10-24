"""PaddleOCR 懒加载封装。

**懒加载是硬性要求，不是优化**：

* PaddleOCR 依赖约 500MB–1GB，模型常驻内存也很大。FastAPI 启动时加载会白占内存，
  拖慢 embedding 服务的首响应。
* 使用时不使用 OCR 就不该付这个成本。
* **未安装 paddleocr 时绝不能导致服务崩溃** —— 只在第一次调 ``/v1/ocr`` 时 import，
  import 失败即 503，Java 侧降级人工录入。

识别不准的三级兜底（G0/G1/G2）由 **Java 侧**落地：
本服务只负责"识别 + 分块 + 标注块级置信度"，**永不自动入库**。
"""

from __future__ import annotations

import base64
import binascii
import logging
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any, List, Optional, Tuple

from ..config import OcrSettings
from ..errors import OcrRecognizeFailedError, OcrUnavailableError

if TYPE_CHECKING:  # pragma: no cover —— 仅类型检查期需要，运行期不 import pydantic
    from ..schemas import OcrBlock

logger = logging.getLogger(__name__)

#: 临时文件后缀 → PIL 可识别的图片格式
_IMAGE_SUFFIX = {
    "jpg": ".jpg",
    "jpeg": ".jpg",
    "png": ".png",
    "bmp": ".bmp",
    "webp": ".webp",
    "tiff": ".tiff",
}


class PaddleOcrEngine:
    """PaddleOCR 引擎封装（懒加载 + 线程安全 + 字段级置信度）。

    Attributes:
        settings: OCR 配置。
        _engine: ``PaddleOCR`` 实例，首次识别时创建。
        _lock: 保护懒加载的互斥锁。
        _load_error: 加载失败原因，用于健康检查展示。
    """

    def __init__(self, settings: OcrSettings) -> None:
        """初始化引擎（**不 import paddleocr，不加载模型**）。

        Args:
            settings: OCR 配置。
        """
        self.settings: OcrSettings = settings
        self._engine: Optional[Any] = None
        self._lock: threading.Lock = threading.Lock()
        self._load_error: Optional[str] = None
        self._load_ms: int = 0

    # ------------------------------------------------------------------ 健康检查

    @property
    def loaded(self) -> bool:
        """模型是否已加载。"""
        return self._engine is not None

    def health_detail(self) -> str:
        """健康检查诊断信息。"""
        if not self.settings.enabled:
            return "OCR 已通过 OCR_ENABLED=false 关闭"
        if self.loaded:
            return f"PaddleOCR 已加载 lang={self.settings.lang} load_ms={self._load_ms}"
        if self._load_error:
            return f"未就绪：{self._load_error}"
        return f"未加载（懒加载，首次调用 /v1/ocr 时加载，模型目录 {self.settings.model_dir}）"

    def is_available(self) -> bool:
        """OCR 能力是否可用（已启用且 paddleocr 可 import）。

        仅做 ``importlib.util.find_spec``，**不真正 import**，保证健康检查极轻量。

        Returns:
            可用返回 ``True``。
        """
        if not self.settings.enabled:
            return False
        import importlib.util

        return importlib.util.find_spec("paddleocr") is not None

    # ------------------------------------------------------------------ 加载

    @staticmethod
    def _paddle_major() -> int:
        """PaddleOCR 主版本号（无法判定时按 2.x 处理）。"""
        try:
            import paddleocr  # noqa: PLC0415

            return int(str(getattr(paddleocr, "__version__", "2")).split(".")[0])
        except Exception:  # noqa: BLE001
            return 2

    def _build_engine_kwargs(self) -> dict:
        """按 PaddleOCR 主版本构造初始化参数。

        2.x 与 3.x 的参数名不同，硬编码任一版本都会在另一版本上直接报错
        （实测 3.7.0 下传 ``show_log`` → ``Unknown argument: show_log``）：
          * 3.x：``use_angle_cls`` → ``use_textline_orientation``；
                 ``det/rec/cls_model_dir`` → ``text_detection/recognition/textline_orientation_model_dir``；
                 ``show_log`` 已移除
          * 2.x：保留 ``use_angle_cls`` / ``*_model_dir`` / ``show_log``

        显式指定模型目录（离线部署）时优先使用；目录不存在则交给 PaddleOCR 自行下载。
        """
        model_dir = Path(self.settings.model_dir)
        det_dir, rec_dir, cls_dir = model_dir / "det", model_dir / "rec", model_dir / "cls"
        has_det_rec = det_dir.is_dir() and rec_dir.is_dir()

        if self._paddle_major() >= 3:
            kwargs: dict = {
                "lang": self.settings.lang,
                "use_textline_orientation": self.settings.use_angle_cls,
                # ★ 兼容性必需（实测 paddlepaddle 3.3.0 + oneDNN 下会抛
                #   NotImplementedError: ConvertPirAttribute2RuntimeAttribute not support）：
                #   关闭文档方向分类与文档矫正（UVDoc），并关掉 oneDNN 加速，
                #   否则 PP-OCRv6 流水线在 CPU 上直接推理失败。
                #   试卷识别是平面扫描件，本就不需要 UVDoc 矫正。
                "use_doc_orientation_classify": False,
                "use_doc_unwarping": False,
                "enable_mkldnn": False,
            }
            if has_det_rec:
                kwargs["text_detection_model_dir"] = str(det_dir)
                kwargs["text_recognition_model_dir"] = str(rec_dir)
                if self.settings.use_angle_cls and cls_dir.is_dir():
                    kwargs["textline_orientation_model_dir"] = str(cls_dir)
            return kwargs

        kwargs = {
            "lang": self.settings.lang,
            "use_angle_cls": self.settings.use_angle_cls,
            "show_log": False,
        }
        if has_det_rec:
            kwargs["det_model_dir"] = str(det_dir)
            kwargs["rec_model_dir"] = str(rec_dir)
            if self.settings.use_angle_cls and cls_dir.is_dir():
                kwargs["cls_model_dir"] = str(cls_dir)
        return kwargs

    def ensure_loaded(self) -> None:
        """确保 OCR 引擎已加载（并发安全）。

        Raises:
            OcrUnavailableError: 未启用 / paddleocr 未安装 / 模型加载失败（5205）。
                这三种都属于"**重试没用**"，Java 侧应长期熔断并提示人工录入。
            OcrRecognizeFailedError: 不会在此抛出（识别错误发生在 :meth:`_recognize`）。
        """
        if self.loaded:
            return
        if not self.settings.enabled:
            raise OcrUnavailableError(
                "OCR 能力已关闭（OCR_ENABLED=false），请在 Java 侧降级为人工录入"
            )
        with self._lock:
            if self.loaded:
                return
            started = time.perf_counter()
            try:
                from paddleocr import PaddleOCR  # type: ignore[import-not-found]  # noqa: PLC0415
            except ImportError as exc:
                self._load_error = "未安装 paddleocr"
                raise OcrUnavailableError(
                    "OCR 能力不可用：未安装 paddleocr/paddlepaddle，"
                    "请执行 install-ocr.bat 后重启服务；Java 侧请降级为人工录入"
                ) from exc

            try:
                self._engine = PaddleOCR(**self._build_engine_kwargs())
            except (TypeError, ValueError) as exc:
                # 参数名跨版本漂移（例如 3.x 移除 show_log、use_angle_cls 改名）：
                # 退化为最小参数集再试一次，避免「装了却完全不可用」
                logger.warning("[OCR] PaddleOCR 初始化参数不被接受（%s），改用最小参数集重试", exc)
                try:
                    self._engine = PaddleOCR(lang=self.settings.lang)
                except Exception as inner:  # noqa: BLE001
                    self._load_error = f"模型加载失败：{type(inner).__name__}"
                    raise OcrUnavailableError(
                        f"OCR 模型加载失败：{type(inner).__name__}；Java 侧请降级为人工录入",
                        detail=str(inner),
                    ) from inner
            except Exception as exc:  # noqa: BLE001
                self._load_error = f"模型加载失败：{type(exc).__name__}"
                raise OcrUnavailableError(
                    f"OCR 模型加载失败：{type(exc).__name__}；Java 侧请降级为人工录入",
                    detail=str(exc),
                ) from exc
            self._load_ms = int((time.perf_counter() - started) * 1000)
            self._load_error = None
            logger.info("[OCR] PaddleOCR 加载完成 lang=%s load_ms=%d", self.settings.lang, self._load_ms)

    # ------------------------------------------------------------------ 识别

    def recognize_file(self, file_path: str) -> Tuple[List["OcrBlock"], float]:
        """识别本地图片文件。

        Args:
            file_path: 图片绝对路径。

        Returns:
            ``(blocks, confidence)``；``confidence`` 为所有块置信度均值。

        Raises:
            OcrUnavailableError: 文件不存在。
            OcrRecognizeFailedError: 识别过程异常。
        """
        path = Path(file_path)
        if not path.is_file():
            raise OcrUnavailableError(f"OCR 图片文件不存在：{file_path}", detail=file_path)
        return self._recognize(str(path))

    def recognize_base64(self, image_base64: str) -> Tuple[List["OcrBlock"], float]:
        """识别 base64 编码的图片。

        Args:
            image_base64: base64 字符串（可带 ``data:image/png;base64,`` 前缀）。

        Returns:
            ``(blocks, confidence)``。

        Raises:
            OcrUnavailableError: base64 非法或解码后为空。
            OcrRecognizeFailedError: 识别过程异常。
        """
        raw = image_base64.strip()
        if raw.startswith("data:"):
            _, _, raw = raw.partition(",")
        try:
            binary = base64.b64decode(raw, validate=False)
        except (binascii.Error, ValueError) as exc:
            raise OcrUnavailableError(
                "OCR 图片 base64 解码失败", detail=type(exc).__name__
            ) from exc
        if not binary:
            raise OcrUnavailableError("OCR 图片 base64 解码后为空")
        suffix = _IMAGE_SUFFIX.get(_sniff_format(binary), ".png")
        temp_path = ""
        try:
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as handle:
                handle.write(binary)
                temp_path = handle.name
            return self._recognize(temp_path)
        finally:
            if temp_path and os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:  # pragma: no cover —— 临时文件清理失败不影响主流程
                    pass

    def _recognize(self, image_path: str) -> Tuple[List["OcrBlock"], float]:
        """执行识别并转换为统一块结构。

        PaddleOCR 3.x 返回 ``[{"rec_texts": [...], "rec_scores": [...], "rec_polys"/"dt_polys": [...]}]``，
        2.x 返回嵌套 list。本方法**同时兼容两种格式**。

        ``OcrBlock`` 在此处**局部导入**：走到这一步说明 paddleocr 已就绪，
        此时 pydantic（FastAPI 硬依赖）必然已安装，不必让模块顶层承担这个依赖。

        Args:
            image_path: 图片路径（临时文件或真实文件）。

        Returns:
            ``(blocks, confidence)``。

        Raises:
            OcrRecognizeFailedError: 识别过程异常（模型在，但这次识别失败）。
        """
        from ..schemas import OcrBlock  # noqa: PLC0415 —— 局部导入，见上方说明

        self.ensure_loaded()
        assert self._engine is not None  # noqa: S101
        try:
            raw = self._engine.ocr(image_path)
        except Exception as exc:  # noqa: BLE001
            raise OcrRecognizeFailedError(
                f"OCR 识别失败：{type(exc).__name__}；Java 侧请改用人工录入或换图重试",
                detail=str(exc),
            ) from exc

        texts, scores, boxes = _parse_paddle_output(raw)
        blocks: List[OcrBlock] = []
        for index, text in enumerate(texts):
            score = float(scores[index]) if index < len(scores) else 0.0
            if score < self.settings.drop_score:
                # 低分文本直接丢弃，避免污染草稿（G2 兜底：宁可少也不要脏）
                continue
            blocks.append(OcrBlock(type="unknown", text=text, bbox=boxes[index], confidence=score))
        if not blocks:
            return [], 0.0
        average = sum(block.confidence for block in blocks) / len(blocks)
        return blocks, round(average, 4)


def _parse_paddle_output(raw: Any) -> Tuple[List[str], List[float], List[Optional[List[float]]]]:
    """解析 PaddleOCR 不同版本的输出结构。

    Args:
        raw: ``PaddleOCR.ocr()`` 的原始返回值。

    Returns:
        ``(texts, scores, boxes)`` 三元组；无法解析时返回空列表。
    """
    texts: List[str] = []
    scores: List[float] = []
    boxes: List[Optional[List[float]]] = []

    if not raw:
        return texts, scores, boxes

    # 3.x：list[dict]
    if isinstance(raw, list) and raw and isinstance(raw[0], dict):
        entry = raw[0]
        texts = [str(t) for t in entry.get("rec_texts", []) or []]
        scores = [float(s) for s in entry.get("rec_scores", []) or []]
        polygons = entry.get("rec_polys") or entry.get("dt_polys") or []
        boxes = [_polygon_to_bbox(p) for p in polygons]
        return texts, scores, boxes

    # 2.x：list[list[list[poly, (text, score)]]]
    if isinstance(raw, list) and raw and isinstance(raw[0], list):
        for page in raw:
            if not page:
                continue
            for line in page:
                if not line or len(line) < 2:
                    continue
                polygon, payload = line[0], line[1]
                if not isinstance(payload, (list, tuple)) or len(payload) < 2:
                    continue
                texts.append(str(payload[0]))
                scores.append(float(payload[1]))
                boxes.append(_polygon_to_bbox(polygon))
    return texts, scores, boxes


def _polygon_to_bbox(polygon: Any) -> Optional[List[float]]:
    """四点多边形转外接框 ``[x1, y1, x2, y2]``。

    Args:
        polygon: 四点坐标列表。

    Returns:
        外接框；输入非法时返回 ``None``。
    """
    try:
        xs = [float(point[0]) for point in polygon]
        ys = [float(point[1]) for point in polygon]
    except (TypeError, IndexError, ValueError):
        return None
    if not xs or not ys:
        return None
    return [round(min(xs), 2), round(min(ys), 2), round(max(xs), 2), round(max(ys), 2)]


def _sniff_format(binary: bytes) -> str:
    """从二进制头猜测图片格式（决定临时文件后缀）。

    Args:
        binary: 图片字节。

    Returns:
        格式标识（``png`` / ``jpg`` / ``bmp`` / ``webp`` / ``tiff``）；未知返回空串。
    """
    if binary[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if binary[:2] == b"\xff\xd8":
        return "jpg"
    if binary[:2] == b"BM":
        return "bmp"
    if binary[:4] == b"RIFF" and binary[8:12] == b"WEBP":
        return "webp"
    if binary[:4] in (b"II*\x00", b"MM\x00*"):
        return "tiff"
    return ""


# --------------------------------------------------------------------------------------
# 进程级单例
# --------------------------------------------------------------------------------------

_engine: Optional[PaddleOcrEngine] = None
_engine_lock: threading.Lock = threading.Lock()


def get_ocr_engine() -> PaddleOcrEngine:
    """获取进程级 PaddleOcrEngine 单例（懒创建，不 import paddleocr）。"""
    global _engine
    if _engine is None:
        with _engine_lock:
            if _engine is None:
                from ..config import get_settings

                _engine = PaddleOcrEngine(get_settings().ocr)
    return _engine


def reset_ocr_engine() -> None:
    """重置单例（仅供测试使用）。"""
    global _engine
    with _engine_lock:
        _engine = None
