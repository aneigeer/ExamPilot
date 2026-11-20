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
    "links 可为空数组；to_slug 只能从【已有页面目录】里选，不得编造。\n\n"
    "【字段类型硬约束】（违反任意一条都视为不合格输出）：\n"
    "6. content 必须是一个 Markdown 字符串，不是数组、不是对象；"
    "正文用 ## 小节分段，不要把要点列表或字典塞进 content。\n"
    "7. source_chunk_ids 必须是整数数组，元素只能从给定 chunk_id 里选，"
    "不能是字符串或单个整数。\n"
    "8. key_points 必须是字符串数组（3-6 条），不能是字符串或对象。\n"
    "9. summary 必须是不少于 20 字的字符串。\n"
    "10. links 必须是数组；没有链接就输出空数组 []。"
)


class DistillError(Exception):
    """单页蒸馏失败（可回退抽取式）。"""


def _clean(text: Any, limit: int) -> str:
    if text is None:
        return ""
    value = str(text).strip()
    value = re.sub(r"\r\n?", "\n", value)
    return value[:limit].strip()


def _coerce_text(value: Any) -> str:
    """把模型可能输出成数组 / 对象的文本字段归一化成**可读纯文本**。

    分层冒烟基线显示本地 7B 会把 ``content`` / ``summary`` 输出成 JSON 数组或
    对象；旧实现 ``_clean`` 里的 ``str(text)`` 会留下 Python repr（``['``、
    ``{'``、``": "`` 等），既让正文失真，又因括号 / 引号与原文 bigram 几乎不
    重合而压低接地率，把本可救回的页推给抽取式兜底。

    归一化口径（**绝不调用 ``str()`` 处理容器**，因此不会产生 repr）：

    * ``None`` → 空串；
    * ``str`` → 原样返回，不改写（接地率校验需要逐字保留原文）；
    * ``list`` → 逐项递归归一化后按行 join，跳过空项；
    * ``dict`` → 逐行 ``键: 值``，键与值都不带引号；
    * 其它类型（数字 / 布尔 / 自定义对象）→ 保守返回空串，由下游长度闸门拦截。

    Args:
        value: JSON 反序列化后的字段值，类型不受控。

    Returns:
        不含 Python / JSON repr 特征的可读文本；非文本输入一律收敛为空串。
    """
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return ""
    if isinstance(value, (int, float)):
        return ""
    if isinstance(value, list):
        list_lines: List[str] = []
        for item in value:
            text = _coerce_text(item).strip()
            if text:
                list_lines.append(text)
        return "\n".join(list_lines)
    if isinstance(value, dict):
        dict_lines: List[str] = []
        for key, item in value.items():
            text = _coerce_text(item).strip()
            if text:
                dict_lines.append(f"{key}: {text}")
        return "\n".join(dict_lines)
    return ""


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


#: 蒸馏 JSON 必需字段（第二次重试时用于诊断「实际缺了哪些字段」）
_REQUIRED_FIELDS = ("title", "summary", "content", "key_points", "source_chunk_ids", "links")


def _coerce_id_list(raw: Any) -> List[int]:
    """把「非数组」的 source_chunk_ids 值尽力解析成整数列表（不做合法性过滤）。

    只处理能安全解析的形态：None → 空、单个整数 → [n]、以及用逗号 / 空白 /
    中文标点分隔的数字字符串（如 "47448,47449"、"47448"）。其余一律返回空列表，
    由调用方走「补全全部输入块 ID」兜底。bool 视为非法（它是 int 子类，但不是块 ID）。
    """
    if raw is None or isinstance(raw, bool):
        return []
    if isinstance(raw, int):
        return [raw]
    if isinstance(raw, str):
        out: List[int] = []
        for part in re.split(r"[,\s，、;；]+", raw.strip()):
            part = part.strip()
            if not part:
                continue
            try:
                out.append(int(part))
            except ValueError:
                continue
        return out
    return []


def _normalize_source_ids(raw_ids: Any, ordered_valid_ids: List[int]) -> List[int]:
    """把模型输出的 source_chunk_ids 归一化成「合法输入块 ID 的有序子集」。

    本地 7B 在 response_format=json_object 下仍会整段漏掉该字段，或输出字符串 /
    单整数。这里做确定性补全，避免整页回退到抽取式兜底：

    * 缺失 / None / 空数组 / 非数组（字符串 "1,2"、单整数等）→ 解析成整数列表，
      只保留合法 ID；归一化后为空则补全为全部输入块 ID（按 chunks 出现顺序）。
    * 是数组且非空 → 沿用原逻辑只保留合法 ID；若一个合法 ID 都没有，抛
      ``DistillError("source_chunk_ids 未引用任何输入块")`` 保持历史安全闸门，
      绝不补全、绝不放行（模型「明确引用了不存在的块」必须回退）。

    ``ordered_valid_ids`` 必须是按 ``cluster["chunks"]`` 出现顺序排列的合法 ID 列表。
    """
    valid_set = set(ordered_valid_ids)

    if isinstance(raw_ids, list):
        if not raw_ids:
            # 空数组视同「没给」，补全为全部输入块
            return list(ordered_valid_ids)
        ids: List[int] = []
        for value in raw_ids:
            try:
                chunk_id = int(value)
            except (TypeError, ValueError):
                continue
            if chunk_id in valid_set and chunk_id not in ids:
                ids.append(chunk_id)
        if not ids:
            raise DistillError("source_chunk_ids 未引用任何输入块")
        return ids

    # 非数组：缺失 / None / 字符串 / 单整数等，尽力解析后只保留合法 ID
    parsed = _coerce_id_list(raw_ids)
    ids = []
    for chunk_id in parsed:
        if chunk_id in valid_set and chunk_id not in ids:
            ids.append(chunk_id)
    if not ids:
        # 归一化后为空（含缺失 / 无法解析 / 全是非法 ID）→ 补全全部输入块
        return list(ordered_valid_ids)
    return ids


def _points_from_content(content: str) -> List[str]:
    """从正文按 ## 小标题 / 换行切出非空要点文字（只截取原文，不凭空编造）。"""
    points: List[str] = []
    for line in (content or "").splitlines():
        line = re.sub(r"^#{1,6}\s*", "", line.strip())
        line = re.sub(r"^[-*+]\s+", "", line).strip()
        if line and line not in points:
            points.append(line)
    return points


def _normalize_key_points(raw_points: Any, content: str) -> List[str]:
    """把模型输出的 key_points 归一化成非空字符串列表。

    模型漏掉该字段或输出非数组时不直接判失败：从 ``content`` 按 ## 小标题或换行
    切出非空行作为要点（绝不编造文字）。调用方保留「仍不足 2 条才回退到原有
    『要点少于 2 条』错误」的语义。
    """
    if isinstance(raw_points, list):
        items = raw_points
    else:
        items = _points_from_content(content)
    return [p for p in (_clean(item, 160) for item in items) if p]


#: 重试时逐字段点名的类型约束：(字段名, 期望类型, 期望类型的可读说明)。
#: 只处理「字段存在但类型写错」；字段缺失仍由 _REQUIRED_FIELDS 单独点名。
_RETRY_TYPE_RULES: Tuple[Tuple[str, type, str], ...] = (
    ("content", str, "Markdown 字符串"),
    ("key_points", list, "字符串数组"),
    ("source_chunk_ids", list, "整数数组"),
)


def _type_label(value: Any) -> str:
    """把 JSON 值的实际类型翻成给模型看的中文短标签（用于重试 prompt 点名）。"""
    if isinstance(value, dict):
        return "对象"
    if isinstance(value, list):
        return "数组"
    if isinstance(value, bool):
        return "布尔值"
    if isinstance(value, str):
        return "字符串"
    if isinstance(value, int):
        return "整数"
    if isinstance(value, float):
        return "数字"
    if value is None:
        return "空值"
    return "其它类型"


def _describe_type_errors(data: Dict[str, Any]) -> List[str]:
    """列出「字段存在但类型不对」的点名文案，供 ``_retry_prompt`` 追加到重试提示。"""
    errors: List[str] = []
    for field, expected_type, expected_label in _RETRY_TYPE_RULES:
        if field not in data or isinstance(data[field], expected_type):
            continue
        errors.append(
            f"上次 {field} 是{_type_label(data[field])}，"
            f"本次必须改成 {expected_label}"
        )
    return errors


def _retry_prompt(base_prompt: str, error: str, last_raw: str) -> str:
    """构造第二次（最后一次）重试 prompt。

    相比泛泛的「所有字段齐全」，这里点名上次实际缺的字段、**点名上次实际
    写错的字段类型**、明确 content 需 400-800 字且含 3-4 个 ## 小节，并附上
    上次输出原文（截断到安全长度）。最多两次调用的限制由 ``distill_cluster`` 保证。
    """
    parts = [base_prompt, "\n\n【上一次输出不合格】", error]
    missing: List[str] = []
    type_errors: List[str] = []
    try:
        data = _extract_json(last_raw)
        missing = [field for field in _REQUIRED_FIELDS if field not in data]
        type_errors = _describe_type_errors(data)
    except DistillError:
        pass
    if missing:
        parts.append("上次 JSON 缺少这些字段，本次必须补齐：" + "、".join(missing))
    if type_errors:
        parts.append("【上次输出字段类型错误】" + "；".join(type_errors))
    parts.append(
        "content 必须是 400-800 字、含 3-4 个 ## 小节的 Markdown 正文；"
        "source_chunk_ids 必须是给定 chunk_id 组成的数组；"
        "key_points 必须是数组；links 没有就输出空数组。"
    )
    parts.append("请严格只输出一个合法 JSON 对象，所有字段齐全。")
    if last_raw:
        # 原文截断到安全长度，避免把噪声 / 超长输出塞满上下文
        parts.append("【上次输出原文（截断）】\n" + last_raw[:800])
    return "\n".join(parts)


def _validate(candidate: Dict[str, Any], cluster: Dict[str, Any],
              catalog: List[Dict[str, Any]], settings: Settings) -> Dict[str, Any]:
    source_chunks = cluster.get("chunks") or []
    source_text = "\n".join(_clean(item.get("content"), 2000) for item in source_chunks)
    valid_ids = {int(item["chunkId"]) for item in source_chunks if item.get("chunkId") is not None}
    # 按 cluster["chunks"] 出现顺序的合法 ID 有序列表（补全 source_chunk_ids 时用）
    ordered_valid_ids: List[int] = []
    for item in source_chunks:
        if item.get("chunkId") is None:
            continue
        chunk_id = int(item["chunkId"])
        if chunk_id not in ordered_valid_ids:
            ordered_valid_ids.append(chunk_id)

    title = _clean(candidate.get("title"), 100) or _clean(cluster.get("title"), 100)
    # A1：模型会把文本字段输出成数组 / 对象，先归一化成可读纯文本再截断，
    # 避免 str() 留下 Python repr 污染正文、并把接地率压低（下游长度闸门不变）。
    summary = _clean(_coerce_text(candidate.get("summary")), 900)
    content = _clean(_coerce_text(candidate.get("content")), settings.wiki.distill_max_chars)
    if len(summary) < 20:
        raise DistillError("摘要过短")
    if len(content) < 80:
        raise DistillError("正文过短")

    # 结构字段确定性补全：key_points / source_chunk_ids 缺失或轻微不合法时补齐，
    # 而不是整页回退抽取式（详见各函数的 docstring）。
    points = _normalize_key_points(candidate.get("key_points"), content)
    if len(points) < 2:
        raise DistillError("要点少于 2 条")

    ids = _normalize_source_ids(candidate.get("source_chunk_ids"), ordered_valid_ids)

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
    last_raw = ""
    for attempt in range(2):
        try:
            raw = await _chat(settings.llm, prompt, strict_json=True)
            last_raw = raw
            candidate = _extract_json(raw)
            draft = _validate(candidate, cluster, catalog, settings)
            return draft, False
        except Exception as exc:  # noqa: BLE001 —— 单页失败必须回退，不能中断整批
            last_error = f"{type(exc).__name__}: {exc}"
            logger.warning("[WikiDistill] %s 第 %d 次失败：%s",
                           cluster.get("slug"), attempt + 1, last_error)
            if attempt == 0:
                prompt = _retry_prompt(prompt, last_error, last_raw)
    logger.warning("[WikiDistill] %s 两次生成均失败（%s），回退抽取式",
                   cluster.get("slug"), last_error)
    return _extractive(cluster, settings), True
