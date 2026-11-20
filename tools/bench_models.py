# -*- coding: utf-8 -*-
"""本地模型选型对比：同一批备考问题，跑多个 Ollama 模型，按「正确性/结构/引用/延迟」打分。

用法
----
    .venv-ai\\Scripts\\python.exe tools\\bench_models.py
    .venv-ai\\Scripts\\python.exe tools\\bench_models.py --models qwen2.5-7b-instruct-baseline qwen3:8b
"""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.request

OLLAMA = "http://127.0.0.1:11434/api/chat"

SYSTEM = (
    "你是一名耐心的备考助教，面向大学生讲题、答疑和给学习建议。你只能依据【参考资料】作答，"
    "但要把资料讲成学生听得懂的话。\n"
    "输出结构：1) 一句话结论；2) 分点讲解 2-4 点；3) 易错点或记忆提示；4) 最后一行「依据：[编号]」。\n"
    "资料只覆盖一部分时，先答覆盖部分，再写「资料未覆盖：X」；完全没有资料、或资料主题与问题无关时，只输出「未找到相关资料」。"
    "300-500 字。"
)

CASES = [
    {
        "q": "什么是RAG检索增强生成？",
        "ctx": "RAG（Retrieval-Augmented Generation）先在知识库中检索与问题相关的片段，"
               "再把片段作为上下文交给大模型生成答案，可以降低幻觉并支持引用溯源。",
        "expect": "answer",
    },
    {
        "q": "TCP 三次握手为什么必须三次？",
        "ctx": "TCP 建立连接需要三次握手：客户端发送 SYN，服务端回复 SYN+ACK，客户端再发送 ACK。"
               "目的：确认双方收发能力正常、防止旧连接请求导致错误连接、同步初始序列号 ISN。",
        "expect": "answer",
    },
    {
        "q": "红烧肉怎么做？",
        "ctx": "TCP 建立连接需要三次握手：客户端发送 SYN，服务端回复 SYN+ACK，客户端再发送 ACK。",
        "expect": "refuse",
    },
]


def chat(model: str, question: str, ctx: str) -> tuple[str, float]:
    user = ""
    if ctx:
        user += f"【参考资料】\n[1] {ctx}\n\n"
    user += f"【问题】\n{question}"
    body = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
        "stream": False,
        "think": False,
        "options": {"temperature": 0.2, "num_ctx": 4096, "num_predict": 700},
    }
    req = urllib.request.Request(OLLAMA, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("message", {}).get("content", ""), time.time() - start


def score(answer: str, expect: str) -> dict:
    text = answer or ""
    has_conclusion = bool(re.search(r"(结论|总之|简单说)", text))
    points = len(re.findall(r"(?m)^\s*[1-9][.、)]", text))
    has_pitfall = bool(re.search(r"易错|注意|记忆", text))
    has_cite = bool(re.search(r"依据\s*[:：]\s*\[?\d", text))
    refused = text.strip().startswith("未找到相关资料") or len(text) < 20
    # 重复句检测：3B 常把同一句换三个小标题重复
    sents = [s.strip() for s in re.split(r"[。！？\n]", text) if len(s.strip()) >= 12]
    repeat = 1 - len(set(sents)) / len(sents) if sents else 0.0

    structure = int(has_conclusion) + int(points >= 2) + int(has_pitfall)
    correct = (refused if expect == "refuse" else (not refused and len(text) >= 60))
    return {
        "structure": structure,
        "cite": int(has_cite),
        "repeat": round(repeat, 2),
        "correct": int(bool(correct)),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="*", default=[
        "qwen2.5-3b-instruct-q8_0-baseline",
        "qwen2.5-7b-instruct-baseline",
        "exam-prep-7b",
    ])
    args = ap.parse_args()

    results = {}
    for model in args.models:
        total = {"structure": 0, "cite": 0, "correct": 0, "latency": 0.0, "chars": 0}
        print(f"\n===== {model} =====", flush=True)
        for case in CASES:
            try:
                answer, cost = chat(model, case["q"], case["ctx"])
            except Exception as exc:  # noqa: BLE001
                print(f"  {case['q'][:16]:18s} ERR {type(exc).__name__}: {exc}", flush=True)
                total["correct"] -= 1
                continue
            s = score(answer, case["expect"])
            total["structure"] += s["structure"]
            total["cite"] += s["cite"]
            total["correct"] += s["correct"]
            total["latency"] += cost
            total["chars"] += len(answer)
            print(f"  {case['q'][:16]:18s} {cost:5.1f}s len={len(answer):4d} "
                  f"结构={s['structure']}/3 引用={s['cite']} 重复={s['repeat']:.2f} 正确={s['correct']}",
                  flush=True)
        results[model] = total

    print("\n===== 汇总（正确性优先，其次结构/引用/延迟）=====")
    for model, t in sorted(results.items(),
                           key=lambda kv: (-kv[1]["correct"], -kv[1]["structure"], -kv[1]["cite"],
                                           kv[1]["latency"])):
        print(f"{model:36s} 正确 {t['correct']}/{len(CASES)}  结构 {t['structure']}/9  "
              f"引用 {t['cite']}  总耗时 {t['latency']:5.1f}s  平均 {t['chars']//max(1,len(CASES))} 字")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
