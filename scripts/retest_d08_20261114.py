# -*- coding: utf-8 -*-
"""D08 —— 50 条 planner「工具级」报告固定条件重跑（2026-11-14，T-C1）。

用途
----
对 50 条题面逐条调用 ``POST /v1/agent/run``（AI 服务 8000，非流式），按
``tool_call`` / ``tool_result`` 事件配对，产出新的工具级报告 JSON。

事件顺序（``ai-service/app/routers/agent.py`` / ``app/agent/events.py``）
------------------------------------------------------------------------
``meta → thought → tool_call → tool_result → … → final → done``

* ``meta.data.promptVersion``  → 固定条件「模型版本串」，逐行取证并校验 50 行一致；
* ``tool_call.data``           → ``{step, tool, arguments}``（对应报告里的 ``args``）；
* ``tool_result.data``         → ``{step, tool, ok, summary, elapsedMs, data}``；
* ``final.data``               → ``{answer, generator, degraded, refused, step_count,
                                   tool_calls, sources}``；
* ``thought`` 文本里的「大模型规划不可用，已切换规则规划器」→ 统计 LLM 规划回退次数。

四个固定条件字段（写进输出 JSON 顶层）
--------------------------------------
``corpusVersion``（只读 SELECT 现查的 COURSEWARE 块数）、``model``、
``promptVersion``（来自运行态 ``meta.promptVersion``）、``timestamp``。

配对口径
--------
``call_total`` = 全部 ``tool_call`` 事件数；``matched`` = 成功按 ``(step, tool)``
配对的次数；判据要求 **``matched == call_total``**。未配对的 call / result 如实
列入 ``unmatchedCalls`` / ``unmatchedResults``，不丢弃、不补齐。

字段口径（每条工具）
--------------------
``step / tool / args / ok / elapsedMs / summary / hits``
* ``ok``：直接取 ``tool_result.data.ok``，**``ok=false`` 如实保留**，不改成 true、
  不删除；非 bool 值时原样保留并标记 ``okMissing``。
* ``hits``：``tool_result.data.data`` 为 list 时取其长度，否则为 0。
  这与 v3 报告一致（``SEARCH_KEEP=3`` 会把检索结果裁到最多 3 条，故 summary
  写「命中 5 条」时 ``hits`` 仍可能是 3；``get_question`` 返回 dict 故为 0）。
  额外记录 ``recallCountFromSummary``（从 summary 解析「命中 N 条」）便于区分。

题面来源（重要设计决策）
------------------------
默认取 ``training/data/eval_planner.jsonl`` 的 ``messages[role=user].content``
（50 条**完整题面**）。原因：
``runtime/d08_planner_full_50.json`` 的 ``rows[].question`` 是 ``stem[:80]`` 的
**展示用截断串**（已只读核对：50/50 均为完整题面的前缀），直接拿它当请求体
会改变原始 50 条 prompt，重跑就不可比。故 v3 文件在本脚本里只作
**格式参照 + 顺序对齐 + 旧基线对照**，不作为新基线、不作为默认题面源。
如需强制使用 v3 的截断串，可显式 ``--questions runtime/d08_planner_full_50.json``
（输出会标记 ``questionsTruncated=True`` 并告警）。

安全约束
--------
* 不硬编码任何口令/令牌：``AI_INTERNAL_TOKEN`` / ``DB_PASSWORD`` 从环境变量或
  ``ai-service/.env`` 读取；控制台与输出都不回显明文。
* 不写库（唯一 SQL 是 ``SELECT COUNT(*)``）；不改任何既有 runtime JSON；
  不改 AI 服务源码；不启停服务；不提交、不推送。
* 输出文件默认为**新建**的 ``runtime/d08_retest_20261114.json``。

前置（主 agent 本机动作，见 C1 重测方案）
------------------------------------------
8080（带同值 ``AI_INTERNAL_TOKEN`` + ``OLLAMA_MODEL=exam-prep-7b``）与 8000 均已起；
``/api/actuator/health`` 200、``/docs`` 200；用 ``.venv-ai/Scripts/python.exe`` 运行。

用法::

    .venv-ai/Scripts/python.exe scripts/retest_d08_20261114.py
    .venv-ai/Scripts/python.exe scripts/retest_d08_20261114.py --out runtime/xxx.json
    .venv-ai/Scripts/python.exe scripts/retest_d08_20261114.py --skip-preflight

退出码：0 = 结构完整且 ``matched == call_total``；2 = 前置/结构/配对失败；
3 = 运行异常。**退出码不代表质量达标**（ok_false 是否 ≤2 由 qa-acceptance 裁定）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# 路径与常量
# ---------------------------------------------------------------------------

PROJECT_ROOT: Path = Path(__file__).resolve().parents[1]
ENV_FILE: Path = PROJECT_ROOT / "ai-service" / ".env"
DEFAULT_QUESTIONS: Path = PROJECT_ROOT / "training" / "data" / "eval_planner.jsonl"
V3_REFERENCE: Path = PROJECT_ROOT / "runtime" / "d08_planner_full_50.json"
DEFAULT_OUT: Path = PROJECT_ROOT / "runtime" / "d08_retest_20261114.json"
PLANNER_FILE: Path = PROJECT_ROOT / "ai-service" / "app" / "agent" / "planner.py"

PROTECTED_FILES: Tuple[str, ...] = (
    "d07_refusal_10.json",
    "d07_refusal_baseline_20261113.json",
    "d08_planner_50.json",
    "d08_planner_tools_50.json",
    "d08_planner_full_50.json",
)

CORPUS_SQL: str = "SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE'"
DEFAULT_JAVA_BASE: str = "http://127.0.0.1:8080/api"
DEFAULT_AI_BASE: str = "http://127.0.0.1:8000"
EXPECTED_PROMPT_VERSION: str = "agent-planner-v4"
EXPECTED_TOTAL: int = 50

#: 修复记录 §6 要求 P0 复测的 4 个题号（1-based，对应 rows[].i）。
FOCUS_CASE_NUMBERS: Tuple[int, ...] = (7, 11, 21, 34)
FOCUS_EXPECTATIONS: Dict[str, str] = {
    "7": "无空参 get_question；若仍取详情，questionId 必须来自检索/错题本返回结果，不得编造 123456",
    "11": "get_question 每次调用均带 questionId，不再出现 step2 空参；同时观察 degraded",
    "21": "search_knowledge 带非空 query，ok=true；不再出现 args={}",
    "34": "不再误选 get_wiki_page 传 query；summary 不再出现 pageId 必须是正整数",
}

_NO_PROXY_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------------------
# 配置读取（环境变量优先，其次 .env；绝不回显）
# ---------------------------------------------------------------------------


def _from_env_file(key: str) -> str:
    """从 ``ai-service/.env`` 读取单个键（最小解析，不打印值）。"""
    if not ENV_FILE.exists():
        return ""
    try:
        for raw_line in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):].lstrip()
            name, value = line.split("=", 1)
            if name.strip() == key:
                return value.strip().strip('"').strip("'")
    except OSError:
        return ""
    return ""


def read_secret(key: str, default: str = "") -> str:
    """读配置：进程环境变量优先，其次 ``.env``。"""
    return os.environ.get(key) or _from_env_file(key) or default


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def http_json(
    method: str,
    url: str,
    body: Optional[Dict[str, Any]],
    headers: Optional[Dict[str, str]],
    timeout: float,
) -> Tuple[int, Optional[Dict[str, Any]], str, int, Optional[str]]:
    """发起 HTTP 请求并解析 JSON；返回 ``(status, parsed, raw_head, ms, error)``。"""
    req_headers: Dict[str, str] = {"Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    data: Optional[bytes] = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=req_headers, method=method)

    started = time.perf_counter()
    status: int = -1
    raw: str = ""
    error: Optional[str] = None
    try:
        with _NO_PROXY_OPENER.open(request, timeout=timeout) as response:
            status = response.getcode()
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        try:
            raw = exc.read().decode("utf-8", "replace")
        except OSError:
            raw = ""
    except Exception as exc:  # noqa: BLE001
        error = f"{type(exc).__name__}: {str(exc)[:160]}"
    elapsed_ms = int((time.perf_counter() - started) * 1000)

    parsed: Optional[Dict[str, Any]] = None
    if raw:
        try:
            candidate = json.loads(raw)
            if isinstance(candidate, dict):
                parsed = candidate
        except (ValueError, TypeError):
            parsed = None
    return status, parsed, raw[:300], elapsed_ms, error


# ---------------------------------------------------------------------------
# 固定条件 1：语料版本（只读）
# ---------------------------------------------------------------------------


def _db_params() -> Dict[str, str]:
    """组装 MySQL 连接参数（口令不落盘、不回显）。"""
    return {
        "host": read_secret("DB_HOST", "127.0.0.1"),
        "port": read_secret("DB_PORT", "3306"),
        "user": read_secret("DB_USER") or read_secret("DB_USERNAME", "root"),
        "password": read_secret("DB_PASSWORD"),
        "database": read_secret("DB_NAME", "exam_prep"),
    }


def _corpus_via_pymysql(params: Dict[str, str]) -> Tuple[Optional[int], Optional[str]]:
    """pymysql 执行唯一只读 SELECT；返回 ``(count, error)``。"""
    try:
        import pymysql  # type: ignore[import-not-found]
    except ImportError as exc:
        return None, f"pymysql 不可用：{exc}"
    conn = None
    try:
        conn = pymysql.connect(
            host=params["host"],
            port=int(params["port"]),
            user=params["user"],
            password=params["password"],
            database=params["database"],
            charset="utf8mb4",
            autocommit=True,
            connect_timeout=10,
            read_timeout=15,
        )
        with conn.cursor() as cursor:
            cursor.execute(CORPUS_SQL)  # 硬编码常量，无外部拼接，只读
            row = cursor.fetchone()
        return int(row[0]), None
    except Exception as exc:  # noqa: BLE001
        return None, f"{type(exc).__name__}: {str(exc)[:180]}"
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass


def _corpus_via_cli(params: Dict[str, str]) -> Tuple[Optional[int], Optional[str]]:
    """pymysql 缺失时用 mysql CLI 兜底；口令经 ``MYSQL_PWD`` 传递，不进 argv。"""
    binary = read_secret("DB_BIN", r"D:\bigplan\tools\mysql-8.0.46\bin\mysql.exe")
    env = os.environ.copy()
    env["MYSQL_PWD"] = params["password"]
    command = [
        binary, "-h", params["host"], "-P", params["port"], "-u", params["user"],
        "--batch", "--skip-column-names", "--default-character-set=utf8mb4",
        "-e", CORPUS_SQL, params["database"],
    ]
    try:
        completed = subprocess.run(
            command, env=env, capture_output=True, text=True, timeout=30, check=False
        )
    except Exception as exc:  # noqa: BLE001
        return None, f"mysql CLI 调用失败：{type(exc).__name__}: {str(exc)[:160]}"
    if completed.returncode != 0:
        return None, f"mysql CLI exit={completed.returncode}: {(completed.stderr or '')[:160]}"
    try:
        return int((completed.stdout or "").strip().splitlines()[0].strip()), None
    except (ValueError, IndexError) as exc:
        return None, f"mysql CLI 输出无法解析：{exc}"


def query_corpus_version() -> Dict[str, Any]:
    """现查 COURSEWARE 块数；失败时 ``corpusVersion=None`` 并如实记 error。"""
    params = _db_params()
    count, error = _corpus_via_pymysql(params)
    source = "pymysql"
    if count is None:
        count, cli_error = _corpus_via_cli(params)
        source = "mysql-cli"
        if count is None:
            error = f"{error} | {cli_error}"
    return {
        "corpusVersion": count,
        "corpusVersionQuery": CORPUS_SQL,
        "corpusVersionSource": source,
        "corpusVersionError": error if count is None else None,
    }


# ---------------------------------------------------------------------------
# 固定条件 2：模型名
# ---------------------------------------------------------------------------


def resolve_model(cli_model: str = "") -> Dict[str, Any]:
    """解析模型名：``--model`` > ``OLLAMA_MODEL``（后端启动参数）> ``.env`` 的 LLM_MODEL。"""
    ollama = os.environ.get("OLLAMA_MODEL") or os.environ.get("RETEST_MODEL") or ""
    llm_file = read_secret("LLM_MODEL")
    if cli_model:
        model, source = cli_model, "cli:--model"
    elif ollama:
        model, source = ollama, "env:OLLAMA_MODEL"
    else:
        model, source = llm_file, "dotenv:LLM_MODEL"
    consistent: Optional[bool] = (ollama == llm_file) if (ollama and llm_file) else None
    return {
        "model": model,
        "modelSource": source,
        "ollamaModelFromEnv": ollama or None,
        "llmModelFromFile": llm_file or None,
        "modelConsistent": consistent,
    }


def static_prompt_version() -> Optional[str]:
    """静态解析 ``planner.py`` 的 ``PROMPT_VERSION``（只读兜底，标注非运行态）。"""
    try:
        text = PLANNER_FILE.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None
    match = re.search(r"^PROMPT_VERSION\s*=\s*[\"']([^\"']+)[\"']", text, re.MULTILINE)
    return match.group(1) if match else None


def probe_prompt_version(ai_base: str, token: str, timeout: float) -> Dict[str, Any]:
    """所有 50 行都无 meta 事件时，用一次 maxSteps=1 探针读运行态 promptVersion。"""
    status, parsed, raw_head, elapsed_ms, error = http_json(
        "POST", ai_base + "/v1/agent/run",
        {"question": "条件探针", "maxSteps": 1},
        {"X-Internal-Token": token}, timeout,
    )
    meta_value: Optional[str] = None
    if status == 200 and isinstance(parsed, dict):
        for item in parsed.get("events") or []:
            if isinstance(item, dict) and item.get("event") == "meta":
                data = item.get("data")
                if isinstance(data, dict):
                    meta_value = data.get("promptVersion")
                break
    if meta_value is not None:
        return {
            "promptVersion": meta_value,
            "promptVersionSource": "meta.promptVersion @ POST /v1/agent/run (probe maxSteps=1)",
            "promptVersionRuntimeVerified": True,
            "probeHttp": status,
            "probeError": None,
        }
    return {
        "promptVersion": static_prompt_version(),
        "promptVersionSource": (
            "static:ai-service/app/agent/planner.py PROMPT_VERSION "
            "(runtime probe failed; NOT runtime-verified)"
        ),
        "promptVersionRuntimeVerified": False,
        "probeHttp": status,
        "probeError": error or (raw_head[:160] if status != 200 else "meta 事件缺失"),
    }


# ---------------------------------------------------------------------------
# 健康检查前置
# ---------------------------------------------------------------------------


def preflight(java_base: str, ai_base: str, timeout: float) -> Dict[str, Any]:
    """检查 8080 ``/api/actuator/health`` 与 8000 ``/docs``。"""
    checks: Dict[str, Any] = {}
    for name, url in (
        ("backendHealth", java_base + "/actuator/health"),
        ("aiDocs", ai_base + "/docs"),
    ):
        status, _, _, elapsed_ms, error = http_json("GET", url, None, {}, timeout)
        checks[name] = {
            "url": url, "http": status, "ok": status == 200,
            "elapsedMs": elapsed_ms, "error": error,
        }
    return {"ok": all(item["ok"] for item in checks.values()), "checks": checks}


# ---------------------------------------------------------------------------
# 题面读取与 v3 对齐
# ---------------------------------------------------------------------------


def load_questions(path: Path) -> Dict[str, Any]:
    """读取 50 条题面。

    支持两种格式：
    * ``.jsonl``：``training/data/eval_planner.jsonl``，取
      ``messages`` 中 ``role=user`` 的 ``content``（**完整题面**）；
    * ``.json``：``runtime/d08_planner_full_50.json``，取 ``rows[].question``
      （**80 字截断展示串**，会标记 ``questionsTruncated=True``）。

    Args:
        path: 题面文件。

    Returns:
        ``{questions, source, sha256, truncated}``。

    Raises:
        SystemExit: 文件缺失 / 结构非法 / 题面为空。
    """
    if not path.exists():
        raise SystemExit(f"题面文件不存在：{path}")
    try:
        raw_bytes = path.read_bytes()
        text = raw_bytes.decode("utf-8")
    except OSError as exc:
        raise SystemExit(f"题面文件读取失败：{path} ({exc})") from exc

    questions: List[str] = []
    truncated = False
    if path.suffix.lower() == ".jsonl":
        for line_no, line in enumerate(text.splitlines(), start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except ValueError as exc:
                raise SystemExit(f"题面 .jsonl 第 {line_no} 行解析失败：{exc}") from exc
            messages = record.get("messages") or []
            stem = ""
            for message in messages:
                if isinstance(message, dict) and message.get("role") == "user":
                    stem = str(message.get("content") or "")
                    break
            if not stem.strip():
                raise SystemExit(f"题面 .jsonl 第 {line_no} 行缺少 user content")
            questions.append(stem.strip())
    else:
        try:
            payload = json.loads(text)
        except ValueError as exc:
            raise SystemExit(f"题面 JSON 解析失败：{path} ({exc})") from exc
        for row in payload.get("rows") or []:
            stem = str(row.get("question") or "").strip()
            if not stem:
                raise SystemExit("题面 JSON 存在空 question，拒绝继续")
            questions.append(stem)
        truncated = True

    if not questions:
        raise SystemExit("题面为空，拒绝继续")
    return {
        "questions": questions,
        "source": str(path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
        "sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "truncated": truncated,
    }


def load_v3_reference(path: Path) -> Dict[str, Any]:
    """只读解析 v3：仅作格式参照、顺序对齐与旧基线对照。"""
    if not path.exists():
        return {"available": False, "note": "v3 参照文件不存在"}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return {"available": False, "note": f"v3 参照文件无法解析：{exc}"}
    rows = payload.get("rows") or []
    prefixes: List[str] = [str(row.get("question") or "") for row in rows]
    return {
        "available": True,
        "file": str(path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
        "total": int(payload.get("total") or len(rows)),
        "call_total": int(payload.get("call_total") or 0),
        "matched": int(payload.get("matched") or 0),
        "ok_true": int(payload.get("ok_true") or 0),
        "ok_false": int(payload.get("ok_false") or 0),
        "elapsed_s": payload.get("elapsed_s"),
        "tool_dist": payload.get("tool_dist") or {},
        "hasPromptVersion": "promptVersion" in payload,
        "hasCorpusVersion": any(k in payload for k in ("corpusVersion", "语料版本")),
        "hasModel": any(k in payload for k in ("model", "modelName", "模型名")),
        "prefixes": prefixes,
        "note": (
            "v3 生成于旧语料 65,511 块、promptVersion=v3、qwen2.5-3b 期，"
            "仅作格式参照与历史对照，禁止当作新基线"
        ),
    }


def align_with_v3(questions: List[str], v3: Dict[str, Any]) -> Dict[str, Any]:
    """核对题面顺序与 v3 展示串是否逐条对齐（v3.question 应为 stem[:80] 前缀）。"""
    prefixes: List[str] = v3.get("prefixes") or []
    if not v3.get("available") or not prefixes:
        return {"checked": False, "reason": "v3 参照不可用"}
    mismatches: List[Dict[str, Any]] = []
    for index, (stem, prefix) in enumerate(zip(questions, prefixes), start=1):
        if not prefix or not stem.startswith(prefix[:min(len(prefix), len(stem))]):
            mismatches.append({"i": index, "stemHead": stem[:30], "v3Head": prefix[:30]})
    return {
        "checked": True,
        "v3Rows": len(prefixes),
        "questionRows": len(questions),
        "prefixMatched": len(questions) - len(mismatches),
        "mismatches": mismatches,
        "note": "v3.rows[].question 是完整题面的前 80 字展示串；对齐只用于确认题面顺序一致",
    }


# ---------------------------------------------------------------------------
# 事件解析与配对
# ---------------------------------------------------------------------------

_RECALL_RE = re.compile(r"命中\s*(\d+)\s*条")
_FALLBACK_TEXT = "大模型规划不可用"


def _hits_from_result(result_data: Dict[str, Any]) -> Tuple[int, Optional[int], str]:
    """按 v3 口径推导 ``hits``。

    Args:
        result_data: ``tool_result`` 事件的 ``data`` 字典。

    Returns:
        ``(hits, recall_from_summary, data_type)``；``hits`` = data.data 为 list
        时的长度，其余为 0；``recall_from_summary`` 为 summary 里「命中 N 条」的 N。
    """
    inner = result_data.get("data")
    data_type = type(inner).__name__
    hits = len(inner) if isinstance(inner, (list, tuple)) else 0
    summary = str(result_data.get("summary") or "")
    match = _RECALL_RE.search(summary)
    recall = int(match.group(1)) if match else None
    return hits, recall, data_type


def parse_events(events: List[Dict[str, Any]]) -> Dict[str, Any]:
    """解析一条 ``/v1/agent/run`` 响应的事件数组并配对 ``tool_call``/``tool_result``。

    Args:
        events: ``AgentRunResponse.events``（``[{event, data}, ...]``）。

    Returns:
        含 ``meta / final / done / tools / calls / results / unmatchedCalls /
        unmatchedResults / llmFallback / eventNames`` 的字典。
    """
    meta: Dict[str, Any] = {}
    final: Dict[str, Any] = {}
    done: Dict[str, Any] = {}
    calls: List[Dict[str, Any]] = []
    results_by_key: Dict[Tuple[Any, Any], List[Dict[str, Any]]] = defaultdict(list)
    results: List[Dict[str, Any]] = []
    llm_fallback = 0
    event_names: List[str] = []
    thought_count = 0

    for item in events:
        if not isinstance(item, dict):
            continue
        name = str(item.get("event") or "")
        data = item.get("data")
        if not isinstance(data, dict):
            data = {}
        event_names.append(name)
        if name == "meta":
            meta = data
        elif name == "final":
            final = data
        elif name == "done":
            done = data
        elif name == "thought":
            thought_count += 1
            if _FALLBACK_TEXT in str(data.get("text") or ""):
                llm_fallback += 1
        elif name == "tool_call":
            calls.append({
                "step": data.get("step"),
                "tool": data.get("tool"),
                "args": data.get("arguments"),
            })
        elif name == "tool_result":
            entry = {
                "step": data.get("step"),
                "tool": data.get("tool"),
                "ok": data.get("ok"),
                "summary": data.get("summary"),
                "elapsedMs": data.get("elapsedMs"),
                "data": data.get("data"),
            }
            results.append(entry)
            results_by_key[(data.get("step"), data.get("tool"))].append(entry)

    tools: List[Dict[str, Any]] = []
    unmatched_calls: List[Dict[str, Any]] = []
    for call in calls:
        key = (call["step"], call["tool"])
        bucket = results_by_key.get(key)
        if bucket:
            result = bucket.pop(0)
            hits, recall, data_type = _hits_from_result(result)
            ok_raw = result.get("ok")
            tools.append({
                "step": call["step"],
                "tool": call["tool"],
                "args": call["args"] if call["args"] is not None else {},
                "ok": ok_raw,                      # ok=false 如实保留，绝不改写
                "okMissing": not isinstance(ok_raw, bool),
                "elapsedMs": result.get("elapsedMs"),
                "summary": result.get("summary"),
                "hits": hits,
                "hitsSource": "len(tool_result.data.data) if list else 0",
                "dataType": data_type,
                "recallCountFromSummary": recall,
                "paired": True,
            })
        else:
            unmatched_calls.append({
                "step": call["step"], "tool": call["tool"],
                "args": call["args"] if call["args"] is not None else {},
                "paired": False,
            })
    unmatched_results: List[Dict[str, Any]] = []
    for bucket in results_by_key.values():
        for leftover in bucket:
            unmatched_results.append({
                "step": leftover["step"], "tool": leftover["tool"],
                "ok": leftover["ok"], "summary": leftover["summary"],
            })

    return {
        "meta": meta,
        "final": final,
        "done": done,
        "tools": tools,
        "calls": len(calls),
        "results": len(results),
        "matched": len(tools),
        "unmatchedCalls": unmatched_calls,
        "unmatchedResults": unmatched_results,
        "llmFallback": llm_fallback,
        "thoughtCount": thought_count,
        "eventNames": event_names,
    }


def run_one(
    ai_base: str, token: str, index: int, question: str, user_id: int, timeout: float
) -> Dict[str, Any]:
    """对一条题面调用 ``/v1/agent/run`` 并产出结构化行。"""
    status, parsed, raw_head, elapsed_ms, error = http_json(
        "POST",
        ai_base + "/v1/agent/run",
        {"question": question, "userId": user_id},
        {"X-Internal-Token": token},
        timeout,
    )
    events: List[Dict[str, Any]] = []
    if isinstance(parsed, dict):
        candidate = parsed.get("events")
        if isinstance(candidate, list):
            events = [item for item in candidate if isinstance(item, dict)]
    info = parse_events(events)
    final = info["final"]
    return {
        "i": index,
        "http": status,
        "ms": elapsed_ms,
        "question": question,
        "steps": final.get("step_count", parsed.get("step_count") if parsed else 0),
        "tool_calls": info["calls"],
        "finalToolCalls": final.get("tool_calls"),
        "tools": info["tools"],
        "refused": bool(final.get("refused", False)),
        "degraded": bool(final.get("degraded", False)),
        "answer": str(final.get("answer") or (parsed or {}).get("answer") or ""),
        "generator": final.get("generator"),
        "sources": len(final.get("sources") or []) if isinstance(final.get("sources"), list) else 0,
        "promptVersion": info["meta"].get("promptVersion"),
        "metaTraceId": info["meta"].get("traceId"),
        "eventNames": info["eventNames"],
        "llmFallback": info["llmFallback"],
        "thoughtCount": info["thoughtCount"],
        "doneTotalMs": info["done"].get("totalMs"),
        "matched": info["matched"],
        "resultEvents": info["results"],
        "unmatchedCalls": info["unmatchedCalls"],
        "unmatchedResults": info["unmatchedResults"],
        "error": error,
        "rawHead": raw_head if status != 200 else None,
    }


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------


def write_output(path: Path, payload: Dict[str, Any]) -> None:
    """写 UTF-8 JSON；拒绝覆盖受保护的既有 runtime 文件。"""
    if path.name in PROTECTED_FILES:
        raise SystemExit(f"拒绝覆盖受保护的既有 runtime JSON：{path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="D08 50 条 planner 工具级报告重跑")
    parser.add_argument("--java-base", default=os.environ.get("RETEST_JAVA_BASE", DEFAULT_JAVA_BASE))
    parser.add_argument("--ai-base", default=os.environ.get("RETEST_AI_BASE", DEFAULT_AI_BASE))
    parser.add_argument("--questions", default=str(DEFAULT_QUESTIONS))
    parser.add_argument("--v3-reference", default=str(V3_REFERENCE))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--user-id", type=int, default=int(os.environ.get("RETEST_USER_ID", "3")))
    parser.add_argument("--timeout", type=float, default=180.0, help="单条 /v1/agent/run 超时秒数")
    parser.add_argument("--preflight-timeout", type=float, default=10.0)
    parser.add_argument("--probe-timeout", type=float, default=120.0)
    parser.add_argument("--model", default="", help="显式覆盖模型名")
    parser.add_argument("--skip-preflight", action="store_true")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    """主流程：前置 → 采条件 → 读题面 → 50 条 → 配对汇总 → 落盘。"""
    args = parse_args(argv)
    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = PROJECT_ROOT / out_path

    started_dt = datetime.now().astimezone()
    started_perf = time.perf_counter()
    print("[D08] 开始：采集固定条件 + 读取 50 条题面（不回显任何令牌/口令）")

    # 1) 健康检查前置
    preflight_result: Dict[str, Any] = {"skipped": True, "ok": True, "checks": {}}
    if not args.skip_preflight:
        preflight_result = preflight(args.java_base, args.ai_base, args.preflight_timeout)
        print(
            f"[D08] 前置 health={preflight_result['checks']['backendHealth']['http']} "
            f"docs={preflight_result['checks']['aiDocs']['http']}"
        )
        if not preflight_result["ok"]:
            print("[D08] 健康检查未通过，中止（需 8080 /api/actuator/health 与 8000 /docs 均 200）")
            return 2

    # 2) 固定条件（语料版本 + 模型名；promptVersion 稍后从 50 行 meta 取）
    corpus = query_corpus_version()
    model_info = resolve_model(args.model)
    token = read_secret("AI_INTERNAL_TOKEN")
    if not token:
        print("[D08] 缺少 AI_INTERNAL_TOKEN（环境变量或 ai-service/.env），中止")
        return 2
    print(
        f"[D08] 条件 corpus={corpus['corpusVersion']} model={model_info['model']} "
        f"（internalTokenConfigured=True，不回显）"
    )

    # 3) 题面 + v3 对齐
    question_set = load_questions(Path(args.questions))
    v3 = load_v3_reference(Path(args.v3_reference))
    alignment = align_with_v3(question_set["questions"], v3)
    print(
        f"[D08] 题面 {len(question_set['questions'])} 条，来源 {question_set['source']} "
        f"truncated={question_set['truncated']} 对齐 {alignment.get('prefixMatched', 'n/a')}"
    )
    if question_set["truncated"]:
        print("[D08] 警告：题面源为 v3 的 80 字截断展示串，与原始 50 条 prompt 不等价！")
    if len(question_set["questions"]) != EXPECTED_TOTAL:
        print(f"[D08] 警告：题面数 {len(question_set['questions'])} != {EXPECTED_TOTAL}，本次不是全量重跑")

    # 4) 逐条真实请求
    rows: List[Dict[str, Any]] = []
    for index, question in enumerate(question_set["questions"], start=1):
        row = run_one(args.ai_base, token, index, question, args.user_id, args.timeout)
        rows.append(row)
        false_count = sum(1 for tool in row["tools"] if tool.get("ok") is False)
        print(
            f"[D08] {index:>2}/{len(question_set['questions'])} http={row['http']} "
            f"calls={row['tool_calls']} matched={row['matched']} ok_false={false_count} "
            f"pv={row['promptVersion']} ms={row['ms']}"
        )

    # 5) promptVersion：优先取 50 行 meta 的运行态值
    row_versions = [row.get("promptVersion") for row in rows]
    observed = sorted({str(v) for v in row_versions if v is not None})
    if len(observed) == 1:
        prompt_info = {
            "promptVersion": observed[0],
            "promptVersionSource": "meta.promptVersion @ 50 x POST /v1/agent/run",
            "promptVersionRuntimeVerified": True,
            "probeHttp": None,
            "probeError": None,
        }
    elif not observed:
        prompt_info = probe_prompt_version(args.ai_base, token, args.probe_timeout)
    else:
        prompt_info = {
            "promptVersion": "MIXED",
            "promptVersionSource": "meta.promptVersion 在 50 行中不一致",
            "promptVersionRuntimeVerified": True,
            "probeHttp": None,
            "probeError": f"观察到多个版本：{observed}",
        }
    version_consistent = (
        len(observed) == 1
        and all(v is not None for v in row_versions)
        and observed[0] == EXPECTED_PROMPT_VERSION
    )

    conditions = {
        "corpusVersion": corpus["corpusVersion"],
        "model": model_info["model"],
        "promptVersion": prompt_info["promptVersion"],
        "timestamp": started_dt.isoformat(timespec="seconds"),
    }
    condition_detail = {
        "corpusVersionQuery": corpus["corpusVersionQuery"],
        "corpusVersionSource": corpus["corpusVersionSource"],
        "corpusVersionError": corpus["corpusVersionError"],
        "modelSource": model_info["modelSource"],
        "ollamaModelFromEnv": model_info["ollamaModelFromEnv"],
        "llmModelFromFile": model_info["llmModelFromFile"],
        "modelConsistent": model_info["modelConsistent"],
        "promptVersionSource": prompt_info["promptVersionSource"],
        "promptVersionRuntimeVerified": prompt_info["promptVersionRuntimeVerified"],
        "promptVersionExpected": EXPECTED_PROMPT_VERSION,
        "promptVersionMatchesV4": version_consistent,
        "promptVersionByRow": row_versions,
        "promptVersionObserved": observed,
        "probeHttp": prompt_info["probeHttp"],
        "probeError": prompt_info["probeError"],
        "internalTokenConfigured": True,
        "internalTokenSource": "env/dotenv:AI_INTERNAL_TOKEN（仅记录是否已注入，不回显任何值）",
        "timestampUtc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "userId": args.user_id,
    }

    # 6) 汇总
    call_total = sum(row["tool_calls"] for row in rows)
    matched = sum(row["matched"] for row in rows)
    all_tools = [tool for row in rows for tool in row["tools"]]
    ok_true = sum(1 for tool in all_tools if tool.get("ok") is True)
    ok_false = sum(1 for tool in all_tools if tool.get("ok") is False)
    ok_missing = sum(1 for tool in all_tools if not isinstance(tool.get("ok"), bool))
    tool_dist: Dict[str, int] = defaultdict(int)
    for tool in all_tools:
        tool_dist[str(tool.get("tool"))] += 1
    unmatched_calls = [
        {"i": row["i"], **item} for row in rows for item in row["unmatchedCalls"]
    ]
    unmatched_results = [
        {"i": row["i"], **item} for row in rows for item in row["unmatchedResults"]
    ]
    llm_fallback_total = sum(int(row["llmFallback"]) for row in rows)
    http_ok = sum(1 for row in rows if row["http"] == 200)
    total = len(rows)

    ok_false_details = [
        {
            "i": row["i"], "step": tool.get("step"), "tool": tool.get("tool"),
            "args": tool.get("args"), "summary": tool.get("summary"),
        }
        for row in rows for tool in row["tools"] if tool.get("ok") is False
    ]
    focus_cases: Dict[str, Any] = {}
    for number in FOCUS_CASE_NUMBERS:
        row = next((item for item in rows if item["i"] == number), None)
        focus_cases[str(number)] = {
            "expected": FOCUS_EXPECTATIONS[str(number)],
            "actual": None if row is None else {
                "http": row["http"],
                "steps": row["steps"],
                "tool_calls": row["tool_calls"],
                "ok_false": sum(1 for t in row["tools"] if t.get("ok") is False),
                "tools": [
                    {"step": t.get("step"), "tool": t.get("tool"), "args": t.get("args"),
                     "ok": t.get("ok"), "summary": t.get("summary")}
                    for t in row["tools"]
                ],
                "degraded": row["degraded"],
            },
        }

    finished_dt = datetime.now().astimezone()
    payload: Dict[str, Any] = {
        "schema": "d08-retest-20261114-v1",
        "task": "D08 50 条 planner 工具级报告固定条件重跑",
        "generatedAt": finished_dt.isoformat(timespec="seconds"),
        # ===== 四个固定条件字段（顶层）=====
        "corpusVersion": conditions["corpusVersion"],
        "model": conditions["model"],
        "promptVersion": conditions["promptVersion"],
        "timestamp": conditions["timestamp"],
        # ===== 同值别名块：兼容不同验收口径（嵌套 meta.promptVersion / 中文键）=====
        "meta": {
            "promptVersion": conditions["promptVersion"],
            "source": "meta 事件 @ 50 x POST /v1/agent/run（逐行取证后聚合）",
        },
        "fixedConditions": {
            "语料版本": conditions["corpusVersion"],
            "模型名": conditions["model"],
            "meta.promptVersion": conditions["promptVersion"],
            "时间戳": conditions["timestamp"],
            "corpusVersion": conditions["corpusVersion"],
            "model": conditions["model"],
            "promptVersion": conditions["promptVersion"],
            "timestamp": conditions["timestamp"],
        },
        "conditionDetail": condition_detail,
        "preflight": preflight_result,
        "endpoint": {
            "run": args.ai_base + "/v1/agent/run",
            "method": "POST",
            "auth": "X-Internal-Token: <AI_INTERNAL_TOKEN>（不回显）",
            "eventOrder": "meta → thought → tool_call → tool_result → … → final → done",
        },
        "questions": {
            "source": question_set["source"],
            "sha256": question_set["sha256"],
            "count": total,
            "truncated": question_set["truncated"],
            "alignmentWithV3": alignment,
        },
        # ===== 配对判据 =====
        "total": total,
        "httpOk": http_ok,
        "call_total": call_total,
        "matched": matched,
        "matchedEqualsCallTotal": matched == call_total,
        "resultEvents": sum(row["resultEvents"] for row in rows),
        "unpairedCalls": unmatched_calls,
        "unpairedResults": unmatched_results,
        "ok_true": ok_true,
        "ok_false": ok_false,
        "okMissing": ok_missing,
        "okFalseDetails": ok_false_details,
        "tool_dist": dict(tool_dist),
        "llmPlannerFallback": llm_fallback_total,
        "llmPlannerFallbackBaseline": 12,
        "llmPlannerFallbackNote": (
            "修复记录 §4 成本核算：v4 提示词 +497 字符，旧基线 12 次 LLM 规划回退；"
            "若本次 >12 应首先怀疑 4096 token 窗口被挤占"
        ),
        "focusCaseNumbers": list(FOCUS_CASE_NUMBERS),
        "focusCases": focus_cases,
        "v3Baseline": {
            key: value for key, value in v3.items() if key != "prefixes"
        },
        "comparisonWithV3": {
            "v3CallTotal": v3.get("call_total"),
            "currentCallTotal": call_total,
            "v3OkFalse": v3.get("ok_false"),
            "currentOkFalse": ok_false,
            "okFalseGate": "修复记录 §6 P1：ok_false 由 5 降到 ≤2（余下若为值幻觉属模型能力）",
            "note": (
                "老报告生成于旧语料 65,511 块 / promptVersion=v3 / 非 7B，"
                "且 LLM 非确定性使工具调用数天然波动，不得把差值当作单向改进证据"
            ),
        },
        "rows": rows,
        "elapsedSeconds": round(time.perf_counter() - started_perf, 2),
        "notes": [
            "本文件由 scripts/retest_d08_20261114.py 生成；未修改任何既有 runtime JSON。",
            "hits = len(tool_result.data.data) if list else 0，与 v3 口径一致；另存 recallCountFromSummary 便于区分命中数与裁剪数。",
            "ok=false 如实保留，未改写、未删除；未配对的 tool_call/tool_result 全部列入 unpaired* 字段。",
            "脚本只做一条只读 SELECT，未写库；未调用 Ollama 原生接口；未启停服务。",
            "退出码只表示『结构完整且 matched==call_total』，不代表质量达标；PASS/PARTIAL/FAIL 由 qa-acceptance 裁定。",
        ],
    }

    try:
        write_output(out_path, payload)
    except SystemExit as exc:
        print(f"[D08] {exc}")
        return 2
    print(
        f"[D08] 完成 rows={total} call_total={call_total} matched={matched} "
        f"ok_true={ok_true} ok_false={ok_false} pv={prompt_info['promptVersion']} "
        f"输出={out_path}"
    )

    structural_ok = (
        corpus["corpusVersion"] is not None
        and bool(conditions["model"])
        and version_consistent
        and http_ok == total
        and total == EXPECTED_TOTAL
        and matched == call_total
        and not unmatched_results
    )
    return 0 if structural_ok else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("[D08] 用户中断")
        sys.exit(3)
    except Exception as exc:  # noqa: BLE001
        print(f"[D08] 运行异常：{type(exc).__name__}: {exc}")
        sys.exit(3)
