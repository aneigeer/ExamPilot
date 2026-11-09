"""第 8 周 W8-7 自动化回归测试集（高频核心链路）。

设计定位：**只读优先 + 可重复 + 证据自证 + 失败即记录继续跑**。

覆盖链路（按执行顺序）：
  H  健康检查（8080 actuator / 8000 /v1/health）
  A  学生 / 管理员登录
  Q  题目列表（total>0）/ 题目详情（含答案）
  P  刷题：开始 → 答 1 题（判分）→ 重复提交幂等 → finish → 报告正确率
  W  错题本（刚答错的题可见）
  AI 答疑：域内（refused=false + sources 非空 + 正文含 [n]）/ 域外（refused=true）
  AG Agent 非流式运行（内部 Token → 200 + final 事件字段）
  T  内部检索工具（内部 Token → 200 + contexts）

已知缺陷不塞进核心断言：单独用 ``xfail`` 段记录（复现记 XFAIL，消失记 XPASS），
不计入退出码，保证脚本每次可重复运行、核心链路口径稳定。

用法::

    .venv-ai/Scripts/python.exe scripts/qa/week8_e2e.py
    .venv-ai/Scripts/python.exe scripts/qa/week8_e2e.py \\
        --base http://127.0.0.1:8080/api --ai-base http://127.0.0.1:8000 \\
        --token local-smoke-token --out 项目规划/第8周/evidence/automation_regression_20261108.json

环境变量：QA_BASE / QA_AI_BASE / QA_TOKEN / QA_STUDENT_NO / QA_STUDENT_PWD /
QA_ADMIN_NO / QA_ADMIN_PWD / QA_AI_SUBJECT / QA_OUT。

退出码：核心断言全通过 0；存在失败 1。XFAIL/XPASS 不影响退出码。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import traceback
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_OUT = os.path.join(PROJECT_ROOT, "项目规划", "第8周", "evidence",
                           "automation_regression_20261108.json")

CITATION_RE = re.compile(r"\[\d+\]")
REFUSAL_TEXT = "未找到相关资料"

RESULTS: List[Dict[str, Any]] = []
KNOWN: List[Dict[str, Any]] = []


# --------------------------------------------------------------------------------------
# 配置
# --------------------------------------------------------------------------------------
def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="第8周 W8-7 自动化回归测试集")
    parser.add_argument("--base", default=os.environ.get("QA_BASE", "http://127.0.0.1:8080/api"),
                        help="Spring Boot 基址（含 /api）")
    parser.add_argument("--ai-base", default=os.environ.get("QA_AI_BASE", "http://127.0.0.1:8000"),
                        help="FastAPI 基址")
    parser.add_argument("--token", default=os.environ.get("QA_TOKEN", "local-smoke-token"),
                        help="内部接口令牌 X-Internal-Token")
    parser.add_argument("--student-no", default=os.environ.get("QA_STUDENT_NO", "2021001"))
    parser.add_argument("--student-pwd", default=os.environ.get("QA_STUDENT_PWD", "123456"))
    parser.add_argument("--admin-no", default=os.environ.get("QA_ADMIN_NO", "admin"))
    parser.add_argument("--admin-pwd", default=os.environ.get("QA_ADMIN_PWD", "admin123"))
    parser.add_argument("--ai-subject", type=int, default=int(os.environ.get("QA_AI_SUBJECT", "5")),
                        help="Agent / 检索工具使用的科目 ID")
    parser.add_argument("--out", default=os.environ.get("QA_OUT", DEFAULT_OUT),
                        help="证据 JSON 落盘路径")
    return parser.parse_args()


CFG = parse_args()
JAVA_BASE = CFG.base.rstrip("/")
AI_BASE = CFG.ai_base.rstrip("/")


# --------------------------------------------------------------------------------------
# 证据收集
# --------------------------------------------------------------------------------------
def check(sid: str, name: str, expected: str, actual: str, ok: bool,
          evidence: str = "", note: str = "") -> bool:
    verdict = "PASS" if ok else "FAIL"
    RESULTS.append({
        "id": sid, "name": name, "expected": expected, "actual": actual,
        "verdict": verdict, "evidence": evidence[:300], "note": note,
    })
    print(f"[{verdict}] {sid} {name} | 期望={expected} | 实际={actual}", flush=True)
    if evidence:
        print(f"        证据: {evidence[:220]}", flush=True)
    return ok


def xfail(sid: str, name: str, defect: str, expected: str, observed: str,
          reproduced: bool, evidence: str = "", inconclusive: bool = False) -> None:
    """已知缺陷探测：reproduced=True → XFAIL（仍复现），False → XPASS（已修复）；
    inconclusive=True（如触发登录限流 429）→ SKIP，不判定缺陷是否消失。"""
    verdict = "SKIP" if inconclusive else ("XFAIL" if reproduced else "XPASS")
    KNOWN.append({
        "id": sid, "defect": defect, "name": name, "expected": expected,
        "observed": observed, "verdict": verdict, "evidence": evidence[:300],
    })
    print(f"[{verdict}] {sid} {name} | 缺陷={defect} | 期望={expected} | 实际={observed}",
          flush=True)


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
          timeout: float = 60.0) -> Resp:
    return call(method, JAVA_BASE + path, body, headers, timeout)


def bearer(token: str) -> Dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def internal_headers() -> Dict[str, str]:
    return {"X-Internal-Token": CFG.token}


def _data(resp: Resp) -> Any:
    return (resp.json() or {}).get("data")


# --------------------------------------------------------------------------------------
# 各链路
# --------------------------------------------------------------------------------------
def section_health(ctx: Dict[str, Any]) -> None:
    print("\n=== H 健康检查 ===", flush=True)
    r = jcall("GET", "/actuator/health", timeout=10)
    body = r.json() or {}
    check("H-1", "Spring Boot /api/actuator/health", "HTTP 200 且 status=UP",
          f"HTTP {r.status} / status={body.get('status')}",
          r.status == 200 and body.get("status") == "UP", r.body[:200])

    a = call("GET", AI_BASE + "/v1/health", timeout=15)
    abody = a.json() or {}
    check("H-2", "FastAPI /v1/health", "HTTP 200 且 status 非 down",
          f"HTTP {a.status} / status={abody.get('status')}",
          a.status == 200 and str(abody.get("status") or "").lower() not in ("", "down"),
          a.body[:200])


def section_auth(ctx: Dict[str, Any]) -> None:
    print("\n=== A 登录 ===", flush=True)
    r = jcall("POST", "/auth/login",
              {"studentNo": CFG.student_no, "password": CFG.student_pwd}, timeout=20)
    data = _data(r) or {}
    token = str(data.get("token") or "")
    uid = int(((data.get("user") or {}).get("id")) or 0)
    ok = r.status == 200 and bool(token) and uid > 0
    check("A-1", "学生登录", "HTTP 200 + token 非空 + userId>0",
          f"HTTP {r.status} / code={(r.json() or {}).get('code')} / userId={uid} / "
          f"tokenLen={len(token)}", ok, f"学号={CFG.student_no}")
    if ok:
        ctx["studentToken"] = token
        ctx["userId"] = uid

    r2 = jcall("POST", "/auth/login",
               {"studentNo": CFG.admin_no, "password": CFG.admin_pwd}, timeout=20)
    d2 = _data(r2) or {}
    admin_token = str(d2.get("token") or "")
    role = str((d2.get("user") or {}).get("role") or "")
    check("A-2", "管理员登录", "HTTP 200 + token 非空 + role=ADMIN",
          f"HTTP {r2.status} / role={role} / tokenLen={len(admin_token)}",
          r2.status == 200 and bool(admin_token) and role == "ADMIN",
          f"账号={CFG.admin_no}")
    ctx["adminRole"] = role


def section_questions(ctx: Dict[str, Any]) -> None:
    print("\n=== Q 题库 ===", flush=True)
    r = jcall("GET", "/questions?page=1&size=10", timeout=20)
    data = _data(r) or {}
    rows = data.get("list") or []
    total = data.get("total")
    check("Q-1", "题目分页列表", "HTTP 200 + total>0 + list 非空",
          f"HTTP {r.status} / total={total} / list={len(rows)}",
          r.status == 200 and isinstance(total, int) and total > 0 and len(rows) > 0,
          r.body[:240])

    if not rows:
        check("Q-2", "题目详情", "先有列表数据", "列表为空，无法取详情", False,
              "", note="前置失败")
        return

    first = rows[0]
    qid = int(first.get("id") or 0)
    ctx["firstQuestion"] = first
    d = jcall("GET", f"/questions/{qid}", timeout=20)
    detail = _data(d) or {}
    options = detail.get("options") or []
    has_answer = any("isAnswer" in o for o in options)
    check("Q-2", "题目详情含选项与答案", "HTTP 200 + options 非空 + 含 isAnswer",
          f"HTTP {d.status} / options={len(options)} / 含isAnswer={has_answer}",
          d.status == 200 and len(options) > 0 and has_answer, d.body[:240])
    ctx["detailQuestionId"] = qid


def section_practice(ctx: Dict[str, Any]) -> None:
    print("\n=== P 刷题闭环 ===", flush=True)
    token = ctx.get("studentToken")
    if not token:
        check("P-0", "刷题前置登录", "已有学生 token", "登录未取得 token", False,
              "", note="前置失败")
        return

    seed = ctx.get("firstQuestion") or {}
    subject_id = int(seed.get("subjectId") or 190)
    qtype = str(seed.get("type") or "SINGLE_CHOICE")
    start_body = {"subjectId": subject_id, "mode": "BY_TYPE", "questionType": qtype,
                  "count": 2}
    r = jcall("POST", "/practice/start", start_body, bearer(token), timeout=30)
    data = _data(r) or {}
    pid = int(data.get("practiceId") or 0)
    total = int(data.get("totalCount") or 0)
    question = data.get("question") or {}
    qid = int(question.get("id") or 0)
    no_answer_leak = ("isAnswer" not in r.body) and ("analysis" not in r.body)
    ok_start = (r.status == 200 and pid > 0 and total >= 1 and qid > 0)
    check("P-1", "开始练习并返回第一题", "HTTP 200 + practiceId>0 + 题数>=1 + 题目存在",
          f"HTTP {r.status} / practiceId={pid} / totalCount={total} / questionId={qid}",
          ok_start, f"subjectId={subject_id} / type={qtype} / count=2")
    check("P-2", "练习题面不泄露答案", "响应内无 isAnswer / analysis",
          f"命中isAnswer={'isAnswer' in r.body} 命中analysis={'analysis' in r.body}",
          no_answer_leak, r.body[:200])
    if not ok_start:
        check("P-3", "后续刷题步骤", "已获得 practiceId/questionId", "前置失败", False,
              "", note="前置失败")
        return
    ctx["practiceId"] = pid
    ctx["practiceQuestionId"] = qid

    # 取详情拿正确答案（题库浏览接口 PUBLIC），据此构造一次确定性答错
    d = jcall("GET", f"/questions/{qid}", timeout=20)
    detail = _data(d) or {}
    options = detail.get("options") or []
    correct = ""
    for option in options:
        if option.get("isAnswer"):
            correct = str(option.get("key") or "")
            break
    keys = [str(o.get("key") or "") for o in options]
    wrong = next((k for k in keys if k != correct), "A")

    answer_body = {"questionId": qid, "userAnswer": wrong}
    a1 = jcall("POST", f"/practice/{pid}/answer", answer_body, bearer(token), timeout=30)
    ad1 = _data(a1) or {}
    judged = ad1.get("judged")
    correct_flag = ad1.get("correct")
    answered = int(ad1.get("answeredCount") or 0)
    ok_judge = (a1.status == 200 and judged is True and correct_flag is False
                and str(ad1.get("correctAnswer") or "") == correct and answered == 1)
    check("P-3", "答 1 题并判分（故意答错）", "200 / judged=true / correct=false / correctAnswer 正确 / answeredCount=1",
          f"HTTP {a1.status} / judged={judged} / correct={correct_flag} / "
          f"correctAnswer={ad1.get('correctAnswer')} / answeredCount={answered}",
          ok_judge, f"提交 {wrong}，正确答案 {correct}")
    ctx["wrongQuestionId"] = qid if correct_flag is False else 0

    a2 = jcall("POST", f"/practice/{pid}/answer", answer_body, bearer(token), timeout=30)
    ad2 = _data(a2) or {}
    answered2 = int(ad2.get("answeredCount") or 0)
    ok_idempotent = (a2.status == 200 and ad2.get("judged") is True
                     and ad2.get("correct") is False and answered2 == answered)
    check("P-4", "重复提交同一题同一答案幂等", "200 / 判分结果不变 / answeredCount 不增",
          f"HTTP {a2.status} / correct={ad2.get('correct')} / "
          f"answeredCount={answered}->{answered2}", ok_idempotent, a2.body[:220])

    f = jcall("POST", f"/practice/{pid}/finish", None, bearer(token), timeout=30)
    fd = _data(f) or {}
    correct_count = int(fd.get("correctCount") or 0)
    answered_count = int(fd.get("answeredCount") or 0)
    accuracy = fd.get("accuracy")
    details = fd.get("details") or []
    expect_acc = round(correct_count * 100.0 / answered_count, 1) if answered_count else 0.0
    acc_ok = accuracy is not None and abs(float(accuracy) - expect_acc) <= 0.05
    ok_finish = (f.status == 200 and fd.get("status") == "FINISHED"
                 and answered_count == 1 and correct_count == 0
                 and len(details) == 1 and acc_ok)
    check("P-5", "结束练习并出报告", "200 / FINISHED / answered=1 / correct=0 / "
          "details=1 / accuracy=correct÷answered",
          f"HTTP {f.status} / status={fd.get('status')} / answered={answered_count} / "
          f"correct={correct_count} / accuracy={accuracy} / details={len(details)}",
          ok_finish, f"复算 accuracy={expect_acc}")

    rep = jcall("GET", f"/practice/{pid}/report", headers=bearer(token), timeout=20)
    rd = _data(rep) or {}
    rep_acc = rd.get("accuracy")
    rep_details = rd.get("details") or []
    rep_ok = (rep.status == 200 and rep_acc is not None
              and abs(float(rep_acc) - expect_acc) <= 0.05
              and len(rep_details) == answered_count)
    check("P-6", "练习报告与 finish 一致", "200 / accuracy 与 finish 一致 / details=answered",
          f"HTTP {rep.status} / accuracy={rep_acc}（finish={accuracy}）/ "
          f"details={len(rep_details)}", rep_ok, rep.body[:220])


def section_wrong_book(ctx: Dict[str, Any]) -> None:
    print("\n=== W 错题本 ===", flush=True)
    token = ctx.get("studentToken")
    if not token:
        check("W-0", "错题本前置登录", "已有学生 token", "登录未取得 token", False, "",
              note="前置失败")
        return
    qid = int(ctx.get("wrongQuestionId") or 0)
    if not qid:
        check("W-0", "错题本前置答错题", "已记录答错题 ID", "无答错题", False, "",
              note="前置失败")
        return
    r = jcall("GET", "/wrong-book", headers=bearer(token), timeout=20)
    rows = _data(r) or []
    ids = [int(x.get("questionId") or 0) for x in rows if isinstance(x, dict)]
    hit = qid in ids
    check("W-1", "错题本可见刚答错的题", "HTTP 200 + 列表包含该 questionId",
          f"HTTP {r.status} / 错题数={len(ids)} / 命中{qid}={hit}", r.status == 200 and hit,
          f"questionId={qid}")


def section_ai_qa(ctx: Dict[str, Any]) -> None:
    print("\n=== AI 答疑 ===", flush=True)
    token = ctx.get("studentToken")
    if not token:
        check("AI-0", "AI 答疑前置登录", "已有学生 token", "登录未取得 token", False, "",
              note="前置失败")
        return
    domain_question = "值传递和地址传递有什么区别"
    r = jcall("POST", "/ai/ask", {"question": domain_question}, bearer(token), timeout=180)
    data = _data(r) or {}
    answer = str(data.get("answer") or "")
    sources = data.get("sources") or []
    has_citation = bool(CITATION_RE.search(answer))
    ok_in = (r.status == 200 and data.get("refused") is False
             and len(sources) > 0 and has_citation)
    check("AI-1", "域内答疑返回溯源答案", "200 / refused=false / sources 非空 / 正文含 [n]",
          f"HTTP {r.status} / refused={data.get('refused')} / sources={len(sources)} / "
          f"含引用={has_citation}", ok_in, f"question={domain_question}；answer={answer[:120]}")
    ctx["aiAnswerLen"] = len(answer)

    out_question = "明天上海天气怎么样"
    r2 = jcall("POST", "/ai/ask", {"question": out_question}, bearer(token), timeout=120)
    d2 = _data(r2) or {}
    refused = d2.get("refused")
    answer2 = str(d2.get("answer") or "")
    sources2 = d2.get("sources")
    ok_out = (r2.status == 200 and refused is True and answer2 == REFUSAL_TEXT
              and isinstance(sources2, list) and len(sources2) == 0)
    check("AI-2", "域外问题拒答", "200 / refused=true / answer=未找到相关资料 / sources=[]",
          f"HTTP {r2.status} / refused={refused} / answerLen={len(answer2)} / "
          f"sources={0 if isinstance(sources2, list) else sources2}",
          ok_out, f"question={out_question}；answer={answer2}")


def section_agent(ctx: Dict[str, Any]) -> None:
    print("\n=== AG Agent 非流式 ===", flush=True)
    uid = int(ctx.get("userId") or 0)
    payload = {"question": "值传递和地址传递有什么区别", "userId": uid,
               "subjectId": CFG.ai_subject, "maxSteps": 4}
    r = call("POST", AI_BASE + "/v1/agent/run", payload, internal_headers(), timeout=180)
    body = r.json() or {}
    events = body.get("events") or []
    final_event = next((e.get("data") or {} for e in events
                        if str(e.get("event") or "") == "final"), {})
    final_ok = bool(final_event) and bool(str(final_event.get("answer") or ""))
    check("AG-1", "Agent 运行返回 final 事件", "HTTP 200 + events 非空 + final 事件存在",
          f"HTTP {r.status} / events={len(events)} / final存在={bool(final_event)}",
          r.status == 200 and len(events) > 0 and final_ok, r.body[:240])

    fields = ["answer", "generator", "degraded", "refused", "step_count", "tool_calls"]
    missing = [k for k in fields if k not in final_event]
    call_ok = ("tool_calls" in final_event and "step_count" in final_event
               and final_event.get("refused") is False)
    check("AG-2", "final 字段齐全且可用", "字段 " + "/".join(fields) + " 全部存在",
          f"缺失={missing} / refused={final_event.get('refused')} / "
          f"step_count={final_event.get('step_count')} / "
          f"tool_calls={final_event.get('tool_calls')}",
          not missing and call_ok, json.dumps(final_event, ensure_ascii=False)[:260])

    # 已知问题：response_model 未声明 final，顶层键被裁剪
    xfail("K-4", "Agent 响应顶层 final 字段缺失",
          "W8-7-KI-04（P2）AgentRunResponse 未声明 final，events 内 final 事件仍可用",
          "响应体含顶层 final 对象", f"顶层键={sorted(body.keys())}",
          "final" not in body, json.dumps(sorted(body.keys()), ensure_ascii=False))


def section_internal_tool(ctx: Dict[str, Any]) -> None:
    print("\n=== T 内部检索工具 ===", flush=True)
    r = call("POST", JAVA_BASE + "/internal/agent/tools/search",
             {"question": "值传递和地址传递有什么区别", "subjectId": CFG.ai_subject,
              "topK": 3}, internal_headers(), timeout=120)
    data = _data(r) or {}
    contexts = data.get("contexts") or []
    ok = r.status == 200 and len(contexts) > 0 and data.get("refused") is False
    check("T-1", "内部检索工具返回 contexts", "200 / contexts 非空 / refused=false",
          f"HTTP {r.status} / contexts={len(contexts)} / mode={data.get('mode')} / "
          f"refused={data.get('refused')}", ok, r.body[:240])
    first = contexts[0] if contexts else {}
    fields = ["chunkId", "title", "score", "refType", "refId"]
    missing = [k for k in fields if k not in first]
    check("T-2", "检索结果字段完整", "contexts[0] 含 " + "/".join(fields),
          f"缺失={missing} / refType={first.get('refType')} / refId={first.get('refId')}",
          not missing, json.dumps(first, ensure_ascii=False)[:260])


def section_known_issues(ctx: Dict[str, Any]) -> None:
    print("\n=== K 已知缺陷探测（不影响核心退出码）===", flush=True)
    token = ctx.get("studentToken") or ""
    qid = int(ctx.get("practiceQuestionId") or ctx.get("detailQuestionId") or 0) or 4022

    # KI-01 错误密码登录返回 HTTP 200（业务码 1001）；429=登录限流，无法判定
    r = jcall("POST", "/auth/login",
              {"studentNo": CFG.student_no, "password": "WrongPass_W8_7"}, timeout=20)
    j = r.json() or {}
    rate_limited = r.status == 429
    xfail("K-1", "错误密码登录仍返回 HTTP 200", "W8-2-D-01（中）",
          "应返回 4xx（业务码非 1001）",
          f"HTTP {r.status} / code={j.get('code')}", r.status == 200, r.body[:200],
          inconclusive=rate_limited)

    # KI-02 不存在的 practiceId 提交答案返回 HTTP 200（业务码 2003）
    if token:
        r2 = jcall("POST", "/practice/999999999/answer",
                   {"questionId": qid, "userAnswer": "A"}, bearer(token), timeout=20)
        j2 = r2.json() or {}
        xfail("K-2", "非法 practiceId 提交仍返回 HTTP 200", "W8-2-D-02（中）",
              "应返回 4xx", f"HTTP {r2.status} / code={j2.get('code')}", r2.status == 200,
              r2.body[:200])
    else:
        xfail("K-2", "非法 practiceId 提交", "W8-2-D-02（中）", "应返回 4xx",
              "无学生 token，未执行", False, "")

    # KI-03 域外「红烧肉做法」未拒答（错误召回 + 无关回答）
    r3 = jcall("POST", "/ai/ask", {"question": "红烧肉做法"}, bearer(token), timeout=120) \
        if token else None
    if r3 is not None:
        d3 = _data(r3) or {}
        reproduced3 = d3.get("refused") is False
        xfail("K-3", "域外「红烧肉做法」未拒答", "W8-3-D-01（P1/中）",
              "refused=true / 正文=未找到相关资料",
              f"HTTP {r3.status} / refused={d3.get('refused')} / "
              f"generator={d3.get('generator')} / sources={len(d3.get('sources') or [])}",
              reproduced3, str(d3.get("answer") or "")[:200])
    else:
        xfail("K-3", "域外「红烧肉做法」拒答", "W8-3-D-01（P1/中）", "refused=true",
              "无学生 token，未执行", False, "")


# --------------------------------------------------------------------------------------
# 编排
# --------------------------------------------------------------------------------------
def safe(fn, ctx: Dict[str, Any]) -> None:
    try:
        fn(ctx)
    except Exception as e:  # noqa: BLE001
        traceback.print_exc()
        check(f"{fn.__name__}-EXC", f"链路 {fn.__name__} 执行异常", "无异常抛出",
              f"{type(e).__name__}: {e}", False, traceback.format_exc()[-400:],
              note="环境/脚本异常，非功能断言")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            pass

    started = time.time()
    marker = "W8-7-" + time.strftime("%Y%m%d-%H%M%S")
    print(f"目标: JAVA={JAVA_BASE}  AI={AI_BASE}  运行标记={marker}", flush=True)

    ctx: Dict[str, Any] = {}
    safe(section_health, ctx)
    safe(section_auth, ctx)
    safe(section_questions, ctx)
    safe(section_practice, ctx)
    safe(section_wrong_book, ctx)
    safe(section_ai_qa, ctx)
    safe(section_agent, ctx)
    safe(section_internal_tool, ctx)
    safe(section_known_issues, ctx)

    total = len(RESULTS)
    passed = sum(1 for r in RESULTS if r["verdict"] == "PASS")
    failed = [r for r in RESULTS if r["verdict"] == "FAIL"]
    xfails = [k for k in KNOWN if k["verdict"] == "XFAIL"]
    xpasses = [k for k in KNOWN if k["verdict"] == "XPASS"]
    skipped = [k for k in KNOWN if k["verdict"] == "SKIP"]

    print("\n==== 汇总 ====", flush=True)
    print(f"核心用例={total} 通过={passed} 失败={len(failed)} "
          f"已知缺陷复现(XFAIL)={len(xfails)} 已修复(XPASS)={len(xpasses)} "
          f"无法判定(SKIP)={len(skipped)}", flush=True)
    for r in failed:
        print(f"  FAIL {r['id']} {r['name']} | 期望={r['expected']} | 实际={r['actual']}",
              flush=True)

    payload = {
        "task": "第8周 W8-7 自动化回归测试集",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "runMarker": marker,
        "target": {"javaBase": JAVA_BASE, "aiBase": AI_BASE, "aiSubjectId": CFG.ai_subject},
        "environment": {
            "studentNo": CFG.student_no,
            "studentUserId": ctx.get("userId"),
            "adminRole": ctx.get("adminRole"),
            "internalTokenConfigured": bool(CFG.token),
            "python": sys.version.split()[0],
        },
        "summary": {
            "total": total, "passed": passed, "failed": len(failed),
            "xfail": len(xfails), "xpass": len(xpasses), "skipped": len(skipped),
            "durationMs": int((time.time() - started) * 1000),
        },
        "footprint": {
            "practiceId": ctx.get("practiceId"),
            "practiceQuestionId": ctx.get("practiceQuestionId"),
            "answeredWrongQuestionId": ctx.get("wrongQuestionId"),
        },
        "results": RESULTS,
        "knownIssues": KNOWN,
    }

    out = os.path.abspath(CFG.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    print(f"结果 JSON: {out}", flush=True)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
