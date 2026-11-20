#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""真题册（PAPER）COURSEWARE 语料块补齐脚本 —— 只写 ai_chunk，不动题目。

================================================================================
为什么需要这个脚本
================================================================================
`scripts/import_question_bank.py` 的 PAPER 分支（process_record 内
"真题册：规则切单选题" 那一段）只调用 insert_question(...) 造题，
随后 `update_source_status(conn, rec.id, status, written, 0, note)`
把 chunk 数**写死为 0**，从头到尾没有调用 upsert_chunks。

后果：303 个真题册（doc_kind='PAPER' 且 parse_status in ('PARSED','PARTIAL')）
切出了 7783 道题，但 question_source.chunk_count=0、ai_chunk 中没有任何
对应 COURSEWARE 块 —— 试卷的解析正文（长文语料）对 RAG 检索完全不可见。

本脚本只补这一块：为这些真题册重新抽取正文 → 切块 → 写 ai_chunk。

================================================================================
补什么（严格边界）
================================================================================
* 只 INSERT ... ON DUPLICATE KEY UPDATE `ai_chunk`；
* **绝不** UPDATE / DELETE `question`、`question_source`；
* 连 question_source.chunk_count 也不动 —— 它由导入流水线的口径负责，
  两边同时写会互相覆盖、口径打架；需要看块数请查 ai_chunk 本身：
      SELECT COUNT(*) FROM ai_chunk
       WHERE source_type='COURSEWARE' AND source_id = 1000000 + <question_source.id>;
* 不改 PENDING / FAILED 记录的状态，不启动 OCR，不编造任何内容。

================================================================================
幂等说明
================================================================================
复用 `import_question_bank.upsert_chunks()`，它按
`uk_source_chunk(source_type, source_id, chunk_no)` 做
`INSERT ... ON DUPLICATE KEY UPDATE`：

* 同一文件重复运行 → 块号相同、内容相同 → 只会把 title/content/hash
  重写一遍，**不会产生重复行**；
* 源文件正文变了 → chunk_no 覆盖为新内容，content_hash 同步更新
  （下游 ChunkSyncService 靠 vector_synced=0 感知需要重算向量）；
* 中途失败的文件整文件回滚，下次重跑自动重试。

================================================================================
与 import_question_bank.py 的关系
================================================================================
**只复用、不修改。** 本脚本 import 该模块，直接调用
    extract_record_text(rec)   抽文件全文（PDF 走 pypdf 子进程 + runtime/pdf_text_cache 缓存）
    chunk_text(text, title)    按段落聚合切块（CHUNK_TARGET=600 / OVERLAP=80）
    upsert_chunks(conn, rec, chunks, subject_id, category_id, has_vector_col)
                               source_type='COURSEWARE'、source_id=1000000+question_source.id
    Record / db_connect / table_columns / load_db_password / CHUNK_* 常量
不复制、不改写任何切块 / 写块逻辑，保证与既有语料块口径**完全一致**
（同样的 chunk_no 起点 0、同样的 title=paper_name、同样的 difficulty=3）。

模块 import 阶段无任何副作用：不连库、不读文件、不跑逻辑，
所有动作都在 main() 里。

================================================================================
运行命令
================================================================================
    # 1) 干跑：只读统计，不写库（安全，可随时跑）
    .venv-ai/Scripts/python.exe scripts/backfill_paper_chunks.py --dry-run

    # 2) 小批量试跑（真写库，前 5 个文件）
    .venv-ai/Scripts/python.exe scripts/backfill_paper_chunks.py --limit 5

    # 3) 只处理某一种状态
    .venv-ai/Scripts/python.exe scripts/backfill_paper_chunks.py --only-status PARTIAL

    # 4) 正式全量（由主 agent 执行）
    .venv-ai/Scripts/python.exe scripts/backfill_paper_chunks.py

口令从环境变量 DB_PASSWORD 或 ai-service/.env 读取（复用 load_db_password），
不硬编码、不打印。

================================================================================
怎么撤销（回滚）
================================================================================
本脚本只写 ai_chunk，撤销就是删掉对应 source_id 的块。两种粒度：

    -- 单个文件（先查出 question_source.id）
    DELETE FROM ai_chunk
     WHERE source_type = 'COURSEWARE'
       AND source_id = 1000000 + <question_source.id>;

    -- 全部真题册语料块（注意：COURSEWARE 偏移段里还有别的资料块，
    -- 只删确实来自 PAPER 的那批 id，别整段清；下面按 question_source 关联删）
    DELETE c FROM ai_chunk c
      JOIN question_source s ON c.source_id = 1000000 + s.id
     WHERE c.source_type = 'COURSEWARE'
       AND s.doc_kind = 'PAPER'
       AND s.parse_status IN ('PARSED','PARTIAL');

题目、状态、计数都不受影响 —— 因为它们本来就没被本脚本碰过。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Set, Tuple

# --------------------------------------------------------------------------------------
# 复用既有导入脚本：只 import，不修改（sys.path 里加 scripts/ 保证同目录可导入）
# --------------------------------------------------------------------------------------
SCRIPTS_DIR: Path = Path(__file__).resolve().parent
PROJECT_ROOT: Path = SCRIPTS_DIR.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import import_question_bank as qb  # noqa: E402  （必须在 sys.path 调整之后）

# --------------------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------------------

#: 目标集合：真题册，且已切出题目（PARSED / PARTIAL）
TARGET_DOC_KIND: str = "PAPER"
TARGET_STATUSES: Tuple[str, ...] = ("PARSED", "PARTIAL")

#: 与 ai_chunk 契约完全一致（取自 import_question_bank，避免各写一份偏移）
CHUNK_SOURCE_TYPE: str = qb.CHUNK_SOURCE_TYPE
CHUNK_SOURCE_OFFSET: int = qb.CHUNK_SOURCE_OFFSET

#: 逐文件失败清单（纯文本登记产物，不是数据库）
FAIL_LIST_PATH: Path = PROJECT_ROOT / "runtime" / "backfill_paper_fail.txt"
DRY_RUN_FAIL_LIST_PATH: Path = PROJECT_ROOT / "runtime" / "backfill_paper_fail.dryrun.txt"

#: 一批 IN (...) 查询的 id 数量上限（避免超长 SQL）
ID_BATCH: int = 500


def log(msg: str) -> None:
    """统一走 qb.log：同一份输出风格，flush=True 保证重定向到日志时实时可见。"""
    qb.log(msg)


# --------------------------------------------------------------------------------------
# 只读查询：目标记录 / 已有块数 / subject+category 外键
# --------------------------------------------------------------------------------------


def fetch_target_records(conn, only_status: str, limit: int) -> List[qb.Record]:  # type: ignore[no-untyped-def]
    """从 question_source 读目标真题册，构造 qb.Record（只读，不写库）。

    Args:
        conn: pymysql 连接（只读用途）。
        only_status: 空字符串表示 PARSED + PARTIAL；否则只取指定状态。
        limit: 0 表示不限，>0 时只取前 N 个（按 id 升序，结果可复现）。

    Returns:
        按 id 升序排列的 Record 列表。
    """
    statuses: Sequence[str] = (only_status,) if only_status else TARGET_STATUSES
    placeholders = ",".join(["%s"] * len(statuses))
    sql = (
        "SELECT id, source_path, file_name, file_ext, file_size, sha256, exam_code, "
        "       subject_name, year, paper_name, doc_kind, text_layer, parse_status, note "
        "FROM question_source "
        "WHERE doc_kind = %s AND parse_status IN (%s) "
        "ORDER BY id" % ("%s", placeholders)
    )
    params: List[object] = [TARGET_DOC_KIND, *statuses]

    with conn.cursor() as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()

    records: List[qb.Record] = []
    for row in rows:
        (rid, spath, fname, fext, fsize, sha, ecode, sname, year, pname,
         kind, layer, status, note) = row
        spath_str = str(spath)
        root = "A" if spath_str.startswith(qb.ROOT_A.as_posix()) else "B"
        records.append(qb.Record(
            root=root,
            path=Path(spath_str),
            rel="",
            id=int(rid),
            file_name=fname or Path(spath_str).name,
            file_ext=(fext or Path(spath_str).suffix).lower(),
            file_size=int(fsize or 0),
            sha256=sha or "",
            exam_code=ecode,
            subject_name=sname,
            year=year,
            paper_name=pname or "",
            doc_kind=kind,
            text_layer=layer,
            parse_status=status,
            note=note or "",
        ))

    if limit and limit > 0:
        records = records[:limit]
    return records


def count_existing_chunks(conn, source_ids: Sequence[int]) -> Dict[int, int]:  # type: ignore[no-untyped-def]
    """统计给定 source_id 在 ai_chunk 中已有的 COURSEWARE 块数（只读）。

    dry-run 用它区分「全新写入」与「覆盖更新」，方便主 agent 判断是否重复跑过。

    Args:
        conn: pymysql 连接。
        source_ids: 已加偏移的 source_id 列表。

    Returns:
        {source_id: 已有块数}；没有块的 id 不出现在结果里。
    """
    result: Dict[int, int] = {}
    ids = list(source_ids)
    for start in range(0, len(ids), ID_BATCH):
        batch = ids[start:start + ID_BATCH]
        if not batch:
            continue
        placeholders = ",".join(["%s"] * len(batch))
        with conn.cursor() as cur:
            cur.execute(
                "SELECT source_id, COUNT(*) FROM ai_chunk "
                "WHERE source_type = %%s AND source_id IN (%s) "
                "GROUP BY source_id" % placeholders,
                [CHUNK_SOURCE_TYPE, *batch],
            )
            for row in cur.fetchall():
                result[int(row[0])] = int(row[1])
    return result


def resolve_chunk_fks(conn, cache: Dict[Tuple[str, str], Tuple[Optional[int], Optional[int]]],
                      rec: qb.Record):  # type: ignore[no-untyped-def]
    """查 ai_chunk 需要的 (subject_id, exam_category_id) —— **只 SELECT，不建不写**。

    注意与 import_question_bank.ensure_subject 的区别：那个函数会 INSERT
    exam_category / subject；本补齐脚本严格遵守「只写 ai_chunk」，
    查不到就填 NULL（ai_chunk 这两列本身允许 NULL），绝不因为补语料而新建科目。

    Args:
        conn: pymysql 连接（只读用途）。
        cache: (exam_code, subject_name) → (subject_id, category_id) 的进程内缓存。
        rec: 当前文件记录。

    Returns:
        (subject_id, exam_category_id)，查不到的位置为 None。
    """
    key = (rec.exam_code or "", rec.subject_name or "")
    if key in cache:
        return cache[key]

    subject_id: Optional[int] = None
    category_id: Optional[int] = None
    with conn.cursor() as cur:
        if rec.exam_code:
            cur.execute("SELECT id FROM exam_category WHERE code = %s LIMIT 1",
                        (rec.exam_code,))
            row = cur.fetchone()
            if row:
                category_id = int(row[0])
        if rec.subject_name:
            cur.execute("SELECT id FROM subject WHERE name = %s LIMIT 1",
                        (rec.subject_name,))
            row = cur.fetchone()
            if row:
                subject_id = int(row[0])
    cache[key] = (subject_id, category_id)
    return subject_id, category_id


# --------------------------------------------------------------------------------------
# 逐文件处理
# --------------------------------------------------------------------------------------


def build_chunks_for(rec: qb.Record) -> List[str]:
    """抽正文 → 切块，返回待写入的块文本列表（纯计算，无副作用除了 PDF 文本缓存）。"""
    text = qb.extract_record_text(rec)
    if not text:
        return []
    title = rec.paper_name or rec.file_name
    return qb.chunk_text(text, title)


def write_fail_list(rows: Sequence[Tuple[str, str]], dry_run: bool) -> Path:
    """写逐文件失败清单；dry-run 与真跑分开两个文件，避免互相覆盖。"""
    path = DRY_RUN_FAIL_LIST_PATH if dry_run else FAIL_LIST_PATH
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8") as fh:
            fh.write("# 真题册语料块补齐失败清单（scripts/backfill_paper_chunks.py）\n")
            fh.write("# 生成时间：%s  dry-run=%s\n" % (
                time.strftime("%Y-%m-%d %H:%M:%S"), dry_run))
            fh.write("# 格式：文件路径<TAB>失败原因\n")
            fh.write("# 处理建议：失败文件不影响已成功的文件（单文件独立事务），修好原因后直接重跑即可\n")
            for p, reason in rows:
                fh.write("%s\t%s\n" % (p, reason.replace("\n", " ")))
    except OSError as exc:
        log("[警告] 失败清单写入失败：%s（不影响补块结果）" % exc)
    return path


def run(argv: Optional[Sequence[str]] = None) -> int:
    """主流程：读目标 → 逐文件抽文本切块 → （真跑时）单文件一个事务写 ai_chunk。"""
    parser = argparse.ArgumentParser(
        description="为真题册（PAPER）补 COURSEWARE 语料块：只写 ai_chunk，不动 question / question_source")
    parser.add_argument("--dry-run", action="store_true",
                        help="只统计：输出「将处理 N 文件 / 将写 M 块」，只读连库、不写库")
    parser.add_argument("--limit", type=int, default=0,
                        help="只处理前 N 个文件（按 question_source.id 升序，便于小批量试跑）")
    parser.add_argument("--only-status", choices=TARGET_STATUSES, default="",
                        help="只处理指定状态的记录；缺省=PARSED+PARTIAL")
    cfg = parser.parse_args(argv)

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if qb.pymysql is None:
        log("[启动] pymysql 未安装，无法连库（即使 dry-run 也需要读 question_source）")
        return 2

    started = time.time()
    mode = "DRY-RUN（不写库）" if cfg.dry_run else "WRITE（写 ai_chunk）"
    log("=" * 78)
    log("真题册 COURSEWARE 语料块补齐  mode=%s" % mode)
    log("目标：doc_kind=%s 且 parse_status in %s  offset=%d  source_type=%s" % (
        TARGET_DOC_KIND,
        (cfg.only_status,) if cfg.only_status else TARGET_STATUSES,
        CHUNK_SOURCE_OFFSET, CHUNK_SOURCE_TYPE))
    log("=" * 78)

    # ---- 连库：dry-run 也连，但只做 SELECT（不连库无法知道要处理哪些文件）----
    try:
        conn = qb.db_connect()
    except Exception as exc:  # noqa: BLE001
        log("[启动] 连库失败：%s" % exc)
        return 2

    files_ok = 0
    files_skip = 0
    files_fail = 0
    chunks_written = 0
    failures: List[Tuple[str, str]] = []
    has_vector_col = False
    skipped_by_layer = 0

    try:
        try:
            records = fetch_target_records(conn, cfg.only_status, cfg.limit)
        except Exception as exc:  # noqa: BLE001
            log("[启动] 读取 question_source 失败：%s" % exc)
            return 2

        total = len(records)
        log("[计划] 命中 %d 个真题册文件" % total)
        if total == 0:
            log("[汇总] files_ok=0 files_skip=0 files_fail=0 chunks_written=0")
            return 0

        # ai_chunk 是否有 vector_synced 列（有则写 0 让下游重算向量）
        try:
            has_vector_col = "vector_synced" in qb.table_columns(conn, "ai_chunk")
        except Exception as exc:  # noqa: BLE001
            log("[警告] 探测 ai_chunk.vector_synced 失败（按无该列处理）：%s" % exc)
            has_vector_col = False

        # 已有块数（只读），用于区分「新增」与「覆盖更新」
        existing: Dict[int, int] = count_existing_chunks(
            conn, [CHUNK_SOURCE_OFFSET + r.id for r in records])
        existing_total = sum(existing.values())
        if existing_total:
            log("[检查] ai_chunk 中已存在 %d 块（覆盖 %d 个文件）—— 本脚本会幂等覆盖，不会产生重复行"
                % (existing_total, len(existing)))
        else:
            log("[检查] ai_chunk 中尚无对应块，本次为全新写入")

        fk_cache: Dict[Tuple[str, str], Tuple[Optional[int], Optional[int]]] = {}

        for i, rec in enumerate(records, start=1):
            label = "[%d/%d] %s" % (i, total, rec.file_name)
            # ---- 前置校验：扫描件不抽（抽出来是空/噪音，写进去是垃圾语料）----
            if rec.text_layer == "SCAN":
                files_skip += 1
                skipped_by_layer += 1
                log("%s chunks=0 status=SKIP reason=text_layer=SCAN（扫描件不切块，避免污染检索语料）"
                    % label)
                continue
            if not rec.path.is_file():
                files_fail += 1
                reason = "原始文件不存在：%s" % rec.path.as_posix()
                failures.append((rec.path.as_posix(), reason))
                log("%s chunks=0 status=FAIL reason=%s" % (label, reason))
                continue

            # ---- 抽正文 + 切块（纯计算；PDF 会用到 runtime/pdf_text_cache 缓存）----
            try:
                chunks = build_chunks_for(rec)
            except Exception as exc:  # noqa: BLE001
                files_fail += 1
                reason = "抽取/切块异常 %s: %s" % (type(exc).__name__, exc)
                failures.append((rec.path.as_posix(), reason))
                log("%s chunks=0 status=FAIL reason=%s" % (label, reason))
                continue

            if not chunks:
                files_skip += 1
                log("%s chunks=0 status=SKIP reason=正文抽取为空，无可切块内容" % label)
                continue

            # ---- dry-run：只计数 ----
            if cfg.dry_run:
                files_ok += 1
                chunks_written += len(chunks)
                log("%s chunks=%d status=OK (dry-run，未写库)" % (label, len(chunks)))
                continue

            # ---- 真跑：单文件一个事务，失败回滚后继续下一个 ----
            try:
                subject_id, category_id = resolve_chunk_fks(conn, fk_cache, rec)
                if subject_id is None:
                    # 与 import 脚本的 ensure_subject 不同：此处**不建科目**，
                    # 查不到就留 NULL（ai_chunk.subject_id 允许 NULL），只提示不阻断。
                    log("[提示] %s 未匹配到 subject(name=%s)，该文件块 subject_id=NULL"
                        % (label, rec.subject_name or "(空)"))
                conn.begin()
                try:
                    # 参数顺序与 import_question_bank 内既有调用完全一致：
                    # (conn, rec, chunks, subject_id, category_id, has_vector_col)
                    # 查不到科目时传 None（该列可空、无外键），不传 0 以免污染「按科目过滤」的检索。
                    qb.upsert_chunks(conn, rec, chunks, subject_id, category_id,
                                     has_vector_col)
                    conn.commit()
                except Exception:
                    conn.rollback()
                    raise
            except Exception as exc:  # noqa: BLE001
                files_fail += 1
                reason = "写库失败 %s: %s" % (type(exc).__name__, exc)
                failures.append((rec.path.as_posix(), reason))
                log("%s chunks=%d status=FAIL reason=%s" % (label, len(chunks), reason))
                continue

            files_ok += 1
            chunks_written += len(chunks)
            log("%s chunks=%d status=OK source_id=%d"
                % (label, len(chunks), CHUNK_SOURCE_OFFSET + rec.id))
    finally:
        try:
            conn.close()
        except Exception:  # noqa: BLE001
            pass

    fail_path = write_fail_list(failures, cfg.dry_run)

    log("-" * 78)
    log("[汇总] files_ok=%d files_skip=%d files_fail=%d chunks_written=%d%s" % (
        files_ok, files_skip, files_fail, chunks_written,
        "（dry-run：以上为预计值，未写库）" if cfg.dry_run else ""))
    if skipped_by_layer:
        log("[汇总] 其中 text_layer=SCAN 跳过 %d 个" % skipped_by_layer)
    if failures:
        log("[汇总] 失败清单：%s" % fail_path.as_posix())
    log("[汇总] 耗时 %.1f 秒" % (time.time() - started))
    log("[提示] 本脚本未修改 question / question_source（含 chunk_count）。"
        "核对块数：SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE' "
        "AND source_id = %d + <question_source.id>;" % CHUNK_SOURCE_OFFSET)
    if not cfg.dry_run:
        log("[提示] 撤销：DELETE FROM ai_chunk WHERE source_type='COURSEWARE' "
            "AND source_id = %d + <question_source.id>;" % CHUNK_SOURCE_OFFSET)

    # 退出码：只有「有失败」才非 0，方便主 agent 判断是否需要重跑
    return 0 if files_fail == 0 else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    return run(argv)


if __name__ == "__main__":
    sys.exit(main())
