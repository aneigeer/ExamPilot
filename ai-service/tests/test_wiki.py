"""LLM Wiki 蒸馏与任务处理器测试。

覆盖三条关键约束：
1. 接地率能识别「格式正确但内容编造」的输出；
2. LLM 输出非 JSON / 引用非法 ID 时必须回退抽取式，而不是产出空任务；
3. WIKI_DISTILL 处理器只回传草稿（PENDING），失败单页不中断整批。
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Dict, List

import pytest

from app.config import Settings
from app.wiki.distiller import (
    SYSTEM_PROMPT,
    _coerce_text,
    _validate,
    distill_cluster,
    grounding_ratio,
)

CLUSTER: Dict[str, Any] = {
    "slug": "kp-5",
    "title": "函数",
    "subjectId": 5,
    "knowledgeId": 5,
    "chunks": [
        {
            "chunkId": 101,
            "title": "函数定义",
            "content": "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        },
        {
            "chunkId": 102,
            "title": "函数声明",
            "content": "函数声明告诉编译器函数的名称、返回类型和参数列表，定义则提供函数体实现。",
        },
        {
            "chunkId": 103,
            "title": "值传递",
            "content": "C 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        },
    ],
}

CATALOG = [{"slug": "kp-6", "title": "数组"}, {"slug": "kp-7", "title": "指针"}]

#: Python repr / JSON dumps 的容器特征，A1 归一化后的文本里绝不允许出现
REPR_MARKERS = ("['", "']", "{'", "': '", "', '", '": "')


def _assert_no_repr(text: str) -> None:
    """断言文本不含任何容器 repr 特征（A1 的核心契约）。

    Args:
        text: 待检查的归一化后文本。
    """
    assert isinstance(text, str)
    for marker in REPR_MARKERS:
        assert marker not in text, f"文本残留 repr 特征 {marker!r}: {text[:120]}"


def _grounded_payload(**overrides: Any) -> Dict[str, Any]:
    """构造一份能通过接地率 / 摘要 / 正文 / 要点校验的合法蒸馏 JSON。

    摘要与正文均取自 ``CLUSTER`` 原始资料，与现有放行用例使用同一套「接地骨架」，
    保证 ``distill_cluster → _validate`` 不会因接地率、摘要 / 正文长度之外的原因
    失败；各用例只需 override 待验证的字段，链路本身全部走真实实现。

    Args:
        **overrides: 覆盖默认字段值（用于制造非数组 / 单整数 / 非法 ID 等形态）。

    Returns:
        可直接 ``json.dumps`` 后作为 ``_chat`` 返回值的 payload 字典。
    """
    payload: Dict[str, Any] = {
        "title": "函数基础",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用；C 语言参数默认按值传递。",
        "content": "## 函数定义\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。\n"
                   "## 参数传递\nC 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        "key_points": ["函数是独立代码块", "参数默认按值传递", "声明与定义分离"],
        "source_chunk_ids": [101, 103],
        "links": [],
    }
    payload.update(overrides)
    return payload


def test_grounding_ratio_detects_hallucination() -> None:
    source = "\n".join(item["content"] for item in CLUSTER["chunks"])
    copied = "函数是完成特定任务的独立代码块，通过函数名调用。"
    hallucinated = "The int type occupies 8 bytes and _Bool is a size specifier in C."
    assert grounding_ratio(copied, source) > 0.3
    assert grounding_ratio(hallucinated, source) < 0.05


def test_distill_accepts_grounded_json(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()
    payload = {
        "title": "函数基础",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用；C 语言参数默认按值传递。",
        "content": "## 函数定义\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。\n"
                   "## 参数传递\nC 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        "key_points": ["函数是独立代码块", "参数默认按值传递", "声明与定义分离"],
        "source_chunk_ids": [101, 103],
        "links": [{"to_slug": "kp-6", "relation": "RELATED", "weight": 0.6},
                  {"to_slug": "fake-page", "relation": "RELATED", "weight": 0.9}],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["title"] == "函数基础"
    assert draft["source_chunk_ids"] == [101, 103]
    # 不存在的 slug 必须被丢弃，避免悬空链接
    assert [item["to_slug"] for item in draft["links"]] == ["kp-6"]
    assert draft["quality_score"] > 0.5


def test_distill_falls_back_when_llm_ignores_json(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return "The int type occupies 8 bytes. _Bool is a size specifier."

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is True
    assert draft["model"] == "extractive-fallback"
    assert draft["source_chunk_ids"]
    assert all(chunk_id in {101, 102, 103} for chunk_id in draft["source_chunk_ids"])
    assert draft["summary"]


def test_distill_rejects_unknown_source_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = Settings.from_env()
    payload = {
        "title": "函数",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        "content": "## 函数\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        "key_points": ["函数是代码块", "可以接收参数"],
        "source_chunk_ids": [999],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is True
    assert draft["model"] == "extractive-fallback"


def test_distill_fills_missing_source_chunk_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    """7B 整段漏掉 source_chunk_ids 时：确定性补全，不回退抽取式。"""
    settings = Settings.from_env()
    payload = {
        "title": "函数基础",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用；C 语言参数默认按值传递。",
        "content": "## 函数定义\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。\n"
                   "## 参数传递\nC 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        "key_points": ["函数是独立代码块", "参数默认按值传递", "声明与定义分离"],
        # 故意整个缺失 source_chunk_ids（模拟 7B 漏字段）
        "links": [],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    valid_ids = {item["chunkId"] for item in CLUSTER["chunks"]}
    ids = draft["source_chunk_ids"]
    assert ids  # 非空
    assert set(ids) <= valid_ids  # 是输入块 ID 的非空子集，不引入非法 ID


def test_distill_parses_string_source_chunk_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    """source_chunk_ids 为字符串 "101,103" 时应解析成 [101, 103]。"""
    settings = Settings.from_env()
    payload = {
        "title": "函数基础",
        "summary": "函数是完成特定任务的独立代码块，通过函数名调用；C 语言参数默认按值传递。",
        "content": "## 函数定义\n函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。\n"
                   "## 参数传递\nC 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
        "key_points": ["函数是独立代码块", "参数默认按值传递"],
        "source_chunk_ids": "101,103",  # 非数组但能安全解析
        "links": [],
    }

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["source_chunk_ids"] == [101, 103]


# 回归：source_chunk_ids 数组全是非法 ID 仍必须回退——
# 由现有 test_distill_rejects_unknown_source_ids 覆盖，确认它仍通过即可，不重复写。


def test_distill_fills_key_points_from_content_when_not_array(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """key_points 非数组时：从 content 按行切出非空要点，不凭空编造、不判失败。

    只替换 _chat 返回构造的假 JSON，_validate / _normalize_key_points /
    _points_from_content 全部走真实实现；断言每条要点都逐字来自 content。
    """
    settings = Settings.from_env()
    payload = _grounded_payload(key_points="要点见正文")  # 字符串，非数组
    assert not isinstance(payload["key_points"], list)

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    # _points_from_content 逐行去掉 "## " 前缀、_clean 截断 160 字后保留原文
    assert draft["key_points"] == [
        "函数定义",
        "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。",
        "参数传递",
        "C 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。",
    ]
    assert draft["source_chunk_ids"] == [101, 103]


def test_distill_still_rejects_when_points_fewer_than_two(
        monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture) -> None:
    """key_points 非数组且 content 只切得出 1 条时：len(points)<2 仍抛错回退。

    「从 content 切要点」不是无条件放行：归一化后不足 2 条，_validate 仍按
    历史语义抛 DistillError("要点少于 2 条")，两次失败后收敛成抽取式草稿。
    caplog 用于把失败原因精确锁定为该错误，而不是摘要/正文/接地率失败。
    """
    settings = Settings.from_env()
    one_line_content = (
        "函数是完成特定任务的独立代码块，通过函数名调用，可以接收参数并返回结果。"
        "函数声明告诉编译器函数的名称、返回类型和参数列表，定义则提供函数体实现。"
        "C 语言函数参数默认按值传递，形参是实参的副本，函数内修改形参不影响实参。"
    )
    assert len(one_line_content) >= 80  # 排除「正文过短」这一失败原因
    payload = _grounded_payload(key_points="要点见正文", content=one_line_content)

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    with caplog.at_level(logging.WARNING, logger="app.wiki.distiller"):
        draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is True
    assert draft["model"] == "extractive-fallback"
    assert "要点少于 2 条" in caplog.text


def test_distill_coerces_single_int_source_chunk_ids(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """source_chunk_ids 为单整数时：_coerce_id_list 归一化为 [n] 并保留合法 ID。

    精确断言 ``[101]``，用于区分「单整数被解析」与「解析为空 → 补全为
    [101, 102, 103]」两条不同分支。
    """
    settings = Settings.from_env()
    payload = _grounded_payload(source_chunk_ids=101)  # int，非数组
    assert isinstance(payload["source_chunk_ids"], int)

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["source_chunk_ids"] == [101]


def test_distill_string_all_invalid_ids_filled_while_array_rejected(
        monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture) -> None:
    """固化一条已知「口径不对称」，**不是安全闸门放松**。

    同一个全非法 ID 值 999，仅因 JSON 类型不同处置不同：

    * 数组 ``[999]`` 命中历史红线「数组且一个合法 ID 都没有 → 抛
      DistillError("source_chunk_ids 未引用任何输入块") → 回退抽取式」；
    * 字符串 ``"999"`` 走「非数组 → _coerce_id_list 尽力解析 → 归一化后为空
      → 补全为 ordered_valid_ids（全部真实输入块 ID）」放行。

    独立验收裁定后者可接受、无需修：补全 ID 全部来自 ``cluster["chunks"]`` 的
    真实 chunkId，不构成编造引用，接地率 0.10 闸门仍在。本用例只把当前行为
    固化下来，防止将来误把任一路径改坏，**不放宽任何闸门**。此处重跑 ``[999]``
    仅用于同值类型对比，独立回归仍由现有 test_distill_rejects_unknown_source_ids 覆盖。
    """
    settings = Settings.from_env()
    payload_array = _grounded_payload(source_chunk_ids=[999])
    payload_string = _grounded_payload(source_chunk_ids="999")
    current: Dict[str, Any] = {"payload": payload_array}

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(current["payload"], ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)

    with caplog.at_level(logging.WARNING, logger="app.wiki.distiller"):
        draft_array, fallback_array = asyncio.run(
            distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback_array is True
    assert draft_array["model"] == "extractive-fallback"
    assert draft_array["quality_score"] == 0.30
    assert "source_chunk_ids 未引用任何输入块" in caplog.text

    current["payload"] = payload_string
    draft_string, fallback_string = asyncio.run(
        distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback_string is False
    assert draft_string["model"] != "extractive-fallback"
    assert draft_string["source_chunk_ids"] == [101, 102, 103]  # 全部真实输入块
    assert draft_string["quality_score"] > 0.5


def test_retry_prompt_names_missing_fields_and_attaches_truncated_raw(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """第二次重试 prompt 点名上次缺的字段，并附上上次 LLM 原始输出（截断 800 字）。

    只替换 _chat（返回构造的假 JSON、记录收到的 user_prompt）；_extract_json /
    _validate / _retry_prompt / 归一化全部走真实实现。第一次输出是「合法 JSON
    但缺 3 个字段且摘要过短」的超长文本，第二次补齐后放行，从而能在真实重试
    prompt 上做精确断言，而不是直接调私有函数。
    """
    settings = Settings.from_env()
    long_content = "".join(
        f"第{index}行：函数声明告诉编译器函数的名称、返回类型和参数列表，定义则提供函数体实现。\n"
        for index in range(1, 31)
    ) + "TAIL_SENTINEL_9F3C7"
    first_payload: Dict[str, Any] = {
        "title": "函数",
        "summary": "摘要过短",       # 4 字 < 20，确保第一次失败并进入重试
        "content": long_content,     # 撑长原始输出到 > 800 字
        # 故意缺 key_points / source_chunk_ids / links
    }
    first_raw = json.dumps(first_payload, ensure_ascii=False)
    second_raw = json.dumps(_grounded_payload(), ensure_ascii=False)
    assert len(first_raw) > 800
    assert "TAIL_SENTINEL_9F3C7" in first_raw

    prompts: List[str] = []

    async def fake_chat(_settings: Any, user_prompt: str, **_kwargs: Any) -> str:
        prompts.append(user_prompt)
        return first_raw if len(prompts) == 1 else second_raw

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["source_chunk_ids"] == [101, 103]
    assert len(prompts) == 2                    # 最多 2 次 LLM 调用
    retry_prompt = prompts[1]
    assert ("上次 JSON 缺少这些字段，本次必须补齐：key_points、source_chunk_ids、links"
            in retry_prompt)
    assert "【上次输出原文（截断）】" in retry_prompt
    assert first_raw[:800] in retry_prompt          # 原文前 800 字被附上
    assert "TAIL_SENTINEL_9F3C7" not in retry_prompt  # 800 字之后被截掉
    assert first_raw[800:] not in retry_prompt


def test_coerce_text_handles_str_list_dict_none_and_others() -> None:
    """A1：`_coerce_text` 四类形态 + 其它类型，输出绝不含容器 repr 特征。

    直接调用真实 `_coerce_text`（不 mock 任何被验证逻辑），逐字断言归一化结果。
    """
    assert _coerce_text(None) == ""
    assert _coerce_text("函数是独立代码块") == "函数是独立代码块"
    assert _coerce_text(["函数定义", "参数传递"]) == "函数定义\n参数传递"
    assert _coerce_text({"要点1": "函数定义", "要点2": "参数传递"}) == (
        "要点1: 函数定义\n要点2: 参数传递")
    # 嵌套容器递归归一化，仍不得出现 repr 特征
    nested = _coerce_text(["函数定义", {"要点": ["参数传递", "值传递"]}])
    assert nested == "函数定义\n要点: 参数传递\n值传递"
    _assert_no_repr(nested)
    # 其它类型（数字 / 浮点 / 布尔 / 自定义对象）保守返回空串
    for other in (101, 0.5, True, object()):
        assert _coerce_text(other) == ""
    # 四类形态的输出都不含 Python repr / JSON 容器特征
    for value in ("函数是独立代码块", ["函数定义", "参数传递"],
                  {"要点1": "函数定义"}, None):
        _assert_no_repr(_coerce_text(value))


def test_validate_coerces_list_content_without_repr(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """A1/A4：content 为数组时，完整链路输出逐行可读文本、不含 repr、不回退。"""
    settings = Settings.from_env()
    raw_lines = [item["content"] for item in CLUSTER["chunks"]]
    payload = _grounded_payload(content=raw_lines)
    assert isinstance(payload["content"], list)

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["content"] == "\n".join(raw_lines)
    assert draft["content"] != str(raw_lines)  # 不是修复前的 str(list) repr
    _assert_no_repr(draft["content"])
    _assert_no_repr(draft["summary"])


def test_validate_coerces_dict_content_without_repr(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """A1/A4：content 为对象时，完整链路输出「键: 值」逐行文本、不含 repr、不回退。"""
    settings = Settings.from_env()
    chunks = CLUSTER["chunks"]
    raw_map: Dict[str, str] = {
        "要点1": chunks[0]["content"],
        "要点2": chunks[1]["content"],
        "要点3": chunks[2]["content"],
    }
    payload = _grounded_payload(content=raw_map)
    assert isinstance(payload["content"], dict)
    expected = "\n".join(f"{key}: {value}" for key, value in raw_map.items())

    async def fake_chat(*args: Any, **kwargs: Any) -> str:
        return json.dumps(payload, ensure_ascii=False)

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["content"] == expected
    assert draft["content"] != str(raw_map)  # 不是修复前的 str(dict) repr
    _assert_no_repr(draft["content"])
    _assert_no_repr(draft["summary"])


def test_system_prompt_declares_field_types_and_keeps_original_rules() -> None:
    """A2：SYSTEM_PROMPT 追加字段类型硬约束，且原有 1-5 条与 JSON 示例逐字保留。"""
    for keyword in (
        "content 必须是一个 Markdown 字符串",
        "不是数组、不是对象",
        "source_chunk_ids 必须是整数数组",
        "key_points 必须是字符串数组",
        "summary 必须是不少于 20 字",
        "links 必须是数组",
        "空数组 []",
    ):
        assert keyword in SYSTEM_PROMPT, keyword
    # 只追加、不改写：原有规则与结构示例仍在
    assert "1. 只依据【原始资料】写作" in SYSTEM_PROMPT
    assert "5. 读者是备考学生" in SYSTEM_PROMPT
    assert '"key_points":["要点1","要点2","要点3"]' in SYSTEM_PROMPT
    assert '"source_chunk_ids":[1,2]' in SYSTEM_PROMPT


def test_retry_prompt_names_actual_field_types(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """A3：重试 prompt 除缺失字段外，点名上次实际写错的字段类型。"""
    settings = Settings.from_env()
    first_payload: Dict[str, Any] = {
        "title": "函数",
        "summary": "短",                      # 摘要过短 → 第一次必失败并进入重试
        "content": ["函数定义", "参数传递"],   # 应为字符串，实为数组
        "key_points": "要点见正文",           # 应为数组，实为字符串
        "source_chunk_ids": 101,             # 应为数组，实为整数
        # 故意缺 links：同时验证「缺失字段」点名未被 A3 破坏
    }
    first_raw = json.dumps(first_payload, ensure_ascii=False)
    second_raw = json.dumps(_grounded_payload(), ensure_ascii=False)
    prompts: List[str] = []

    async def fake_chat(_settings: Any, user_prompt: str, **_kwargs: Any) -> str:
        prompts.append(user_prompt)
        return first_raw if len(prompts) == 1 else second_raw

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert draft["content"]
    assert len(prompts) == 2
    retry_prompt = prompts[1]
    assert "上次 JSON 缺少这些字段，本次必须补齐：links" in retry_prompt
    assert "上次 content 是数组，本次必须改成 Markdown 字符串" in retry_prompt
    assert "上次 key_points 是字符串，本次必须改成 字符串数组" in retry_prompt
    assert "上次 source_chunk_ids 是整数，本次必须改成 整数数组" in retry_prompt
    assert "【上次输出字段类型错误】" in retry_prompt
    assert "【上次输出原文（截断）】" in retry_prompt
    assert first_raw[:800] in retry_prompt


def test_retry_prompt_names_object_content_type(
        monkeypatch: pytest.MonkeyPatch) -> None:
    """A3：content 为对象时点名「对象」，字段齐全时不误报缺失字段。"""
    settings = Settings.from_env()
    first_payload: Dict[str, Any] = {
        "title": "函数",
        "summary": "短",
        "content": {"要点1": "函数定义", "要点2": "参数传递"},
        "key_points": ["函数是代码块", "参数按值传递"],
        "source_chunk_ids": [101],
        "links": [],
    }
    first_raw = json.dumps(first_payload, ensure_ascii=False)
    second_raw = json.dumps(_grounded_payload(), ensure_ascii=False)
    prompts: List[str] = []

    async def fake_chat(_settings: Any, user_prompt: str, **_kwargs: Any) -> str:
        prompts.append(user_prompt)
        return first_raw if len(prompts) == 1 else second_raw

    monkeypatch.setattr("app.wiki.distiller._chat", fake_chat)
    draft, fallback = asyncio.run(distill_cluster(CLUSTER, CATALOG, settings))
    assert fallback is False
    assert len(prompts) == 2
    retry_prompt = prompts[1]
    assert "上次 content 是对象，本次必须改成 Markdown 字符串" in retry_prompt
    assert "上次 key_points 是字符串，本次必须改成 字符串数组" not in retry_prompt
    assert "缺少这些字段" not in retry_prompt


class WikiToolBridge:
    async def tool_post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        assert path == "/internal/agent/tools/wiki-search"
        return {
            "engine": "WIKI",
            "hits": [{
                "id": 1,
                "slug": "kp-27",
                "title": "言语理解与表达",
                "summary": "考察语句逻辑关系与词语选择。",
                "content": "## 考点\n" + "内容" * 400,
                "subjectId": 190,
                "knowledgeId": 27,
                "score": 0.78,
                "channel": "VECTOR",
            }],
        }

    async def tool_get(self, path: str, params: Any = None) -> Any:
        assert path == "/internal/agent/tools/wiki-page/1"
        return {
            "id": 1,
            "slug": "kp-27",
            "title": "言语理解与表达",
            "summary": "考察语句逻辑关系与词语选择。",
            "content": "## 考点\n正文",
            "keyPoints": ["逻辑关系", "词语选择"],
            "relatedPages": [{"id": 2, "title": "判断推理", "slug": "kp-29"}],
        }


def test_agent_wiki_tools_build_contexts() -> None:
    from app.agent.tools import AgentContext, ToolRegistry

    registry = ToolRegistry(WikiToolBridge())  # type: ignore[arg-type]
    ctx = AgentContext(question="言语理解考什么", subject_id=190)
    result = asyncio.run(registry.execute("search_wiki", {"query": "言语理解"}, ctx))
    assert result.ok
    assert result.data[0]["pageId"] == 1
    assert result.contexts[0]["sourceType"] == "WIKI"
    # 截断规则：正文最多 600 字符 + 固定标记
    assert len(result.contexts[0]["content"]) <= 600 + len("…[truncated]")

    page = asyncio.run(registry.execute("get_wiki_page", {"pageId": 1}, ctx))
    assert page.ok
    assert page.data["relatedPages"][0]["pageId"] == 2
    assert page.contexts[0]["refType"] == "WIKI"


class WikiBridge:
    def __init__(self) -> None:
        self.posted: List[Dict[str, Any]] = []
        self.progress: List[int] = []

    async def wiki_input(self, scope: str, subject_id: Any, limit: int, max_chunks: int) -> Dict[str, Any]:
        return {"clusters": [CLUSTER, dict(CLUSTER, slug="kp-6", title="数组")],
                "catalog": CATALOG}

    async def post_wiki_revision(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self.posted.append(payload)
        return {"revisionId": len(self.posted)}


def test_wiki_handler_posts_pending_drafts(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.worker import wiki_handlers

    async def fake_distill(cluster: Dict[str, Any], catalog: List[Dict[str, Any]],
                           settings: Any) -> Any:
        return {
            "title": cluster["title"],
            "summary": "函数是完成特定任务的独立代码块。",
            "content": "## 函数\n函数是完成特定任务的独立代码块。",
            "key_points": ["函数是代码块", "可以调用"],
            "source_chunk_ids": [101],
            "links": [],
            "quality_score": 0.5,
            "model": "fake",
        }, False

    async def report(percent: int) -> None:
        bridge.progress.append(percent)

    monkeypatch.setattr(wiki_handlers, "distill_cluster", fake_distill)
    bridge = WikiBridge()
    result = asyncio.run(wiki_handlers.handle_wiki_distill(
        "WIKI_DISTILL", {"scope": "ALL", "limit": 2, "maxChunks": 3}, 77, bridge, report))

    assert result["total"] == 2
    assert result["drafted"] == 2
    assert len(bridge.posted) == 2
    assert all(item["task_id"] == 77 for item in bridge.posted)
    assert bridge.posted[0]["slug"] == "kp-5"
    assert bridge.posted[1]["slug"] == "kp-6"
    assert bridge.progress[-1] == 99
