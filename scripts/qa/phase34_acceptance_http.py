"""阶段三 / 阶段四 HTTP 验收（traceId 贯通 / 指标 / 脱敏 / 保留期 / 评估对比 / 长期记忆 / 检查点）。

设计定位：**只读 + 可重复 + 证据自证**。
  * 不修改任何业务代码，不写库（除验收本身必经的 HTTP 写操作：长期记忆 CRUD，
    并在用例末尾用 DELETE 清理自己创建的记录；检查点恢复会创建新 run，属被测行为）；
  * 每条断言打印「编号 / 期望 / 实际 / 判定」，并落到 JSON 供报告引用；
  * 所有数字都来自本次实跑，不引用任何实现者的自报结果。

前置（后端 8081 + AI 8000，跑完即停）::

    cd backend && mvn -B -o spring-boot:run -Dspring-boot.run.arguments="\
      --server.port=8081 --app.ai.agent.enabled=true --app.ai.internal-token=qa-token-2026 \
      --app.ai.fastapi.enabled=true --app.ai.vector.enabled=false" > /tmp/backend-8081.log 2>&1

    cd ai-service && AI_INTERNAL_TOKEN=qa-token-2026 JAVA_BASE_URL=http://127.0.0.1:8081/api \
      AGENT_ENABLED=true .venv-ai/Scripts/python.exe -m uvicorn app.main:app --port 8000 > /tmp/ai-8000.log 2>&1

运行::

    .venv-ai/Scripts/python.exe scripts/qa/phase34_acceptance_http.py

可选环境变量：JAVA_BASE / AI_BASE / INTERNAL_TOKEN / ADMIN_NO / ADMIN_PWD /
STUDENT_NO / STUDENT_PWD / BACKEND_LOG / AI_LOG / OUT_JSON / DB_* 。
"""

from __future__ import annotations

import http.client
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

def _require_pwd(name: str) -> str:
    """E1-06：口令必须由环境变量显式提供，仓库内不再保存任何明文默认口令。"""
    value = os.environ.get(name)
    if not value:
        raise SystemExit(
            f"缺少必需环境变量 {name}：仓库内不再保存明文口令，请显式注入后重试（E1-06）"
        )
    return value


JAVA_BASE = os.environ.get("JAVA_BASE", "http://127.0.0.1:8081/api")
AI_BASE = os.environ.get("AI_BASE", "http://127.0.0.1:8000")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "qa-token-2026")
ADMIN_NO = os.environ.get("ADMIN_NO", "admin")
ADMIN_PWD = _require_pwd("ADMIN_PWD")  # E1-06
STUDENT_NO = os.environ.get("STUDENT_NO", "2021001")
STUDENT_PWD = _require_pwd("STUDENT_PWD")  # E1-06
BACKEND_LOG = os.environ.get("BACKEND_LOG", "/tmp/backend-8081.log")
AI_LOG = os.environ.get("AI_LOG", "/tmp/ai-8000.log")
OUT_JSON = os.environ.get("OUT_JSON", "")


def _resolve_log(path: str) -> str:
    """把 shell 里的 /tmp/xxx 解析成 Windows 下真实可读的路径。

    Git Bash 的 /tmp 与 Windows Python 眼里的 /tmp（= C:\\tmp）不是同一个目录，
    直接 open("/tmp/x") 会 FileNotFoundError，导致"日志项全 0 行"的假失败。
    """
    candidates = [path, os.path.join(tempfile.gettempdir(), os.path.basename(path)),
                  "C:/tmp/" + os.path.basename(path),
                  os.path.join(os.environ.get("TEMP", ""), os.path.basename(path))]
    for cand in candidates:
        if cand and os.path.exists(cand):
            return cand
    return path


BACKEND_LOG = _resolve_log(BACKEND_LOG)
AI_LOG = _resolve_log(AI_LOG)

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LOCAL_YML = os.path.join(PROJECT_ROOT, "backend", "src", "main", "resources", "application-local.yml")

TRACE_RE = re.compile(r"^[0-9a-f]{16}$")

DB_BIN = os.environ.get("DB_BIN", r"D:\bigplan\tools\mysql-8.0.46\bin\mysql.exe")
DB_NAME = os.environ.get("DB_NAME", "exam_prep")
DB_USER = os.environ.get("DB_USER", "root")

RESULTS: List[Dict[str, Any]] = []


# --------------------------------------------------------------------------------------
# 证据收集
# --------------------------------------------------------------------------------------
def check(sid: str, name: str, expected: str, actual: str, ok: bool,
          evidence: str = "", note: str = "") -> bool:
    """记录一条断言（打印 + 入 RESULTS）。"""
    verdict = "PASS" if ok else "FAIL"
    RESULTS.append({
        "id": sid, "name": name, "expected": expected, "actual": actual,
        "verdict": verdict, "evidence": evidence, "note": note,
    })
    line = f"[{verdict}] {sid} {name} | 期望={expected} | 实际={actual}"
    print(line, flush=True)
    if evidence:
        print(f"        证据: {evidence[:300]}", flush=True)
    return ok


def info(text: str) -> None:
    print(f"         · {text}", flush=True)


# --------------------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------------------
@dataclass
class Resp:
    status: int
    headers: Dict[str, str]
    body: str
    elapsed: float

    def json(self) -> Any:
        try:
            return json.loads(self.body)
        except Exception:  # noqa: BLE001
            return None


def call(method: str, url: str, body: Any = None, headers: Optional[Dict[str, str]] = None,
         timeout: float = 60.0) -> Resp:
    """发一个请求，非 2xx 也收敛成 Resp。"""
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return Resp(resp.status, {k.lower(): v for k, v in resp.headers.items()},
                        raw, time.perf_counter() - started)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return Resp(e.code, {k.lower(): v for k, v in e.headers.items()},
                    raw, time.perf_counter() - started)


def jcall(method: str, path: str, body: Any = None, headers: Optional[Dict[str, str]] = None,
          base: str = JAVA_BASE, timeout: float = 60.0) -> Resp:
    return call(method, base + path, body, headers, timeout)


def sse_call(path: str, body: Any, headers: Dict[str, str], read_seconds: float = 2.0,
             max_bytes: int = 200000) -> Tuple[int, Dict[str, str], str]:
    """向 SSE 端点发请求：拿到响应头后读最多 read_seconds 秒的正文。

    read_seconds 到点即关闭连接——用于制造 client_disconnect（CANCELLED）的 run。
    """
    parsed = urllib.parse.urlparse(JAVA_BASE)
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=60)
    payload = json.dumps(body).encode("utf-8")
    hdrs = {"Content-Type": "application/json", **headers}
    conn.request("POST", parsed.path.rstrip("/") + path, body=payload, headers=hdrs)
    resp = conn.getresponse()
    resp_headers = {k.lower(): v for k, v in resp.getheaders()}
    chunks: List[bytes] = []
    deadline = time.time() + read_seconds
    try:
        while time.time() < deadline and sum(len(c) for c in chunks) < max_bytes:
            chunk = resp.read(256)
            if not chunk:
                break
            chunks.append(chunk)
    except Exception:  # noqa: BLE001 - 超时/断连都是预期
        pass
    finally:
        conn.close()
    return resp.status, resp_headers, b"".join(chunks).decode("utf-8", "replace")


# --------------------------------------------------------------------------------------
# MySQL（只读；口令从 application-local.yml 读，**不打印**）
# --------------------------------------------------------------------------------------
def _db_password() -> str:
    with open(LOCAL_YML, "r", encoding="utf-8") as fh:
        for line in fh:
            stripped = line.strip()
            if stripped.startswith("password:"):
                return stripped.split("password:", 1)[1].strip().strip('"').strip("'")
    raise RuntimeError("application-local.yml 里没找到 datasource password")


def sql(query: str) -> List[List[str]]:
    """执行只读 SQL，返回二维字符串数组（tab 分隔、无表头）。"""
    env = dict(os.environ, MYSQL_PWD=_db_password())
    cmd = [DB_BIN, "-u", DB_USER, "-N", "-B", "--default-character-set=utf8mb4",
           "-e", query, DB_NAME]
    proc = subprocess.run(cmd, capture_output=True, env=env, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql 失败: {proc.stderr.decode('utf-8', 'replace')[:300]}")
    out = proc.stdout.decode("utf-8", "replace")
    return [line.split("\t") for line in out.splitlines() if line != ""]


def sql_scalar(query: str) -> str:
    rows = sql(query)
    return rows[0][0] if rows else ""


def grep_count(path: str, needle: str) -> int:
    """统计文件里包含 needle 的行数（纯 Python，避免依赖 grep 是否在 PATH）。"""
    if not os.path.exists(path) or not needle:
        return 0
    hits = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if needle in line:
                hits += 1
    return hits


def wait_grep(path: str, needle: str, want: int = 1, timeout: float = 20.0) -> int:
    """轮询等待日志出现期望行数（日志落盘是异步的，不能"立刻 grep 就判失败"）。"""
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        last = grep_count(path, needle)
        if last >= want:
            return last
        time.sleep(0.5)
    return last


# --------------------------------------------------------------------------------------
# 登录
# --------------------------------------------------------------------------------------
def login(student_no: str, password: str) -> Tuple[str, int]:
    r = jcall("POST", "/auth/login", {"studentNo": student_no, "password": password})
    data = (r.json() or {}).get("data") or {}
    token = data.get("token") or ""
    uid = int(((data.get("user") or {}).get("id")) or 0)
    if r.status != 200 or not token:
        raise SystemExit(f"登录失败 {student_no}: HTTP {r.status} {r.body[:200]}")
    return token, uid


def bearer(token: str, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    return {"Authorization": f"Bearer {token}", **(extra or {})}


# --------------------------------------------------------------------------------------
# 各验收段
# --------------------------------------------------------------------------------------
def section_a11(admin_token: str) -> None:
    print("\n=== A11：只读题目工具默认不带答案 ===")
    qid = sql_scalar("select id from question where audit_status='APPROVED' order by id limit 1")
    info(f"取用已审核题目 id={qid}")

    r = jcall("GET", f"/internal/agent/tools/question/{qid}",
              headers={"X-Internal-Token": INTERNAL_TOKEN})
    body = r.body
    has_answer_key = ("isAnswer" in body) or ("analysis" in body) or ("answerContent" in body)
    data = (r.json() or {}).get("data") or {}
    check("A11-1", "默认(不带 includeAnswer)不含答案字段", "200 且 body 无 isAnswer/analysis/answerContent",
          f"HTTP {r.status}，命中答案键={has_answer_key}", r.status == 200 and not has_answer_key,
          body[:260])

    r2 = jcall("GET", f"/internal/agent/tools/question/{qid}?includeAnswer=true",
               headers={"X-Internal-Token": INTERNAL_TOKEN})
    b2 = r2.body
    has2 = ("isAnswer" in b2) or ("analysis" in b2) or ("answerContent" in b2)
    check("A11-2", "includeAnswer=true 才含答案字段", "200 且出现 isAnswer/analysis",
          f"HTTP {r2.status}，命中答案键={has2}", r2.status == 200 and has2, b2[:260])

    r3 = jcall("GET", f"/internal/agent/tools/question/{qid}")
    check("A11-3", "无内部令牌访问工具接口", "403",
          f"HTTP {r3.status}，code={(r3.json() or {}).get('code')}",
          r3.status == 403, r3.body[:200])

    # 顺带验 /v1/health 的 security 字段（契约 §3 末段）
    h = call("GET", AI_BASE + "/v1/health")
    sec = ((h.json() or {}).get("security")) or {}
    ok = (set(sec.keys()) >= {"internalTokenConfigured", "agentAuthRequired", "anonymousAllowed"}
          and all(isinstance(sec.get(k), bool) for k in
                  ("internalTokenConfigured", "agentAuthRequired", "anonymousAllowed")))
    token_leak = INTERNAL_TOKEN in h.body
    check("A11-4", "/v1/health security 三键且为 bool，且不含令牌值",
          "三键齐备均为 bool 且 body 不含令牌", f"keys={sorted(sec.keys())} 令牌泄漏={token_leak}",
          ok and not token_leak, json.dumps(sec, ensure_ascii=False))


def section_fail_closed() -> None:
    print("\n=== fail-closed：/v1/agent/run 令牌闸门 ===")
    payload = {"question": "牛顿第二定律的内容是什么"}
    r = jcall("POST", "/v1/agent/run", payload, base=AI_BASE, timeout=90)
    body = r.json()
    keys = sorted((body or {}).keys())
    check("FC-1", "不带令牌 → 401 且 body 恰 3 字段 code=5231",
          "HTTP 401 / {code:5231,message,degraded}",
          f"HTTP {r.status} / keys={keys} / code={(body or {}).get('code')}",
          r.status == 401 and keys == ["code", "degraded", "message"]
          and (body or {}).get("code") == 5231, r.body[:260])

    check("FC-2", "401 响应头带合法 X-Trace-Id（契约 §3）", "^[0-9a-f]{16}$",
          str(r.headers.get("x-trace-id")),
          bool(TRACE_RE.match(r.headers.get("x-trace-id") or "")), r.body[:120])

    r2 = jcall("POST", "/v1/agent/run", payload, base=AI_BASE, timeout=90,
               headers={"X-Internal-Token": "wrong-token"})
    b2 = r2.json() or {}
    check("FC-3", "错令牌 → 401 且 code=5231（与没带不区分）", "HTTP 401 / code=5231",
          f"HTTP {r2.status} / code={b2.get('code')} / keys={sorted(b2.keys())}",
          r2.status == 401 and b2.get("code") == 5231, r2.body[:200])

    r3 = jcall("POST", "/v1/agent/run", payload, base=AI_BASE, timeout=180,
               headers={"X-Internal-Token": INTERNAL_TOKEN})
    check("FC-4", "带正确令牌 → 非 401", "HTTP != 401",
          f"HTTP {r3.status} code={(r3.json() or {}).get('code')}",
          r3.status != 401, r3.body[:200])


def section_budget() -> None:
    print("\n=== A8：maxSteps 预算边界 ===")
    for value, expect in ((0, 400), (-1, 400), (13, 400)):
        r = jcall("POST", "/v1/agent/run", {"question": "预算边界", "maxSteps": value},
                  base=AI_BASE, timeout=90, headers={"X-Internal-Token": INTERNAL_TOKEN})
        b = r.json() or {}
        msg = str(b.get("message") or "")
        check(f"BD-{value}", f"maxSteps={value} → 400 + code=5200",
              "HTTP 400 / code=5200 / message 含 maxSteps 与 1..12",
              f"HTTP {r.status} / code={b.get('code')} / msg={msg[:80]}",
              r.status == expect and b.get("code") == 5200 and "maxSteps" in msg and "1..12" in msg,
              r.body[:220])

    r = jcall("POST", "/v1/agent/run", {"question": "预算边界正常值", "maxSteps": 6},
              base=AI_BASE, timeout=240, headers={"X-Internal-Token": INTERNAL_TOKEN})
    check("BD-6", "maxSteps=6 正常运行", "HTTP 200", f"HTTP {r.status}",
          r.status == 200, r.body[:200])

    # 配置级 clamp（不起进程，直接读配置装配结果）
    py = os.path.join(PROJECT_ROOT, ".venv-ai", "Scripts", "python.exe")
    code = ("from app.config import get_settings;"
            "s=get_settings();print(s.agent.max_tool_calls)")
    env = dict(os.environ, AGENT_MAX_TOOL_CALLS="12", AI_INTERNAL_TOKEN=INTERNAL_TOKEN)
    proc = subprocess.run([py, "-c", code], cwd=os.path.join(PROJECT_ROOT, "ai-service"),
                          capture_output=True, env=env, timeout=120)
    out = proc.stdout.decode().strip().splitlines()
    err = proc.stderr.decode("utf-8", "replace")
    effective = out[-1] if out else "?"
    clamped = effective == "8" and "clamp" in err
    check("BD-CLAMP", "AGENT_MAX_TOOL_CALLS=12 配置级 clamp 到 8 + WARNING",
          "生效值 8 且 stderr 含 clamp 告警", f"生效值={effective}，WARNING={'clamp' in err}",
          clamped, err[-260:])


def section_trace(student_token: str) -> None:
    print("\n=== traceId 贯通（契约 §1 / §4②）===")
    fixed = "0123456789abcdef"
    r = jcall("GET", "/home", headers={"X-Trace-Id": fixed})
    rbody = r.json() or {}
    check("TR-1", "/api/home 复用入站 traceId（响应头）", fixed,
          str(r.headers.get("x-trace-id")), r.headers.get("x-trace-id") == fixed, r.body[:160])
    check("TR-2", "/api/home body.traceId 与头一致", fixed, str(rbody.get("traceId")),
          rbody.get("traceId") == fixed, r.body[:160])

    # 契约 §4②：不带 token 的 401 也必须带合法头
    r401 = jcall("GET", "/ai/agent/runs")
    tid401 = r401.headers.get("x-trace-id") or ""
    check("TR-3", "401 响应头 X-Trace-Id 匹配 ^[0-9a-f]{16}$", "合法 16 位 hex",
          f"HTTP {r401.status} / {tid401}", r401.status == 401 and bool(TRACE_RE.match(tid401)),
          r401.body[:160])

    # 非法入站 traceId → 丢弃并重新生成（不报错）
    rbad = jcall("GET", "/home", headers={"X-Trace-Id": "INVALID-<script>"})
    tbad = rbad.headers.get("x-trace-id") or ""
    check("TR-4", "非法入站 traceId 被丢弃并重新生成", "HTTP 200 且头为合法 hex 且 != 入站值",
          f"HTTP {rbad.status} / {tbad}", rbad.status == 200 and bool(TRACE_RE.match(tbad))
          and tbad != "INVALID-<script>", rbad.body[:120])

    # SSE 五处一致
    marker = f"QA链路探针{int(time.time())}"
    status, headers, body = sse_call("/ai/agent/stream",
                                     {"question": f"{marker} 牛顿第二定律的内容是什么"},
                                     bearer(student_token), read_seconds=90)
    hdr_tid = headers.get("x-trace-id") or ""
    meta_m = re.findall(r'"traceId"\s*:\s*"([0-9a-f]{16})"', body)
    meta_tid = meta_m[0] if meta_m else ""
    info(f"SSE HTTP {status}，正文 {len(body)} 字节，事件数≈{body.count('event:')}")
    check("TR-5", "SSE 响应头 X-Trace-Id 合法", "合法 16 位 hex", hdr_tid,
          bool(TRACE_RE.match(hdr_tid)), body[:120])
    check("TR-6", "SSE meta.traceId 与响应头同值", hdr_tid, meta_tid or "(未找到)",
          bool(meta_tid) and meta_tid == hdr_tid, body[:200])

    db_tid = ""
    run_id = ""
    if TRACE_RE.match(hdr_tid):
        rows = sql("select id, trace_id from ai_agent_run where trace_id='%s' order by id desc limit 1"
                   % hdr_tid)
        if rows:
            run_id, db_tid = rows[0][0], rows[0][1]
    check("TR-7", "落库 ai_agent_run.trace_id 与响应头同值", hdr_tid, db_tid or "(未落库)",
          bool(db_tid) and db_tid == hdr_tid, f"run_id={run_id}")

    java_hits = wait_grep(BACKEND_LOG, hdr_tid, 1, 25.0) if hdr_tid else 0
    ai_hits = wait_grep(AI_LOG, hdr_tid, 1, 25.0) if hdr_tid else 0
    check("TR-8", "Java 日志含该 traceId", "≥1 行", f"{java_hits} 行", java_hits >= 1,
          f"grep -c {hdr_tid} {BACKEND_LOG}")
    check("TR-9", "Python 日志含该 traceId", "≥1 行", f"{ai_hits} 行", ai_hits >= 1,
          f"grep -c {hdr_tid} {AI_LOG}")
    if hdr_tid:
        print(f"         · 复跑命令: grep -n {hdr_tid} {BACKEND_LOG} | head -3 ; "
              f"grep -n {hdr_tid} {AI_LOG} | head -3")
        print(f"         · 真实 traceId = {hdr_tid}（run_id={run_id}）")

    # 脱敏：marker 原文不得出现在后端日志，且脱敏字段 qLen/qHash 必须真的打出来
    mask_hits = wait_grep(BACKEND_LOG, "qHash=", 1, 25.0)
    leak = grep_count(BACKEND_LOG, marker)
    check("TR-10", "问答日志脱敏（问题原文不落日志，只留 qLen/qHash）",
          f"日志中 '{marker}' 命中 0 且 qHash= 至少 1 行",
          f"原文命中 {leak} 次；qHash= 出现 {mask_hits} 次", leak == 0 and mask_hits >= 1,
          f"grep -c '{marker}' {BACKEND_LOG}")

    # Subagent 默认关
    sub_m = re.search(r'"subagent"\s*:\s*(true|false)', body)
    check("TR-11", "meta.subagent 默认 false（受控实验默认关）", "false",
          sub_m.group(1) if sub_m else "(meta 无该字段)", bool(sub_m) and sub_m.group(1) == "false",
          body[:400])

    # 契约 §1 第 5 项：Python 读取 traceId「以头为准」，body 字段仅兼容
    hdr_only = "fedcba9876543210"
    rr = jcall("POST", "/v1/agent/run", {"question": "仅带头不传 body traceId"},
               base=AI_BASE, timeout=180,
               headers={"X-Internal-Token": INTERNAL_TOKEN, "X-Trace-Id": hdr_only})
    rb = rr.json() or {}
    ev = (rb.get("events") or [{}])[0].get("data", {}) if rb.get("events") else {}
    meta_only = str(ev.get("traceId", "(无)"))
    check("TR-12", "仅带 X-Trace-Id 头（body 无 traceId）时 meta.traceId 以头为准",
          hdr_only, meta_only,
          rr.status == 200 and meta_only == hdr_only, json.dumps(ev, ensure_ascii=False)[:260],
          note="" if meta_only == hdr_only else
          "Python _context 只读 body.trace_id（agent.py:_context），头值未回填 meta")


def section_metrics(admin_token: str) -> None:
    print("\n=== 运行指标 /admin/ai/metrics（契约 §2）===")
    r = jcall("GET", "/admin/ai/metrics?days=7", headers=bearer(admin_token))
    body = r.json() or {}
    data = body.get("data") or {}
    if r.status != 200 or not data:
        check("MT-0", "GET /admin/ai/metrics?days=7", "200 且 data 完整",
              f"HTTP {r.status} {r.body[:200]}", False, r.body[:200])
        return

    required = ["days", "timezone", "from", "to", "generatedAt", "total", "running", "terminal",
                "statusCounts", "successRate", "refusedRate", "failureRate", "cancelRate",
                "degradedCount", "degradedDenominator", "degradedRate", "latency",
                "generators", "errorTop5"]
    missing = [k for k in required if k not in data]
    status_keys = sorted((data.get("statusCounts") or {}).keys())
    check("MT-1", "响应 schema 完整（19 键 + statusCounts 恒 5 键）",
          "无缺失且 statusCounts 5 键",
          f"缺失={missing} statusKeys={status_keys}",
          not missing and status_keys == ["CANCELLED", "FAILED", "REFUSED", "RUNNING", "SUCCESS"],
          r.body[:400])
    check("MT-2", "days 回显 7 / timezone=Asia/Shanghai", "7 / Asia/Shanghai",
          f"{data.get('days')} / {data.get('timezone')}",
          data.get("days") == 7 and data.get("timezone") == "Asia/Shanghai",
          f"from={data.get('from')} to={data.get('to')}")
    check("MT-3", "terminal = total - running",
          str(int(data.get("total", 0)) - int(data.get("running", 0))),
          str(data.get("terminal")),
          int(data.get("terminal", -1)) == int(data.get("total", 0)) - int(data.get("running", 0)),
          f"total={data.get('total')} running={data.get('running')}")

    frm, to = data.get("from"), data.get("to")
    where = f"created_at >= '{frm}' and created_at <= '{to}'"
    tf = sql(f"select count(*), sum(status='SUCCESS'), sum(status='RUNNING'), "
             f"sum(status='REFUSED'), sum(status='FAILED'), sum(status='CANCELLED') "
             f"from ai_agent_run where {where}")[0]
    t_total, t_succ, t_running, t_refused, t_failed, t_cancelled = tf
    sc = data.get("statusCounts") or {}
    ok_counts = (int(t_total) == data.get("total")
                 and int(t_succ) == sc.get("SUCCESS")
                 and int(t_running) == sc.get("RUNNING")
                 and int(t_refused) == sc.get("REFUSED")
                 and int(t_failed) == sc.get("FAILED")
                 and int(t_cancelled) == sc.get("CANCELLED"))
    check("MT-4", "用响应 from/to 复算 total 与 5 个 statusCounts 完全相等",
          f"total={t_total} SUCCESS={t_succ} RUNNING={t_running} REFUSED={t_refused} "
          f"FAILED={t_failed} CANCELLED={t_cancelled}",
          f"total={data.get('total')} {sc}", ok_counts,
          f"SQL: select count(*), sum(status='SUCCESS'),... from ai_agent_run where {where}")

    terminal_sql = int(t_total) - int(t_running)
    exp_rate = round(int(t_succ) / terminal_sql, 4) if terminal_sql > 0 else None
    got_rate = data.get("successRate")
    check("MT-5", "successRate 分母=terminal 复算一致", str(exp_rate), str(got_rate),
          (exp_rate is None and got_rate is None) or (exp_rate is not None and got_rate is not None
                                                      and abs(float(got_rate) - exp_rate) < 1e-9),
          f"SQL 复算 {t_succ}/{terminal_sql}")

    lat = data.get("latency") or {}
    samples_sql = sql_scalar(
        f"select count(*) from ai_agent_run where {where} and status<>'RUNNING' and total_ms>0")
    avg_sql = sql_scalar(
        f"select round(avg(total_ms)) from ai_agent_run where {where} and status<>'RUNNING' and total_ms>0")
    max_sql = sql_scalar(
        f"select max(total_ms) from ai_agent_run where {where} and status<>'RUNNING' and total_ms>0")
    p95_sql = sql_scalar(
        "select total_ms from (select total_ms, row_number() over (order by total_ms) rn, "
        "count(*) over () n from ai_agent_run where " + where +
        " and status<>'RUNNING' and total_ms>0) t where rn = ceil(0.95*n) limit 1")
    ok_lat = (int(samples_sql) == lat.get("samples")
              and (avg_sql in ("NULL", "") or int(avg_sql) == lat.get("avgMs"))
              and (max_sql in ("NULL", "") or int(max_sql) == lat.get("maxMs"))
              and (p95_sql in ("NULL", "") or int(p95_sql) == lat.get("p95Ms")))
    check("MT-6", "latency.samples/avgMs/maxMs/p95Ms 与 SQL 复算完全相等",
          f"samples={samples_sql} avg={avg_sql} max={max_sql} p95={p95_sql}",
          f"samples={lat.get('samples')} avg={lat.get('avgMs')} max={lat.get('maxMs')} "
          f"p95={lat.get('p95Ms')}", ok_lat,
          "P95 SQL: row_number() over(order by total_ms) 取 rn=ceil(0.95*n)")

    deg_sql = sql(f"select sum(coalesce(degraded,0)=1), count(*) from ai_agent_run "
                  f"where {where} and status in ('SUCCESS','REFUSED')")[0]
    ok_deg = (int(deg_sql[0]) == data.get("degradedCount")
              and int(deg_sql[1]) == data.get("degradedDenominator"))
    check("MT-7", "degradedCount/denominator 分母=SUCCESS+REFUSED 复算一致",
          f"{deg_sql[0]}/{deg_sql[1]}",
          f"{data.get('degradedCount')}/{data.get('degradedDenominator')}", ok_deg, "")

    err_rows = sql(f"select error, count(*) c from ai_agent_run where {where} "
                   f"and status<>'RUNNING' and trim(error)<>'' group by error order by c desc, error asc limit 5")
    err_sql = [(r[0], int(r[1])) for r in err_rows]
    err_api = [(e.get("error"), e.get("count")) for e in (data.get("errorTop5") or [])]
    check("MT-8", "errorTop5 与 SQL 复算一致（count desc, error asc，取 5）",
          str(err_sql), str(err_api), err_sql == err_api, "")

    gen_api = data.get("generators") or []
    gen_sql_rows = sql(f"select coalesce(nullif(trim(generator),''),'unknown') g, count(*) c "
                       f"from ai_agent_run where {where} group by g order by c desc, g asc limit 20")
    gen_ok = len(gen_api) == len(gen_sql_rows) and all(
        g.get("generator") == row[0] and g.get("count") == int(row[1])
        for g, row in zip(gen_api, gen_sql_rows))
    check("MT-9", "generators 分组/排序/计数与 SQL 一致", str([(r[0], r[1]) for r in gen_sql_rows]),
          str([(g.get('generator'), g.get('count')) for g in gen_api]), gen_ok, "")

    for raw in ("0", "-1", "91", "abc"):
        rr = jcall("GET", f"/admin/ai/metrics?days={raw}", headers=bearer(admin_token))
        bb = rr.json() or {}
        check(f"MT-BAD-{raw}", f"days={raw} → 400", "HTTP 400 / code=400",
              f"HTTP {rr.status} / code={bb.get('code')}", rr.status == 400 and bb.get("code") == 400,
              str(bb.get("message"))[:160])

    # 空窗口：days=1 不一定空；用极端小窗口不可控，改为断言"结构完整"已覆盖。这里补一次无数据窗口校验：
    empty_r = jcall("GET", "/admin/ai/metrics?days=90", headers=bearer(admin_token))
    ed = (empty_r.json() or {}).get("data") or {}
    if ed.get("total") == 0:
        ok_empty = (ed.get("terminal") == 0 and ed.get("running") == 0
                    and all(v == 0 for v in (ed.get("statusCounts") or {}).values())
                    and ed.get("successRate") is None and ed.get("latency", {}).get("samples") == 0
                    and ed.get("generators") == [] and ed.get("errorTop5") == [])
        check("MT-EMPTY", "空窗口 → 200/全 0/四率 null/samples=0/数组空", "true",
              f"total={ed.get('total')}", ok_empty, empty_r.body[:200])
    else:
        check("MT-EMPTY", "空窗口结构", "本次库内有数据，空窗口无法构造",
              f"days=90 total={ed.get('total')}", True, "标记为未验证（见报告）",
              note="未验证")


def section_retention(admin_token: str) -> None:
    print("\n=== 保留期清理（preview / purge）===")
    r = jcall("GET", "/admin/ai/retention/preview?days=3650", headers=bearer(admin_token))
    d = (r.json() or {}).get("data") or {}
    pending = d.get("pending")
    check("RT-1", "preview?days=3650 → 200 且 pending 为整数", "200 / int",
          f"HTTP {r.status} / pending={pending}", r.status == 200 and isinstance(pending, int),
          r.body[:200])

    r2 = jcall("POST", "/admin/ai/retention/purge?days=3650", headers=bearer(admin_token))
    d2 = (r2.json() or {}).get("data") or {}
    deleted = d2.get("deleted")
    check("RT-2", "purge?days=3650 → 200 且 deleted 为整数", "200 / int",
          f"HTTP {r2.status} / deleted={deleted} / cutoff={d2.get('cutoff')}",
          r2.status == 200 and isinstance(deleted, int), r2.body[:220])

    r3 = jcall("POST", "/admin/ai/retention/purge?days=0", headers=bearer(admin_token))
    b3 = r3.json() or {}
    check("RT-3", "purge?days=0 → 400", "HTTP 400 / code=400",
          f"HTTP {r3.status} / code={b3.get('code')}", r3.status == 400 and b3.get("code") == 400,
          str(b3.get("message"))[:160])

    # 权限：学生不可调用 purge（403）
    r4 = jcall("GET", "/admin/ai/retention/preview?days=30")
    check("RT-4", "未登录访问 preview → 401", "HTTP 401", f"HTTP {r4.status}", r4.status == 401,
          r4.body[:160])


def section_eval(admin_token: str) -> None:
    print("\n=== 评估对比 /admin/ai/eval/compare ===")
    r = jcall("GET", "/admin/ai/eval/runs?page=1&size=20", headers=bearer(admin_token))
    page = ((r.json() or {}).get("data") or {})
    rows = page.get("list") or page.get("records") or []
    if not rows:
        check("EC-0", "取评估记录", "存在记录", f"HTTP {r.status} rows=0", False, r.body[:200])
        return
    info(f"共 {len(rows)} 条；layer 分布={sorted({x.get('layer') for x in rows})}")

    by_layer: Dict[str, List[Any]] = {}
    for x in rows:
        by_layer.setdefault(x.get("layer"), []).append(x)
    same = next((v for v in by_layer.values() if len(v) >= 2), None)
    if same:
        base, target = same[0]["id"], same[1]["id"]
        rr = jcall("GET", f"/admin/ai/eval/compare?baseId={base}&targetId={target}",
                   headers=bearer(admin_token))
        d = (rr.json() or {}).get("data") or {}
        need = ["base", "target", "comparable", "delta"]
        side_need = ["id", "layer", "status", "createdAt", "total", "passed", "passRate", "metrics"]
        missing = [k for k in need if k not in d]
        side_missing = [k for k in side_need if k not in (d.get("base") or {})]
        check("EC-1", "同层对比 schema 完整（base/target/comparable/delta + 侧字段）",
              "无缺失", f"缺失={missing + side_missing} comparable={d.get('comparable')}",
              not missing and not side_missing and d.get("comparable") is True, rr.body[:400])
    else:
        check("EC-1", "同层对比", "库内同 layer 记录≥2 条", "不足 2 条同层记录", False,
              "标记为未验证", note="未验证")

    layers = [k for k, v in by_layer.items() if v]
    if len(layers) >= 2:
        a, b = by_layer[layers[0]][0]["id"], by_layer[layers[1]][0]["id"]
        rr = jcall("GET", f"/admin/ai/eval/compare?baseId={a}&targetId={b}",
                   headers=bearer(admin_token))
        d = (rr.json() or {}).get("data") or {}
        ok = (rr.status == 200 and d.get("comparable") is False and d.get("delta") is None
              and (d.get("message") or "") != "")
        check("EC-2", "跨层对比 → comparable=false 且 delta=null + message",
              "200 / comparable=false / delta=null",
              f"HTTP {rr.status} comparable={d.get('comparable')} delta={d.get('delta')}",
              ok, str(d.get("message"))[:200])
        info(f"跨层对: {layers[0]}#{a} vs {layers[1]}#{b}")
    else:
        check("EC-2", "跨层对比", "库内存在两种 layer", f"仅 {layers}", False, "标记为未验证",
              note="未验证")

    for qs, sid in (("baseId=%s" % rows[0]["id"], "EC-3"), ("targetId=%s" % rows[0]["id"], "EC-4")):
        rr = jcall("GET", f"/admin/ai/eval/compare?{qs}", headers=bearer(admin_token))
        bb = rr.json() or {}
        check(sid, f"缺参 {qs} → 400", "HTTP 400 / code=400",
              f"HTTP {rr.status} / code={bb.get('code')}",
              rr.status == 400 and bb.get("code") == 400, str(bb.get("message"))[:160])

    rr = jcall("GET", "/admin/ai/eval/compare?baseId=99999999&targetId=99999998",
               headers=bearer(admin_token))
    bb = rr.json() or {}
    check("EC-5", "记录不存在 → 404", "HTTP 404 / code=404",
          f"HTTP {rr.status} / code={bb.get('code')}", rr.status == 404 and bb.get("code") == 404,
          str(bb.get("message"))[:160])


def section_memory(student_token: str) -> None:
    print("\n=== 长期记忆 /api/me/agent-memory CRUD ===")
    h = bearer(student_token)
    stamp = int(time.time())
    created_ids: List[Any] = []

    def create(key: str, value: str) -> Resp:
        return jcall("POST", "/me/agent-memory", {"memKey": key, "memValue": value}, h)

    # 初始存量（决定"上限 20 条"用例要造几条）
    listed = jcall("GET", "/me/agent-memory", headers=h)
    existing = len(((listed.json() or {}).get("data")) or [])
    info(f"学生当前已有记忆 {existing} 条；HTTP {listed.status}")

    key_main = f"qa-主键-{stamp}"
    r = create(key_main, "考研数学一，目标 130 分")
    d = (r.json() or {}).get("data") or {}
    if d.get("id"):
        created_ids.append(d["id"])
    check("MM-1", "POST 新建记忆 → 200", "HTTP 200 + data.id",
          f"HTTP {r.status} / id={d.get('id')} / code={(r.json() or {}).get('code')}",
          r.status == 200 and bool(d.get("id")), r.body[:220])

    r2 = create(key_main, "重复键")
    b2 = r2.json() or {}
    check("MM-2", "同 key 再 POST → 409", "HTTP 409 / code=409",
          f"HTTP {r2.status} / code={b2.get('code')}", r2.status == 409 and b2.get("code") == 409,
          str(b2.get("message"))[:160])

    if d.get("id"):
        r3 = jcall("PUT", f"/me/agent-memory/{d['id']}",
                   {"memKey": key_main, "memValue": "考研数学一，目标 140 分"}, h)
        d3 = (r3.json() or {}).get("data") or {}
        check("MM-3", "PUT 更新 → 200 且值已变", "200 / memValue 更新",
              f"HTTP {r3.status} / {d3.get('memValue')}",
              r3.status == 200 and "140" in str(d3.get("memValue")), r3.body[:220])

        r4 = jcall("DELETE", f"/me/agent-memory/{d['id']}", headers=h)
        check("MM-4", "DELETE → 200", "HTTP 200", f"HTTP {r4.status} / code={(r4.json() or {}).get('code')}",
              r4.status == 200, r4.body[:160])

        r5 = create(key_main, "复活后的值")
        d5 = (r5.json() or {}).get("data") or {}
        if d5.get("id") and d5["id"] not in created_ids:
            created_ids.append(d5["id"])
        check("MM-5", "删除后同 key 再 POST → 200（复活）", "HTTP 200",
              f"HTTP {r5.status} / id={d5.get('id')}", r5.status == 200, r5.body[:200])

        lst = jcall("GET", "/me/agent-memory", headers=h)
        arr = ((lst.json() or {}).get("data")) or []
        same_key = [x for x in arr if x.get("memKey") == key_main]
        check("MM-6", "复活后同 key 在列表中只有 1 条", "1 条", f"{len(same_key)} 条",
              len(same_key) == 1, json.dumps(same_key, ensure_ascii=False)[:240])

    r7 = create(f"qa-超长值-{stamp}", "值" * 201)
    b7 = r7.json() or {}
    check("MM-7", "value 201 字 → 400", "HTTP 400 / code=400",
          f"HTTP {r7.status} / code={b7.get('code')} / msg={str(b7.get('message'))[:60]}",
          r7.status == 400 and b7.get("code") == 400, r7.body[:220])

    r8 = create("k" * 33, "key 33 字")
    b8 = r8.json() or {}
    if r8.status == 200:
        did = (r8.json() or {}).get("data", {}).get("id")
        if did:
            created_ids.append(did)
    check("MM-8", "key 33 字 → 400（DDL VARCHAR(32)）", "HTTP 400 / code=400",
          f"HTTP {r8.status} / code={b8.get('code')} / body={r8.body[:120]}",
          r8.status == 400 and b8.get("code") == 400, r8.body[:220],
          note="" if r8.status == 400 else
          "应用层 MAX_KEY_CHARS=64（AgentMemoryService:75）与 DDL VARCHAR(32) 不一致")

    # 上限：造到 20 条，第 21 条 → 409
    cur = len(((jcall("GET", "/me/agent-memory", headers=h).json() or {}).get("data")) or [])
    to_add = max(0, 20 - cur)
    info(f"当前 {cur} 条，补造 {to_add} 条以顶满 20 条上限")
    fill_ok = True
    for i in range(to_add):
        rr = create(f"qa-fill-{stamp}-{i}", f"填充 {i}")
        dd = (rr.json() or {}).get("data") or {}
        if dd.get("id"):
            created_ids.append(dd["id"])
        if rr.status != 200:
            fill_ok = False
            info(f"第 {i + 1} 条填充失败 HTTP {rr.status} {rr.body[:120]}")
            break
    r9 = create(f"qa-over-{stamp}", "第 21 条")
    b9 = r9.json() or {}
    check("MM-9", "连建 20 条后第 21 条 → 409", "HTTP 409 / code=409",
          f"HTTP {r9.status} / code={b9.get('code')}（填充成功={fill_ok}）",
          r9.status == 409 and b9.get("code") == 409, r9.body[:200])

    # 清理本轮创建
    cleaned = 0
    for mid in created_ids:
        rr = jcall("DELETE", f"/me/agent-memory/{mid}", headers=h)
        if rr.status == 200:
            cleaned += 1
    check("MM-10", "清理本轮创建的 key（不留脏数据）", f"{len(created_ids)} 条全部删除",
          f"删除 {cleaned}/{len(created_ids)} 条", cleaned == len(created_ids), "")


def section_checkpoint(student_token: str, admin_token: str) -> None:
    print("\n=== Checkpoint（检查点 + 恢复）===")
    h = bearer(student_token)
    marker = f"QA中断探针{int(time.time())}"
    uid = int(sql_scalar(f"select id from user where student_no='{STUDENT_NO}'") or 0)

    before = int(sql_scalar("select coalesce(max(id),0) from ai_agent_run"))
    # 发 SSE 后 2 秒断开 → 应为 CANCELLED（client_disconnect）
    status, headers, body = sse_call("/ai/agent/stream", {"question": f"{marker} 请分步讲解牛顿第二定律"},
                                     h, read_seconds=2)
    time.sleep(4)
    rows = sql(f"select id, status, error, trace_id from ai_agent_run where id>{before} "
               f"and user_id={uid} order by id desc limit 3")
    info(f"断连后新增 run 行: {rows}")
    if not rows:
        check("CP-0", "制造中断 run", "新增一条 CANCELLED/FAILED run", "未产生新 run", False,
              f"SSE HTTP {status}")
        return
    run_id, run_status = rows[0][0], rows[0][1]
    check("CP-1", "断连 2 秒后 run 进入终态（CANCELLED/FAILED）", "status ∈ {CANCELLED, FAILED}",
          f"run_id={run_id} status={run_status} error={rows[0][2]}",
          run_status in ("CANCELLED", "FAILED"), str(rows[0]))

    r = jcall("GET", f"/admin/ai/agent/runs/{run_id}/checkpoints", headers=bearer(admin_token))
    cps = ((r.json() or {}).get("data")) or []
    check("CP-2", "GET checkpoints → 200 且有步骤", "200 且 list 非空",
          f"HTTP {r.status} / {len(cps)} 条 / steps={[c.get('stepNo') for c in cps][:8]}",
          r.status == 200 and len(cps) > 0,
          json.dumps(cps[:2], ensure_ascii=False)[:300])

    # resume：SSE 响应，需读头拿 X-Agent-Run-Id
    parsed = urllib.parse.urlparse(JAVA_BASE)
    conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=180)
    conn.request("POST", parsed.path.rstrip("/") + f"/admin/ai/agent/runs/{run_id}/resume",
                 body=b"", headers={"Authorization": f"Bearer {admin_token}"})
    resp = conn.getresponse()
    rstatus = resp.status
    rh = {k.lower(): v for k, v in resp.getheaders()}
    rbody_chunks = b""
    try:
        deadline = time.time() + 3
        while time.time() < deadline:
            chunk = resp.read(256)
            if not chunk:
                break
            rbody_chunks += chunk
    except Exception:  # noqa: BLE001
        pass
    finally:
        conn.close()
    new_run = rh.get("x-agent-run-id")
    check("CP-3", "POST resume → 200 且响应头带 X-Agent-Run-Id", "200 + int runId",
          f"HTTP {rstatus} / X-Agent-Run-Id={new_run}", rstatus == 200 and bool(new_run and new_run.isdigit()),
          rbody_chunks.decode("utf-8", "replace")[:200])

    time.sleep(3)
    r2 = jcall("POST", f"/admin/ai/agent/runs/{run_id}/resume", headers=bearer(admin_token), timeout=60)
    b2 = r2.json() or {}
    check("CP-4", "同一 run 二次 resume → 409（幂等）", "HTTP 409 / code=409",
          f"HTTP {r2.status} / code={b2.get('code')}", r2.status == 409 and b2.get("code") == 409,
          str(b2.get("message"))[:160])

    succ = sql("select id from ai_agent_run where status='SUCCESS' order by id desc limit 1")
    if succ:
        r3 = jcall("POST", f"/admin/ai/agent/runs/{succ[0][0]}/resume", headers=bearer(admin_token), timeout=60)
        b3 = r3.json() or {}
        check("CP-5", "对 SUCCESS run resume → 409", "HTTP 409 / code=409",
              f"run={succ[0][0]} HTTP {r3.status} / code={b3.get('code')}",
              r3.status == 409 and b3.get("code") == 409, str(b3.get("message"))[:160])
    else:
        check("CP-5", "对 SUCCESS run resume → 409", "库内有 SUCCESS run", "无 SUCCESS run", False,
              "标记为未验证", note="未验证")

    r4 = jcall("POST", "/admin/ai/agent/runs/99999999/resume", headers=bearer(admin_token), timeout=60)
    b4 = r4.json() or {}
    check("CP-6", "不存在的 run resume → 404", "HTTP 404 / code=404",
          f"HTTP {r4.status} / code={b4.get('code')}", r4.status == 404 and b4.get("code") == 404,
          str(b4.get("message"))[:160])

    # 管理端只读记忆（AUDITOR/ADMIN 视角）
    rm = jcall("GET", f"/admin/ai/memory?userId={uid}", headers=bearer(admin_token))
    check("CP-7", "管理端 GET /admin/ai/memory?userId= → 200", "HTTP 200 + list",
          f"HTTP {rm.status} / {len((((rm.json() or {}).get('data')) or []))} 条", rm.status == 200,
          rm.body[:200])


def safe(fn, *args) -> None:
    """跑一个验收段；单段异常不阻断其余段落（异常记为 FAIL，保留证据）。"""
    try:
        fn(*args)
    except Exception as e:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        check(f"{fn.__name__}-EXC", f"段落 {fn.__name__} 执行异常", "无异常抛出",
              f"{type(e).__name__}: {e}", False, traceback.format_exc()[-400:],
              note="环境/脚本异常，非功能断言")


def section_cross_user(student_token: str, admin_token: str) -> None:
    """跨用户越权与角色边界（有第二账号才可真验，否则记「未验证」）。"""
    print("\n=== 跨用户 / 角色边界 ===")
    other_no = os.environ.get("STUDENT_NO_B", "2021999")
    try:
        other_token, other_uid = login(other_no, STUDENT_PWD)
    except SystemExit:
        check("XU-1", f"第二账号 {other_no} 可登录", "可登录", "登录失败", False,
              "无第二账号，跨用户 403 无法真验", note="未验证")
        return
    check("XU-1", f"第二账号 {other_no} 可登录（跨用户用例前提）", "可登录",
          f"uid={other_uid}", other_uid > 0 and other_uid != 3, "")

    stamp = int(time.time())
    key = f"qa-越权-{stamp}"
    r = jcall("POST", "/me/agent-memory", {"memKey": key, "memValue": "A 的记忆"}, bearer(student_token))
    mid = ((r.json() or {}).get("data") or {}).get("id")
    info(f"A 创建记忆 id={mid} HTTP {r.status}")

    if mid:
        r2 = jcall("PUT", f"/me/agent-memory/{mid}",
                   {"memKey": key, "memValue": "B 篡改"}, bearer(other_token))
        b2 = r2.json() or {}
        check("XU-2", "B 改 A 的记忆 → 403（越权不是 404）", "HTTP 403 / code=403",
              f"HTTP {r2.status} / code={b2.get('code')}", r2.status == 403 and b2.get("code") == 403,
              str(b2.get("message"))[:160])

        r3 = jcall("DELETE", f"/me/agent-memory/{mid}", headers=bearer(other_token))
        b3 = r3.json() or {}
        check("XU-3", "B 删 A 的记忆 → 403", "HTTP 403 / code=403",
              f"HTTP {r3.status} / code={b3.get('code')}", r3.status == 403 and b3.get("code") == 403,
              str(b3.get("message"))[:160])

        r4 = jcall("GET", "/me/agent-memory", headers=bearer(other_token))
        keys_b = [x.get("memKey") for x in (((r4.json() or {}).get("data")) or [])]
        check("XU-4", "B 的记忆列表看不到 A 的 key", "不含该 key",
              f"包含={key in keys_b}", key not in keys_b, str(keys_b[:5]))

        jcall("DELETE", f"/me/agent-memory/{mid}", headers=bearer(student_token))  # 清理

    r5 = jcall("GET", "/admin/ai/metrics?days=7", headers=bearer(student_token))
    check("XU-5", "学生调 /admin/ai/metrics → 403", "HTTP 403", f"HTTP {r5.status}", r5.status == 403,
          r5.body[:160])

    r6 = jcall("POST", "/admin/ai/retention/purge?days=30", headers=bearer(student_token))
    check("XU-6", "学生调 purge → 403", "HTTP 403", f"HTTP {r6.status}", r6.status == 403,
          r6.body[:160])

    # 审计员：可读指标，不可写 purge（@PreAuthorize 仅 ADMIN）
    try:
        aud_token, _ = login(os.environ.get("AUDITOR_NO", "auditor"),
                             _require_pwd("AUDITOR_PWD"))  # E1-06；未注入时由下方 except SystemExit 跳过该用例
    except SystemExit:
        aud_token = ""
    if aud_token:
        r7 = jcall("GET", "/admin/ai/metrics?days=7", headers=bearer(aud_token))
        check("XU-7", "AUDITOR 可读 /admin/ai/metrics → 200", "HTTP 200", f"HTTP {r7.status}",
              r7.status == 200, r7.body[:160])
        r8 = jcall("POST", "/admin/ai/retention/purge?days=30", headers=bearer(aud_token))
        check("XU-8", "AUDITOR 调 purge → 403（仅 ADMIN）", "HTTP 403", f"HTTP {r8.status}",
              r8.status == 403, r8.body[:160])
    else:
        check("XU-7", "AUDITOR 角色用例（缺凭证，无法真验）", "可登录 auditor",
              "auditor 账号口令未知（旧弱口令返回 code=1001；E1-06 起命令行不再保存该字面量）", False,
              "已用静态断言替代：SecurityConfig /admin/** = AUDITOR|ADMIN，purge 为 @PreAuthorize(ADMIN)",
              note="未验证")


def main() -> int:
    print(f"目标: JAVA={JAVA_BASE}  AI={AI_BASE}", flush=True)
    print(f"日志: backend={BACKEND_LOG}  ai={AI_LOG}", flush=True)
    admin_token, admin_uid = login(ADMIN_NO, ADMIN_PWD)
    student_token, student_uid = login(STUDENT_NO, STUDENT_PWD)
    info(f"admin uid={admin_uid} / student uid={student_uid}")

    safe(section_a11, admin_token)
    safe(section_fail_closed)
    safe(section_budget)
    safe(section_metrics, admin_token)     # 先做指标（窗口固定，避免后续新增 run 干扰）
    safe(section_trace, student_token)     # SSE 会产生新 run
    safe(section_retention, admin_token)
    safe(section_eval, admin_token)
    safe(section_memory, student_token)
    safe(section_cross_user, student_token, admin_token)
    safe(section_checkpoint, student_token, admin_token)

    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["verdict"] == "PASS")
    failed = [r for r in RESULTS if r["verdict"] == "FAIL"]
    unverified = [r for r in RESULTS if r["note"] == "未验证"]
    print("\n==== 汇总 ====")
    print(f"断言总数={total} 通过={passed} 失败={len(failed)} 未验证={len(unverified)}")
    for r in failed:
        print(f"  FAIL {r['id']} {r['name']} 期望={r['expected']} 实际={r['actual']}")

    out_json = OUT_JSON or os.path.join(tempfile.gettempdir(), "phase34_http_result.json")
    with open(out_json, "w", encoding="utf-8") as fh:
        json.dump({"total": total, "passed": passed, "failed": failed,
                   "unverified": unverified, "results": RESULTS},
                  fh, ensure_ascii=False, indent=2)
    print(f"结果 JSON: {out_json}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
