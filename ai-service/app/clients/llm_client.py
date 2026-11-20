"""大模型代理客户端：本地 Ollama 千问 3B（OpenAI 兼容协议）。

**定位**：本服务只是**代理**，不做提示词工程的质量闸门 ——
``PromptBuilder`` / ``AnswerPostProcessor``（L3 幻觉校验）在 Java 侧，本服务原样透传。

**降级语义（硬性要求）**：

* ``LLM_API_KEY`` 缺失 → :class:`LlmUnavailableError`（5209）→ 路由返回 503
  → Java 侧 ``AiQaService`` 走 ``ExtractiveAnswerClient`` 本地抽取。
* 上游超时 / 5xx → 同样 5209，**绝不把上游异常透传成 500**。
* 本服务**不返回** ``generator=extractive``：抽取式是 Java 侧的职责，
  混进来会造成两个兜底层互相污染。
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any, Dict, List, Optional

from ..config import LlmSettings
from ..errors import CapabilityUnavailableError, LlmUnavailableError

logger = logging.getLogger(__name__)

#: 答案中引用标注的正则：[1] [1,2] [1][2]
_CITATION_PATTERN = re.compile(r"\[(\d+(?:\s*[,，]\s*\d+)*)\]")

#: 系统提示词：强制引用 + 拒答，配合 Java 侧 L3 幻觉校验
_SYSTEM_PROMPT = (
    "你是大学生备考助学助手。严格遵守以下规则：\n"
    "1. 只依据【参考资料】回答问题，资料中没有的内容一律回答“资料中未提及”。\n"
    "2. 每个结论后面必须用 [1]、[2] 标注引用来源编号，编号对应资料序号。\n"
    "3. 资料与问题无关时，直接回答“未找到相关资料”，不要编造。\n"
    "4. 回答简洁、准确、有条理，语言使用简体中文。"
)


class LlmClient:
    """本地 Ollama 3B 代理客户端（基于 ``httpx``，OpenAI 兼容请求体）。

    Attributes:
        settings: LLM 配置。
    """

    def __init__(self, settings: LlmSettings) -> None:
        """初始化客户端（**不校验 Key，不建连接**）。

        Args:
            settings: LLM 配置。
        """
        self.settings: LlmSettings = settings

    # ------------------------------------------------------------------ 健康检查

    @property
    def ready(self) -> bool:
        """是否配置了 API Key。"""
        return bool(self.settings.api_key)

    def health_detail(self) -> str:
        """健康检查诊断信息（**绝不回显 Key 内容**）。"""
        if not self.ready:
            return "未配置 LLM_API_KEY，/v1/llm 将返回 503，Java 侧走抽取式"
        return f"model={self.settings.model} base_url={self.settings.base_url} key=已配置"

    # ------------------------------------------------------------------ 生成

    def chat(
        self,
        question: str,
        contexts: Optional[List[str]] = None,
        *,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
    ) -> Dict[str, Any]:
        """调用大模型生成回答。

        Args:
            question: 用户问题。
            contexts: 检索到的语料片段（RAG 上下文）。
            max_tokens: 覆盖配置的最大生成 token 数。
            temperature: 覆盖配置的采样温度。

        Returns:
            ``{"answer": str, "sources": [{"index": int, "snippet": str}],
              "generator": str, "cost_ms": int}``。

        Raises:
            LlmUnavailableError: 未配置 Key、缺 httpx、上游超时或返回异常。
        """
        if not self.ready:
            raise LlmUnavailableError(
                "大模型能力不可用：未配置 LLM_API_KEY，Java 侧请走抽取式兜底"
            )
        try:
            import httpx  # type: ignore[import-not-found]
        except ImportError as exc:
            raise CapabilityUnavailableError(
                "大模型代理不可用：未安装 httpx，请执行 install.bat 后重启服务",
                code=5209,
            ) from exc

        payload = self._build_payload(question, contexts or [], max_tokens, temperature)
        url = f"{self.settings.base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.api_key}",
            "Content-Type": "application/json",
        }

        started = time.perf_counter()
        try:
            # trust_env=False：**不要**读系统 HTTP_PROXY。
            # 本机验证过：env 里存在 CODEBUDDY_SERVICE_PROXY_URL 时，httpx 会把
            # http://127.0.0.1:11434（Ollama）也当外部请求发给代理，代理立即回 502，
            # 表现为「模型明明在线却答不出」。本地服务调用必须绕过代理。
            with httpx.Client(timeout=self.settings.timeout_seconds, trust_env=False) as client:
                response = client.post(url, json=payload, headers=headers)
        except httpx.TimeoutException as exc:
            raise LlmUnavailableError(
                "大模型调用超时，Java 侧请走抽取式兜底", detail=str(exc)
            ) from exc
        except Exception as exc:  # noqa: BLE001
            raise LlmUnavailableError(
                f"大模型调用失败：{type(exc).__name__}，Java 侧请走抽取式兜底", detail=str(exc)
            ) from exc
        cost_ms = int((time.perf_counter() - started) * 1000)

        if response.status_code != 200:
            # 只取前 200 字符，避免把上游长错误体带进日志
            snippet = response.text[:200]
            raise LlmUnavailableError(
                f"大模型返回异常状态 {response.status_code}，Java 侧请走抽取式兜底",
                detail=snippet,
            )
        try:
            data = response.json()
            answer = data["choices"][0]["message"]["content"]
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise LlmUnavailableError(
                "大模型返回格式异常，Java 侧请走抽取式兜底", detail=type(exc).__name__
            ) from exc

        if not isinstance(answer, str) or not answer.strip():
            raise LlmUnavailableError("大模型返回空回答，Java 侧请走抽取式兜底")

        return {
            "answer": answer.strip(),
            "sources": extract_sources(answer, contexts or []),
            "generator": self.settings.generator_name,
            "cost_ms": cost_ms,
        }

    def _build_payload(
        self,
        question: str,
        contexts: List[str],
        max_tokens: Optional[int],
        temperature: Optional[float],
    ) -> Dict[str, Any]:
        """组装 OpenAI 兼容请求体。

        Args:
            question: 用户问题。
            contexts: 检索到的语料片段。
            max_tokens: 覆盖的最大 token 数。
            temperature: 覆盖的采样温度。

        Returns:
            请求体 dict。

        Raises:
            LlmUnavailableError: 没有任何上下文（拒答场景不该调大模型）。
        """
        if not contexts:
            raise LlmUnavailableError("无语料上下文，大模型不应被调用（拒答场景由 Java 侧处理）")
        blocks: List[str] = []
        for index, snippet in enumerate(contexts, start=1):
            blocks.append(f"[{index}] {snippet}")
        user_prompt = f"【参考资料】\n" + "\n\n".join(blocks) + f"\n\n【问题】\n{question}"
        return {
            "model": self.settings.model,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self.settings.temperature if temperature is None else temperature,
            "max_tokens": self.settings.max_tokens if max_tokens is None else max_tokens,
            "stream": False,
        }


def extract_sources(answer: str, contexts: List[str]) -> List[Dict[str, Any]]:
    """从答案中的 ``[n]`` 标注提取溯源列表。

    用于 Java 侧的 L3 幻觉校验（编号是否与 ``sources`` 自洽）。

    Args:
        answer: 模型返回的答案正文。
        contexts: 请求时传入的上下文列表。

    Returns:
        ``[{"index": int, "snippet": str}, ...]``，按 index 升序去重；
        无引用标注时返回空 list。
    """
    if not contexts:
        return []
    indexes: set = set()
    for match in _CITATION_PATTERN.finditer(answer or ""):
        for token in re.split(r"[,，]", match.group(1)):
            token = token.strip()
            if token.isdigit():
                value = int(token)
                if 1 <= value <= len(contexts):
                    indexes.add(value)
    return [
        {"index": index, "snippet": contexts[index - 1][:500]}
        for index in sorted(indexes)
    ]


# --------------------------------------------------------------------------------------
# 进程级单例
# --------------------------------------------------------------------------------------

_client: Optional[LlmClient] = None


def get_llm_client() -> LlmClient:
    """获取进程级 LlmClient 单例（懒创建）。"""
    global _client
    if _client is None:
        from ..config import get_settings

        _client = LlmClient(get_settings().llm)
    return _client


def reset_llm_client() -> None:
    """重置单例（仅供测试使用）。"""
    global _client
    _client = None
