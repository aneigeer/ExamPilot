# -*- coding: utf-8 -*-
"""模拟面试端到端：开始 -> 取题 -> 作答 -> 评分。"""
import json
import sys

import requests

BASE = "http://127.0.0.1:8080/api"
ADMIN = ("admin", "Pv5#6Kjm2KJRHS!_^f5KJwkG")


def main():
    s = requests.Session()
    r = s.post(f"{BASE}/auth/login", json={"studentNo": ADMIN[0], "password": ADMIN[1]}, timeout=15)
    token = (r.json().get("data") or {}).get("token")
    if not token:
        print("LOGIN FAIL", r.text[:300])
        return 1
    s.headers["Authorization"] = f"Bearer {token}"

    topic = sys.argv[1] if len(sys.argv) > 1 else "RAG"
    r = s.post(f"{BASE}/ai/interview/start",
               json={"topic": topic, "total": 1}, timeout=180)
    print("HTTP", r.status_code)
    body = r.json()
    print(json.dumps(body, ensure_ascii=False)[:1200])
    data = body.get("data") or {}
    sid = data.get("session_id") or data.get("sessionId") or data.get("id")
    q = data.get("question") or data.get("firstQuestion") or (data.get("questions") or [{}])[0]
    if not sid or not q:
        print("NO_SESSION_OR_QUESTION")
        return 2

    r = s.post(f"{BASE}/ai/interview/answer",
               json={"session_id": sid, "answer": "RAG 是检索增强生成：先从知识库检索相关片段，再把片段作为上下文交给大模型生成答案，可以降低幻觉并支持引用。"},
               timeout=180)
    print("ANSWER HTTP", r.status_code)
    print(json.dumps(r.json(), ensure_ascii=False)[:1200])
    return 0


if __name__ == "__main__":
    sys.exit(main())
