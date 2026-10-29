"""FastAPI 应用入口。

职责：
1. 创建应用（标题/版本/描述，Swagger 在 ``/docs`` —— 使用加分项）
2. 注册全部路由
3. 注册**全局异常处理器**：所有错误都返回统一错误体 ``{code, message, degraded}``，**绝不返回 500 堆栈**
4. TraceId 中间件：透传/生成 ``X-Trace-Id``，与 Java 侧 ``traceId`` 串联，可按 traceId 查全链路日志
5. 启动时**只做配置打印与能力探测，绝不加载模型**（embedding/OCR 都是懒加载）

**最重要的一条设计约束**：本模块在**缺少可选依赖**（onnxruntime / qdrant-client /
paddleocr / httpx）时**必须仍能正常启动**，只有 ``/v1/health`` 起来，
其余接口按能力返回 503 —— 这样 Java 侧的降级链路才成立。
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any, Awaitable, Callable

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .clients.java_bridge import JavaBridge
from .config import get_settings
from .errors import AiServiceError, ErrorCode
from .routers import agent as agent_router
from .routers import ask as ask_router
from .routers import embed as embed_router
from .routers import health as health_router
from .routers import index as index_router
from .routers import llm as llm_router
from .routers import ocr as ocr_router
from .routers import search as search_router
from .routers import interview as interview_router
from .routers import wiki as wiki_router
from .schemas import ErrorResponse
from .worker.job_worker import start_workers

# --------------------------------------------------------------------------------------
# 日志
# --------------------------------------------------------------------------------------

logger = logging.getLogger("ai_service")

#: TraceId 头名（与 Java 侧 ``X-Trace-Id`` 一致）
TRACE_ID_HEADER = "X-Trace-Id"

DESCRIPTION = """
**大学生多考试备考知识库与题库系统 —— AI 微服务**

本服务只负责四件事：

1. `POST /v1/embed` —— 文本向量化（bge-small-zh-v1.5 ONNX INT8，512 维）
2. `POST /v1/search` —— 向量检索 + 轻量重排（Qdrant）
3. `POST /v1/ocr` —— 试卷 OCR 识别（PaddleOCR 懒加载）
4. `POST /v1/llm` —— 大模型代理（GLM / OpenAI 兼容）

**降级底线**：任何依赖不可用时返回 `503` + `{"code":5xxx,"message":"...","degraded":true}`，
Java 侧自动降级到 ngram 全文检索 + 本地抽取式，**系统功能零损失**。

接口契约详见 `docs/AI微服务扩展设计方案.md` 章节 C.1。
"""


def configure_logging(level: str = "INFO") -> None:
    """配置根日志（幂等，可重复调用）。

    Args:
        level: 日志级别名，如 ``DEBUG`` / ``INFO``。
    """
    logging.basicConfig(
        level=getattr(logging, level, logging.INFO),
        format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
        force=True,
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动/停止 Redis Stream worker。

    worker 未启用或 Redis 不可用时只告警，不影响 FastAPI 启动与其余路由。
    """
    settings = get_settings()
    tasks: list[asyncio.Task] = []
    if (settings.jobs.worker_enabled or settings.jobs.eval_worker_enabled
            or settings.jobs.wiki_worker_enabled):
        if not settings.agent.internal_token:
            logger.warning(
                "[启动] AI_INTERNAL_TOKEN 未配置：任务回调与 Agent 工具调用将被 Java 拒绝（403）"
            )
        bridge = JavaBridge(settings.agent)
        tasks = await start_workers(settings.jobs, bridge)
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)


def create_app() -> FastAPI:
    """创建并配置 FastAPI 应用。

    Returns:
        配置完成的 :class:`FastAPI` 实例。
    """
    settings = get_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title="备考知识库 AI 微服务",
        version=settings.version,
        description=DESCRIPTION,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
        lifespan=lifespan,
    )

    # —— CORS：本机使用放开，生产按 CORS_ORIGINS 收紧
    origins = [item.strip() for item in settings.cors_origins.split(",") if item.strip()]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins or ["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=[TRACE_ID_HEADER],
    )

    # —— TraceId 中间件
    app.middleware("http")(trace_id_middleware)

    # —— 路由注册（路径以 /v1 开头，与 Java 侧契约一致）
    app.include_router(health_router.router)
    app.include_router(embed_router.router)
    app.include_router(search_router.router)
    app.include_router(index_router.router)
    app.include_router(ocr_router.router)
    app.include_router(llm_router.router)
    app.include_router(agent_router.router)
    app.include_router(ask_router.router)
    app.include_router(wiki_router.router)
    app.include_router(interview_router.router)

    # —— 全局异常处理器
    register_exception_handlers(app)

    # —— 根路径：给个友好提示，避免访问 / 时看到 404
    @app.get("/", include_in_schema=False)
    def root() -> dict:
        """服务根路径（返回基本信息与文档地址）。"""
        return {
            "service": settings.app_name,
            "version": settings.version,
            "docs": "/docs",
            "health": "/v1/health",
        }

    logger.info("[启动] AI 微服务已初始化 %s", settings.safe_dump())
    logger.info(
        "[启动] 提示：embedding / OCR 均为懒加载，模型在首次调用对应接口时才加载"
    )
    return app


#: 模块级应用实例 `app` 见文件末尾（必须等中间件与异常处理器定义完成后才能创建）


# --------------------------------------------------------------------------------------
# 中间件
# --------------------------------------------------------------------------------------


async def trace_id_middleware(
    request: Request, call_next: Callable[[Request], Awaitable[Any]]
) -> Any:
    """TraceId 中间件：透传或生成 ``X-Trace-Id``，并记录耗时。

    Args:
        request: 入站请求。
        call_next: 下一个中间件/路由。

    Returns:
        带 ``X-Trace-Id`` 头的响应。
    """
    trace_id = request.headers.get(TRACE_ID_HEADER) or uuid.uuid4().hex[:16]
    request.state.trace_id = trace_id
    started = time.perf_counter()
    response = await call_next(request)
    cost_ms = int((time.perf_counter() - started) * 1000)
    response.headers[TRACE_ID_HEADER] = trace_id
    response.headers["X-Cost-Ms"] = str(cost_ms)
    return response


# --------------------------------------------------------------------------------------
# 全局异常处理
# --------------------------------------------------------------------------------------


def register_exception_handlers(app: FastAPI) -> None:
    """注册全局异常处理器。

    四类异常统一转成 :class:`ErrorResponse`：

    1. :class:`AiServiceError` —— 业务异常，用自带的 code/message/status
    2. :class:`RequestValidationError` —— 参数校验失败 → 400 / 5200
    3. :class:`StarletteHTTPException` —— 路由级 HTTP 异常（如 404）
    4. ``Exception`` —— **兜底**：500 / 5299，只记日志不返回堆栈

    Args:
        app: 目标应用。
    """

    @app.exception_handler(AiServiceError)
    async def handle_ai_error(request: Request, exc: AiServiceError) -> JSONResponse:
        """业务异常处理器（含全部依赖不可用场景）。"""
        logger.warning(
            "[错误] path=%s code=%d message=%s detail=%s",
            request.url.path, exc.code, exc.message, exc.detail or "-",
        )
        return JSONResponse(status_code=exc.http_status, content=exc.to_body())

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        """参数校验异常处理器（pydantic 校验失败）。"""
        first = exc.errors()[0] if exc.errors() else {}
        location = ".".join(str(part) for part in first.get("loc", []) if part != "body")
        message = first.get("msg", "参数校验失败")
        logger.warning("[参数错误] path=%s field=%s msg=%s", request.url.path, location, message)
        # 去掉 pydantic 前缀，保留可读信息；不回传完整 errors 数组（信息量过大且可能含内部字段名）
        clean = message.replace("Value error, ", "")
        return JSONResponse(
            status_code=400,
            content=ErrorResponse(
                code=ErrorCode.PARAM_ERROR,
                message=f"参数错误：{location or 'body'} {clean}",
                degraded=False,
            ).model_dump(),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        """HTTP 异常处理器（404 / 405 等），统一成同一错误体。"""
        logger.warning("[HTTP 异常] path=%s status=%d", request.url.path, exc.status_code)
        code = ErrorCode.PARAM_ERROR if exc.status_code < 500 else ErrorCode.INTERNAL_ERROR
        return JSONResponse(
            status_code=exc.status_code,
            content=ErrorResponse(
                code=code,
                message=str(exc.detail),
                degraded=exc.status_code >= 500,
            ).model_dump(),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        """兜底异常处理器：记录堆栈到日志，只对外返回模糊提示。

        **绝不允许把堆栈返回给调用方**（生产环境泄露实现细节），
        更不允许出现裸 500 —— 这里统一转成 5299。
        """
        logger.exception("[未预期异常] path=%s type=%s", request.url.path, type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content=ErrorResponse(
                code=ErrorCode.INTERNAL_ERROR,
                message="AI 服务内部错误，请稍后重试；Java 侧可降级到全文检索",
                degraded=True,
            ).model_dump(),
        )


# --------------------------------------------------------------------------------------
# 模块级应用实例
#
# ★ 必须放在文件末尾：`create_app()` 会引用下方定义的 `trace_id_middleware` 与
#   `register_exception_handlers`，若在定义之前执行 `create_app()`，Python 会抛
#   `NameError: name 'trace_id_middleware' is not defined`，服务直接起不来。
#   供 ``uvicorn app.main:app`` 使用。
# --------------------------------------------------------------------------------------

app = create_app()
