# -*- coding: utf-8 -*-
"""端到端冒烟：登录 -> 题库筛选 -> 全库随机练习 -> 按年份模考 -> Agent。"""
import json
import sys

import requests

BASE = "http://127.0.0.1:8080/api"
ADMIN = ("admin", "Pv5#6Kjm2KJRHS!_^f5KJwkG")


def show(name, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")
    return ok


def main():
    s = requests.Session()
    r = s.post(f"{BASE}/auth/login", json={"studentNo": ADMIN[0], "password": ADMIN[1]}, timeout=15)
    if r.status_code != 200:
        print("LOGIN HTTP", r.status_code, r.text[:300])
        return 1
    body = r.json()
    data = body.get("data") or {}
    token = data.get("token") or data.get("accessToken") or data.get("access_token")
    if not token:
        print("LOGIN BODY", json.dumps(body, ensure_ascii=False)[:400])
        return 1
    s.headers["Authorization"] = f"Bearer {token}"
    print("login ok, role=", data.get("role"))

    results = []

    r = s.get(f"{BASE}/questions/years", timeout=15)
    years = r.json().get("data") or []
    results.append(show("年份清单", r.status_code == 200 and len(years) >= 5,
                        f"n={len(years)} first={years[0] if years else None}"))

    r = s.get(f"{BASE}/questions/exam-categories", timeout=15)
    cats = r.json().get("data") or []
    results.append(show("类别清单", r.status_code == 200 and len(cats) >= 1, f"{cats[:3]}"))

    r = s.get(f"{BASE}/questions", params={"year": 2022, "page": 1, "size": 3}, timeout=15)
    d = r.json().get("data") or {}
    recs = d.get("records") or d.get("list") or []
    years_ok = all(x.get("year") == 2022 for x in recs) if recs else False
    results.append(show("按年份筛题", r.status_code == 200 and len(recs) > 0 and years_ok,
                        f"n={len(recs)} years={[x.get('year') for x in recs]}"))

    # 全库随机：不传 subjectId / examCode / year
    r = s.post(f"{BASE}/practice/start",
               json={"mode": "RANDOM", "count": 5}, timeout=20)
    d = r.json().get("data") or {}
    results.append(show("全库随机练习", r.status_code == 200 and d.get("practiceId"),
                        f"practiceId={d.get('practiceId')} total={d.get('totalCount')} keys={list(d)[:8]}"))

    # 按年份随机
    r = s.post(f"{BASE}/practice/start",
               json={"mode": "RANDOM", "count": 5, "year": 2020}, timeout=20)
    d20 = r.json().get("data") or {}
    results.append(show("按年份随机练习", r.status_code == 200 and d20.get("practiceId"),
                        f"recordId={d20.get('recordId')}"))

    # 模拟考试：按年份组卷
    r = s.post(f"{BASE}/exams/mock/start",
               json={"count": 5, "durationMinutes": 10, "year": 2021}, timeout=30)
    m = r.json().get("data") or {}
    results.append(show("按年份模拟考试", r.status_code == 200 and m.get("recordId"),
                        f"recordId={m.get('recordId')} name={m.get('paperName') or m.get('name')}"))

    # Agent（SSE 首帧）
    try:
        r = s.post(f"{BASE}/ai/agent/stream",
                   json={"question": "什么是 RAG 检索增强生成？"},
                   timeout=120, stream=True)
        head = ""
        for chunk in r.iter_content(chunk_size=256, decode_unicode=True):
            if chunk:
                head += chunk if isinstance(chunk, str) else chunk.decode("utf-8", "ignore")
            if len(head) > 600:
                break
        ok = r.status_code == 200 and ("event:" in head or "data:" in head)
        results.append(show("Agent 流式响应", ok, f"http={r.status_code} head={head[:180]!r}"))
    except Exception as e:
        results.append(show("Agent 流式响应", False, f"{type(e).__name__}: {e}"))

    print("\nSUMMARY", sum(1 for x in results if x), "/", len(results))
    return 0 if all(results) else 2


if __name__ == "__main__":
    sys.exit(main())
