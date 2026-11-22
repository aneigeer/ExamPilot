"""Java 后端内部接口客户端（Agent 工具 / 任务回调）。

所有调用统一携带 ``X-Internal-Token``；失败一律收敛为 ``JavaBridgeError``，
由调用方决定降级，不把异常透传给 HTTP 层。

**TraceId 透传**（契约 §1 第 8 条）：当前上下文有 trace 时带 ``X-Trace-Id``，
**没有就不带** —— 由 Java ``TraceIdFilter`` 生成兜底。这里刻意不自造一个：
若两侧各自生成，Java 侧的响应头/meta/DB 会用它的值，而工具调用带的是另一个，
"按 traceId grep 全链路"就会断成两截，且看起来像"日志丢了"。
"""

from __future__ import annotations

import logging
from contextvars import ContextVar
from typing import Any, Dict, Optional

from ..config import AgentSettings
from ..observability.otel import span
from ..observability.trace import TRACE_ID_HEADER, get_trace_id

logger = logging.getLogger(__name__)

INTERNAL_TOKEN_HEADER = "X-Internal-Token"

#: 单次 Agent Run 的 Token 累加器（ContextVar 隔离并发请求）
_LLM_USAGE: ContextVar[Optional[Dict[str, Any]]] = ContextVar("llm_usage", default=None)


def reset_llm_usage() -> None:
    """开始新的 Agent Run 前清零。"""
    _LLM_USAGE.set({"model": "", "promptTokens": 0, "completionTokens": 0,
                    "totalTokens": 0, "estimated": False})


def llm_usage_snapshot() -> Dict[str, Any]:
    """读取当前 Run 的累计用量（无调用时返回全 0）。"""
    return dict(_LLM_USAGE.get() or {"model": "", "promptTokens": 0,
                                     "completionTokens": 0, "totalTokens": 0,
                                     "estimated": False})


def _record_usage(data: Dict[str, Any]) -> None:
    usage = _LLM_USAGE.get()
    if usage is None:
        reset_llm_usage()
        usage = _LLM_USAGE.get()
    usage["model"] = str(data.get("model") or usage.get("model") or "")
    usage["promptTokens"] += int(data.get("promptTokens") or 0)
    usage["completionTokens"] += int(data.get("completionTokens") or 0)
    usage["totalTokens"] += int(data.get("totalTokens") or 0)
    usage["estimated"] = bool(usage.get("estimated")) or bool(data.get("estimated"))


class JavaBridgeError(Exception):
    """Java 内部接口不可用 / 返回非 2xx。"""


class JavaBridge:
    """异步 HTTP 客户端（复用单个 ``httpx.AsyncClient``）。"""

    def __init__(self, settings: AgentSettings) -> None:
        self.settings = settings

    def _headers(self) -> Dict[str, str]:
        """构造内部调用请求头：内部令牌 + （有则）TraceId。

        Returns:
            ``{X-Internal-Token}``，上下文里有 trace 时再附 ``X-Trace-Id``。
        """
        headers: Dict[str, str] = {
            INTERNAL_TOKEN_HEADER: self.settings.internal_token,
        }
        trace_id = get_trace_id()
        if trace_id:
            # 缺失时不带：Java Filter 会生成一个并在响应头/日志/MDC 统一使用
            headers[TRACE_ID_HEADER] = trace_id
        return headers

    @staticmethod
    def _log_failure(method: str, path: str, reason: str) -> None:
        """记录一次内部调用失败（带 traceId，便于按 traceId grep 全链路）。

        ⚠️ 只记方法/路径/失败原因，**不记令牌与请求体**（可能含用户隐私）。
        """
        logger.warning(
            "[JavaBridge] %s %s 失败 reason=%s trace=%s",
            method, path, reason, get_trace_id() or "-",
        )

    async def _post(self, path: str, payload: Any, timeout: float) -> Any:
        try:
            import httpx  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover - 依赖缺失时才触发
            raise JavaBridgeError("未安装 httpx，无法调用 Java 内部接口") from exc
        url = f"{self.settings.java_base_url}{path}"
        try:
            async with httpx.AsyncClient(
                timeout=timeout, trust_env=False
            ) as client:
                resp = await client.post(url, json=payload, headers=self._headers())
        except Exception as exc:  # noqa: BLE001
            self._log_failure("POST", path, type(exc).__name__)
            raise JavaBridgeError(
                f"调用 Java 内部接口失败：{type(exc).__name__}"
            ) from exc
        if resp.status_code >= 400:
            self._log_failure("POST", path, f"HTTP {resp.status_code}")
            raise JavaBridgeError(
                f"Java 内部接口返回 {resp.status_code}：{resp.text[:200]}"
            )
        try:
            body = resp.json()
        except ValueError as exc:
            raise JavaBridgeError("Java 内部接口返回非 JSON") from exc
        # 统一响应体 {code,message,data}；code 非 200 视为失败
        if isinstance(body, dict) and body.get("code") not in (None, 200):
            raise JavaBridgeError(
                f"Java 业务错误 {body.get('code')}：{body.get('message')}"
            )
        return body.get("data") if isinstance(body, dict) else body

    async def _get(self, path: str, params: Optional[Dict[str, Any]], timeout: float) -> Any:
        try:
            import httpx  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover
            raise JavaBridgeError("未安装 httpx，无法调用 Java 内部接口") from exc
        url = f"{self.settings.java_base_url}{path}"
        try:
            async with httpx.AsyncClient(
                timeout=timeout, trust_env=False
            ) as client:
                resp = await client.get(url, params=params, headers=self._headers())
        except Exception as exc:  # noqa: BLE001
            self._log_failure("GET", path, type(exc).__name__)
            raise JavaBridgeError(
                f"调用 Java 内部接口失败：{type(exc).__name__}"
            ) from exc
        if resp.status_code >= 400:
            self._log_failure("GET", path, f"HTTP {resp.status_code}")
            raise JavaBridgeError(
                f"Java 内部接口返回 {resp.status_code}：{resp.text[:200]}"
            )
        try:
            body = resp.json()
        except ValueError as exc:
            raise JavaBridgeError("Java 内部接口返回非 JSON") from exc
        if isinstance(body, dict) and body.get("code") not in (None, 200):
            raise JavaBridgeError(
                f"Java 业务错误 {body.get('code')}：{body.get('message')}"
            )
        return body.get("data") if isinstance(body, dict) else body

    # ------------------------------------------------------------------ 任务回调

    async def job_progress(self, job_id: int, percent: int) -> None:
        await self._post(f"/internal/jobs/{job_id}/progress", {"percent": percent}, 5.0)

    async def job_complete(self, job_id: int, result: Any) -> None:
        await self._post(f"/internal/jobs/{job_id}/complete", {"result": result}, 10.0)

    async def job_fail(self, job_id: int, error: str) -> None:
        await self._post(f"/internal/jobs/{job_id}/fail", {"error": error}, 5.0)

    # ------------------------------------------------------------------ 工具 / LLM

    async def tool_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        return await self._get(path, params, self.settings.tool_timeout_seconds)

    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Any:
        return await self._post(path, payload, self.settings.tool_timeout_seconds)

    async def tool_post_long(self, path: str, payload: Dict[str, Any],
                             timeout: float = 180.0) -> Any:
        """长耗时内部调用：判分 / 学习计划等需要等待模型生成的场景。

        ``tool_post`` 默认 10s（工具查询够用），但模型生成在本地 7B 上可能 30s+，
        用默认超时会把「模型慢」误判成「服务不可用」。
        """
        return await self._post(path, payload, timeout)

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        with span("gen_ai.chat", {
            "gen_ai.system": "ollama",
            "gen_ai.operation.name": "chat",
        }) as otel_span:
            data = await self._post(
                "/internal/agent/llm",
                {"systemPrompt": system_prompt, "userPrompt": user_prompt},
                self.settings.llm_timeout_seconds,
            )
            if isinstance(data, dict):
                otel_span.set_attribute("gen_ai.usage.input_tokens",
                                        int(data.get("promptTokens") or 0))
                otel_span.set_attribute("gen_ai.usage.output_tokens",
                                        int(data.get("completionTokens") or 0))
                otel_span.set_attribute("gen_ai.response.model",
                                        str(data.get("model") or ""))
            if isinstance(data, dict):
                _record_usage(data)
                content = data.get("content")
                if not isinstance(content, str) or not content.strip():
                    raise JavaBridgeError("Java LLM 代理返回空内容")
                return content.strip()
            # 兼容旧版 Java 只返回字符串
            if not isinstance(data, str) or not data.strip():
                raise JavaBridgeError("Java LLM 代理返回空内容")
            return data.strip()

    # ------------------------------------------------------------------ Wiki 蒸馏

    async def wiki_input(
        self,
        scope: str,
        subject_id: Optional[int],
        limit: int,
        max_chunks: int,
    ) -> Dict[str, Any]:
        """拉取蒸馏输入（按知识点聚类后的原始块 + 已发布页面目录）。"""
        params: Dict[str, Any] = {
            "scope": scope,
            "limit": limit,
            "maxChunks": max_chunks,
        }
        if subject_id is not None:
            params["subjectId"] = subject_id
        data = await self._get("/internal/wiki/input", params, 20.0)
        return data if isinstance(data, dict) else {}

    async def post_wiki_revision(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """回传 LLM 蒸馏草稿（Java 侧只写 PENDING 修订，不直接发布）。"""
        data = await self._post("/internal/wiki/revisions", payload, 20.0)
        return data if isinstance(data, dict) else {}

    async def full_answer(
        self,
        question: str,
        subject_id: Optional[int] = None,
        draft_answer: str = "",
        contexts: Optional[list] = None,
    ) -> Dict[str, Any]:
        """由 Java 定稿：L3 校验 draft，或基于 contexts 生成，或走完整 RAG 兜底。"""
        return await self._post(
            "/internal/agent/answer",
            {
                "question": question,
                "subjectId": subject_id,
                "draftAnswer": draft_answer,
                "contexts": contexts or [],
            },
            self.settings.llm_timeout_seconds,
        )
