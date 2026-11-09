# -*- coding: utf-8 -*-
"""降级演练取证探针。

用法:
    python probe.py <label> [--ood]

对 /api/ai/ask 与 /api/ai/capability 发起真实请求，输出一行 JSON，
包含 HTTP 状态码、耗时与关键字段，便于演练过程中反复取证。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any, Dict

BASE = "http://127.0.0.1:8080"
TOKEN = (
    "eyJhbGciOiJIUzM4NCJ9.eyJ1aWQiOjMsInJvbGUiOiJTVFVERU5UIiwic3R1IjoiMjAyMTAw"
    "MSIsInN1YiI6IjMiLCJpYXQiOjE3OTQyMjkyODcsImV4cCI6MTc5NDgzNDA4N30."
    "T-Fw-4kusEHHMuCGFOgo1EPTcCzYxUboIsiz9GkbhLxD4DEfnUpRZ2Ik5BvFBXAU"
)
ASK_PATH = "/api/ai/ask"
CAP_PATH = "/api/ai/capability"
QUESTION_IN = "进程和线程有什么区别"
QUESTION_OOD = "红烧肉怎么做"


def request(path: str, payload: Any = None, timeout: int = 120) -> Dict[str, Any]:
    """发起一次 HTTP 请求并返回状态码、耗时与解析后的 JSON。"""
    url = BASE + path
    data = None
    headers = {"Authorization": "Bearer " + TOKEN}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    started = time.time()
    status = -1
    body = ""
    err = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.getcode()
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:  # 4xx/5xx 也要取证
        status = exc.code
        body = exc.read().decode("utf-8", "replace")
        err = "HTTPError"
    except Exception as exc:  # 连接失败（AI 服务被停时可出现）
        err = type(exc).__name__ + ": " + str(exc)[:200]
    elapsed_ms = int((time.time() - started) * 1000)
    parsed = None
    if body:
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError:
            parsed = None
    return {
        "path": path,
        "http": status,
        "elapsed_ms": elapsed_ms,
        "error": err,
        "parsed": parsed,
        "raw_head": body[:400],
    }


def main() -> int:
    """执行一次完整取证（ask + capability），打印单行 JSON。"""
    label = sys.argv[1] if len(sys.argv) > 1 else "probe"
    ood = "--ood" in sys.argv
    question = QUESTION_OOD if ood else QUESTION_IN
    result: Dict[str, Any] = {
        "label": label,
        "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "question": question,
    }
    ask = request(ASK_PATH, {"question": question})
    data = (ask.get("parsed") or {}).get("data") or {}
    result["ask"] = {
        "http": ask["http"],
        "elapsed_ms": ask["elapsed_ms"],
        "error": ask["error"],
        "generator": data.get("generator"),
        "degraded": data.get("degraded"),
        "refused": data.get("refused"),
        "engine": data.get("engine"),
        "sources": len(data.get("sources") or []),
        "cost_millis": data.get("costMillis"),
        "retrieval_mode": data.get("retrievalMode"),
        "answer_len": len(data.get("answer") or ""),
        "answer_head": (data.get("answer") or "")[:120],
        "raw_head": ask["raw_head"],
    }
    cap = request(CAP_PATH)
    cdata = (cap.get("parsed") or {}).get("data") or {}
    result["capability"] = {
        "http": cap["http"],
        "elapsed_ms": cap["elapsed_ms"],
        "error": cap["error"],
        "aiServiceStatus": cdata.get("aiServiceStatus"),
        "vectorChannelAvailable": cdata.get("vectorChannelAvailable"),
        "vectorIndexReady": cdata.get("vectorIndexReady"),
        "generator": cdata.get("generator"),
        "degraded": cdata.get("degraded"),
        "circuitOpen": cdata.get("circuitOpen"),
        "circuitRemainingMillis": cdata.get("circuitRemainingMillis"),
        "chunkCount": cdata.get("chunkCount"),
        "extractiveMode": cdata.get("extractiveMode"),
    }
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
