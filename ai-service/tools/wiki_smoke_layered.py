"""分层代表性冒烟（只读、不写库、不推向量）——「修复前基线 vs 修复后」同条件对比。

用途：补第二段独立验收 #9「4/5 兜底率只取自 5 个超大文档簇、可能高估全量」的
样本代表性缺口。按 ai_chunk ACTIVE 块数（chunk_count）把候选文档分成 大/中/小
三层，每层取固定数量簇，用与离线批量完全相同的取数函数与蒸馏内核跑一遍，
只把观测结果落到 --out 指定的 JSON，供两次运行（基线 / 修复版）逐字段对比。

只读保证：脚本内唯一的数据库动作是 pymysql.connect(**db_config())；所有查询都由
distill_wiki_batch 的 select_docs / build_clusters / load_catalog 完成（纯读）。
不写库、不推摘要向量、不调用服务 HTTP 接口、不启停任何服务、不跑全量。

LLM 推理由主 agent 独占 Ollama 执行：本脚本只是「带观测的调用壳」，运行时
distill_cluster 内部仍会真实调用 _chat，脚本本身不提供任何跳过推理的开关。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

import pymysql

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(__file__).resolve().parent
for _p in (str(ROOT), str(TOOLS)):
    if _p not in sys.path:
        sys.path.append(_p)

from app.config import get_settings  # noqa: E402
from app.db import db_config  # noqa: E402
import app.wiki.distiller as distiller  # noqa: E402
import distill_wiki_batch as batch  # noqa: E402

# 分层口径：按文档 ai_chunk ACTIVE 块数 chunk_count 划三层
LARGE_MIN = 500
MEDIUM_MIN = 20
SMALL_MIN = batch.MIN_DOC_CHUNKS          # 3，select_docs 的 HAVING 已保证下界
MAX_PER_LAYER = 4                          # 每层上限，保证总簇数 <= 12
LAYER_NAMES = ("大", "中", "小")

#: 当前簇的原始输出收集器。逐簇顺序 await、无并发，故单槽即可。
_CAPTURE: Dict[str, List[str]] = {"raws": []}


def _layer_of(chunk_count: int) -> str:
    if chunk_count >= LARGE_MIN:
        return "大"
    if chunk_count >= MEDIUM_MIN:
        return "中"
    return "小"


def split_layers(docs: List[Dict[str, Any]],
                 per_layer: int) -> Dict[str, List[Dict[str, Any]]]:
    """分层逻辑（复核点之一）：按 chunk_count 分 大/中/小 三层。

    select_docs 已按 chunk_count 降序返回，这里保持原顺序分桶，再各取前
    per_layer 个；某层不足则自然全取（切片不会补齐）。
    """
    buckets: Dict[str, List[Dict[str, Any]]] = {name: [] for name in LAYER_NAMES}
    for doc in docs:
        buckets[_layer_of(int(doc["chunk_count"]))].append(doc)
    return {name: items[:per_layer] for name, items in buckets.items()}


class _FailureCapture(logging.Handler):
    """收集 [WikiDistill] ... 失败：... / 回退抽取式 日志，供 result.error 取用。"""

    def __init__(self) -> None:
        super().__init__(level=logging.WARNING)
        self.messages: List[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.messages.append(record.getMessage())
        except Exception:
            pass


def _wrap_chat(original):
    """把 distiller._chat 换成「只观测、不改语义」的包装函数。

    复核点之一（记录后仍调原函数）：distill_cluster 在函数体里以模块全局名
    _chat 发起调用，替换模块属性后即走到这里。包装层不绕过任何校验、不短路、
    不替换假数据，只把原函数真实返回值记进 raws 再原样交回。
    """
    async def wrapped(llm_settings, user_prompt, *, strict_json):
        # ① 真实 await 原函数：JSON/引用/接地校验与重试全部由 distiller 自己完成
        raw = await original(llm_settings, user_prompt, strict_json=strict_json)
        # ② 把本次返回的原始字符串追加到「当前簇」的 raws
        _CAPTURE["raws"].append(raw if isinstance(raw, str) else str(raw))
        # ③ 原样返回 ① 的真实结果（等价于 return await original(...)），绝不返回伪造数据；
        #    若 original 抛异常，异常同样原样向上传播，不在这里吞掉
        return raw
    return wrapped


def _last_failure(messages: List[str]) -> str:
    """取最后一次失败/回退日志作为 error（成功簇返回空串）。"""
    for msg in reversed(messages):
        if "失败" in msg or "回退" in msg:
            return msg
    return ""


async def run_all(clusters, catalog, settings, layer_by_slug, chunk_by_slug, emit):
    original_chat = distiller._chat
    distiller._chat = _wrap_chat(original_chat)  # monkeypatch：内部 _chat 全局名随之生效
    handler = _FailureCapture()
    distiller.logger.addHandler(handler)
    results: List[Dict[str, Any]] = []
    try:
        for index, cluster in enumerate(clusters, start=1):
            slug = str(cluster["slug"])
            _CAPTURE["raws"] = []            # 每簇独立收集原始输出
            handler.messages.clear()         # 每簇独立收集失败日志
            started = time.perf_counter()
            draft: Dict[str, Any] = {}
            used_fallback: Any = None
            error = ""
            try:
                draft, used_fallback = await distiller.distill_cluster(cluster, catalog, settings)
            except Exception as exc:         # 单簇异常不中断整批，记进该簇 error
                error = f"{type(exc).__name__}: {exc}"
            if not error:
                error = _last_failure(handler.messages)
            raws = list(_CAPTURE["raws"])
            content = draft.get("content") or ""
            summary = draft.get("summary") or ""
            ids = draft.get("source_chunk_ids") or []
            rec = {
                "slug": slug,
                "layer": layer_by_slug.get(slug, ""),
                "chunk_count": chunk_by_slug.get(slug, 0),
                "fallback": used_fallback,
                "model": draft.get("model"),
                "quality": draft.get("quality_score"),
                "content_len": len(content),
                "summary_len": len(summary),
                # 补 draft_content / draft_summary：消除第四段独立验收指出的证据缺口
                # （旧证据只有长度与 raws，无法直接读出「修复后正文是否仍含 Python repr」）。
                # 保存正文/摘要的完整原文、不截断，供未来重跑冒烟直接核对。
                "draft_content": content,
                "draft_summary": summary,
                "ids_len": len(ids),
                "llm_calls": len(raws),
                "elapsed_s": round(time.perf_counter() - started, 2),
                "error": error,
                "raws": raws,
            }
            results.append(rec)
            emit(f"  [{index}/{len(clusters)}] {slug} layer={rec['layer']} "
                 f"chunks={rec['chunk_count']} fallback={rec['fallback']} "
                 f"model={rec['model']} q={rec['quality']} "
                 f"content={rec['content_len']} summary={rec['summary_len']} "
                 f"ids={rec['ids_len']} calls={rec['llm_calls']} "
                 f"{rec['elapsed_s']}s"
                 + (f" error={error[:140]}" if error else ""))
    finally:
        distiller.logger.removeHandler(handler)
        distiller._chat = original_chat    # 复原，避免污染同进程后续调用
    return results


def _make_emit(log_path):
    handle = None
    if log_path:
        path = Path(log_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        handle = path.open("w", encoding="utf-8")

    def emit(line=""):
        print(line, flush=True)
        if handle:
            handle.write(line + "\n")
            handle.flush()
    return emit, handle


def main() -> int:
    ap = argparse.ArgumentParser(
        description="分层代表性 Wiki 冒烟（只读、不写库、不推向量）：修复前基线 vs 修复后 同条件对比")
    ap.add_argument("--out", required=True,
                    help="结果 JSON 路径（top key: meta / results）")
    ap.add_argument("--log", default="",
                    help="可选：把同样的终端输出另存一份日志（末行仍为 SMOKE_FALLBACK）")
    ap.add_argument("--per-layer", type=int, default=4,
                    help="每层取几个簇（1..4，默认 4；总簇数 <= 12）")
    args = ap.parse_args()

    emit, log_handle = _make_emit(args.log)
    try:
        # clamp 到 1..4，保证「总簇数 <= 12」对任意入参恒成立
        per_layer = min(max(args.per_layer, 1), MAX_PER_LAYER)
        emit(f"per-layer 生效值={per_layer}（上限 {MAX_PER_LAYER}，"
             f"总簇数 <= {MAX_PER_LAYER * len(LAYER_NAMES)}）")

        settings = get_settings()
        conn = pymysql.connect(**db_config())
        try:
            docs = batch.select_docs(conn, 0, "")   # 全部候选，按 chunk_count 降序
            layers = split_layers(docs, per_layer)
            selected = [(name, doc) for name in LAYER_NAMES for doc in layers[name]]
            for name in LAYER_NAMES:
                emit(f"[分层] {name}层 选中 {len(layers[name])} 个 "
                     f"chunk_count={[int(d['chunk_count']) for d in layers[name]]}")
            clusters = batch.build_clusters(conn, [doc for _, doc in selected])
            catalog = batch.load_catalog(conn)
            # catalog 保持恒定、不随蒸馏结果追加新 slug：两次对比的输入条件完全一致
            emit(f"候选文档 {len(docs)} 个，分层选中 {len(selected)} 个簇，"
                 f"目录现有 {len(catalog)} 页")
        finally:
            conn.close()

        layer_by_slug = {f"doc-{doc['doc_id']}": name for name, doc in selected}
        chunk_by_slug = {f"doc-{doc['doc_id']}": int(doc["chunk_count"])
                         for _, doc in selected}
        results = asyncio.run(run_all(clusters, catalog, settings,
                                      layer_by_slug, chunk_by_slug, emit))

        parts = []
        for name in LAYER_NAMES:
            layer_results = [r for r in results if r["layer"] == name]
            x = sum(1 for r in layer_results if r["fallback"] is True)
            parts.append(f"{name} {x}/{len(layer_results)}")
        emit("LAYER_FALLBACK=" + " / ".join(parts))

        # --out（复核点之一）：{"meta": {...}, "results": [...]}，绝不写库
        meta = {
            "script": str(Path(__file__).resolve()),
            "timestamp": datetime.now().isoformat(timespec="seconds"),
            "model": settings.llm.model,
            "generator": settings.llm.generator_name,
            "layer_def": {
                "大": f"chunk_count >= {LARGE_MIN}",
                "中": f"{MEDIUM_MIN} <= chunk_count <= {LARGE_MIN - 1}",
                "小": f"{SMALL_MIN} <= chunk_count <= {MEDIUM_MIN - 1}",
            },
            "per_layer": per_layer,
            "total_clusters": len(clusters),
        }
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps({"meta": meta, "results": results},
                       ensure_ascii=False, indent=2),
            encoding="utf-8")
        emit(f"已写出 {out_path}")

        total_x = sum(1 for r in results if r["fallback"] is True)
        emit(f"SMOKE_FALLBACK= {total_x} / {len(results)}")
    finally:
        if log_handle:
            log_handle.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
