"""Router / Subagent 离线 A/B 评测（可重复执行）。

对比三组配置：
  A 基线：route=false, subagent=false
  B 仅 Router：route=true, subagent=false
  C 仅 Subagent：route=false, subagent=true

调用方式（先分别用不同端口/环境变量启动三个 AI 服务实例）：

  ROUTE_ENABLED=false AGENT_SUBAGENT_ENABLED=false uvicorn app.main:app --port 8011
  ROUTE_ENABLED=true  AGENT_SUBAGENT_ENABLED=false uvicorn app.main:app --port 8012
  ROUTE_ENABLED=false AGENT_SUBAGENT_ENABLED=true  uvicorn app.main:app --port 8013

  python -m eval.ab_router_subagent \
      --baseline http://127.0.0.1:8011 --route http://127.0.0.1:8012 \
      --subagent http://127.0.0.1:8013 --out ../runtime/ab_router_subagent_report.md

只读脚本：不修改任何配置默认值（route.enabled 默认仍为 false）。
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List

import httpx

#: 多子问题 / 复合问题集（覆盖需要拆解与不需要拆解两类）
QUESTIONS = [
    "帮我对比一下进程和线程的区别，再给两道相关练习题",
    "我四级阅读总是错主旨题，应该怎么练？顺便推荐一个两周计划",
    "解释 TCP 三次握手，并说明为什么不能是两次；再举一个攻击例子",
    "数据结构的栈和队列分别适合什么场景？各给一个代码例子",
    "我薄弱的知识点是哪些？今天应该先刷什么题？",
    "线性代数特征值怎么求？和相似对角化什么关系？",
    "这道题不会：求极限 lim(x->0) (sin x - x)/x^3",
    "帮我制定考研数学最后 30 天的复习安排",
]


@dataclass
class ModeResult:
    name: str
    latencies: List[float] = field(default_factory=list)
    refused: int = 0
    total: int = 0
    tokens: int = 0
    sources: int = 0

    def add(self, latency: float, refused: bool, tokens: int, sources: int) -> None:
        self.latencies.append(latency)
        self.total += 1
        self.refused += 1 if refused else 0
        self.tokens += tokens
        self.sources += sources

    def summary(self) -> Dict[str, Any]:
        def pct(p: float) -> float:
            if not self.latencies:
                return 0.0
            ordered = sorted(self.latencies)
            index = min(len(ordered) - 1, max(0, int(round(p * (len(ordered) - 1)))))
            return round(ordered[index], 1)

        return {
            "runs": self.total,
            "p50_ms": pct(0.50),
            "p95_ms": pct(0.95),
            "refusal_rate": round(self.refused / self.total, 4) if self.total else 0.0,
            "avg_tokens": round(self.tokens / self.total, 1) if self.total else 0.0,
            "avg_sources": round(self.sources / self.total, 2) if self.total else 0.0,
        }


def _resolve_internal_token() -> str:
    import os
    from pathlib import Path
    token = os.environ.get("AI_INTERNAL_TOKEN", "").strip()
    if token:
        return token
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if env_file.is_file():
        for raw in env_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw.strip()
            if line.startswith("AI_INTERNAL_TOKEN="):
                return line.partition("=")[2].strip().strip('"').strip("'")
    return ""


def run_mode(base_url: str, name: str, timeout: float = 180.0, limit: int = 0) -> ModeResult:
    result = ModeResult(name=name)
    token = _resolve_internal_token()
    headers = {"X-Internal-Token": token} if token else {}
    qs = QUESTIONS[:limit] if limit and limit > 0 else QUESTIONS
    with httpx.Client(timeout=timeout, trust_env=False) as client:
        for question in qs:
            started = time.perf_counter()
            payload = {"question": question, "sessionId": f"ab-{name}", "userId": 3}
            final: Dict[str, Any] = {}
            try:
                with client.stream("POST", f"{base_url}/v1/agent/stream",
                                   json=payload, headers=headers) as response:
                    if response.status_code != 200:
                        final = {"errorCode": f"HTTP_{response.status_code}"}
                    else:
                        event_name = ""
                        for line in response.iter_lines():
                            if line.startswith("event:"):
                                event_name = line.split(":", 1)[1].strip()
                            elif line.startswith("data:") and event_name == "final":
                                final = json.loads(line.split(":", 1)[1].strip())
                            elif line.startswith("data:") and event_name == "done":
                                break
            except Exception as exc:  # noqa: BLE001 - 单个问题失败不终止整组
                final = {"errorCode": type(exc).__name__}
            latency = round((time.perf_counter() - started) * 1000, 1)
            result.add(
                latency=latency,
                refused=bool(final.get("refused")) or bool(final.get("errorCode")),
                tokens=int(final.get("totalTokens") or 0),
                sources=len(final.get("sources") or []),
            )
            print(f"[{name}] {latency:>7.0f}ms refused={final.get('refused')} "
                  f"err={final.get('errorCode')} tokens={final.get('totalTokens')} "
                  f"sources={len(final.get('sources') or [])} q={question[:24]}")
    return result


def markdown(results: List[ModeResult]) -> str:
    lines = ["# Router / Subagent A/B 评测报告", ""]
    lines.append("| 模式 | 运行数 | P50 (ms) | P95 (ms) | 拒答/失败率 | 平均 Token | 平均来源数 |")
    lines.append("|---|---|---|---|---|---|---|")
    for item in results:
        s = item.summary()
        lines.append(f"| {item.name} | {s['runs']} | {s['p50_ms']} | {s['p95_ms']} | "
                     f"{s['refusal_rate']} | {s['avg_tokens']} | {s['avg_sources']} |")
    lines.append("")
    base = results[0].summary() if results else {}
    for item in results[1:]:
        s = item.summary()
        if base and base.get("p95_ms"):
            delta = round((s["p95_ms"] - base["p95_ms"]) / base["p95_ms"] * 100, 1)
            lines.append(f"- {item.name} 相对基线 P95 变化：{delta:+.1f}%")
    lines.append("")
    lines.append("## 结论建议（按数据判断，不修改 route.enabled 默认值）")
    lines.append("- 若 B 组 P95 明显升高而来源数/拒答率无改善，保持 route.enabled=false。")
    lines.append("- 若 C 组在多子问题集上来源覆盖提升但 Token 翻倍，仅在用户显式要求“拆解/对比”时开启。")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True, help="route=false, subagent=false 的实例")
    parser.add_argument("--route", required=False, help="route=true 的实例")
    parser.add_argument("--subagent", required=False, help="subagent=true 的实例")
    parser.add_argument("--out", default="../runtime/ab_router_subagent_report.md")
    parser.add_argument("--limit", type=int, default=0, help="每组只跑前 N 题（0=全部 8 题）")
    args = parser.parse_args()

    results = [run_mode(args.baseline, "A 基线", limit=args.limit)]
    if args.route:
        results.append(run_mode(args.route, "B 仅 Router", limit=args.limit))
    if args.subagent:
        results.append(run_mode(args.subagent, "C 仅 Subagent", limit=args.limit))

    report = markdown(results)
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write(report)
    print(f"\n报告已写入 {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
