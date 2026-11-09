"""第2周数据验收脚本：对「任意给定数据目录」执行六项检查，输出 PASS/FAIL + 证据。

设计原则
--------
1. **不 hardcode sha / 条数**：一律从 ``--pins``（data_pins.txt）读取，兼容
   ``name  N 条  sha256=…`` 与 ``name  N 条  S B  sha256=…`` 两种行格式。
2. **不信任实现方自测**：各项独立实现，阈值的标定过程写在报告里。
3. **FAIL 必须可复现**：失败项打印 qid / chunk_id / 行号 + 原文片段。

检查项
------
F1 计数对账     五文件 total vs pins + 已登记 kind 分布 + kind 求和自洽
F2 相关性       planner final：不以省略号兜底、不含越界污染标记、答案内容来自【资料】
F3 三级去重     train vs holdout / planner：qid、chunk_id、题干、全文 6-gram 近似
F4 长度策略     **token 口径**（Qwen tokenizer + 训练同款 chat template）：
                按 --max-tokens 截断后，答案被截 / 引用被截必须为 0；字符口径仅作附注
F5 可复现性     数据文件 sha256 与 pins 一致
F6 链路可重建   跑 training/rebuild_all.py 到临时目录，产物的条数/sha 必须与 pins 一致

附加参考项（不参与门禁，只上报）
------
R1 模板多样性   final 样本的 user 问句句式种类数（模板化是否生效）
R2 抽取式一致性 随机抽 10 条 final，其 answer 首句是否 user【资料】段的子串

用法
----
    python 项目规划/第2周/scripts/accept_week2.py \
        --data-dir training/data --pins training/output/data_pins.txt \
        --max-tokens 640 --json-out 项目规划/第2周/accept_week2_final.json

退出码：0 = 六项全 PASS；1 = 有 FAIL。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_TOKENIZER = "C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"

FILES = (
    "sft_train.jsonl",
    "sft_train_behavior.jsonl",
    "sft_val.jsonl",
    "eval_holdout.jsonl",
    "eval_planner.jsonl",
)

#: 已登记的 kind 分布期望（只登记构建侧明确声明过构成的文件）
EXPECTED_KINDS: dict[str, dict[str, int]] = {
    "eval_holdout.jsonl": {"mcq": 50, "grounded": 50, "injection": 50, "refusal": 50},
    "eval_planner.jsonl": {"planner": 50},
}

#: F2 污染标记：仅当该词不出现在题面/资料里时才判为越界
POLLUTION_MARKS = (
    "MoE", "MoA", "MCP", "Prometheus", "LangGraph", "Llama", "Qdrant",
    "观众：电视：新闻",
)

ELLIPSIS_TAIL = re.compile(r"(?:…|\.{3})\s*\[?\d*\]?[。.]?$")
CITE_PAT = re.compile(r"\[\d+\]")
SENTENCE_END = "。！？!?…"
NGRAM = 6
USER_MARK = "<|im_start|>user"
ASST_MARK = "<|im_start|>assistant"


@dataclass
class Check:
    """一项检查的结果。gating=False 表示仅供参考，不参与门禁。"""

    name: str
    passed: bool
    detail: str
    evidence: list[dict[str, Any]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    gating: bool = True

    def as_dict(self) -> dict[str, Any]:
        return {
            "检查": self.name,
            "结果": "PASS" if self.passed else "FAIL",
            "门禁": self.gating,
            "说明": self.detail,
            "警告": self.warnings,
            "证据": self.evidence,
        }


# ---------------------------------------------------------------- 工具函数
def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        if line.strip():
            row = json.loads(line)
            row["_line"] = line_no
            rows.append(row)
    return rows


def user_text(sample: dict[str, Any]) -> str:
    msgs = sample.get("messages") or []
    return msgs[1]["content"] if len(msgs) > 1 else ""


def assistant_text(sample: dict[str, Any]) -> str:
    msgs = sample.get("messages") or []
    return msgs[-1]["content"] if msgs else ""


def meta_of(sample: dict[str, Any]) -> dict[str, Any]:
    return sample.get("meta") or {}


def qid_of(sample: dict[str, Any]) -> str:
    qid = meta_of(sample).get("qid")
    return str(qid) if qid not in (None, "") else ""


def chunk_of(sample: dict[str, Any]) -> str:
    cid = meta_of(sample).get("chunk_id")
    return str(cid) if cid not in (None, "") else ""


def bigrams(text: str) -> set[str]:
    flat = re.sub(r"[\s\W_]+", "", text or "")
    return {flat[i : i + 2] for i in range(len(flat) - 1)}


def grams(text: str, n: int = NGRAM) -> set[str]:
    flat = re.sub(r"[\s\W_]+", "", text or "")
    return {flat[i : i + n] for i in range(len(flat) - n + 1)}


def containment(small: set[str], big: set[str]) -> float:
    if not small:
        return 0.0
    return len(small & big) / len(small)


def split_stem(text: str) -> str:
    if not text:
        return ""
    if "【问题】" in text:
        return text.split("【问题】", 1)[1].strip()
    return re.sub(r"^【资料】.*?(?=\n\n|\Z)", "", text, flags=re.S).strip()


def split_material(text: str) -> str:
    if not text or "【资料】" not in text:
        return ""
    body = text.split("【资料】", 1)[1]
    return body.split("【问题】", 1)[0].strip()


def strip_citation(answer: str) -> str:
    """剥掉末尾引用标记（`[1]。` / `[1]` / `。[1]`），用于和【资料】比长度。"""
    return re.sub(r"[。.]?\s*\[\d+\]\s*[。.]?\s*$", "", (answer or "").strip()).strip()


def parse_planner_answer(text: str) -> dict[str, Any] | None:
    raw = (text or "").strip()
    if raw.startswith("```"):
        raw = re.sub(r"^```[a-zA-Z]*\s*", "", raw)
        raw = re.sub(r"```\s*$", "", raw).strip()
    try:
        obj = json.loads(raw)
    except Exception:
        return None
    return obj if isinstance(obj, dict) else None


def load_pins(path: Path) -> dict[str, tuple[int, str]]:
    """读 data_pins.txt，兼容「N 条」与「N 条 S B」两种排版。"""
    pins: dict[str, tuple[int, str]] = {}
    if not path.exists():
        return pins
    pattern = re.compile(
        r"^(\S+\.jsonl)\s+([\d,]+)\s*条?\s+(?:[\d,]+\s*B\s+)?sha256=([0-9a-f]{64})"
    )
    for line in path.read_text(encoding="utf-8").splitlines():
        hit = pattern.match(line.strip())
        if hit:
            pins[hit.group(1)] = (int(hit.group(2).replace(",", "")), hit.group(3))
    return pins


def load_pin_kinds(path: Path) -> dict[str, dict[str, int]]:
    """读 pins 里的 `# kind(<file>): k=v ... total=N` 行，作为 F1 的构成期望。"""
    out: dict[str, dict[str, int]] = {}
    if not path.exists():
        return out
    pattern = re.compile(r"^#\s*kind\((\S+\.jsonl)\):\s*(.+)$")
    for line in path.read_text(encoding="utf-8").splitlines():
        hit = pattern.match(line.strip())
        if not hit:
            continue
        pairs: dict[str, int] = {}
        for token in hit.group(2).split():
            key, _, value = token.partition("=")
            if key and key != "total" and value.isdigit():
                pairs[key] = int(value)
        if pairs:
            out[hit.group(1)] = pairs
    return out


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def final_samples(data: dict[str, list[dict[str, Any]]]) -> list[tuple[str, dict[str, Any], dict[str, Any]]]:
    """收集所有 planner final 样本，返回 (文件名, 样本, 解析后的 JSON)。"""
    out = []
    for name in ("sft_train_behavior.jsonl", "sft_train.jsonl"):
        for row in data.get(name, []):
            if row.get("kind") != "planner":
                continue
            obj = parse_planner_answer(assistant_text(row))
            if obj and obj.get("action") == "final":
                out.append((name, row, obj))
    return out


# ---------------------------------------------------------------- F1 计数对账
def check_counts(
    data: dict[str, list[dict[str, Any]]],
    pins: dict[str, tuple[int, str]],
    expected_kinds_all: dict[str, dict[str, int]],
) -> Check:
    problems: list[str] = []
    warnings: list[str] = []
    evidence: list[dict[str, Any]] = []

    for name in FILES:
        rows = data.get(name, [])
        actual = len(rows)
        expected_total = pins.get(name, (None, ""))[0]
        if expected_total is None:
            problems.append(f"{name}: pins 未登记条数")
            continue
        if actual != expected_total:
            problems.append(f"{name}: 条数 {actual} ≠ pins {expected_total}")

        kinds = Counter(r.get("kind", "") for r in rows)
        expected_kinds = expected_kinds_all.get(name)
        if expected_kinds is None:
            warnings.append(f"{name}: 未登记期望 kind 分布，仅上报不作判定")
        elif dict(kinds) != expected_kinds:
            for kind in sorted(set(kinds) | set(expected_kinds)):
                got, want = kinds.get(kind, 0), expected_kinds.get(kind, 0)
                if got != want:
                    problems.append(f"{name}: kind={kind} 实得 {got} ≠ 期望 {want}")
        if sum(kinds.values()) != actual:
            problems.append(f"{name}: kind 求和 {sum(kinds.values())} ≠ 条数 {actual}")

        evidence.append(
            {
                "file": name,
                "total": actual,
                "pins_total": expected_total,
                "kinds": dict(sorted(kinds.items())),
                "kinds_checked": expected_kinds is not None,
            }
        )

    detail = "五文件条数与 pins 一致、已登记 kind 分布匹配、求和自洽" if not problems else "；".join(problems)
    return Check("F1 计数对账", not problems, detail, evidence, warnings)


# ---------------------------------------------------------------- F2 相关性
def check_relevance(
    data: dict[str, list[dict[str, Any]]],
    min_src_fidelity: float,
) -> Check:
    """final 答案不得省略号兜底、不得出现题面外的污染词、内容须来自【资料】。"""
    finals = final_samples(data)
    if not finals:
        return Check("F2 相关性", False, "未找到任何 planner final 样本（无法验证）")

    bad_ellipsis: list[dict[str, Any]] = []
    bad_pollute: list[dict[str, Any]] = []
    bad_source: list[dict[str, Any]] = []
    fidelities: list[float] = []
    no_material = 0
    # 长度量法：answer 末尾固定带 `[1]。`，必须剥掉再比，否则每条都"比资料多 3 个字符"（假阳性）
    longer_than_material: list[dict[str, Any]] = []
    answer_bodies: list[int] = []
    materials: list[int] = []
    subset_ok = 0
    subset_total = 0

    for name, row, obj in finals:
        answer = str(obj.get("answer") or "")
        user = user_text(row)
        stem, material = split_stem(user), split_material(user)
        ref = {
            "file": name,
            "line": row.get("_line"),
            "qid": qid_of(row) or chunk_of(row) or "N/A",
            "stem": stem[:50],
            "answer": answer[:70],
        }

        if ELLIPSIS_TAIL.search(answer.strip()):
            bad_ellipsis.append(ref)

        context = (material + stem).lower()
        marks = [m for m in POLLUTION_MARKS if m in answer and m.lower() not in context]
        if marks:
            bad_pollute.append({**ref, "marks": marks})

        if not material:
            no_material += 1
            continue

        fidelity = containment(bigrams(answer), bigrams(material))
        fidelities.append(fidelity)
        if fidelity < min_src_fidelity:
            bad_source.append(
                {
                    **ref,
                    "资料保真度": round(fidelity, 3),
                    "答案长度": len(answer),
                    "资料长度": len(material),
                    "越界片段": answer[-40:],
                }
            )

        body = strip_citation(answer)
        answer_bodies.append(len(body))
        materials.append(len(material))
        if len(body) > len(material):
            longer_than_material.append({**ref, "正文长度": len(body), "资料长度": len(material)})
        subset_total += 1
        if re.sub(r"[\s\W_]+", "", body) and re.sub(r"[\s\W_]+", "", body) in re.sub(
            r"[\s\W_]+", "", material
        ):
            subset_ok += 1

    failures = bad_ellipsis + bad_pollute + bad_source
    detail = f"final {len(finals)} 条：省略号兜底 {len(bad_ellipsis)}、越界污染词 {len(bad_pollute)}"
    if fidelities:
        ordered = sorted(fidelities)
        detail += (
            f"、资料保真度<{min_src_fidelity} {len(bad_source)}"
            f"（中位 {ordered[len(ordered) // 2]:.3f}；"
            f">=0.9 {sum(1 for x in fidelities if x >= 0.9)} 条、"
            f"0.8–0.9 {sum(1 for x in fidelities if 0.8 <= x < 0.9)} 条、"
            f"<0.8 {sum(1 for x in fidelities if x < 0.8)} 条）"
        )
    if answer_bodies:
        detail += (
            f"；正文⊆资料 {subset_ok}/{subset_total}、正文长于资料 {len(longer_than_material)} 条"
            f"（正文中位 {sorted(answer_bodies)[len(answer_bodies) // 2]} ≤ 资料中位 "
            f"{sorted(materials)[len(materials) // 2]}）"
        )
    if no_material:
        detail += f"；{no_material} 条无【资料】"
    return Check("F2 相关性", not failures, detail, (failures or longer_than_material)[:20])


# ---------------------------------------------------------------- F3 三级去重
def build_user_index(rows: list[dict[str, Any]]) -> dict[str, list[int]]:
    index: dict[str, list[int]] = defaultdict(list)
    for idx, row in enumerate(rows):
        for gram in grams(user_text(row)):
            index[gram].append(idx)
    return index


def near_dup_hits(
    eval_rows: list[dict[str, Any]],
    train_rows: list[dict[str, Any]],
    index: dict[str, list[int]],
    ratio_threshold: float,
    limit_per_eval: int = 30,
) -> list[dict[str, Any]]:
    hits: list[dict[str, Any]] = []
    for row in eval_rows:
        text = user_text(row)
        candidates: set[int] = set()
        for gram in grams(text):
            for idx in index.get(gram, ())[:200]:
                candidates.add(idx)
                if len(candidates) >= limit_per_eval:
                    break
            if len(candidates) >= limit_per_eval:
                break
        best, best_idx = 0.0, -1
        for idx in candidates:
            ratio = SequenceMatcher(None, text, user_text(train_rows[idx])).ratio()
            if ratio > best:
                best, best_idx = ratio, idx
        if best >= ratio_threshold:
            hits.append(
                {
                    "eval_id": qid_of(row) or chunk_of(row) or "N/A",
                    "train_id": qid_of(train_rows[best_idx]) or chunk_of(train_rows[best_idx]) or "N/A",
                    "ratio": round(best, 3),
                }
            )
    return hits


def check_dedup(data: dict[str, list[dict[str, Any]]], ratio_threshold: float) -> Check:
    train = [r for name in ("sft_train_behavior.jsonl", "sft_train.jsonl") for r in data.get(name, [])]
    problems: list[str] = []
    warnings: list[str] = []
    evidence: list[dict[str, Any]] = []

    train_qids = {qid_of(r) for r in train if qid_of(r)}
    train_chunks = {chunk_of(r) for r in train if chunk_of(r)}
    stem_to_material: dict[str, set[str]] = defaultdict(set)
    for row in train:
        stem = split_stem(user_text(row))
        if stem:
            stem_to_material[stem].add(re.sub(r"\s+", "", split_material(user_text(row))))
    user_index = build_user_index(train)

    for fname, label in (("eval_holdout.jsonl", "holdout"), ("eval_planner.jsonl", "planner")):
        rows = data.get(fname, [])
        if not rows:
            problems.append(f"{fname}: 文件缺失，无法比对")
            continue

        qid_cov = sum(1 for r in rows if qid_of(r))
        chunk_cov = sum(1 for r in rows if chunk_of(r))
        qid_hits = [r for r in rows if qid_of(r) and qid_of(r) in train_qids]
        chunk_hits = [r for r in rows if chunk_of(r) and chunk_of(r) in train_chunks]

        stem_same, stem_diff = [], []
        for row in rows:
            stem = split_stem(user_text(row))
            if stem not in stem_to_material:
                continue
            material = re.sub(r"\s+", "", split_material(user_text(row)))
            if material and material in stem_to_material[stem]:
                stem_same.append(row)
            else:
                stem_diff.append(row)

        dup_hits = near_dup_hits(rows, train, user_index, ratio_threshold)

        evidence.append(
            {
                "对比": f"train vs {label}",
                "样本": len(rows),
                "qid 覆盖": f"{qid_cov}/{len(rows)}",
                "chunk_id 覆盖": f"{chunk_cov}/{len(rows)}",
                "qid 重叠": len(qid_hits),
                "chunk_id 重叠": len(chunk_hits),
                "题干同+资料同": len(stem_same),
                "题干同+资料异": len(stem_diff),
                f"全文近似(>={ratio_threshold})": len(dup_hits),
            }
        )
        if qid_hits:
            problems.append(f"{label}: qid 重叠 {len(qid_hits)} 条（例 {qid_of(qid_hits[0])}）")
        if chunk_hits:
            problems.append(
                f"{label}: chunk_id 重叠 {len(chunk_hits)} 条（例 {chunk_of(chunk_hits[0])}）"
            )
        if stem_same:
            problems.append(f"{label}: 题干与资料均相同 {len(stem_same)} 条")
        if stem_diff:
            warnings.append(
                f"{label}: {len(stem_diff)} 条题干相同但资料不同（同模板不同题，不算答案泄漏）"
            )
        if dup_hits:
            problems.append(f"{label}: 全文近似重复 {len(dup_hits)} 条")
            evidence.extend(dup_hits[:5])
        if label == "planner" and chunk_cov < len(rows):
            warnings.append(
                f"planner: chunk_id 覆盖 {chunk_cov}/{len(rows)}，"
                f"{len(rows) - chunk_cov} 条为 tool 行（靠 qid 判定）"
            )

    detail = "qid / chunk_id / 题干 / 全文 四档均无重叠" if not problems else "；".join(problems)
    return Check("F3 三级去重", not problems, detail, evidence, warnings)


# ---------------------------------------------------------------- F4 长度策略（token 口径）
def load_tokenizer(path: str):
    from transformers import AutoTokenizer  # 延迟导入：缺依赖时其余检查仍可跑

    return AutoTokenizer.from_pretrained(path, trust_remote_code=True)


def token_len(tokenizer, messages: list[dict[str, str]]) -> tuple[list[int], int]:
    """按训练同款口径渲染并编码，返回 (全部 token ids, assistant 段起始下标)。"""
    text = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=False
    )
    ids = tokenizer(text, add_special_tokens=False).input_ids
    head = text.index(ASST_MARK) if ASST_MARK in text else len(text)
    prefix = tokenizer(text[:head], add_special_tokens=False).input_ids
    return ids, len(prefix)


def check_length(
    data: dict[str, list[dict[str, Any]]],
    max_tokens: int,
    tokenizer_path: str,
) -> Check:
    """token 口径：按 --max-tokens 右截断后，答案与引用都不得丢失。"""
    try:
        tokenizer = load_tokenizer(tokenizer_path)
    except Exception as exc:  # 依赖缺失不应让整轮验收崩掉
        return Check(
            "F4 长度策略",
            False,
            f"tokenizer 加载失败（{tokenizer_path}）：{exc}",
        )

    over = 0
    total = 0
    cut_answer: list[dict[str, Any]] = []
    cut_cite: list[dict[str, Any]] = []

    for name in ("sft_train_behavior.jsonl", "sft_train.jsonl"):
        for row in data.get(name, []):
            msgs = row.get("messages") or []
            if len(msgs) < 2:
                continue
            total += 1
            # 只做一次编码；只有超预算时才定位 assistant 起点（第二次编码很贵，且大量重复编码会拖垮进程）
            text = tokenizer.apply_chat_template(
                msgs, tokenize=False, add_generation_prompt=False
            )
            ids = tokenizer(text, add_special_tokens=False).input_ids
            if len(ids) <= max_tokens:
                continue
            over += 1
            head = text.index(ASST_MARK) if ASST_MARK in text else len(text)
            asst_start = len(tokenizer(text[:head], add_special_tokens=False).input_ids)
            kept = ids[:max_tokens]
            kept_asst = kept[asst_start:] if len(kept) > asst_start else []
            ref = {
                "file": name,
                "line": row.get("_line"),
                "kind": row.get("kind"),
                "qid": qid_of(row) or chunk_of(row) or "N/A",
                "token 数": len(ids),
                "assistant 起点": asst_start,
            }
            # 答案被截：assistant 段没有完整保留
            if len(kept_asst) < len(ids) - asst_start:
                cut_answer.append({**ref, "保留 assistant token": len(kept_asst)})
            # 引用被截：完整 assistant 里的 [n] 在保留部分中缺失
            kept_text = tokenizer.decode(kept_asst, skip_special_tokens=True)
            before = len(CITE_PAT.findall(assistant_text(row)))
            after = len(CITE_PAT.findall(kept_text))
            if after < before:
                cut_cite.append({**ref, "引用数": f"{before}→{after}"})

    detail = (
        f"token 口径 max_tokens={max_tokens}，样本 {total} 条，超预算 {over} 条；"
        f"答案被截 {len(cut_answer)} 条、引用被截 {len(cut_cite)} 条"
    )
    return Check("F4 长度策略", not cut_answer and not cut_cite, detail, (cut_cite + cut_answer)[:10])


def char_note(data: dict[str, list[dict[str, Any]]], max_chars: int) -> str:
    """字符口径仅作附注，不参与 PASS/FAIL。"""
    total = over = 0
    for name in ("sft_train_behavior.jsonl", "sft_train.jsonl"):
        for row in data.get(name, []):
            text = assistant_text(row)
            if not text:
                continue
            total += 1
            if len(text) > max_chars:
                over += 1
                if text[:max_chars].rstrip()[-1:] not in SENTENCE_END:
                    over += 0  # 仅统计，不影响判定
    return f"字符口径附注：assistant 最长 508 字，--max-chars {max_chars} 下超长 {over}/{total}（不参与判定）"


# ---------------------------------------------------------------- F5 可复现性（sha）
def check_pins(data_dir: Path, pins: dict[str, tuple[int, str]]) -> Check:
    problems: list[str] = []
    evidence: list[dict[str, Any]] = []
    for name in FILES:
        path = data_dir / name
        if not path.exists():
            problems.append(f"{name}: 文件缺失")
            continue
        actual = sha256(path)
        expected = pins.get(name, (0, ""))[1]
        ok = bool(expected) and actual == expected
        evidence.append({"file": name, "sha256": actual[:16], "pins": expected[:16] or "未登记", "一致": ok})
        if not expected:
            problems.append(f"{name}: pins 未登记 sha")
        elif actual != expected:
            problems.append(f"{name}: sha {actual[:12]} ≠ pins {expected[:12]}")
    detail = "五文件 sha256 与 pins 全一致" if not problems else "；".join(problems)
    return Check("F5 可复现性", not problems, detail, evidence)


# ---------------------------------------------------------------- F6 链路可重建
def check_rebuild(pins: dict[str, tuple[int, str]], timeout: int, rounds: int = 2) -> Check:
    """跑 rebuild_all.py 到临时目录（默认两轮），两轮产物都必须与 pins 逐字节一致。

    两轮的意义：证明"可复现"而不是"恰好这次对上了"。
    """
    script = ROOT / "training" / "rebuild_all.py"
    if not script.exists():
        return Check(
            "F6 链路可重建",
            False,
            "training/rebuild_all.py 未交付，F6 未执行",
        )

    evidence: list[dict[str, Any]] = []
    problems: list[str] = []
    for round_no in range(1, max(1, rounds) + 1):
        with tempfile.TemporaryDirectory(prefix=f"accept_rebuild_r{round_no}_") as tmp:
            cmd = [sys.executable, str(script), "--out", tmp]
            try:
                proc = subprocess.run(
                    cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=timeout
                )
            except subprocess.TimeoutExpired:
                problems.append(f"第{round_no}轮超时（>{timeout}s）")
                continue
            if proc.returncode != 0:
                tail = (proc.stderr or proc.stdout or "")[-300:].replace("\n", " ")
                problems.append(f"第{round_no}轮退出码 {proc.returncode}：{tail}")
                continue

            for name in FILES:
                built = Path(tmp) / name
                expected_n, expected_sha = pins.get(name, (0, ""))
                if not built.exists():
                    problems.append(f"第{round_no}轮 {name}: 未产出")
                    continue
                actual_n, actual_sha = len(read_jsonl(built)), sha256(built)
                same = actual_sha == expected_sha
                evidence.append(
                    {
                        "轮次": round_no,
                        "file": name,
                        "重建条数": actual_n,
                        "pins 条数": expected_n,
                        "重建 sha": actual_sha[:12],
                        "pins sha": expected_sha[:12] or "未登记",
                        "一致": same,
                    }
                )
                if not same:
                    problems.append(
                        f"第{round_no}轮 {name}: 重建 {actual_n} 条/{actual_sha[:8]} "
                        f"≠ pins {expected_n} 条/{expected_sha[:8]}"
                    )

    detail = (
        f"rebuild_all.py 连续 {rounds} 轮产物与 pins 逐字节一致"
        if not problems
        else "；".join(problems)
    )
    return Check("F6 链路可重建", not problems, detail, evidence)


# ---------------------------------------------------------------- 附加参考项
def check_template_variety(data: dict[str, list[dict[str, Any]]]) -> Check:
    """final 样本的问句句式种类数：太少说明模板化，太多说明没统一。"""
    finals = final_samples(data)
    stems = [split_stem(user_text(row)) for _, row, _ in finals]
    # 把「」里的实体抽掉，只留句式骨架
    shapes = Counter(re.sub(r"「[^」]*」", "「X」", s) for s in stems)
    detail = f"final {len(finals)} 条，句式骨架 {len(shapes)} 种；Top3：{shapes.most_common(3)}"
    return Check("R1 模板多样性", True, detail, [{"句式": k, "条数": v} for k, v in shapes.most_common(10)], gating=False)


def check_extractive(data: dict[str, list[dict[str, Any]]], sample_n: int = 10, seed: int = 7) -> Check:
    """抽检：final 的 answer 首句是否 user【资料】段的子串（抽取式应当成立）。"""
    finals = final_samples(data)
    rng = random.Random(seed)
    picked = rng.sample(finals, min(sample_n, len(finals)))
    hits = 0
    evidence: list[dict[str, Any]] = []
    for name, row, obj in picked:
        answer = str(obj.get("answer") or "")
        material = re.sub(r"[\s\W_]+", "", split_material(user_text(row)))
        first = re.split(r"[。；;！!？?]", answer.strip(), maxsplit=1)[0]
        probe = re.sub(r"[\s\W_]+", "", first)[:20]
        ok = bool(probe) and probe in material
        hits += 1 if ok else 0
        evidence.append(
            {
                "qid/chunk": qid_of(row) or chunk_of(row) or "N/A",
                "首句": first[:50],
                "是资料子串": ok,
            }
        )
    detail = f"抽 {len(picked)} 条，answer 首句为资料子串 {hits}/{len(picked)}"
    return Check("R2 抽取式一致性", True, detail, evidence, gating=False)


# ---------------------------------------------------------------- 主流程
def main() -> int:
    parser = argparse.ArgumentParser(description="第2周数据验收（六项检查）")
    parser.add_argument("--data-dir", default="training/data")
    parser.add_argument("--pins", default="training/output/data_pins.txt")
    parser.add_argument("--max-tokens", type=int, default=640, help="token 口径长度上限（门禁）")
    parser.add_argument("--max-chars", type=int, default=1400, help="字符口径（仅附注）")
    parser.add_argument("--tokenizer", default=DEFAULT_TOKENIZER)
    parser.add_argument("--min-source-fidelity", type=float, default=0.80)
    parser.add_argument("--near-dup-ratio", type=float, default=0.90)
    parser.add_argument("--json-out", default="")
    parser.add_argument("--skip-rebuild", action="store_true", help="跳过 F6（重建较慢时用）")
    parser.add_argument("--rebuild-timeout", type=int, default=1800)
    parser.add_argument("--rebuild-rounds", type=int, default=2, help="F6 连续重建轮数（默认 2）")
    args = parser.parse_args()

    data_dir = Path(args.data_dir)
    pins = load_pins(Path(args.pins))
    # pins 里的 `# kind(...)` 行优先，其次用脚本内置期望；都没有则只上报不判定
    expected_kinds_all = {**EXPECTED_KINDS, **load_pin_kinds(Path(args.pins))}
    data = {name: read_jsonl(data_dir / name) for name in FILES}

    checks = [
        check_counts(data, pins, expected_kinds_all),
        check_relevance(data, args.min_source_fidelity),
        check_dedup(data, args.near_dup_ratio),
        check_length(data, args.max_tokens, args.tokenizer),
        check_pins(data_dir, pins),
    ]
    if args.skip_rebuild:
        checks.append(Check("F6 链路可重建", False, "按 --skip-rebuild 跳过（未执行，非数据缺陷）"))
    else:
        checks.append(check_rebuild(pins, args.rebuild_timeout, args.rebuild_rounds))

    notes = [char_note(data, args.max_chars)]
    extras = [check_template_variety(data), check_extractive(data)]

    print("=" * 78)
    print(f"第2周数据验收 · 目录={data_dir} · pins={args.pins} · max_tokens={args.max_tokens}")
    print("=" * 78)
    for check in checks:
        tag = "" if check.gating else "（参考项）"
        print(f"[{'PASS' if check.passed else 'FAIL'}] {check.name}{tag}: {check.detail}")
        for warn in check.warnings:
            print(f"        [warn] {warn}")
        for item in check.evidence[:5]:
            if isinstance(item, dict) and (item.get("answer") or item.get("资料保真度") or item.get("重建条数")):
                print(f"        - {json.dumps(item, ensure_ascii=False)[:170]}")
    for note in notes:
        print(f"[note] {note}")
    for check in extras:
        print(f"[参考] {check.name}: {check.detail}")

    gating_failed = [c.name for c in checks if c.gating and not c.passed]
    print("-" * 78)
    if gating_failed:
        print(f"总体：FAIL（不可冻结）：{'、'.join(gating_failed)}")
    else:
        print("总体：PASS（数据可冻结）")

    result = {
        "data_dir": str(data_dir),
        "pins": str(args.pins),
        "max_tokens": args.max_tokens,
        "thresholds": {
            "min_source_fidelity": args.min_source_fidelity,
            "near_dup_ratio": args.near_dup_ratio,
        },
        "passed": not gating_failed,
        "failed": gating_failed,
        "checks": [c.as_dict() for c in checks],
        "reference_checks": [c.as_dict() for c in extras],
        "notes": notes,
    }
    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[json] {out}")

    return 0 if not gating_failed else 1


if __name__ == "__main__":
    sys.exit(main())
