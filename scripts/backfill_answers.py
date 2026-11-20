# -*- coding: utf-8 -*-
"""答案回填：把 D:\\题库 里解析册/答案册的答案，按「考次 + 套号 + 分册」精确配对回题库。

为什么需要单独一个脚本
----------------------
导入脚本 `import_question_bank.py` 的答案映射分组键是 `(exam_code, year, 套号)`。
但四六级同一套卷会拆成 3 份分册（选词填空 / 长篇阅读 / 阅读），三份的题号都从 1 开始，
合并时 `setdefault` 只保留第一份，其余分册答案被整份丢弃；6 月与 12 月两次考试也会互相覆盖。
本脚本把分组键细化为 `(exam_code, year, 考次月份, 套号, 分册类型)`，再按 `question.sort`
（导入时写入的原题号）逐题配对。

安全约束
--------
1. 只补空：已经有 `is_answer=1` 的题一律跳过，绝不覆盖。
2. 分组键必须完全一致才配对；缺套号/缺月份的组不与有套号的组交叉。
3. 只在选项里确实存在该字母时才写入。
4. `--dry-run` 只统计不写库。

用法
----
    .venv-ai\\Scripts\\python.exe scripts/backfill_answers.py --dry-run
    .venv-ai\\Scripts\\python.exe scripts/backfill_answers.py --apply
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pymysql  # noqa: E402

import import_question_bank as qb  # noqa: E402

#: 分册类型，按优先级匹配（长词在前，避免「长篇阅读」被判成「阅读」）
SECTION_RULES: List[Tuple[str, str]] = [
    ("选词填空", "选词"),
    ("长篇阅读", "长篇"),
    ("仔细阅读", "仔细"),
    ("快速阅读", "快速"),
    ("完形填空", "完形"),
    ("新题型", "新题型"),
    ("阅读理解", "阅读"),
    ("阅读", "阅读"),
    ("听力", "听力"),
    ("翻译", "翻译"),
    ("写作", "写作"),
    ("作文", "写作"),
    ("语法", "语法"),
    ("词汇", "词汇"),
]

#: 同一年同一考试的多套卷版本（国考尤其明显：副省级/市地级/行政执法）。
#: 这些卷题号完全相同但答案不同，必须进分组键，否则会互相覆盖。
VARIANT_RULES: List[Tuple[str, str]] = [
    ("副省级", "副省"),
    ("行政执法", "执法"),
    ("市地级", "市地"),
    ("地市级", "市地"),
    ("省部级", "副省"),
    ("省级", "省级"),
    ("乡镇", "乡镇"),
]

SET_RE = re.compile(r"第\s*([一二三四五1-5])\s*套")
SITTING_RE = re.compile(r"(20\d{2})\s*[.年\-_]\s*(\d{1,2})")
DATE_ONLY_RE = re.compile(r"(20\d{2})\s*[.年\-_]\s*(\d{1,2})")
#: 题号：行首 1. / 1．/ 1、/ 1,（OCR 常在数字与标点间插空格，先归一化）
QNO_RE2 = re.compile(r"(?m)^\s*(\d{1,3})\s*[.．、,，]\s*")
#: 答案解析的「答案」前缀：【答案】 / 答案： / 参考答案 / [答案]
ANSWER_PREFIX = re.compile(
    r"^\s*(?:【\s*答\s*案\s*】|\[\s*答\s*案\s*\]|参\s*考\s*答\s*案\s*[:：]?|答\s*案\s*[:：]?)\s*")
#: 答案藏在解析末尾：故正确答案为 C / 正确答案是 B / 故选 D / 故答案选 A
ANSWER_INLINE = re.compile(
    r"(?:故\s*)?(?:正确\s*)?答\s*案\s*(?:为|是|选)?\s*[:：]?\s*([A-D])(?![A-Za-z])"
    r"|故\s*选\s*[:：]?\s*([A-D])(?![A-Za-z])"
    r"|应\s*选\s*[:：]?\s*([A-D])(?![A-Za-z])")
#: 无题号答案序列：答案]D] / 【答案】K / 答案：B
ANSWER_SEQ = re.compile(r"答\s*案\s*[\]】\)]?\s*[:：]?\s*([A-D])\s*[\]】\)]?")


def tokens(file_name: str, parent: str) -> Dict[str, str]:
    """从文件名（必要时借父目录名）提取 月份 / 套号 / 分册。"""
    blob = file_name
    month = ""
    m = SITTING_RE.search(blob)
    if not m:
        m = SITTING_RE.search(parent)
    if m:
        month = "%02d" % int(m.group(2))

    set_no = ""
    sm = SET_RE.search(blob)
    if not sm:
        sm = SET_RE.search(parent)
    if sm:
        raw = sm.group(1)
        set_no = {"一": "1", "二": "2", "三": "3", "四": "4", "五": "5"}.get(raw, raw)

    section = ""
    for keyword, label in SECTION_RULES:
        if keyword in blob:
            section = label
            break

    variant = ""
    for keyword, label in VARIANT_RULES:
        if keyword in blob:
            variant = label
            break
    return {"month": month, "set": set_no, "section": section, "variant": variant}


def parse_answers(text: str) -> Dict[int, Tuple[str, str]]:
    """解析答案册。支持三类排法：

    1) 逐题解析：``1.A【解析】…`` / ``【答案】A`` / ``答案：A``
    2) 紧凑区间：``1-5 ABCDA`` / ``6—10 BCDAB``
    3) 一行多题：``1.A 2.B 3.C 4.D``（该行至少 3 组才算）

    OCR 文本会在数字与标点间插空格（``1 . A``），这里先做归一化再切分。
    """
    body = re.sub(r"(?m)^(\s*)(\d{1,3})\s*([.．、,，])\s*", r"\1\2\3", text or "")
    body = re.sub(r"[ \t\u3000]+", " ", body)

    base: Dict[int, Tuple[str, str]] = {}
    marks = list(QNO_RE2.finditer(body))
    for i, match in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(body)
        block = body[match.end():end]
        stripped = ANSWER_PREFIX.sub("", block)
        # 兼容「【答案】[A]」「答案（B）」「答案 C」等写法：字母外面可能还有括号
        am = re.match(r"\s*[\[\(（【]?\s*([A-D])\s*[\]\)）】]?(?![A-Za-z])", stripped)
        letter = am.group(1) if am else ""
        if not letter:
            # 解析体例：答案在段末（「故正确答案为 C」「故选 D」）。
            # 一段里可能先出现「…得出正确答案为 D」，最后才「故正确答案为 C」，
            # 以**最后一条**为准（定论句）。
            found = list(ANSWER_INLINE.finditer(block))
            if found:
                letter = next((g for g in found[-1].groups() if g), "")
        analysis = qb.clean_text(block)[:2000]
        if letter and analysis:
            base[int(match.group(1))] = (letter, analysis)

    for m in re.finditer(r"(?m)^\s*(\d{1,3})\s*[-—~－]\s*(\d{1,3})\s*[:：]?\s*([A-D]{2,20})\s*$", body):
        start, end, letters = int(m.group(1)), int(m.group(2)), m.group(3)
        if end - start + 1 == len(letters):
            for i, ch in enumerate(letters):
                base.setdefault(start + i, (ch, ""))

    for line in body.splitlines():
        pairs = re.findall(r"(?<![0-9])(\d{1,3})\s*[.．、)]\s*([A-D])(?![A-Za-z])", line)
        if len(pairs) >= 3:
            for qno_s, letter in pairs:
                base.setdefault(int(qno_s), (letter, ""))
    return base


def parse_answer_sequence(text: str) -> List[str]:
    """提取「无题号」的答案序列：``【答案】D]`` / ``答案]K]`` / ``答案：B``。

    长篇阅读/信息匹配类解析册常把 10 个答案按顺序排列、不写题号；
    这类只能按「答案出现顺序 ↔ 试题题号顺序」映射，由调用方保证同组只有一份试卷。
    """
    body = re.sub(r"[ \t\u3000]+", " ", text or "")
    return [m.group(1) for m in ANSWER_SEQ.finditer(body)]


def read_text(path: Path, ext: str) -> str:
    """优先读 OCR 缓存（字体损坏/扫描版答案册），否则走常规文本抽取。"""
    import hashlib

    cache = (Path(__file__).resolve().parent.parent / "runtime" / "ocr_text"
             / (hashlib.md5(str(Path(path)).encode("utf-8")).hexdigest() + ".txt"))
    if cache.exists():
        return cache.read_text(encoding="utf-8", errors="replace")
    if ext == ".pdf":
        text, _ = qb.pdf_text(path)
        return text
    if ext == ".docx":
        return qb.docx_text(path)
    return qb.read_text_file(path)


def db_connect():
    pwd = ""
    env = Path(__file__).resolve().parent.parent / "ai-service" / ".env"
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("DB_PASSWORD="):
            pwd = line.split("=", 1)[1].strip()
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                           database="exam_prep", charset="utf8mb4", autocommit=False)


def load_sources(conn):
    with conn.cursor() as cur:
        cur.execute("""
            SELECT id, source_path, file_name, exam_code, year, doc_kind, text_layer
            FROM question_source
            WHERE exam_code IS NOT NULL AND year IS NOT NULL
              AND text_layer IN ('TEXT','SPARSE','NATIVE')
              AND doc_kind IN ('ANSWER','ANALYSIS','PAPER')
        """)
        return cur.fetchall()


def main() -> int:
    ap = argparse.ArgumentParser(description="按考次+套号+分册回填答案")
    ap.add_argument("--apply", action="store_true", help="真正写库（默认 dry-run）")
    ap.add_argument("--dry-run", action="store_true", help="只统计")
    ap.add_argument("--limit-groups", type=int, default=0)
    args = ap.parse_args()
    apply = args.apply and not args.dry_run

    conn = db_connect()
    rows = load_sources(conn)
    print(f"[load] 候选来源 {len(rows)} 条", flush=True)

    answer_groups: Dict[Tuple, Dict[int, Tuple[str, str]]] = defaultdict(dict)
    seq_groups: Dict[Tuple, List[str]] = {}
    paper_groups: Dict[Tuple, List[Tuple[str, str]]] = defaultdict(list)

    for _sid, path, fname, ecode, year, kind, _layer in rows:
        tk = tokens(fname, Path(path).parent.name)
        key = (ecode, year, tk["month"], tk["set"], tk["section"], tk["variant"])
        if kind in ("ANSWER", "ANALYSIS"):
            paper_groups.setdefault(key, [])
        else:
            paper_groups[key].append((path, fname))

    if args.limit_groups:
        keep = set(list(paper_groups)[: args.limit_groups])
        paper_groups = {k: v for k, v in paper_groups.items() if k in keep}

    parsed = 0
    for _sid, path, fname, ecode, year, kind, _layer in rows:
        if kind not in ("ANSWER", "ANALYSIS"):
            continue
        tk = tokens(fname, Path(path).parent.name)
        key = (ecode, year, tk["month"], tk["set"], tk["section"], tk["variant"])
        if key not in paper_groups:
            continue
        try:
            text = read_text(Path(path), Path(path).suffix.lower())
        except Exception as exc:  # noqa: BLE001
            print(f"  SKIP {fname[:50]} -> {type(exc).__name__}", flush=True)
            continue
        parsed += 1
        for qno, value in parse_answers(text).items():
            answer_groups[key].setdefault(qno, value)
        seq = parse_answer_sequence(text)
        if len(seq) >= 3:
            seq_groups.setdefault(key, seq)

    print(f"[parse] 解析答案册 {parsed} 份，有效答案组 {len(answer_groups)} 个，"
          f"无题号序列组 {len(seq_groups)} 个", flush=True)

    # 整卷 vs 分册：四六级的真题册是整卷（section=''），答案册按分册命名。
    # 分册答案用的是**绝对题号**（阅读 46-55、选词 26-35），可以安全合并到整卷；
    # 同一题号在不同分册出现不同答案时判为冲突，该题号直接丢弃。
    merged: Dict[Tuple, Dict[int, Tuple[str, str]]] = {}
    conflicts: Dict[Tuple, set] = defaultdict(set)
    for (ecode, year, month, set_no, section, variant), amap in answer_groups.items():
        if not section:
            continue
        base = (ecode, year, month, set_no, variant)
        bucket = merged.setdefault(base, {})
        for qno, value in amap.items():
            old = bucket.get(qno)
            if old is None:
                bucket[qno] = value
            elif old[0] != value[0]:
                conflicts[base].add(qno)
    for base, bad in conflicts.items():
        for qno in bad:
            merged.get(base, {}).pop(qno, None)
    print(f"[merge] 整卷可用的分册合并组 {len(merged)} 个，冲突题号 {sum(len(v) for v in conflicts.values())} 个",
          flush=True)

    stats = defaultdict(lambda: {"fill": 0, "papers": 0, "miss": 0, "seq": 0})
    pending = []
    for key, papers in paper_groups.items():
        ecode, year, month, set_no, section, variant = key
        amap = answer_groups.get(key) or {}
        # 整卷（无分册）→ 用同年同套各分册合并后的绝对题号答案
        if not amap and not section:
            amap = merged.get((ecode, year, month, set_no, variant)) or {}
        seq = seq_groups.get(key) or []
        if not amap and not seq:
            continue
        for path, fname in papers:
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT q.id, q.sort
                    FROM question q
                    WHERE q.origin_path=%s AND q.is_deleted=0
                      AND NOT EXISTS (SELECT 1 FROM question_option o
                                      WHERE o.question_id=q.id AND o.is_answer=1)
                """, (path,))
                targets = cur.fetchall()
            if not targets:
                continue

            # 路径 A：答案册带题号 → 按 sort 精确配对，命中率 <50% 整组跳过
            if amap:
                matched = sum(1 for _qid, sort_no in targets if int(sort_no or 0) in amap)
                if matched < max(3, int(len(targets) * 0.5)):
                    stats[ecode]["skip"] = stats[ecode].get("skip", 0) + len(targets)
                    continue
                stats[ecode]["papers"] += 1
                pairs = []
                for qid, sort_no in targets:
                    hit = amap.get(int(sort_no or 0))
                    if hit:
                        pairs.append((qid, hit[0]))
                # 路径 B：无题号答案 → 仅当同组只有一份试卷且数量匹配时按顺序映射
            elif seq and len(papers) == 1 and \
                    0.7 * len(targets) <= len(seq) <= 1.3 * len(targets):
                ordered = sorted(targets, key=lambda r: int(r[1] or 0))
                pairs = [(qid, letter) for (qid, _s), letter in zip(ordered, seq)]
                stats[ecode]["papers"] += 1
                stats[ecode]["seq"] += len(pairs)
            else:
                stats[ecode]["skip"] = stats[ecode].get("skip", 0) + len(targets)
                continue

            for qid, letter in pairs:
                with conn.cursor() as cur:
                    cur.execute("SELECT option_key FROM question_option WHERE question_id=%s", (qid,))
                    keys = [r[0] for r in cur.fetchall()]
                if letter not in keys:
                    stats[ecode]["miss"] += 1
                    continue
                stats[ecode]["fill"] += 1
                pending.append((qid, letter))

    print("\n[预估] 按考试类别可回填：")
    for ecode, s in sorted(stats.items(), key=lambda kv: -kv[1]["fill"]):
        print(f"  {ecode:15s} 可填 {s['fill']:5d} 题 / 涉及 {s['papers']:3d} 份试卷 / "
              f"未命中 {s['miss']:5d} / 跳过 {s.get('skip', 0):5d} / 顺序映射 {s['seq']:4d}")

    if not apply:
        print("\n[dry-run] 未写库。加 --apply 执行。")
        return 0

    done = 0
    try:
        with conn.cursor() as cur:
            for qid, letter in pending:
                # 只写正确答案标记；不回填 analysis（解析文本串题风险高，宁缺勿错）
                cur.execute("UPDATE question_option SET is_answer=1 "
                            "WHERE question_id=%s AND option_key=%s", (qid, letter))
                done += 1
                if done % 500 == 0:
                    conn.commit()
                    print(f"  ... 已写 {done}", flush=True)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    print(f"[apply] 写库完成：{done} 题补上答案")
    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
