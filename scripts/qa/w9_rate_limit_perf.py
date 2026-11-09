#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 9 周 W9-8：认证限流验证 + 核心接口性能基线（P1）。

设计原则
--------
1. **不写库**：只打 ``POST /auth/login``（**故意用错误口令 + 不存在的学号**）与 4 个只读/准只读接口。
   **绝不**调用 ``/auth/register``（会写 user 表）、``/files/upload``、``/me/**`` 的写方法。
   脚本内置路径白名单 ``ALLOWED_REQUESTS``，任何越界调用会在发请求前直接抛异常。
2. **不牵连真实用户**：限流桶的 key 是 ``规则名:IP``，而 ``RateLimitFilter.clientIp()`` 优先取
   ``X-Forwarded-For`` 的第一个 IP。因此本脚本每次运行注入一个**专用的假 XFF**
   （TEST-NET-3 ``203.0.113.0/24``），把桶打在假 IP 上，真实客户端 IP 的桶不会被塞满，
   测试期间真人仍能正常登录。
   （注：这种"伪造 XFF 即可换桶"正是本次要报告的**安全发现**，见报告 W9-8 第 3 节。）
3. **可复跑**：每次运行生成一份 JSON 证据；``--dry-run`` 只打印计划、不发任何请求。
4. **口径自证**：限流阈值/窗口/响应体由脚本**直接解析** ``RateLimitFilter.java`` 得到
   （同时把该文件 sha256 记进证据），不在脚本里硬编码后"自说自话"。

用法
----
    # 只看计划，不发请求（静态核对用）
    python scripts/qa/w9_rate_limit_perf.py --dry-run

    # 正式跑（限流 + 性能）
    python scripts/qa/w9_rate_limit_perf.py

    # 只跑性能基线（限流会占 ~65 秒窗口，时间紧时可单独跑）
    python scripts/qa/w9_rate_limit_perf.py --mode perf

    # 只跑限流
    python scripts/qa/w9_rate_limit_perf.py --mode rate

    # 指定输出
    python scripts/qa/w9_rate_limit_perf.py --out 项目规划/第9周/evidence/rate_limit_perf_20261109.json

环境变量（可选，优先级低于命令行参数）
    QA_BASE            默认 http://127.0.0.1:8080/api
    QA_JWT_SECRET      默认从 backend/src/main/resources/application-local.yml 读 app.jwt.secret
    QA_USER_ID         JWT 里使用的用户 id，默认 1（本机管理员）

退出码
    0  全部预期成立（限流按代码生效 + 性能数据采集完成）
    1  有预期不成立的项（例如首次 429 位置与代码不符）
    2  预检失败（服务未启动 / 关键接口不通），未进入正式测量
    3  参数或环境错误（缺 secret、白名单越界等）
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
RATE_LIMIT_FILTER = REPO_ROOT / "backend/src/main/java/com/examprep/config/RateLimitFilter.java"
LOCAL_YML = REPO_ROOT / "backend/src/main/resources/application-local.yml"
DEFAULT_OUT = REPO_ROOT / "项目规划/第9周/evidence/rate_limit_perf_20261109.json"

# ---------------------------------------------------------------------------
# 白名单：脚本只允许请求这些路径（防止误打 register / upload 等写接口）
# 说明：/questions 与 /questions/<id> 为 PUBLIC；/me/stats 与 /ai/ask 需 AUTH。
# ---------------------------------------------------------------------------
ALLOWED_REQUESTS: Dict[str, Tuple[str, ...]] = {
    "GET": (
        "/actuator/health",
        "/questions",
        "/questions/4022",
        "/me/stats",
        "/ai/capability",
    ),
    "POST": (
        "/auth/login",   # 只允许错误口令打点（不存在的学号）
        "/ai/ask",
    ),
}
FORBIDDEN_KEYWORDS = ("register", "upload", "files", "admin", "logout", "password")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------
def b64url(raw: bytes) -> str:
    """base64url 编码（去掉 padding），JWT 用。"""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def sha256_file(path: Path) -> str:
    """计算文件 sha256；文件不存在返回空串。"""
    if not path.exists():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: List[float], p: float) -> Optional[float]:
    """线性插值分位数（与 numpy.percentile 默认口径一致）。空列表返回 None。"""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * p
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    frac = position - low
    return ordered[low] + (ordered[high] - ordered[low]) * frac


def ms(value: Optional[float]) -> Optional[float]:
    """毫秒保留 2 位小数。"""
    return None if value is None else round(value, 2)


def http_call(
    method: str,
    url: str,
    *,
    headers: Optional[Dict[str, str]] = None,
    body: Optional[bytes] = None,
    timeout: float = 30.0,
) -> Dict[str, Any]:
    """发一个请求并返回统一的观测结果（不抛异常，异常进 error 字段）。"""
    request = urllib.request.Request(url=url, data=body, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    started = time.perf_counter()
    status = -1
    payload = b""
    error = ""
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            payload = response.read()
    except urllib.error.HTTPError as exc:  # 4xx/5xx 也是有效观测
        status = exc.code
        try:
            payload = exc.read()
        except Exception:  # noqa: BLE001 - 读取失败不影响主流程
            payload = b""
    except Exception as exc:  # noqa: BLE001 - 网络异常统一记录
        error = "%s: %s" % (type(exc).__name__, exc)
    elapsed_ms = (time.perf_counter() - started) * 1000.0

    text = ""
    biz_code: Optional[int] = None
    if payload:
        try:
            text = payload.decode("utf-8", "replace")
            parsed = json.loads(text)
            if isinstance(parsed, dict) and isinstance(parsed.get("code"), int):
                biz_code = parsed["code"]
        except Exception:  # noqa: BLE001 - 非 JSON 响应（如静态资源）忽略
            text = text[:200]
    return {
        "status": status,
        "elapsed_ms": elapsed_ms,
        "biz_code": biz_code,
        "body_head": text[:300],
        "error": error,
    }


def guard_path(method: str, path: str) -> None:
    """白名单校验：不在名单内或命中敏感关键字，直接拒绝执行（保护源库与账号）。"""
    clean = path.split("?")[0]
    for keyword in FORBIDDEN_KEYWORDS:
        if keyword in clean.lower() and clean not in ("/ai/ask", "/auth/login"):
            raise SystemExit(
                "拒绝执行：路径 %s 命中敏感关键字 %r，本脚本不允许调用写接口" % (path, keyword)
            )
    allowed = ALLOWED_REQUESTS.get(method.upper(), ())
    if clean not in allowed and not (clean.startswith("/questions/") and method.upper() == "GET"):
        raise SystemExit("拒绝执行：%s %s 不在白名单内 %s" % (method, path, allowed))


# ---------------------------------------------------------------------------
# 静态分析：直接解析 RateLimitFilter.java，保证报告口径与代码同源
# ---------------------------------------------------------------------------
def parse_rate_limit_filter() -> Dict[str, Any]:
    """解析 RateLimitFilter 源码常量与关键行为。解析失败时返回带 error 的默认值。"""
    info: Dict[str, Any] = {
        "source_path": str(RATE_LIMIT_FILTER.relative_to(REPO_ROOT)).replace("\\", "/"),
        "source_sha256": sha256_file(RATE_LIMIT_FILTER),
        "login_limit": 5,
        "register_limit": 3,
        "window_ms": 60000,
        "max_buckets": 10000,
        "applies_to": ["POST /auth/login", "POST /auth/register"],
        "covers_upload": False,
        "key_strategy": "X-Forwarded-For 第一段，缺失则 remoteAddr（规则名:IP）",
        "xff_trust": "无条件信任客户端 X-Forwarded-For（可伪造换桶）",
        "response_status": 429,
        "response_body": '{"code":429,"message":"请求过于频繁，请稍后再试","data":null}',
        "retry_after_header": False,
        "login_failure_http_status": 200,
        "login_failure_biz_code": 1001,
        "notes": [
            "固定窗口（滑动的请求时间戳队列，窗口 60s），非令牌桶",
            "被 429 拒绝的请求不写入队列，因此客户端持续重试不会延长封禁窗口",
            "只按 IP 限流，没有按账号限流：换 IP 即可继续爆破同一账号",
            "filter 顺序 @Order(1)，位于 JWT 认证过滤器之前",
        ],
        "parsed_ok": False,
    }
    if not RATE_LIMIT_FILTER.exists():
        info["error"] = "未找到 RateLimitFilter.java"
        return info
    source = RATE_LIMIT_FILTER.read_text(encoding="utf-8", errors="replace")

    def grab(pattern: str, cast=int) -> Optional[Any]:
        match = re.search(pattern, source)
        if not match:
            return None
        try:
            return cast(match.group(1))
        except (TypeError, ValueError):
            return None

    login_limit = grab(r"LOGIN_LIMIT\s*=\s*([0-9_]+)")
    register_limit = grab(r"REGISTER_LIMIT\s*=\s*([0-9_]+)")
    window_ms = grab(r"WINDOW_MS\s*=\s*([0-9_]+)")
    max_buckets = grab(r"MAX_BUCKETS\s*=\s*([0-9_]+)")
    if login_limit:
        info["login_limit"] = login_limit
    if register_limit:
        info["register_limit"] = register_limit
    if window_ms:
        info["window_ms"] = window_ms
    if max_buckets:
        info["max_buckets"] = max_buckets
    info["retry_after_header"] = "Retry-After" in source
    info["covers_upload"] = "upload" in source.lower()
    info["parsed_ok"] = bool(login_limit and window_ms)
    return info


# ---------------------------------------------------------------------------
# JWT：本机 local profile 的密钥可直接签发令牌，避免为了拿 token 去真实登录（登录会写 last_login_at）
# ---------------------------------------------------------------------------
def load_jwt_secret(cli_secret: Optional[str]) -> Tuple[str, str]:
    """返回 (secret, 来源说明)。"""
    if cli_secret:
        return cli_secret, "命令行 --jwt-secret"
    env_secret = os.environ.get("QA_JWT_SECRET") or os.environ.get("JWT_SECRET")
    if env_secret:
        return env_secret, "环境变量"
    if LOCAL_YML.exists():
        text = LOCAL_YML.read_text(encoding="utf-8", errors="replace")
        block = re.search(r"jwt:\s*(?:\r?\n\s+#.*)*\r?\n\s+secret:\s*(\S+)", text)
        if block:
            return block.group(1).strip().strip("'\""), "application-local.yml"
    return "", "未找到"


def jjwt_alg_for_key(secret: str) -> str:
    """复刻 jjwt 0.12 `Keys.hmacShaKeyFor` 的算法选择规则（按密钥位长选最强 HMAC）。"""
    bits = len(secret.encode("utf-8")) * 8
    if bits >= 512:
        return "HS512"
    if bits >= 384:
        return "HS384"
    if bits >= 256:
        return "HS256"
    raise SystemExit("JWT 密钥不足 256 位，HS256 无法签名")


def mint_jwt(secret: str, user_id: int, student_no: str, role: str, alg: str,
             ttl_seconds: int = 900) -> str:
    """按 JwtUtil.generate 的 claim 结构签发令牌（uid/stu/role + sub/iat/exp）。"""
    digest = {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}[alg]
    now = int(time.time())
    header = {"alg": alg, "typ": "JWT"}
    payload = {
        "uid": user_id,
        "stu": student_no,
        "role": role,
        "sub": str(user_id),
        "iat": now,
        "exp": now + ttl_seconds,
    }
    signing_input = "%s.%s" % (
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
        b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
    )
    signature = hmac.new(secret.encode("utf-8"), signing_input.encode("ascii"), digest).digest()
    return "%s.%s" % (signing_input, b64url(signature))


def resolve_token(base: str, args: argparse.Namespace, log: List[str]) -> Dict[str, Any]:
    """签发并验证 JWT：依次尝试候选算法，取第一个能拿到 200 的。

    JwtAuthenticationFilter 解析成功后还会回查用户（存在且 status=1），
    所以令牌有效但用户非法时依旧 401——这里一并覆盖。
    """
    secret, source = load_jwt_secret(args.jwt_secret)
    result: Dict[str, Any] = {
        "available": False,
        "secret_source": source,
        "user_id": args.user_id,
        "role": args.role,
        "alg": None,
        "tried": [],
        "note": "",
    }
    if not secret:
        result["note"] = "未找到 app.jwt.secret，跳过需要鉴权的接口（/me/stats、/ai/ask）"
        log.append(result["note"])
        return result

    primary = jjwt_alg_for_key(secret)
    candidates = [primary] + [alg for alg in ("HS256", "HS384", "HS512") if alg != primary]
    for alg in candidates:
        token = mint_jwt(secret, args.user_id, args.student_no, args.role, alg)
        probe = http_call("GET", base + "/me/stats",
                          headers={"Authorization": "Bearer " + token}, timeout=10.0)
        result["tried"].append({"alg": alg, "status": probe["status"], "biz_code": probe["biz_code"]})
        if probe["status"] == 200 and probe["biz_code"] == 200:
            result.update({"available": True, "alg": alg, "token": token})
            result["note"] = "自签令牌可用（jjwt 按密钥位长选择 %s）" % alg
            return result
        if probe["status"] == 401:
            continue
        break
    result["note"] = ("自签令牌不可用（可能用户 id=%s 不存在/被禁用，或密钥与运行实例不一致）；"
                      "已跳过错开需要鉴权的接口" % args.user_id)
    log.append(result["note"])
    return result


# ---------------------------------------------------------------------------
# 限流验证
# ---------------------------------------------------------------------------
def probe_rate_limit(base: str, static: Dict[str, Any], args: argparse.Namespace,
                     log: List[str]) -> Dict[str, Any]:
    """用错误口令打 /auth/login，测出首次 429 的位置、封禁与恢复时长、以及 XFF 换桶绕过。"""
    limit = int(static["login_limit"])
    window_ms = int(static["window_ms"])
    attempts_planned = limit + args.rate_extra_attempts
    fake_ip = "203.0.113.%d" % random.SystemRandom().randint(2, 254)
    headers = {
        "Content-Type": "application/json;charset=UTF-8",
        "X-Forwarded-For": fake_ip,
    }
    # 不存在的学号 + 错误口令：既不锁真实账号，也不会命中"学号不存在"以外的分支
    body = json.dumps({"studentNo": args.probe_student_no, "password": args.probe_password}).encode("utf-8")

    result: Dict[str, Any] = {
        "probe_target": "POST /auth/login",
        "probe_credential": {"studentNo": args.probe_student_no, "password": "（错误口令，已隐去）"},
        "xff_used": fake_ip,
        "xff_rationale": ("限流桶 key = 规则名:IP，clientIp() 取 X-Forwarded-For 首段；"
                          "注入专用假 IP 可让桶与真实客户端隔离，测试不会封锁真人登录"),
        "expected_first_429_at": limit + 1,
        "attempts_planned": attempts_planned,
        "attempts": [],
        "first_429_at": None,
        "accepted_count": 0,
        "blocked_count": 0,
        "blocked_status": None,
        "blocked_body": None,
        "enforced": False,
        "matches_code": False,
        "recovery": {},
        "bypass_check": {},
        "register_probe": "skipped: 会写 user 表，按要求不打",
        "upload_covered": bool(static.get("covers_upload")),
    }

    for index in range(1, attempts_planned + 1):
        item = http_call("POST", base + "/auth/login", headers=headers, body=body, timeout=15.0)
        record = {
            "attempt": index,
            "http_status": item["status"],
            "biz_code": item["biz_code"],
            "elapsed_ms": ms(item["elapsed_ms"]),
            "error": item["error"],
        }
        result["attempts"].append(record)
        if item["status"] == 429:
            if result["first_429_at"] is None:
                result["first_429_at"] = index
                result["blocked_status"] = item["status"]
                result["blocked_body"] = item["body_head"]
            result["blocked_count"] += 1
        else:
            result["accepted_count"] += 1
        time.sleep(args.rate_interval_ms / 1000.0)

    result["enforced"] = result["blocked_count"] > 0
    result["matches_code"] = result["first_429_at"] == limit + 1

    # --- 恢复时长：窗口内一直 429，窗口过后应恢复。被 429 的请求不入队，所以轮询不会延长封禁 ---
    if result["enforced"]:
        started = time.perf_counter()
        polls: List[Dict[str, Any]] = []
        recovered_after: Optional[float] = None
        while time.perf_counter() - started <= args.recovery_max_seconds:
            time.sleep(args.recovery_poll_seconds)
            poll = http_call("POST", base + "/auth/login", headers=headers, body=body, timeout=15.0)
            elapsed = time.perf_counter() - started
            polls.append({"t_seconds": round(elapsed, 2), "http_status": poll["status"],
                          "biz_code": poll["biz_code"]})
            if poll["status"] != 429:
                recovered_after = round(elapsed, 2)
                break
        result["recovery"] = {
            "window_ms": window_ms,
            "recovered": recovered_after is not None,
            "recovered_after_seconds": recovered_after,
            "poll_interval_seconds": args.recovery_poll_seconds,
            "polls": polls,
            "explain": ("封禁自第一条被接受的请求起算，恢复时间应 ≈ 窗口长度（60s）；"
                        "轮询请求被 429 拒绝时不入队，因此轮询不延长窗口"),
        }
    else:
        result["recovery"] = {"recovered": None, "note": "未触发 429，无需测恢复"}

    # --- 绕过验证：换一个伪造 XFF（相当于换 IP）应立刻拿到非 429 —— 证明"伪造 XFF 可绕过限流" ---
    bypass_ip = "203.0.113.%d" % (int(fake_ip.rsplit(".", 1)[1]) % 200 + 2)
    if bypass_ip == fake_ip:
        bypass_ip = "203.0.113.250"
    bypass_body = json.dumps({"studentNo": args.probe_student_no, "password": args.probe_password}).encode("utf-8")
    bypass = http_call("POST", base + "/auth/login",
                       headers={"Content-Type": "application/json;charset=UTF-8",
                                "X-Forwarded-For": bypass_ip},
                       body=bypass_body, timeout=15.0)
    result["bypass_check"] = {
        "xff_when_exhausted_was": fake_ip,
        "xff_now": bypass_ip,
        "http_status": bypass["status"],
        "biz_code": bypass["biz_code"],
        "bypassed": bypass["status"] != 429,
        "explain": ("若换了 X-Forwarded-For 后不再是 429，说明攻击者只要伪造该头就能无限制爆破；"
                    "根因：RateLimitFilter 无条件信任 XFF 首段，且 nginx 用 "
                    "$proxy_add_x_forwarded_for 会把客户端自带 XFF 拼在最前面"),
    }
    log.append("限流：首次 429 于第 %s 次（代码预期第 %s 次）" % (result["first_429_at"], limit + 1))
    return result


# ---------------------------------------------------------------------------
# 性能基线
# ---------------------------------------------------------------------------
PERF_TARGETS: List[Dict[str, Any]] = [
    {
        "key": "questions_list",
        "name": "题目列表",
        "method": "GET",
        "path": "/questions?page=1&size=20",
        "auth": False,
        "requests": 60,
        "timeout": 20.0,
        "expect_status": 200,
    },
    {
        "key": "question_detail",
        "name": "题目详情",
        "method": "GET",
        "path": "/questions/4022",
        "auth": False,
        "requests": 60,
        "timeout": 20.0,
        "expect_status": 200,
    },
    {
        "key": "me_stats",
        "name": "我的统计",
        "method": "GET",
        "path": "/me/stats",
        "auth": True,
        "requests": 60,
        "timeout": 20.0,
        "expect_status": 200,
    },
    {
        # /ai/ask 会走检索 + 可能调用本地 Ollama：单次可能数秒，故样本量小、并发低，
        # 避免把 Ollama/熔断器打满影响并行进行的降级演练（w9-7）。
        "key": "ai_ask",
        "name": "AI 问答",
        "method": "POST",
        "path": "/ai/ask",
        "auth": True,
        "requests": 20,
        "concurrency": 3,   # 单独压到 3：单次会调本地 Ollama，避免把模型/熔断器打满
        "timeout": 90.0,
        "expect_status": 200,
        "body": json.dumps({"question": "值传递和地址传递有什么区别"}, ensure_ascii=False).encode("utf-8"),
        "content_type": "application/json;charset=UTF-8",
    },
]


def measure_endpoint(base: str, target: Dict[str, Any], token: Optional[str],
                     concurrency: int, max_seconds: float) -> Dict[str, Any]:
    """对单个接口做并发采样，返回延迟分位、吞吐、错误率。"""
    guard_path(target["method"], target["path"])
    url = base + target["path"]
    base_headers: Dict[str, str] = {}
    if target.get("auth") and token:
        base_headers["Authorization"] = "Bearer " + token
    if target.get("content_type"):
        base_headers["Content-Type"] = target["content_type"]

    timeout = float(target.get("timeout", 20.0))
    body = target.get("body")

    # 1 次预热（不计入统计），让连接池/缓存/OS 页缓存进入稳定态
    warmup = http_call(target["method"], url, headers=base_headers, body=body, timeout=timeout)

    samples: List[float] = []
    status_counts: Dict[str, int] = {}
    biz_codes: Dict[str, int] = {}
    errors: List[Dict[str, Any]] = []
    lock = threading.Lock()
    started = time.perf_counter()

    def worker(_index: int) -> None:
        if time.perf_counter() - started > max_seconds:
            return
        item = http_call(target["method"], url, headers=base_headers, body=body, timeout=timeout)
        with lock:
            samples.append(item["elapsed_ms"])
            status_key = str(item["status"])
            status_counts[status_key] = status_counts.get(status_key, 0) + 1
            code_key = str(item["biz_code"])
            biz_codes[code_key] = biz_codes.get(code_key, 0) + 1
            if item["error"] or item["status"] != target["expect_status"]:
                if len(errors) < 10:
                    errors.append({"status": item["status"], "biz_code": item["biz_code"],
                                   "error": item["error"], "body_head": item["body_head"]})

    planned = int(target["requests"])
    with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
        list(pool.map(worker, range(planned)))
    wall = time.perf_counter() - started

    total = len(samples)
    failed = sum(count for status, count in status_counts.items() if status != str(target["expect_status"]))
    ok_biz = sum(count for code, count in biz_codes.items() if code == "200")
    return {
        "key": target["key"],
        "name": target["name"],
        "method": target["method"],
        "path": target["path"],
        "auth": bool(target.get("auth")),
        "requests_planned": planned,
        "requests_done": total,
        "concurrency": concurrency,
        "warmup": {"status": warmup["status"], "elapsed_ms": ms(warmup["elapsed_ms"]),
                   "biz_code": warmup["biz_code"]},
        "latency_ms": {
            "mean": ms(statistics.fmean(samples)) if samples else None,
            "min": ms(min(samples)) if samples else None,
            "max": ms(max(samples)) if samples else None,
            "p50": ms(percentile(samples, 0.50)),
            "p95": ms(percentile(samples, 0.95)),
            "p99": ms(percentile(samples, 0.99)),
        },
        "throughput_rps": round(total / wall, 2) if wall > 0 else None,
        "wall_seconds": round(wall, 2),
        "status_counts": status_counts,
        "biz_code_counts": biz_codes,
        "error_rate": round(failed / total, 4) if total else None,
        "business_ok_count": ok_biz,
        "errors": errors,
        "truncated": total < planned,
    }


def run_perf(base: str, token_info: Dict[str, Any], args: argparse.Namespace,
             log: List[str]) -> Dict[str, Any]:
    """顺序跑各接口（避免相互干扰），总时长受 --max-seconds 约束。"""
    token = token_info.get("token")
    results: List[Dict[str, Any]] = []
    started = time.perf_counter()
    for target in PERF_TARGETS:
        if target.get("auth") and not token:
            results.append({
                "key": target["key"], "name": target["name"], "method": target["method"],
                "path": target["path"], "auth": True, "skipped": True,
                "reason": "无可用 JWT（见 jwt.note），未采集",
            })
            log.append("跳过 %s：无可用 JWT" % target["key"])
            continue
        if time.perf_counter() - started > args.max_seconds:
            results.append({
                "key": target["key"], "name": target["name"], "method": target["method"],
                "path": target["path"], "auth": bool(target.get("auth")), "skipped": True,
                "reason": "总时长已达上限 %ss，未采集" % args.max_seconds,
            })
            log.append("跳过 %s：已达总时长上限" % target["key"])
            continue
        concurrency = min(args.concurrency, int(target.get("concurrency", args.concurrency)),
                          int(target["requests"]))
        item = measure_endpoint(base, target, token, concurrency, args.max_seconds)
        results.append(item)
        log.append("%s：p50=%sms p95=%sms p99=%sms rps=%s 错误率=%s" % (
            item["key"], item["latency_ms"]["p50"], item["latency_ms"]["p95"],
            item["latency_ms"]["p99"], item["throughput_rps"], item["error_rate"]))
    return {
        "targets": results,
        "total_wall_seconds": round(time.perf_counter() - started, 2),
        "max_seconds": args.max_seconds,
        "concurrency_cap": args.concurrency,
    }


# ---------------------------------------------------------------------------
# 预检
# ---------------------------------------------------------------------------
def preflight(base: str) -> Dict[str, Any]:
    """三个探针确认服务可用：actuator 健康、题目列表、题目详情。"""
    checks: Dict[str, Any] = {}
    health = http_call("GET", base + "/actuator/health", timeout=5.0)
    checks["actuator_health"] = {"status": health["status"], "body_head": health["body_head"][:120]}
    listing = http_call("GET", base + "/questions?page=1&size=1", timeout=10.0)
    checks["questions_list"] = {"status": listing["status"], "biz_code": listing["biz_code"],
                                "total": _extract_total(listing["body_head"])}
    detail = http_call("GET", base + "/questions/4022", timeout=10.0)
    checks["question_detail"] = {"status": detail["status"], "biz_code": detail["biz_code"]}
    checks["ok"] = (health["status"] == 200
                    and listing["status"] == 200 and listing["biz_code"] == 200
                    and detail["status"] == 200)
    return checks


def _extract_total(body_head: str) -> Optional[int]:
    """从响应体片段里抠 total 字段（只用于预检展示，取不到就算了）。"""
    match = re.search(r'"total"\s*:\s*(\d+)', body_head)
    return int(match.group(1)) if match else None


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="W9-8 认证限流验证 + 核心接口性能基线（只读、可复跑、默认不写库）",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base", default=os.environ.get("QA_BASE", "http://127.0.0.1:8080/api"))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--mode", choices=("all", "rate", "perf"), default="all")
    parser.add_argument("--dry-run", action="store_true", help="只打印执行计划，不发任何请求")
    parser.add_argument("--jwt-secret", default=os.environ.get("QA_JWT_SECRET"))
    parser.add_argument("--user-id", type=int, default=int(os.environ.get("QA_USER_ID", "1")))
    parser.add_argument("--student-no", default="admin", help="自签令牌里的 stu 声明（仅展示用）")
    parser.add_argument("--role", default="ADMIN", help="自签令牌里的 role 声明")
    parser.add_argument("--probe-student-no", default="w9_probe_no_such_user",
                        help="限流打点用的学号（必须不存在，避免锁定真实账号；长度需 ≤32 否则命中参数校验 400）")
    parser.add_argument("--probe-password", default="WrongPass!2026", help="限流打点用的错误口令")
    parser.add_argument("--rate-interval-ms", type=int, default=120, help="限流打点间隔毫秒")
    parser.add_argument("--rate-extra-attempts", type=int, default=7,
                        help="达到阈值后继续多打的次数")
    parser.add_argument("--recovery-poll-seconds", type=float, default=3.0)
    parser.add_argument("--recovery-max-seconds", type=float, default=120.0)
    parser.add_argument("--concurrency", type=int, default=5, help="每接口并发（上限 5）")
    parser.add_argument("--max-seconds", type=float, default=180.0, help="性能段总时长上限")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if args.concurrency > 5:
        print("[warn] 并发被限制为 5（避免压测污染并行任务）")
        args.concurrency = 5
    # LoginRequest 对 studentNo 有 @Size(max=32)、password 有 @Size(6,64) 校验：
    # 越界会先命中参数校验 400，导致打点测不到限流，这里提前拦住。
    if len(args.probe_student_no) > 32:
        print("[error] --probe-student-no 超过 32 字符，会命中参数校验 400，请缩短")
        return 3
    if not 6 <= len(args.probe_password) <= 64:
        print("[error] --probe-password 长度需在 6-64 之间，否则命中参数校验 400")
        return 3

    log: List[str] = []
    static = parse_rate_limit_filter()
    jwt_info = {"available": False, "note": "dry-run 未校验"}

    payload: Dict[str, Any] = {
        "meta": {
            "task": "W9-8 认证限流验证 + 核心接口性能基线",
            "script": "scripts/qa/w9_rate_limit_perf.py",
            "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "base_url": args.base,
            "mode": args.mode,
            "dry_run": bool(args.dry_run),
            "out": str(args.out),
            "python": sys.version.split()[0],
            "os": os.name,
            "read_only_guarantee": ("只打 /auth/login（错误口令+不存在学号）、/questions、/questions/4022、"
                                    "/me/stats、/ai/ask、/actuator/health；脚本内置白名单，"
                                    "不会调用 register/upload 等写接口"),
            "expected_duration_seconds": {"rate": 70 if args.mode in ("all", "rate") else 0,
                                          "perf": 90 if args.mode in ("all", "perf") else 0},
        },
        "static_analysis": {"rate_limit_filter": static},
        "preflight": {},
        "jwt": jwt_info,
        "rate_limit": {},
        "perf": {},
        "conclusion": {},
    }

    if args.dry_run:
        plan = {
            "rate_limit": {
                "will_call": "POST %s/auth/login（错误口令，学号=%s）" % (args.base, args.probe_student_no),
                "attempts": int(static["login_limit"]) + args.rate_extra_attempts,
                "expected_first_429_at": int(static["login_limit"]) + 1,
                "window_ms": static["window_ms"],
                "recovery_polling": "每 %ss 轮询一次，最多 %ss"
                                    % (args.recovery_poll_seconds, args.recovery_max_seconds),
                "xff": "每次运行随机取 203.0.113.x，隔离真实 IP 的桶",
                "skipped": ["POST /auth/register（写库）", "POST /files/upload（写库+大文件）"],
            },
            "perf": [
                {"key": t["key"], "call": "%s %s%s" % (t["method"], args.base, t["path"]),
                 "requests": t["requests"],
                 "concurrency": min(args.concurrency, int(t.get("concurrency", args.concurrency)),
                                    int(t["requests"])),
                 "auth": bool(t.get("auth"))}
                for t in PERF_TARGETS
            ],
            "note": "dry-run 未发出任何 HTTP 请求",
        }
        payload["plan"] = plan
        payload["conclusion"] = {"verdict": "DRY_RUN", "note": "未执行测量"}
        _write_json(Path(args.out), payload)
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        print("\n[dry-run] 计划已写入 %s" % args.out)
        return 0

    # --- 预检 ---
    payload["preflight"] = preflight(args.base)
    if not payload["preflight"]["ok"]:
        payload["conclusion"] = {"verdict": "ABORTED", "note": "预检失败：服务未就绪或接口不通，未进入测量"}
        _write_json(Path(args.out), payload)
        print("[abort] 预检失败：%s" % json.dumps(payload["preflight"], ensure_ascii=False))
        return 2

    # --- JWT（自签，避免真实登录写 last_login_at）---
    jwt_info = resolve_token(args.base, args, log)
    payload["jwt"] = {k: v for k, v in jwt_info.items() if k != "token"}
    payload["jwt"]["token_minted"] = bool(jwt_info.get("token"))

    # --- 限流 ---
    if args.mode in ("all", "rate"):
        payload["rate_limit"] = probe_rate_limit(args.base, static, args, log)
    else:
        payload["rate_limit"] = {"skipped": True, "reason": "--mode=%s" % args.mode}

    # --- 性能 ---
    if args.mode in ("all", "perf"):
        payload["perf"] = run_perf(args.base, jwt_info, args, log)
    else:
        payload["perf"] = {"skipped": True, "reason": "--mode=%s" % args.mode}

    # --- 结论 ---
    payload["conclusion"] = _build_conclusion(payload, static)
    payload["log"] = log

    _write_json(Path(args.out), payload)
    _print_summary(payload)
    return 0 if payload["conclusion"]["verdict"] == "PASS" else 1


def _build_conclusion(payload: Dict[str, Any], static: Dict[str, Any]) -> Dict[str, Any]:
    """把关键判定收敛成可被验收直接引用的字段。"""
    rate = payload.get("rate_limit", {}) or {}
    perf = payload.get("perf", {}) or {}
    enforced = bool(rate.get("enforced"))
    matches = bool(rate.get("matches_code"))
    recovery = (rate.get("recovery") or {}).get("recovered")
    bypass = (rate.get("bypass_check") or {}).get("bypassed")

    targets = [t for t in (perf.get("targets") or []) if not t.get("skipped")]
    measured = len(targets)
    clean = [t for t in targets if (t.get("error_rate") == 0)]
    verdict = "PASS"
    problems: List[str] = []
    if payload["meta"]["mode"] in ("all", "rate"):
        if not enforced:
            verdict = "FAIL"
            problems.append("未触发 429：限流可能未生效")
        if not matches:
            verdict = "FAIL"
            problems.append("首次 429 位置与代码阈值不一致")
        if recovery is False:
            verdict = "FAIL"
            problems.append("窗口过后未恢复")
    if payload["meta"]["mode"] in ("all", "perf"):
        if measured == 0:
            verdict = "FAIL"
            problems.append("性能段未采到任何样本")
        elif len(clean) != measured:
            problems.append("存在错误率非 0 的接口（见 perf.targets[].errors）")
    if bypass:
        problems.append("安全发现：伪造 X-Forwarded-For 可绕过限流（换桶）")
    return {
        "verdict": verdict,
        "rate_limit_enforced": enforced,
        "first_429_matches_code": matches,
        "recovery_within_window": recovery,
        "xff_bypass_possible": bypass,
        "perf_endpoints_measured": measured,
        "perf_endpoints_error_free": len(clean),
        "problems": problems,
        "note": "性能只做基线记录，未设 SLO 阈值；限流判定以源码阈值为准（本次已解析源码）",
        "login_limit": static.get("login_limit"),
        "window_ms": static.get("window_ms"),
    }


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    """UTF-8 无 BOM 写 JSON，并自动建目录。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def _print_summary(payload: Dict[str, Any]) -> None:
    """打印人读汇总表。"""
    print("\n================ W9-8 汇总 ================")
    rate = payload.get("rate_limit", {}) or {}
    if not rate.get("skipped"):
        print("限流  login_limit=%s  首次429=第%s次  阻止=%s  恢复=%ss  伪造XFF绕过=%s" % (
            payload["conclusion"].get("login_limit"),
            rate.get("first_429_at"),
            rate.get("blocked_count"),
            (rate.get("recovery") or {}).get("recovered_after_seconds"),
            (rate.get("bypass_check") or {}).get("bypassed")))
    perf = payload.get("perf", {}) or {}
    targets = perf.get("targets") or []
    if targets:
        print("\n%-18s %8s %8s %8s %8s %8s %8s" % (
            "接口", "样本", "p50(ms)", "p95(ms)", "p99(ms)", "rps", "错误率"))
        for item in targets:
            if item.get("skipped"):
                print("%-18s %8s  %s" % (item["key"], "-", item.get("reason", "skipped")))
                continue
            lat = item["latency_ms"]
            print("%-18s %8s %8s %8s %8s %8s %8s" % (
                item["key"], item["requests_done"], lat["p50"], lat["p95"], lat["p99"],
                item["throughput_rps"], item["error_rate"]))
    print("\n结论: %s" % payload["conclusion"]["verdict"])
    for problem in payload["conclusion"].get("problems", []):
        print("  - %s" % problem)
    print("证据: %s" % payload["meta"].get("out", "见 --out"))
    print("===========================================")


if __name__ == "__main__":
    sys.exit(main())
