"""LLM Wiki 蒸馏器。

**为什么需要这么多道校验**（实测教训）：
本地 qwen2.5-7b 在「严格 JSON + 中文」提示下曾出现
① 完全忽略 JSON 格式输出英文散文、② 编造「int 8 字节」等事实错误。
因此蒸馏链路的顺序是：

1. JSON 输出（失败 → 一次修复重试）
2. 结构校验（标题 / 摘要 / 正文 / 要点 / 来源 ID 是否合法）
3. **引用校验**：source_chunk_ids 必须是输入块的真子集
4. **接地校验**：生成文本与原始资料的字符 bigram 重合率过低 → 判定为编造
5. 以上任一失败 → 抽取式兜底（仍产出可审阅的草稿，而不是空任务）

最终产物一律只写 `wiki_page_revision(PENDING)`，人工审核通过后才发布。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Dict, List, Optional, Tuple

from ..config import LlmSettings, Settings

logger = logging.getLogger(__name__)

_WORD = re.compile(r"[\u4e00-\u9fffA-Za-z0-9]+")
_RELATIONS = {"RELATED", "SIBLING", "PREREQUISITE"}

SYSTEM_PROMPT = (
    "你是大学生备考知识库的 Wiki 编辑。把给定的原始资料块提炼成一个结构化主题页草稿。\n"
    "硬性规则：\n"
    "1. 只依据【原始资料】写作，资料中没有的内容绝不补充；不确定就写「资料未提及」。\n"
    "2. 只输出一个 JSON 对象，不要 Markdown 代码块，不要任何解释文字。\n"
    "3. 所有结论必须能被 source_chunk_ids 中的资料块支撑，ID 只能从给定块里选。\n"
    "4. 语言使用简体中文，不要输出英文。\n"
    "5. 读者是备考学生：要跨资料综合出「考点、方法、易错点」，"
    "禁止逐题抄题干；正文用 3-4 个 ## 小节，400-800 字。\n\n"
    "JSON 结构（字段名不可改）：\n"
    '{"title":"主题标题","summary":"80-200字摘要","content":"Markdown 正文，含 ## 小标题",'
    '"key_points":["要点1","要点2","要点3"],"source_chunk_ids":[1,2],'
    '"links":[{"to_slug":"kp-5","relation":"RELATED","weight":0.6}]}\n'
    "links 可为空数组；to_slug 只能从【已有页面目录】里选，不得编造。"
)


class DistillError(Exception):
    """单页蒸馏失败（可回退抽取式）。"""


def _clean(text: Any, limit: int) -> str:
    if text is None:
        return ""
    value = str(text).strip()
    value = re.sub(r"\r\n?", "\n", value)
    return value[:limit].strip()


def _extract_json(raw: str) -> Dict[str, Any]:
    """从模型输出中提取 JSON 对象（容忍代码块 / 前后废话）。"""
    if not raw or not raw.strip():
        raise DistillError("模型返回空内容")
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise DistillError("模型输出不含 JSON 对象")
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError as exc:
        raise DistillError(f"JSON 解析失败：{exc}") from exc
    if not isinstance(data, dict):
        raise DistillError("模型输出不是 JSON 对象")
    return data


def _bigrams(text: str) -> set:
    """字符 bigram 集合（中文接地校验用；英文按词也退化为字符二元组）。"""
    compact = re.sub(r"\s+", "", text or "")
    return {compact[i:i + 2] for i in range(len(compact) - 1) if compact[i:i + 2].strip()}


def grounding_ratio(generated: str, source: str) -> float:
    """生成文本中被原始资料覆盖的 bigram 占比（0-1）。"""
    generated_grams = _bigrams(generated)
    if not generated_grams:
        return 0.0
    source_grams = _bigrams(source)
    if not source_grams:
        return 0.0
    return len(generated_grams & source_grams) / len(generated_grams)


async def _chat(settings: LlmSettings, user_prompt: str, *, strict_json: bool) -> str:
    """调用 OpenAI 兼容端点（Ollama / 云端同一套协议）。"""
    try:
        import httpx  # type: ignore[import-not-found]
    except ImportError as exc:  # pragma: no cover
        raise DistillError("未安装 httpx，无法调用大模型") from exc

    url = f"{settings.base_url.rstrip('/')}/chat/completions"
    base_payload: Dict[str, Any] = {
        "model": settings.model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": min(0.3, max(0.0, settings.temperature)),
        "max_tokens": min(1600, max(600, settings.max_tokens)),
        "stream": False,
    }
    headers = {
        "Authorization": f"Bearer {settings.api_key}",
        "Content-Type": "application/json",
    }
    payload = dict(base_payload)
    if strict_json:
        payload["response_format"] = {"type": "json_object"}

    try:
        async with httpx.AsyncClient(timeout=settings.timeout_seconds, trust_env=False) as client:
            response = await client.post(url, json=payload, headers=headers)
            if response.status_code == 400 and strict_json:
                # 部分 Ollama 版本不接受 response_format：去掉后重试一次
                logger.info("[WikiDistill] 端点不支持 response_format，退回纯提示词约束")
                response = await client.post(url, json=base_payload, headers=headers)
    except Exception as exc:  # noqa: BLE001
        raise DistillError(f"大模型调用失败：{type(exc).__name__}") from exc

    if response.status_code != 200:
        raise DistillError(f"大模型返回 HTTP {response.status_code}: {response.text[:160]}")
    try:
        body = response.json()
        content = body["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        raise DistillError(f"大模型响应格式异常：{type(exc).__name__}") from exc
    if not isinstance(content, str) or not content.strip():
        raise DistillError("大模型返回空内容")
    return content.strip()


def _build_prompt(cluster: Dict[str, Any], catalog: List[Dict[str, Any]]) -> str:
    lines: List[str] = [f"【主题】{cluster.get('title')}", "【原始资料】"]
    for chunk in cluster.get("chunks") or []:
        lines.append(
            f"- chunk_id={chunk.get('chunkId')} | 来源={chunk.get('sourceType')} "
            f"| 标题={chunk.get('title')}"
        )
        lines.append(f"  正文：{_clean(chunk.get('content'), 900)}")
    catalog_lines = [
        f"- {item.get('slug')}：{item.get('title')}"
        for item in catalog
        if item.get("slug") and item.get("title")
    ]
    if catalog_lines:
        lines.append("\n【已有页面目录】（links 的 to_slug 只能从这里选）")
        lines.extend(catalog_lines[:80])
    else:
        lines.append("\n【已有页面目录】空；links 请输出空数组。")
    lines.append(
        "\n【写作要求】跨资料综合，不要逐题复述题干；summary 概括主题，"
        "content 给出考点 / 方法 / 易错点；key_points 3-6 条。"
    )
    lines.append("请输出符合结构的 JSON。")
    return "\n".join(lines)


def _validate(candidate: Dict[str, Any], cluster: Dict[str, Any],
              catalog: List[Dict[str, Any]], settings: Settings) -> Dict[str, Any]:
    source_chunks = cluster.get("chunks") or []
    source_text = "\n".join(_clean(item.get("content"), 2000) for item in source_chunks)
    valid_ids = {int(item["chunkId"]) for item in source_chunks if item.get("chunkId") is not None}

    title = _clean(candidate.get("title"), 100) or _clean(cluster.get("title"), 100)
    summary = _clean(candidate.get("summary"), 900)
    content = _clean(candidate.get("content"), settings.wiki.distill_max_chars)
    if len(summary) < 20:
        raise DistillError("摘要过短")
    if len(content) < 80:
        raise DistillError("正文过短")

    key_points = candidate.get("key_points")
    if not isinstance(key_points, list):
        raise DistillError("key_points 不是数组")
    points = [_clean(item, 160) for item in key_points if _clean(item, 160)]
    if len(points) < 2:
        raise DistillError("要点少于 2 条")

    raw_ids = candidate.get("source_chunk_ids")
    if not isinstance(raw_ids, list):
        raise DistillError("source_chunk_ids 不是数组")
    ids: List[int] = []
    for value in raw_ids:
        try:
            chunk_id = int(value)
        except (TypeError, ValueError):
            continue
        if chunk_id in valid_ids and chunk_id not in ids:
            ids.append(chunk_id)
    if not ids:
        raise DistillError("source_chunk_ids 未引用任何输入块")

    # 接地校验：整段内容 + 摘要与原始资料的 bigram 重合率
    ratio = grounding_ratio(summary + "\n" + content, source_text)
    if ratio < 0.10:
        raise DistillError(f"接地率过低（{ratio:.3f}），判定为编造")

    allowed_slugs = {str(item.get("slug")) for item in catalog if item.get("slug")}
    links: List[Dict[str, Any]] = []
    raw_links = candidate.get("links")
    if isinstance(raw_links, list):
        for item in raw_links:
            if not isinstance(item, dict):
                continue
            to_slug = _clean(item.get("to_slug"), 100)
            if not to_slug or to_slug not in allowed_slugs or to_slug == cluster.get("slug"):
                continue
            relation = str(item.get("relation") or "RELATED").upper()
            if relation not in _RELATIONS:
                relation = "RELATED"
            try:
                weight = float(item.get("weight", 0.6))
            except (TypeError, ValueError):
                weight = 0.6
            links.append({
                "to_slug": to_slug,
                "relation": relation,
                "weight": max(0.0, min(1.0, weight)),
            })

    quality = (
        0.35
        + 0.20 * min(1.0, len(ids) / max(1, len(valid_ids)))
        + 0.20 * (1.0 if len(points) >= 3 else 0.5)
        + 0.15 * min(1.0, len(content) / 800)
        + 0.10 * min(1.0, ratio / 0.4)
    )
    return {
        "title": title,
        "summary": summary,
        "content": content,
        "key_points": points[:8],
        "source_chunk_ids": ids[:10],
        "links": links[:5],
        "quality_score": round(min(1.0, quality), 4),
        "model": settings.llm.generator_name or settings.llm.model,
    }


def _extractive(cluster: Dict[str, Any], settings: Settings) -> Dict[str, Any]:
    """抽取式兜底：不做任何改写，只做结构化拼接。"""
    chunks = cluster.get("chunks") or []
    picked = chunks[:3]
    texts = [_clean(item.get("content"), 400) for item in picked]
    summary = (texts[0] if texts else _clean(cluster.get("title"), 200))[:400]
    points = []
    for item in picked:
        text = _clean(item.get("content"), 200)
        points.append(text[:120] if text else _clean(item.get("title"), 120))
    points = [p for p in points if p][:5] or ["资料未提及"]

    content_lines = ["## 概述", summary, "", "## 资料要点"]
    content_lines.extend(f"- {p}" for p in points)
    content_lines.append("")
    content_lines.append("## 原文片段")
    for item in picked:
        content_lines.append(f"### {_clean(item.get('title'), 100) or '资料片段'}")
        content_lines.append(_clean(item.get("content"), 500))
        content_lines.append("")
    content = "\n".join(content_lines)[:settings.wiki.distill_max_chars]

    ids = [int(item["chunkId"]) for item in picked if item.get("chunkId") is not None]
    return {
        "title": _clean(cluster.get("title"), 100),
        "summary": summary,
        "content": content,
        "key_points": points,
        "source_chunk_ids": ids,
        "links": [],
        "quality_score": 0.30,
        "model": "extractive-fallback",
    }


async def distill_cluster(cluster: Dict[str, Any], catalog: List[Dict[str, Any]],
                          settings: Settings) -> Tuple[Dict[str, Any], bool]:
    """蒸馏一个主题簇。

    Returns:
        ``(draft, used_fallback)``；任何异常都收敛成抽取式草稿，不抛出。
    """
    prompt = _build_prompt(cluster, catalog)
    last_error = ""
    for attempt in range(2):
        try:
            raw = await _chat(settings.llm, prompt, strict_json=True)
            candidate = _extract_json(raw)
            draft = _validate(candidate, cluster, catalog, settings)
            return draft, False
        except Exception as exc:  # noqa: BLE001 —— 单页失败必须回退，不能中断整批
            last_error = f"{type(exc).__name__}: {exc}"
            logger.warning("[WikiDistill] %s 第 %d 次失败：%s",
                           cluster.get("slug"), attempt + 1, last_error)
            if attempt == 0:
                prompt = (
                    prompt
                    + "\n\n【上一次输出不合格】\n"
                    + last_error
                    + "\n请严格只输出一个合法 JSON 对象，所有字段齐全，source_chunk_ids 只能取给定的 chunk_id。"
                )
    logger.warning("[WikiDistill] %s 两次生成均失败（%s），回退抽取式",
                   cluster.get("slug"), last_error)
    return _extractive(cluster, settings), True
