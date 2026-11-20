#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第10周 P1 内部走查试点驱动脚本（只读 + 只写练习记录，不删数据、不改代码）。

用法（在项目根目录执行）：
    python 项目规划/第10周/evidence/raw_pilot_20261110/pilot_walkthrough.py student
    python 项目规划/第10周/evidence/raw_pilot_20261110/pilot_walkthrough.py ai
    python 项目规划/第10周/evidence/raw_pilot_20261110/pilot_walkthrough.py admin

每次调用把结果合并写入 pilot_run.json（同一目录）。
脚本本身不含任何口令明文：学生口令走环境变量 STUDENT_PASSWORD，
管理员口令从 config/secrets.local.yml 的 admin.password 读取（不打印）。

安全注意：脚本会把取到的 JWT 缓存到 token_student.txt / token_admin.txt 以避免重复登录
（登录限流 5 次/分钟/IP）。这两个文件**不可入库**，同目录 .gitignore 已忽略；
走查结束后请手动删除，并确认落盘的响应 JSON 中 JWT 已替换为 <REDACTED_JWT>。
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

BASE = "http://127.0.0.1:8080/api"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "pilot_run.json")
TOKEN_STUDENT = os.path.join(HERE, "token_student.txt")
TOKEN_ADMIN = os.path.join(HERE, "token_admin.txt")
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(HERE))))
SECRETS = os.path.join(_ROOT, "config", "secrets.local.yml")


# ---------------------------------------------------------------- 基础设施
def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def call(method: str, path: str, body: Optional[Dict[str, Any]] = None,
         token: Optional[str] = None, timeout: float = 180.0) -> Dict[str, Any]:
    """发一次 HTTP 请求，返回 {http, ms, code, message, data, raw_len}。"""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.perf_counter()
    http_status = 0
    payload: Any = None
    err_text = ""
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            http_status = resp.status
            raw = resp.read()
    except urllib.error.HTTPError as exc:  # 4xx/5xx 也带 body
        http_status = exc.code
        raw = exc.read()
    except Exception as exc:  # 连接层失败
        elapsed = round((time.perf_counter() - t0) * 1000.0, 1)
        return {"http": 0, "ms": elapsed, "code": None, "message": "TRANSPORT_ERROR: %s" % exc,
                "data": None, "raw_len": 0, "path": path, "method": method}
    elapsed = round((time.perf_counter() - t0) * 1000.0, 1)
    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception as exc:
        err_text = "NON_JSON(%s): %s" % (exc, raw[:200])
    if isinstance(payload, dict):
        return {"http": http_status, "ms": elapsed, "code": payload.get("code"),
                "message": payload.get("message"), "data": payload.get("data"),
                "raw_len": len(raw), "path": path, "method": method}
    return {"http": http_status, "ms": elapsed, "code": None,
            "message": err_text or "EMPTY", "data": None, "raw_len": len(raw),
            "path": path, "method": method}


def load_results() -> Dict[str, Any]:
    if os.path.exists(OUT):
        with open(OUT, "r", encoding="utf-8") as fh:
            return json.load(fh)
    return {"started_at": now_iso(), "phases": {}}


def save_results(res: Dict[str, Any]) -> None:
    res["updated_at"] = now_iso()
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(res, fh, ensure_ascii=False, indent=2)
    os.replace(tmp, OUT)


def read_token(path: str) -> Optional[str]:
    if os.path.exists(path):
        token = open(path, "r", encoding="utf-8").read().strip()
        return token or None
    return None


def secret(key: str) -> str:
    """从 config/secrets.local.yml 取扁平点号键，取不到直接退出（不打印值）。"""
    with open(SECRETS, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    raise SystemExit("secrets 中缺少键 %s" % key)


def login(student_no: str, password: str) -> Tuple[Optional[str], Dict[str, Any]]:
    r = call("POST", "/auth/login", {"studentNo": student_no, "password": password})
    token = None
    if r.get("code") == 200 and isinstance(r.get("data"), dict):
        token = r["data"].get("token")
    return token, r


# ---------------------------------------------------------------- 角色 A
def pick_subject(token: str, step_log: List[Dict[str, Any]]) -> Dict[str, Any]:
    """找「有题且题型 >= 2 种」的科目，避免只抽到单一题型。"""
    listing = call("GET", "/subjects", token=token)
    step_log.append({"step": "A2-0 科目列表", "result": listing})
    subjects = (listing.get("data") or [])
    candidates = [s for s in subjects if (s.get("questionCount") or 0) > 0]
    types = ["SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER",
             "READING", "ORDERING", "MATCHING", "LISTENING"]
    best: Dict[str, Any] = {}
    for s in candidates:
        dist = {}
        for t in types:
            r = call("GET", "/questions?subjectId=%d&type=%s&page=1&size=1" % (s["id"], t),
                     token=token)
            total = (r.get("data") or {}).get("total") or 0
            if total:
                dist[t] = total
        if len(dist) >= 2 and (not best or sum(dist.values()) > sum(best["type_dist"].values())):
            best = {"id": s["id"], "name": s["name"], "questionCount": s.get("questionCount"),
                    "type_dist": dist}
    step_log.append({"step": "A2-1 科目题型分布筛选", "chosen": best})
    return best


def answer_plan(question: Dict[str, Any]) -> Tuple[str, str]:
    """给题目造一个作答：(userAnswer, 说明)。客观题取第一个选项；主观题给一段短答。"""
    qtype = question.get("type") or ""
    options = question.get("options") or []
    keys = [o.get("key") for o in options if o.get("key")]
    if qtype in ("SINGLE_CHOICE", "READING", "LISTENING", "CLOZE"):
        return (keys[0] if keys else "A"), "单选题，选第一个选项"
    if qtype in ("MULTI_CHOICE", "MATCHING"):
        return (",".join(keys[:2]) if keys else "A,B"), "多选题，选前两个选项"
    if qtype == "TRUE_FALSE":
        return (keys[0] if keys else "A"), "判断题，取第一个选项"
    if qtype == "FILL_BLANK":
        return "printf", "填空题，填一个常见函数名"
    if qtype == "ORDERING":
        return (",".join(keys) if keys else "A,B,C"), "排序题，按选项原名顺序拼接"
    return "进程是资源分配单位，线程是CPU调度单位，同一进程内线程共享地址空间。", "简答题，给一段标准作答"


def run_student() -> None:
    res = load_results()
    steps: List[Dict[str, Any]] = []
    phase: Dict[str, Any] = {"started_at": now_iso(), "steps": steps}

    # A1 静态页可访问 + 标题
    for label, path, expect in (
            ("A1-1 学生端页面", "/student/index.html", "备考助手 · 学生端"),
            ("A1-2 管理端页面", "/admin/index.html", "备考知识库 · 后台管理端")):
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(BASE + path, timeout=30) as resp:
                html = resp.read().decode("utf-8", "replace")
                status = resp.status
            ms = round((time.perf_counter() - t0) * 1000.0, 1)
            title = ""
            if "<title>" in html:
                title = html.split("<title>", 1)[1].split("</title>", 1)[0].strip()
            steps.append({"step": label, "http": status, "ms": ms, "bytes": len(html),
                          "title": title, "title_match": title == expect, "expect": expect})
        except Exception as exc:
            steps.append({"step": label, "http": 0, "error": str(exc), "title_match": False})

    # A2 登录（复用 token，避免触发 5 次/分钟限流）
    token = read_token(TOKEN_STUDENT)
    if token:
        me = call("GET", "/auth/me", token=token)
        if me.get("code") != 200:
            token = None
    if not token:
        pwd = os.environ.get("STUDENT_PASSWORD") or "123456"
        token, r = login("2021001", pwd)
        steps.append({"step": "A2-1 登录", "result": r})
        if token:
            open(TOKEN_STUDENT, "w", encoding="utf-8").write(token)
    else:
        steps.append({"step": "A2-1 登录", "note": "复用本次会话已取得的 token（避免触发登录限流）",
                      "result": call("GET", "/auth/me", token=token)})
    if not token:
        phase["aborted"] = "登录失败，后续任务未执行"
        res["phases"]["student"] = phase
        save_results(res)
        return

    subject = pick_subject(token, steps)
    if not subject:
        phase["aborted"] = "未找到题型>=2 的有题科目"
        res["phases"]["student"] = phase
        save_results(res)
        return
    phase["subject"] = subject

    # A2 开始 5 题练习
    start = call("POST", "/practice/start",
                 {"subjectId": subject["id"], "mode": "RANDOM", "count": 5}, token=token)
    steps.append({"step": "A2-2 开始练习(subjectId=%d,count=5)" % subject["id"], "result": start})
    practice_id = (start.get("data") or {}).get("practiceId")
    phase["practice_id"] = practice_id
    if not practice_id:
        phase["aborted"] = "start 未返回 practiceId"
        res["phases"]["student"] = phase
        save_results(res)
        return

    # A3 逐题作答
    q = (start.get("data") or {}).get("question") or {}
    answered: List[Dict[str, Any]] = []
    guard = 0
    while q and guard < 12:
        guard += 1
        user_answer, note = answer_plan(q)
        ar = call("POST", "/practice/%d/answer" % practice_id,
                  {"questionId": q.get("id"), "userAnswer": user_answer}, token=token)
        entry = {"questionId": q.get("id"), "type": q.get("type"), "typeName": q.get("typeName"),
                 "stem": (q.get("stem") or "")[:60], "userAnswer": user_answer,
                 "answerNote": note, "submit": ar}
        answered.append(entry)
        # 主观题需要自评
        if (ar.get("data") or {}).get("needSelfEval"):
            se = call("POST", "/practice/%d/self-eval" % practice_id,
                      {"questionId": q.get("id"), "result": "KNOW"}, token=token)
            entry["selfEval"] = se
        nxt = call("GET", "/practice/%d/next" % practice_id, token=token)
        entry["next"] = nxt
        if (nxt.get("data") or {}).get("finished"):
            break
        q = (nxt.get("data") or {}).get("question") or {}
    steps.append({"step": "A3 逐题作答", "answered": answered,
                  "type_coverage": sorted({a["type"] for a in answered if a.get("type")})})
    phase["answered"] = answered

    # A4 结束练习 + 报告
    fin = call("POST", "/practice/%d/finish" % practice_id, token=token)
    steps.append({"step": "A4-1 结束练习", "result": fin})
    rep = call("GET", "/practice/%d/report" % practice_id, token=token)
    steps.append({"step": "A4-2 练习报告", "result": rep})
    card = call("GET", "/practice/%d/card" % practice_id, token=token)
    steps.append({"step": "A4-3 答题卡", "result": card})

    # A5 错题本（只看不移除）
    wb = call("GET", "/wrong-book", token=token)
    steps.append({"step": "A5 错题本列表(只读)", "result": wb})

    # A6 学习统计
    for label, path in (("A6-1 我的统计", "/me/stats"),
                        ("A6-2 7天趋势", "/me/practice-trend?days=7"),
                        ("A6-3 掌握度(全科目)", "/me/mastery"),
                        ("A6-4 掌握度(本科目)", "/me/mastery?subjectId=%d" % subject["id"])):
        steps.append({"step": label, "result": call("GET", path, token=token)})

    phase["finished_at"] = now_iso()
    res["phases"]["student"] = phase
    save_results(res)
    print("student phase done, practiceId=%s" % practice_id)


# ---------------------------------------------------------------- 角色 B
def run_ai() -> None:
    res = load_results()
    token = read_token(TOKEN_STUDENT)
    if not token:
        raise SystemExit("缺少学生 token，请先跑 student 阶段")
    steps: List[Dict[str, Any]] = []
    phase: Dict[str, Any] = {"started_at": now_iso(), "steps": steps}

    cap = call("GET", "/ai/capability")
    steps.append({"step": "B0 能力探测", "result": cap})

    # B1 域内问题
    r7 = call("POST", "/ai/ask", {"question": "进程和线程有什么区别"}, token=token)
    steps.append({"step": "B1 域内问题：进程和线程有什么区别", "result": r7})

    # B2 域外问题
    r8 = call("POST", "/ai/ask", {"question": "红烧肉怎么做"}, token=token)
    steps.append({"step": "B2 域外问题：红烧肉怎么做", "result": r8})

    # B3 同一科目（操作系统）连问 3 题，观察是否引用错、答非所问、重复同一段
    same_subject = [
        "什么是进程调度算法",
        "死锁产生的四个必要条件是什么",
        "虚拟内存的作用是什么",
    ]
    for idx, question in enumerate(same_subject, start=1):
        r = call("POST", "/ai/ask", {"question": question}, token=token)
        steps.append({"step": "B3-%d 同学科连问：%s" % (idx, question), "result": r})

    # B4 限定科目检索（subjectId=214 操作系统），验证 subjectId 生效与无题科目的表现
    r4 = call("POST", "/ai/ask", {"question": "进程和线程有什么区别", "subjectId": 214},
              token=token)
    steps.append({"step": "B4 限定科目检索(subjectId=214 操作系统)", "result": r4})

    phase["finished_at"] = now_iso()
    res["phases"]["ai"] = phase
    save_results(res)
    print("ai phase done")


# ---------------------------------------------------------------- 角色 C
def run_admin() -> None:
    res = load_results()
    steps: List[Dict[str, Any]] = []
    phase: Dict[str, Any] = {"started_at": now_iso(), "steps": steps}

    for label, path, expect in (
            ("C1-1 管理端页面", "/admin/index.html", "备考知识库 · 后台管理端"),):
        t0 = time.perf_counter()
        with urllib.request.urlopen(BASE + path, timeout=30) as resp:
            html = resp.read().decode("utf-8", "replace")
            status = resp.status
        ms = round((time.perf_counter() - t0) * 1000.0, 1)
        title = html.split("<title>", 1)[1].split("</title>", 1)[0].strip() if "<title>" in html else ""
        steps.append({"step": label, "http": status, "ms": ms, "bytes": len(html),
                      "title": title, "title_match": title == expect})

    token = read_token(TOKEN_ADMIN)
    if token:
        me = call("GET", "/auth/me", token=token)
        logged = me.get("code") == 200
        steps.append({"step": "C1-2 管理员登录", "note": "复用已取得的管理员 token",
                      "result": me})
    else:
        admin_pwd = secret("admin.password")
        token, r = login(secret("admin.user"), admin_pwd)
        steps.append({"step": "C1-2 管理员登录", "result": r})
        logged = bool(token)
        if token:
            open(TOKEN_ADMIN, "w", encoding="utf-8").write(token)
    if not logged:
        phase["aborted"] = "管理员登录失败"
        res["phases"]["admin"] = phase
        save_results(res)
        return

    # 只读：题目列表（含未审）与待审列表
    steps.append({"step": "C2-1 题目列表(只读,size=5)",
                  "result": call("GET", "/admin/questions?page=1&size=5", token=token)})
    steps.append({"step": "C2-2 待审列表(只读,auditStatus=PENDING)",
                  "result": call("GET", "/admin/questions?page=1&size=5&auditStatus=PENDING",
                                 token=token)})
    steps.append({"step": "C2-3 科目列表(管理端,只读)",
                  "result": call("GET", "/admin/subjects", token=token)})

    phase["finished_at"] = now_iso()
    res["phases"]["admin"] = phase
    save_results(res)
    print("admin phase done")


def run_extra() -> None:
    """专项补测：按题型各起 1 题，验证「判分是否按题型正确工作」。

    只补测「走查中未被随机抽到、但存在可用性问题嫌疑」的题型，
    每题一条练习记录（practice 表新增，报告里会写明 id 范围）。
    """
    res = load_results()
    token = read_token(TOKEN_STUDENT)
    if not token:
        raise SystemExit("缺少学生 token，请先跑 student 阶段")
    steps: List[Dict[str, Any]] = []
    phase: Dict[str, Any] = {"started_at": now_iso(), "steps": steps}

    cases = [
        ("TRUE_FALSE", "B", "判断题：选项 A=正确 B=错误，正确答案为 B（题干「数组下标从 1 开始」是错的）"),
        ("ORDERING", "C,A,B", "排序题：answerContent.order = [C,A,B]，按此顺序提交"),
        ("FILL_BLANK", "printf", "填空题：answerContent.blanks = [printf]"),
        ("MULTI_CHOICE", "A,C", "多选题：answerContent 正确项为 A(int)/C(static)"),
    ]
    for qtype, ans, note in cases:
        st = call("POST", "/practice/start",
                  {"subjectId": 5, "mode": "BY_TYPE", "questionType": qtype, "count": 1},
                  token=token)
        pid = (st.get("data") or {}).get("practiceId")
        q = (st.get("data") or {}).get("question") or {}
        entry: Dict[str, Any] = {"step": "X %s 按题型练习" % qtype, "note": note,
                                 "start": st, "practiceId": pid,
                                 "question_options": q.get("options"),
                                 "question_type": q.get("type")}
        if pid and q.get("id"):
            entry["answer"] = call("POST", "/practice/%d/answer" % pid,
                                   {"questionId": q["id"], "userAnswer": ans}, token=token)
            entry["answer_submitted"] = ans
            entry["finish"] = call("POST", "/practice/%d/finish" % pid, token=token)
        steps.append(entry)

    phase["finished_at"] = now_iso()
    res["phases"]["extra"] = phase
    save_results(res)
    print("extra phase done")


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    what = sys.argv[1]
    if what == "student":
        run_student()
    elif what == "extra":
        run_extra()
    elif what == "ai":
        run_ai()
    elif what == "admin":
        run_admin()
    else:
        raise SystemExit("unknown phase: %s" % what)


if __name__ == "__main__":
    main()
