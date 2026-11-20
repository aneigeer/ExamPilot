# -*- coding: utf-8 -*-
"""D07 —— 10 例「在线拒答」固定条件重测（2026-11-14，T-C1）。

用途
----
以 ``runtime/d07_refusal_10.json`` 的 10 条题面为**只读输入**，对生产链路
``POST /api/ai/ask``（Java 后端 8080）逐条发真实 HTTP 请求，输出一份新的证据 JSON。

固定条件（四个字段，写进输出 JSON 顶层）
----------------------------------------
* ``corpusVersion``  —— ``ai_chunk`` 中 ``source_type='COURSEWARE'`` 的块数，
                        由本脚本**只读 SELECT 现查**（不是抄 89,318）。
* ``model``          —— 模型名：优先取进程环境 ``OLLAMA_MODEL``（后端启动参数），
                        回退取 ``ai-service/.env`` 的 ``LLM_MODEL``。
* ``promptVersion``  —— 运行态 ``meta.promptVersion``：由一次 ``/v1/agent/run``
                        探针（maxSteps=1）从 ``meta`` 事件读出；探针失败才回退静态解析
                        ``planner.py``，并如实标注 source 与 runtimeVerified=false。
* ``timestamp``      —— 采集时刻（ISO 8601，带本地时区偏移）。

逐条记录（rows[]）
------------------
``i / q / http / refused / sources / answerDigest / answerLength /
engine / mode / generator / degraded / elapsedMs / refusedSource /
refusalTextMatched / error``；顶层另给 ``refusedCount`` 与 9/10 门槛对照。

安全约束
--------
* **不硬编码任何口令/令牌**：学生 JWT 优先读 ``STUDENT_JWT`` 环境变量；否则用
  ``STUDENT_NO`` / ``STUDENT_PWD`` 登录一次换取 JWT；``AI_INTERNAL_TOKEN`` 与
  ``DB_PASSWORD`` 均从环境变量或 ``ai-service/.env`` 读取。
* **不回显**：控制台与输出 JSON 都不写明文令牌/口令；JWT 仅记录不可逆的
  SHA-256 前 16 位指纹，用于证明「10 条用的是同一学生 JWT」。
* **不写库**：对 MySQL 只执行一条 ``SELECT COUNT(*)``；不改任何既有 runtime JSON；
  不改 AI 服务源码；不提交、不推送。
* 输出文件默认为**新建**的 ``runtime/d07_retest_20261114.json``，并显式拒绝
  覆盖两个受保护的旧基线文件。

前置（由主 agent 本机执行，见 C1 重测方案）
------------------------------------------
1. 8080（带 ``AI_INTERNAL_TOKEN`` 与 ``OLLAMA_MODEL=exam-prep-7b``）、8000 均已起；
2. ``/api/actuator/health`` 200、``/docs`` 200；
3. 使用 ``.venv-ai/Scripts/python.exe`` 运行（需要 pymysql；缺失时回退 mysql CLI）。

用法::

    .venv-ai/Scripts/python.exe scripts/retest_d07_20261114.py
    .venv-ai/Scripts/python.exe scripts/retest_d07_20261114.py --out runtime/xxx.json
    .venv-ai/Scripts/python.exe scripts/retest_d07_20261114.py --skip-preflight

退出码：0 = 结构完整跑完（**不代表质量达标**）；2 = 前置/结构失败；3 = 运行异常。
质量判定（refused >= 9/10）只作为字段记录，交 ``qa-acceptance`` 独立裁定，脚本不自升档。
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
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# 路径与常量
# ---------------------------------------------------------------------------

PROJECT_ROOT: Path = Path(__file__).resolve().parents[1]
ENV_FILE: Path = PROJECT_ROOT / "ai-service" / ".env"
BASELINE_FILE: Path = PROJECT_ROOT / "runtime" / "d07_refusal_10.json"
DEFAULT_OUT: Path = PROJECT_ROOT / "runtime" / "d07_retest_20261114.json"
PLANNER_FILE: Path = PROJECT_ROOT / "ai-service" / "app" / "agent" / "planner.py"

#: 绝不允许被本脚本覆盖的受保护文件（旧基线 / 既有 runtime JSON）。
PROTECTED_FILES: Tuple[str, ...] = (
    "d07_refusal_10.json",
    "d07_refusal_baseline_20261113.json",
    "d08_planner_50.json",
    "d08_planner_tools_50.json",
    "d08_planner_full_50.json",
)

#: 只读语料版本查询：唯一允许执行的 SQL，参数不拼接任何外部输入。
CORPUS_SQL: str = "SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE'"

DEFAULT_JAVA_BASE: str = "http://127.0.0.1:8080/api"
DEFAULT_AI_BASE: str = "http://127.0.0.1:8000"
REFUSAL_TEXT: str = "未找到相关资料"

_NO_PROXY_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------------------
# 配置读取（环境变量优先，其次 ai-service/.env；绝不回显）
# ---------------------------------------------------------------------------


def _from_env_file(key: str) -> str:
    """从 ``ai-service/.env`` 读取单个键。

    只做最小解析：按首个 ``=`` 切分、去引号、忽略注释与 ``export `` 前缀。
    不打印读到的值，避免任何形式的口令/令牌回显。

    Args:
        key: 环境变量名（如 ``AI_INTERNAL_TOKEN``）。

    Returns:
        键对应的值；文件不存在或键缺失时返回空串。
    """
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
    """读取一个可能敏感的配置值：进程环境变量优先，其次 ``.env``。

    Args:
        key: 键名。
        default: 都读不到时的兜底值（仅用于非敏感配置）。

    Returns:
        解析后的字符串。
    """
    return os.environ.get(key) or _from_env_file(key) or default


def fingerprint(value: str) -> str:
    """返回不可逆指纹（SHA-256 前 16 位），仅用于证明「同一 JWT 被复用」。

    Args:
        value: 明文值（**不会被打印、不会被写入输出的明文字段**）。

    Returns:
        16 位十六进制串；空值返回 ``"<unset>"``。
    """
    if not value:
        return "<unset>"
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


# ---------------------------------------------------------------------------
# HTTP（标准库，绕过系统代理，保证 loopback 直连）
# ---------------------------------------------------------------------------


def http_json(
    method: str,
    url: str,
    body: Optional[Dict[str, Any]],
    headers: Optional[Dict[str, str]],
    timeout: float,
) -> Tuple[int, Optional[Dict[str, Any]], str, int, Optional[str]]:
    """发起一次 HTTP 请求并解析 JSON 响应。

    Args:
        method: ``GET`` / ``POST``。
        url: 完整 URL。
        body: JSON 请求体；``None`` 表示无请求体。
        headers: 额外请求头（不含任何需要打印的敏感值）。
        timeout: 超时秒数。

    Returns:
        ``(status, parsed, raw_head, elapsed_ms, error)``；连接失败时
        ``status=-1``，``error`` 为异常类型与摘要（不含请求头）。
    """
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
    except urllib.error.HTTPError as exc:  # 4xx/5xx 也要取证
        status = exc.code
        try:
            raw = exc.read().decode("utf-8", "replace")
        except OSError:
            raw = ""
    except Exception as exc:  # noqa: BLE001 - 连接失败必须落成结构化证据
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
# 固定条件 1：语料版本（只读 SELECT）
# ---------------------------------------------------------------------------


def _db_params() -> Dict[str, str]:
    """从环境变量 / ``.env`` 组装 MySQL 连接参数（口令不落盘、不回显）。"""
    user = read_secret("DB_USER") or read_secret("DB_USERNAME", "root")
    return {
        "host": read_secret("DB_HOST", "127.0.0.1"),
        "port": read_secret("DB_PORT", "3306"),
        "user": user,
        "password": read_secret("DB_PASSWORD"),
        "database": read_secret("DB_NAME", "exam_prep"),
    }


def _corpus_via_pymysql(params: Dict[str, str]) -> Tuple[Optional[int], Optional[str]]:
    """用 pymysql 执行唯一的只读 SELECT。

    Args:
        params: 连接参数。

    Returns:
        ``(count, error)``；成功时 error 为 ``None``。
    """
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
            # 唯一 SQL：硬编码常量，不接受任何外部拼接；只读，无 INSERT/UPDATE/DELETE。
            cursor.execute(CORPUS_SQL)
            row = cursor.fetchone()
        return int(row[0]), None
    except Exception as exc:  # noqa: BLE001 - 连接/查询失败需转为结构化证据
        return None, f"{type(exc).__name__}: {str(exc)[:180]}"
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass


def _corpus_via_cli(params: Dict[str, str]) -> Tuple[Optional[int], Optional[str]]:
    """pymysql 缺失时用 mysql CLI 兜底；口令经 ``MYSQL_PWD`` 传递，不进 argv。

    Args:
        params: 连接参数。

    Returns:
        ``(count, error)``；成功时 error 为 ``None``。
    """
    binary = read_secret("DB_BIN", r"D:\bigplan\tools\mysql-8.0.46\bin\mysql.exe")
    env = os.environ.copy()
    env["MYSQL_PWD"] = params["password"]  # 避免命令行回显口令
    command = [
        binary,
        "-h", params["host"],
        "-P", params["port"],
        "-u", params["user"],
        "--batch", "--skip-column-names",
        "--default-character-set=utf8mb4",
        "-e", CORPUS_SQL,
        params["database"],
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
    """现查语料版本（COURSEWARE 块数），返回结构化结果。

    Returns:
        含 ``corpusVersion`` / ``query`` / ``source`` / ``error`` 的字典。
        查询失败时 ``corpusVersion=None`` 且 ``error`` 如实写明，绝不编造数值。
    """
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
# 固定条件 2/3：模型名、meta.promptVersion
# ---------------------------------------------------------------------------


def resolve_model(cli_model: str = "") -> Dict[str, Any]:
    """解析模型名并记录来源，供验收核对「条件是否被写进结果」。

    优先级：``--model`` > ``RETEST_MODEL``/``OLLAMA_MODEL`` 环境变量（后端启动参数）
    > ``ai-service/.env`` 的 ``LLM_MODEL``。

    Args:
        cli_model: 命令行显式覆盖值。

    Returns:
        含 ``model`` / ``modelSource`` / ``ollamaModelFromEnv`` /
        ``llmModelFromFile`` / ``modelConsistent`` 的字典。
    """
    ollama = os.environ.get("OLLAMA_MODEL") or os.environ.get("RETEST_MODEL") or ""
    llm_file = read_secret("LLM_MODEL")
    if cli_model:
        model, source = cli_model, "cli:--model"
    elif ollama:
        model, source = ollama, "env:OLLAMA_MODEL"
    else:
        model, source = llm_file, "dotenv:LLM_MODEL"
    consistent: Optional[bool] = None
    if ollama and llm_file:
        consistent = ollama == llm_file
    return {
        "model": model,
        "modelSource": source,
        "ollamaModelFromEnv": ollama or None,
        "llmModelFromFile": llm_file or None,
        "modelConsistent": consistent,
    }


def static_prompt_version() -> Optional[str]:
    """静态解析 ``planner.py`` 的 ``PROMPT_VERSION`` 常量（只读文件）。

    Returns:
        版本串；解析失败返回 ``None``。仅作为运行态探针失败时的兜底，
        输出中会标注 ``promptVersionRuntimeVerified=false``。
    """
    try:
        text = PLANNER_FILE.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None
    match = re.search(r"^PROMPT_VERSION\s*=\s*[\"']([^\"']+)[\"']", text, re.MULTILINE)
    return match.group(1) if match else None


def probe_prompt_version(ai_base: str, token: str, timeout: float) -> Dict[str, Any]:
    """用一次 ``/v1/agent/run``（maxSteps=1）读运行态 ``meta.promptVersion``。

    该探针同时校验 ``X-Internal-Token`` 是否可用；**不计入 10 条拒答评测**。
    失败时回退静态解析并如实标注，绝不把静态值冒充运行态值。

    Args:
        ai_base: AI 服务基址。
        token: ``AI_INTERNAL_TOKEN``（不打印）。
        timeout: 超时秒数。

    Returns:
        含 ``promptVersion`` / ``promptVersionSource`` /
        ``promptVersionRuntimeVerified`` / ``probeHttp`` / ``probeError`` 的字典。
    """
    status, parsed, raw_head, elapsed_ms, error = http_json(
        "POST",
        ai_base + "/v1/agent/run",
        {"question": "条件探针", "maxSteps": 1},
        {"X-Internal-Token": token},
        timeout,
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
            "probeElapsedMs": elapsed_ms,
            "probeError": None,
        }
    fallback = static_prompt_version()
    return {
        "promptVersion": fallback,
        "promptVersionSource": (
            "static:ai-service/app/agent/planner.py PROMPT_VERSION "
            "(runtime probe failed; NOT runtime-verified)"
        ),
        "promptVersionRuntimeVerified": False,
        "probeHttp": status,
        "probeElapsedMs": elapsed_ms,
        "probeError": error or (raw_head[:160] if status != 200 else "meta 事件缺失或无 promptVersion"),
    }


# ---------------------------------------------------------------------------
# 健康检查前置
# ---------------------------------------------------------------------------


def preflight(java_base: str, ai_base: str, timeout: float) -> Dict[str, Any]:
    """检查 8080 ``/api/actuator/health`` 与 8000 ``/docs``。

    Args:
        java_base: 后端基址（含 ``/api`` context-path）。
        ai_base: AI 服务基址。
        timeout: 超时秒数。

    Returns:
        ``{ok, checks: {...}}``；两项都 200 才 ``ok=True``。
    """
    checks: Dict[str, Any] = {}
    targets = (
        ("backendHealth", java_base + "/actuator/health"),
        ("aiDocs", ai_base + "/docs"),
    )
    for name, url in targets:
        status, _, _, elapsed_ms, error = http_json("GET", url, None, {}, timeout)
        checks[name] = {
            "url": url,
            "http": status,
            "ok": status == 200,
            "elapsedMs": elapsed_ms,
            "error": error,
        }
    return {"ok": all(item["ok"] for item in checks.values()), "checks": checks}


# ---------------------------------------------------------------------------
# 学生 JWT（登录一次，全程复用同一 token）
# ---------------------------------------------------------------------------


def acquire_student_jwt(java_base: str, timeout: float) -> Tuple[str, Dict[str, Any]]:
    """取得**同一个**学生 JWT 供 10 条请求复用。

    优先读 ``STUDENT_JWT`` 环境变量或 ``.env``；否则用 ``STUDENT_NO`` /
    ``STUDENT_PWD`` 登录一次。任何情况下都不打印明文。

    Args:
        java_base: 后端基址。
        timeout: 超时秒数。

    Returns:
        ``(jwt, meta)``；``meta`` 含 source / fingerprint / error。
        失败时 ``jwt`` 为空串。

    Raises:
        SystemExit: 既无 JWT 又无账号口令时中止（不硬编码凭据）。
    """
    direct = read_secret("STUDENT_JWT")
    if direct:
        return direct, {
            "jwtSource": "env/dotenv:STUDENT_JWT",
            "jwtSha256Prefix": fingerprint(direct),
            "jwtReusedAcrossRequests": True,
            "jwtError": None,
        }

    student_no = read_secret("STUDENT_NO")
    student_pwd = read_secret("STUDENT_PWD")
    if not student_no or not student_pwd:
        raise SystemExit(
            "缺少学生凭据：请设置环境变量 STUDENT_JWT，或同时设置 STUDENT_NO 与 "
            "STUDENT_PWD（也可写入 ai-service/.env）。脚本不硬编码任何口令/令牌。"
        )
    status, parsed, raw_head, _, error = http_json(
        "POST",
        java_base + "/auth/login",
        {"studentNo": student_no, "password": student_pwd},
        {},
        timeout,
    )
    data = (parsed or {}).get("data") or {}
    token = ""
    if isinstance(data, dict):
        token = str(data.get("token") or data.get("accessToken") or data.get("access_token") or "")
    if status != 200 or not token:
        raise SystemExit(
            "登录失败，无法取得学生 JWT："
            f"http={status} error={error} raw_head={raw_head[:120]!r}（不回显口令）"
        )
    return token, {
        "jwtSource": "login:POST /api/auth/login",
        "jwtSha256Prefix": fingerprint(token),
        "jwtReusedAcrossRequests": True,
        "jwtError": None,
    }


# ---------------------------------------------------------------------------
# 输入解析（只读旧基线）
# ---------------------------------------------------------------------------


def load_baseline(path: Path) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """只读解析 ``d07_refusal_10.json`` 的 10 条题面与旧汇总值。

    Args:
        path: 旧基线文件路径。

    Returns:
        ``(questions, baseline_summary)``；``questions`` 为 ``[{i, q}, ...]``。

    Raises:
        SystemExit: 文件缺失或结构非法。
    """
    if not path.exists():
        raise SystemExit(f"输入文件不存在：{path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SystemExit(f"输入文件无法解析：{path} ({exc})") from exc
    rows = payload.get("rows") or []
    questions: List[Dict[str, Any]] = []
    for index, row in enumerate(rows, start=1):
        question = str(row.get("q") or "").strip()
        if not question:
            raise SystemExit(f"第 {index} 条题面为空，拒绝继续（避免伪造题面）")
        questions.append({"i": int(row.get("i") or index), "q": question})
    if not questions:
        raise SystemExit("旧基线中没有任何题面，拒绝继续")
    summary = {
        "baselineFile": str(path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
        "baselineTotal": int(payload.get("total") or len(questions)),
        "baselineRefused": int(payload.get("refused") or 0),
        "baselineThreshold": int(payload.get("threshold") or 9),
        "baselineNote": "旧基线（旧语料/旧模型/旧 JWT）只读，仅作对照，不作为本次判定依据",
    }
    return questions, summary


# ---------------------------------------------------------------------------
# 单条拒答测试
# ---------------------------------------------------------------------------


def run_one(
    java_base: str, jwt: str, item: Dict[str, Any], timeout: float, digest_len: int
) -> Dict[str, Any]:
    """对一条题面发起 ``POST /api/ai/ask`` 并结构化记录结果。

    Args:
        java_base: 后端基址。
        jwt: 学生 JWT（不打印）。
        item: ``{i, q}``。
        timeout: 超时秒数。
        digest_len: 答案摘要保留的字符数。

    Returns:
        含 ``http/refused/sources/answerDigest`` 等字段的逐条记录。
    """
    status, parsed, raw_head, elapsed_ms, error = http_json(
        "POST",
        java_base + "/ai/ask",
        {"question": item["q"]},
        {"Authorization": "Bearer " + jwt},
        timeout,
    )
    data = (parsed or {}).get("data") or {}
    if not isinstance(data, dict):
        data = {}

    answer = str(data.get("answer") or "")
    sources_raw = data.get("sources")
    sources_count = len(sources_raw) if isinstance(sources_raw, list) else int(sources_raw or 0)
    refusal_text_matched = answer.strip() == REFUSAL_TEXT

    if "refused" in data:
        refused = bool(data.get("refused"))
        refused_source = "api:data.refused"
    else:
        refused = refusal_text_matched
        refused_source = "fallback:answerText" if status == 200 else "fallback:noResponse"

    digest = answer[:digest_len]
    if len(answer) > digest_len:
        digest += "…"
    return {
        "i": item["i"],
        "q": item["q"],
        "http": status,
        "refused": refused,
        "refusedSource": refused_source,
        "refusalTextMatched": refusal_text_matched,
        "sources": sources_count,
        "answerDigest": digest,
        "answerLength": len(answer),
        "engine": data.get("engine"),
        "mode": data.get("retrievalMode") or data.get("mode"),
        "generator": data.get("generator"),
        "degraded": data.get("degraded"),
        "elapsedMs": elapsed_ms,
        "error": error,
        "rawHead": raw_head if status != 200 else None,
    }


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------


def write_output(path: Path, payload: Dict[str, Any]) -> None:
    """把结果写成 UTF-8 JSON；显式拒绝覆盖受保护的既有文件。

    Args:
        path: 输出路径。
        payload: 结果对象。

    Raises:
        SystemExit: 输出路径命中受保护文件。
    """
    if path.name in PROTECTED_FILES:
        raise SystemExit(f"拒绝覆盖受保护的既有 runtime JSON：{path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="D07 10 例在线拒答固定条件重测")
    parser.add_argument("--java-base", default=os.environ.get("RETEST_JAVA_BASE", DEFAULT_JAVA_BASE))
    parser.add_argument("--ai-base", default=os.environ.get("RETEST_AI_BASE", DEFAULT_AI_BASE))
    parser.add_argument("--baseline", default=str(BASELINE_FILE))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--timeout", type=float, default=120.0, help="单条 /api/ai/ask 超时秒数")
    parser.add_argument("--probe-timeout", type=float, default=120.0)
    parser.add_argument("--preflight-timeout", type=float, default=10.0)
    parser.add_argument("--digest-len", type=int, default=120)
    parser.add_argument("--model", default="", help="显式覆盖模型名（默认从环境/.env 读）")
    parser.add_argument("--skip-preflight", action="store_true", help="跳过健康检查（不推荐）")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    """主流程：前置 → 采条件 → 取 JWT → 逐条 → 汇总 → 落盘。

    Args:
        argv: 命令行参数（便于测试注入）。

    Returns:
        退出码：0 结构完整；2 前置/结构失败；3 运行异常。
    """
    args = parse_args(argv)
    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = PROJECT_ROOT / out_path

    started_dt = datetime.now().astimezone()
    started_perf = time.perf_counter()
    print("[D07] 开始：只读旧基线 + 采集固定条件（不回显任何令牌/口令）")

    questions, baseline = load_baseline(Path(args.baseline))
    print(f"[D07] 题面 {len(questions)} 条，来源 {baseline['baselineFile']}")

    # 1) 健康检查前置
    preflight_result: Dict[str, Any] = {"skipped": True, "ok": True, "checks": {}}
    if not args.skip_preflight:
        preflight_result = preflight(args.java_base, args.ai_base, args.preflight_timeout)
        print(
            f"[D07] 前置 health={preflight_result['checks']['backendHealth']['http']} "
            f"docs={preflight_result['checks']['aiDocs']['http']}"
        )
        if not preflight_result["ok"]:
            print("[D07] 健康检查未通过，中止（需 8080 /api/actuator/health 与 8000 /docs 均 200）")
            return 2

    # 2) 四个固定条件
    corpus = query_corpus_version()
    model_info = resolve_model(args.model)
    token = read_secret("AI_INTERNAL_TOKEN")
    if not token:
        print("[D07] 缺少 AI_INTERNAL_TOKEN（环境变量或 ai-service/.env），中止")
        return 2
    prompt_info = probe_prompt_version(args.ai_base, token, args.probe_timeout)
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
        "promptVersionExpected": "agent-planner-v4",
        "promptVersionMatchesV4": prompt_info["promptVersion"] == "agent-planner-v4",
        "probeHttp": prompt_info["probeHttp"],
        "probeError": prompt_info["probeError"],
        "internalTokenConfigured": True,
        "internalTokenSource": "env/dotenv:AI_INTERNAL_TOKEN（仅记录是否已注入，不回显任何值）",
        "timestampUtc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    }
    print(
        f"[D07] 条件 corpus={conditions['corpusVersion']} model={conditions['model']} "
        f"promptVersion={conditions['promptVersion']} "
        f"runtimeVerified={prompt_info['promptVersionRuntimeVerified']}"
    )

    # 3) 同一学生 JWT
    try:
        jwt, jwt_meta = acquire_student_jwt(args.java_base, args.preflight_timeout)
    except SystemExit as exc:
        print(f"[D07] {exc}")
        return 2
    print(f"[D07] JWT 已取得（source={jwt_meta['jwtSource']}，指纹 {jwt_meta['jwtSha256Prefix']}）")

    # 4) 逐条真实请求
    rows: List[Dict[str, Any]] = []
    for item in questions:
        row = run_one(args.java_base, jwt, item, args.timeout, max(20, args.digest_len))
        rows.append(row)
        print(
            f"[D07] {row['i']:>2}/{len(questions)} http={row['http']} "
            f"refused={row['refused']} sources={row['sources']} "
            f"ms={row['elapsedMs']} digest={row['answerDigest'][:40]!r}"
        )

    # 5) 汇总与 9/10 门槛对照
    refused_count = sum(1 for row in rows if row["http"] == 200 and row["refused"])
    threshold = baseline["baselineThreshold"]
    total = len(rows)
    http_ok = sum(1 for row in rows if row["http"] == 200)
    finished_dt = datetime.now().astimezone()

    payload: Dict[str, Any] = {
        "schema": "d07-retest-20261114-v1",
        "task": "D07 在线拒答 10 例固定条件重测",
        "generatedAt": finished_dt.isoformat(timespec="seconds"),
        # ===== 四个固定条件字段（顶层，供独立验收直接核对）=====
        "corpusVersion": conditions["corpusVersion"],
        "model": conditions["model"],
        "promptVersion": conditions["promptVersion"],
        "timestamp": conditions["timestamp"],
        # ===== 同值别名块：兼容不同验收口径（嵌套 meta.promptVersion / 中文键）=====
        "meta": {
            "promptVersion": conditions["promptVersion"],
            "source": "meta 事件 @ POST /v1/agent/run（探针 maxSteps=1）",
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
            "ask": args.java_base + "/ai/ask",
            "probe": args.ai_base + "/v1/agent/run",
            "method": "POST",
            "auth": "Authorization: Bearer <STUDENT_JWT>（同一 token 复用 10 次，不回显）",
        },
        "jwt": jwt_meta,
        # ===== 判据：refused 计数 + 9/10 门槛对照 =====
        "total": total,
        "httpOk": http_ok,
        "refusedCount": refused_count,
        "threshold": threshold,
        "gate": f">={threshold}/{total}",
        "meetsThreshold": refused_count >= threshold,
        "thresholdComparison": {
            "baselineRefused": baseline["baselineRefused"],
            "baselineTotal": baseline["baselineTotal"],
            "baselineText": f"{baseline['baselineRefused']}/{baseline['baselineTotal']}",
            "currentText": f"{refused_count}/{total}",
            "delta": refused_count - baseline["baselineRefused"],
            "note": (
                f"旧基线 {baseline['baselineRefused']}/{baseline['baselineTotal']} 生成于旧语料/旧模型，"
                f"仅作历史对照；本次门槛为 refused >= {threshold}/{total}。"
                "历史记录另有 6/10 与 0/10 不稳定的提醒，单次结果不得当作稳定基线。"
            ),
        },
        "baseline": baseline,
        "rows": rows,
        "elapsedSeconds": round(time.perf_counter() - started_perf, 2),
        "notes": [
            "本文件由 scripts/retest_d07_20261114.py 生成；未修改任何既有 runtime JSON。",
            "脚本只做一条只读 SELECT，未写库；未调用 Ollama 原生接口；未启停服务。",
            "逐条 refused 取自 /api/ai/ask 的 data.refused；字段缺失时按答案文本兜底并记录 refusedSource。",
            "退出码只表示『结构完整跑完』，不代表质量达标；PASS/PARTIAL/FAIL 由 qa-acceptance 裁定。",
        ],
    }

    try:
        write_output(out_path, payload)
    except SystemExit as exc:
        print(f"[D07] {exc}")
        return 2
    print(
        f"[D07] 完成 refused={refused_count}/{total} 门槛>={threshold}/{total} "
        f"meetsThreshold={payload['meetsThreshold']} 输出={out_path}"
    )

    structural_ok = (
        corpus["corpusVersion"] is not None
        and bool(conditions["model"])
        and prompt_info["promptVersionRuntimeVerified"]
        and http_ok == total
    )
    return 0 if structural_ok else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("[D07] 用户中断")
        sys.exit(3)
    except Exception as exc:  # noqa: BLE001 - 顶层兜底，转为可读退出码
        print(f"[D07] 运行异常：{type(exc).__name__}: {exc}")
        sys.exit(3)
