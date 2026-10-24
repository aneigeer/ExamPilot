"""Java 后端内部接口客户端（Agent 工具 / 任务回调）。

所有调用统一携带 ``X-Internal-Token``；失败一律收敛为 ``JavaBridgeError``，
由调用方决定降级，不把异常透传给 HTTP 层。
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from ..config import AgentSettings

logger = logging.getLogger(__name__)

INTERNAL_TOKEN_HEADER = "X-Internal-Token"


class JavaBridgeError(Exception):
    """Java 内部接口不可用 / 返回非 2xx。"""


class JavaBridge:
    """异步 HTTP 客户端（复用单个 ``httpx.AsyncClient``）。"""

    def __init__(self, settings: AgentSettings) -> None:
        self.settings = settings

    def _headers(self) -> Dict[str, str]:
        return {INTERNAL_TOKEN_HEADER: self.settings.internal_token}

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
            raise JavaBridgeError(
                f"调用 Java 内部接口失败：{type(exc).__name__}"
            ) from exc
        if resp.status_code >= 400:
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
            raise JavaBridgeError(
                f"调用 Java 内部接口失败：{type(exc).__name__}"
            ) from exc
        if resp.status_code >= 400:
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

    async def llm_complete(self, system_prompt: str, user_prompt: str) -> str:
        data = await self._post(
            "/internal/agent/llm",
            {"systemPrompt": system_prompt, "userPrompt": user_prompt},
            self.settings.llm_timeout_seconds,
        )
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
