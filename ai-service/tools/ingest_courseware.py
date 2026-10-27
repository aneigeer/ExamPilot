"""课件语料导入脚本：Word（docx）→ ``courseware`` + ``ai_chunk``。

把 634 份教学 docx（约 375.7 万字）抽取、切块并写入知识库，
使其成为 AI 问答可检索的语料（``source_type='COURSEWARE'``）。

设计要点
--------
1. **不新增依赖**：docx 本质是 zip + XML，用标准库 ``zipfile`` 读
   ``word/document.xml`` 即可，不引入 ``python-docx``。
2. **分块策略**：按段落聚合到 600 字、80 字重叠，每块首行加 ``《文档标题》``
   —— 标题进正文能显著提升"这个问题属于哪篇文档"的检索命中率。
3. **幂等**：``courseware`` 按 ``(category, title)``、``ai_chunk`` 按
   ``(source_type, source_id, chunk_no)`` 判重；重复执行只更新不新增。
4. **单篇失败不中断**：解析异常、空文档、加密文档一律跳过并计入失败清单。
5. **批量插入**：每 200 行一条 INSERT，6000+ 行逐条插入会慢到不可接受。
6. **``vector_synced=0``**：本脚本只管写 MySQL，向量化交给 ``/v1/index/sync``
   或 Java 侧 ``POST /api/admin/ai/reindex``。**不在本脚本里做 embedding**
   —— 那会把「可重跑的灌库」和「耗时的推理」耦合在一起，失败后重跑成本极高。

用法
----
::

    # ① 建表 + 建科目（只需一次）
    mysql -uroot -p"$DB_PASSWORD" exam_prep < sql/migration-courseware.sql

    # ② 干跑：只统计不写库
    python ai-service/tools/ingest_courseware.py --dry-run

    # ③ 小样验证：只灌 5 篇
    python ai-service/tools/ingest_courseware.py --limit 5

    # ④ 全量
    python ai-service/tools/ingest_courseware.py

    # 覆盖模式（内容变更后重灌）
    python ai-service/tools/ingest_courseware.py --force

退出码
------
``0`` 全部成功；``1`` 有失败项或数据库错误。
"""

from __future__ import annotations

import argparse
import hashlib
import html
import os
import re
import sys
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# --------------------------------------------------------------------------------------
# 常量配置（需要调整时集中改这里）
# --------------------------------------------------------------------------------------

#: docx 素材根目录（Word版/ 下按分类分三个子目录）
DEFAULT_SOURCE_DIR = Path(r"C:/Users/15241/Desktop/112233/Word版")

#: 目录名 → 科目名。**必须与 sql/migration-courseware.sql 里 INSERT 的 3 个科目名一致**
SUBJECT_MAP: Dict[str, str] = {
    "01 AI大模型面试系列": "AI 大模型面试",
    "02 AI大模型": "AI 大模型",
    "03 大厂项目架构": "大厂项目架构",
}

#: 目标块长（正文字符数，不含《标题》前缀）
TARGET_CHARS = 600
#: 相邻块重叠字符数（避免句子被切断导致语义丢失）
OVERLAP_CHARS = 80
#: 低于该长度的尾块并入上一块（避免产生"孤立一两句话"的碎片块）
MIN_TAIL_CHARS = 50
#: 低于该字数的整篇文档直接跳过（实测素材里存在只写了一行章节名 9 字的封面页）
MIN_DOC_CHARS = 100
#: 单篇切块数超过该值时打"疑似名单/目录型"告警（实测有 787 块的纯公司名单文档）
SUSPICIOUS_CHUNKS = 100
#: 单条 INSERT 的行数（6000+ 行逐条插入会非常慢）
BATCH_ROWS = 200

#: 数据库连接（可用环境变量覆盖，与 backend/application.yml 的默认值保持一致）
DB_CONFIG: Dict[str, object] = {
    "host": os.getenv("DB_HOST", "127.0.0.1"),
    "port": int(os.getenv("DB_PORT", "3306")),
    "user": os.getenv("DB_USERNAME", "root"),
    "password": os.environ.get("DB_PASSWORD", ""),
    "database": os.getenv("DB_NAME", "exam_prep"),
    "charset": "utf8mb4",
}

#: 文件名去掉数字前缀：``013 104.RAG优化相关面试题`` → ``RAG优化相关面试题``
#: 允许重复剥离（``013 104.xxx`` 需要剥两次），但仅在数字后跟分隔符时才剥，
#: 避免把 ``12306超高并发`` 里的 12306 当成序号砍掉。
_TITLE_PREFIX_PATTERN = re.compile(r"^\d{1,4}\s*[.、．,，\-_]\s*|^\d{1,4}\s+")


# --------------------------------------------------------------------------------------
# 数据结构
# --------------------------------------------------------------------------------------


@dataclass
class DocItem:
    """一篇待导入的文档。

    Attributes:
        path: docx 绝对路径。
        category: 所属分类（即一级子目录名）。
        title: 文档标题（文件名去数字前缀）。
    """

    path: Path
    category: str
    title: str


@dataclass
class DocResult:
    """单篇文档的处理结果。

    Attributes:
        title: 文档标题。
        category: 所属分类。
        source_file: 原始文件相对路径（写入 courseware 便于追溯）。
        char_count: 正文字数。
        chunks: 切出的块列表。
        error: 失败原因；成功时为空串。
    """

    title: str
    category: str
    source_file: str = ""
    char_count: int = 0
    chunks: List[str] = field(default_factory=list)
    error: str = ""


@dataclass
class IngestStats:
    """整体统计（结束时打印）。"""

    scanned: int = 0
    succeeded: int = 0
    failed: int = 0
    skipped: int = 0
    total_chars: int = 0
    total_chunks: int = 0
    inserted_chunks: int = 0
    updated_chunks: int = 0
    failures: List[str] = field(default_factory=list)


# --------------------------------------------------------------------------------------
# docx 解析（零依赖）
# --------------------------------------------------------------------------------------


def extract_docx_text(path: Path) -> str:
    """从 docx 中抽取纯文本（标准库实现，不依赖 python-docx）。

    docx 是一个 zip，正文在 ``word/document.xml``。处理顺序：
    先补段落/换行/制表符的语义，再剥标签，最后反转义实体。

    Args:
        path: docx 文件路径。

    Returns:
        清洗后的正文；空文档返回空串。

    Raises:
        zipfile.BadZipFile: 文件不是合法 zip（损坏或加密）。
        KeyError: zip 内缺少 ``word/document.xml``（非 Word 格式）。
    """
    with zipfile.ZipFile(path) as archive:
        raw: bytes = archive.read("word/document.xml")
    xml: str = raw.decode("utf-8", errors="ignore")

    # ① 语义标记 → 可读字符（必须在剥标签之前做）
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    xml = re.sub(r"<w:br[^>]*/>", "\n", xml)
    xml = xml.replace("</w:p>", "\n")          # 段落结束即换行
    xml = xml.replace("</w:tr>", "\n")          # 表格行结束也换行

    # ② 剥掉所有标签
    text: str = re.sub(r"<[^>]+>", "", xml)

    # ③ HTML 实体反转义（&amp; &lt; 等）
    text = html.unescape(text)

    # ③.5 部分文档把 <br> 写成转义实体（&lt;br&gt;），反转义后会留下字面量 <br>，
    #      必须再清一次，否则这些标签会作为正文进索引，污染检索。
    text = re.sub(r"<\s*br\s*/?\s*>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]{0,40}>", "", text)

    # ④ 空白归一：全角空格/不换行空格→半角，压缩连续空格，压缩多余空行
    text = text.replace("\u3000", " ").replace("\u00a0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n\n", text)
    return text.strip()


def clean_title(file_name: str) -> str:
    """从文件名提取标题（去掉数字前缀与扩展名）。

    Args:
        file_name: 文件名，如 ``013 104.RAG优化相关面试题.docx``。

    Returns:
        标题，如 ``RAG优化相关面试题``。
    """
    stem: str = Path(file_name).stem
    # 最多剥 3 层前缀，避免 "013 104." 这类双前缀
    for _ in range(3):
        new_stem = _TITLE_PREFIX_PATTERN.sub("", stem, count=1).strip()
        if new_stem == stem or not new_stem:
            break
        stem = new_stem
    return stem.strip() or Path(file_name).stem


# --------------------------------------------------------------------------------------
# 切块
# --------------------------------------------------------------------------------------


def chunk_text(
    text: str,
    title: str,
    *,
    target: int = TARGET_CHARS,
    overlap: int = OVERLAP_CHARS,
) -> List[str]:
    """按段落聚合切块，每块首行加 ``《标题》``。

    算法：
    1. 以段落（空行分隔）为单位，超长段落先按 ``target - overlap`` 硬切；
    2. 顺序累加段落，超过 ``target`` 就封块；
    3. 封块时把尾部 ``overlap`` 个字符带到下一块开头（保证跨块语义连续）；
    4. 末尾不足 ``MIN_TAIL_CHARS`` 的碎片并入上一块。

    Args:
        text: 正文。
        title: 文档标题（写入每块首行，提升检索命中率）。
        target: 目标块长（正文字符数）。
        overlap: 相邻块重叠字符数。

    Returns:
        块列表，每块形如 ``《标题》\\n正文``；正文为空时返回空列表。
    """
    if not text or not text.strip():
        return []

    # ① 段落 → 单元（超长段落硬切）
    units: List[str] = []
    for paragraph in text.split("\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if len(paragraph) <= target:
            units.append(paragraph)
            continue
        stride: int = max(1, target - overlap)
        for start in range(0, len(paragraph), stride):
            piece: str = paragraph[start:start + target]
            if piece.strip():
                units.append(piece.strip())

    # ② 顺序累加到 target
    bodies: List[str] = []
    buffer: str = ""
    for unit in units:
        if not buffer:
            buffer = unit
        elif len(buffer) + 1 + len(unit) <= target:
            buffer = f"{buffer}\n{unit}"
        else:
            bodies.append(buffer)
            tail: str = buffer[-overlap:] if overlap > 0 else ""
            buffer = f"{tail}\n{unit}" if tail else unit
    if buffer.strip():
        bodies.append(buffer)

    # ③ 碎片尾块并入上一块（避免 "只有一句话" 的块污染检索）
    if len(bodies) >= 2 and len(bodies[-1]) < MIN_TAIL_CHARS:
        bodies[-2] = f"{bodies[-2]}\n{bodies[-1]}"
        bodies.pop()

    # ④ 加标题前缀
    prefix: str = f"《{title}》"
    return [f"{prefix}\n{body.strip()}" for body in bodies if body.strip()]


def md5_hex(text: str) -> str:
    """计算 MD5（与 ``ai_chunk.content_hash char(32)`` 对齐）。

    Args:
        text: 待哈希文本。

    Returns:
        32 位小写十六进制串。
    """
    return hashlib.md5(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------------------
# 扫描
# --------------------------------------------------------------------------------------


def scan_documents(source_dir: Path, limit: Optional[int] = None) -> List[DocItem]:
    """扫描素材目录，返回待导入文档列表。

    只处理 ``SUBJECT_MAP`` 中声明的一级子目录；根目录下的 ``目录.docx``（总目录）
    因不属于任何分类会被跳过。

    Args:
        source_dir: 素材根目录。
        limit: 最多返回多少篇（用于小样验证）。

    Returns:
        文档列表，按分类、文件名排序。

    Raises:
        FileNotFoundError: 素材根目录不存在。
    """
    if not source_dir.is_dir():
        raise FileNotFoundError(f"素材目录不存在：{source_dir}")

    items: List[DocItem] = []
    for category in sorted(SUBJECT_MAP.keys()):
        category_dir: Path = source_dir / category
        if not category_dir.is_dir():
            print(f"  [警告] 分类目录不存在，已跳过：{category_dir}")
            continue
        # rglob：01 目录下有多层子目录（多专题）
        for path in sorted(category_dir.rglob("*.docx")):
            if path.name.startswith("~$"):
                continue  # Word 临时锁文件
            items.append(
                DocItem(path=path, category=category, title=clean_title(path.name))
            )
            if limit is not None and len(items) >= limit:
                return items
    return items


# --------------------------------------------------------------------------------------
# 数据库
# --------------------------------------------------------------------------------------


def connect_db():
    """建立数据库连接。

    Returns:
        ``pymysql`` 连接（autocommit 关闭，由调用方显式提交）。

    Raises:
        SystemExit: ``pymysql`` 未安装或连接失败时直接退出并给出可读提示。
    """
    try:
        import pymysql  # type: ignore[import-not-found]
    except ImportError:
        print(
            "[错误] 未安装 pymysql。请执行：\n"
            "  .venv-ai\\Scripts\\pip.exe install pymysql",
            file=sys.stderr,
        )
        raise SystemExit(1)
    try:
        return pymysql.connect(**DB_CONFIG)
    except Exception as exc:  # noqa: BLE001
        print(
            f"[错误] 数据库连接失败：{type(exc).__name__}: {exc}\n"
            f"  当前配置 host={DB_CONFIG['host']} port={DB_CONFIG['port']} "
            f"user={DB_CONFIG['user']} db={DB_CONFIG['database']}\n"
            "  可用环境变量覆盖：DB_HOST / DB_PORT / DB_USERNAME / DB_PASSWORD / DB_NAME",
            file=sys.stderr,
        )
        raise SystemExit(1) from exc


def resolve_subject_ids(connection) -> Dict[str, int]:
    """按科目名解析 ``subject.id``。

    Args:
        connection: 数据库连接。

    Returns:
        ``{科目名: subject_id}``。

    Raises:
        SystemExit: 有科目缺失（说明 migration SQL 没执行）。
    """
    names = tuple(SUBJECT_MAP.values())
    placeholders = ",".join(["%s"] * len(names))
    with connection.cursor() as cursor:
        cursor.execute(
            f"SELECT name, id FROM subject WHERE name IN ({placeholders})", names
        )
        found: Dict[str, int] = {str(name): int(sid) for name, sid in cursor.fetchall()}
    missing = [name for name in names if name not in found]
    if missing:
        print(
            f"[错误] subject 表缺少科目：{missing}\n"
            "  请先执行：mysql -uroot -p<DB_PASSWORD> exam_prep < sql/migration-courseware.sql",
            file=sys.stderr,
        )
        raise SystemExit(1)
    return found


def table_exists(connection, table: str) -> bool:
    """判断表是否存在。

    Args:
        connection: 数据库连接。
        table: 表名。

    Returns:
        存在返回 ``True``。
    """
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT COUNT(*) FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s",
            (table,),
        )
        return int(cursor.fetchone()[0]) > 0


def upsert_courseware(
    connection, result: DocResult, subject_id: int, force: bool
) -> int:
    """写入 / 更新 ``courseware`` 记录。

    ``force=False`` 时若记录已存在，只复用其 id，不覆盖 ``char_count`` /
    ``chunk_count``（保留首次导入的统计口径）；``force=True`` 时全量刷新。

    Args:
        connection: 数据库连接。
        result: 文档处理结果。
        subject_id: 归属科目 ID。
        force: ``True`` 时覆盖已有记录的统计字段。

    Returns:
        ``courseware.id``。

    Raises:
        RuntimeError: 写入后取不到自增 ID。
    """
    insert_columns = (
        "INSERT INTO courseware "
        "(title, category, subject_id, source_file, char_count, chunk_count, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'ACTIVE') "
    )
    if force:
        # 覆盖模式：统计字段全量刷新
        sql = insert_columns + (
            "ON DUPLICATE KEY UPDATE "
            "  id = LAST_INSERT_ID(id), "
            "  subject_id = VALUES(subject_id), "
            "  source_file = VALUES(source_file), "
            "  char_count = VALUES(char_count), "
            "  chunk_count = VALUES(chunk_count), "
            "  status = 'ACTIVE'"
        )
    else:
        # 非覆盖模式：已存在则只取回 id，不改动既有统计
        sql = insert_columns + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)"

    with connection.cursor() as cursor:
        cursor.execute(
            sql,
            (
                result.title,
                result.category,
                subject_id,
                result.source_file,
                result.char_count,
                len(result.chunks),
            ),
        )
        courseware_id = int(cursor.lastrowid)
    if courseware_id <= 0:
        raise RuntimeError(f"courseware 写入后未取得 id（title={result.title}）")
    return courseware_id


def delete_chunks_of(connection, courseware_id: int) -> int:
    """删除某课件已有的全部块（``--force`` 重灌时用）。

    Args:
        connection: 数据库连接。
        courseware_id: 课件 ID。

    Returns:
        删除条数。
    """
    with connection.cursor() as cursor:
        cursor.execute(
            "DELETE FROM ai_chunk WHERE source_type = 'COURSEWARE' AND source_id = %s",
            (courseware_id,),
        )
        return int(cursor.rowcount)


def insert_chunks(
    connection,
    courseware_id: int,
    result: DocResult,
    subject_id: int,
    exam_category_id: int,
) -> int:
    """批量写入 ``ai_chunk``。

    写入的是"待向量化"状态（``vector_synced=0``）：向量化由
    ``/v1/index/sync`` 或 Java 侧 reindex 完成，本脚本不做 embedding。

    Args:
        connection: 数据库连接。
        courseware_id: 课件 ID。
        result: 文档处理结果。
        subject_id: 科目 ID。
        exam_category_id: 考试类别 ID（跟随科目）。

    Returns:
        MySQL 的**影响行数**：新增记 1、更新记 2。因此
        「首次导入」时等于块数，「重复执行」时约为块数的 2 倍。
        **不要把它当作块数用** —— 块数请用 ``len(result.chunks)``。
    """
    if not result.chunks:
        return 0

    # 动态拼批量 VALUES，避免 6000+ 次单行往返
    row_placeholder = (
        "(%s,%s,%s,%s,%s,%s,%s,NULL,3,'COURSEWARE',%s,NULL,%s,NOW(),'ACTIVE',0)"
    )
    rows: List[Tuple[object, ...]] = []
    for chunk_no, content in enumerate(result.chunks):
        rows.append(
            (
                "COURSEWARE",
                courseware_id,
                chunk_no,
                result.title[:200],
                content,
                exam_category_id,
                subject_id,
                courseware_id,      # ref_id 指向 courseware
                md5_hex(content),
            )
        )

    affected: int = 0
    with connection.cursor() as cursor:
        for start in range(0, len(rows), BATCH_ROWS):
            batch = rows[start:start + BATCH_ROWS]
            placeholders = ",".join([row_placeholder] * len(batch))
            flat: List[object] = [value for row in batch for value in row]
            cursor.execute(
                "INSERT INTO ai_chunk "
                "(source_type, source_id, chunk_no, title, content, exam_category_id, subject_id, "
                " knowledge_id, difficulty, ref_type, ref_id, page_no, content_hash, "
                " source_updated_at, status, vector_synced) VALUES " + placeholders +
                " ON DUPLICATE KEY UPDATE "
                "  title = VALUES(title), content = VALUES(content), "
                "  content_hash = VALUES(content_hash), source_updated_at = NOW(), "
                "  status = 'ACTIVE', vector_synced = 0",
                flat,
            )
            affected += int(cursor.rowcount)
    return affected


# --------------------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------------------


def inspect_docx_shape(path: Path) -> Tuple[int, int]:
    """统计 docx 内的文本 run 数与图片数（用于区分「真空文档」与「纯图片文档」）。

    这个区分很重要：纯图片文档（整页截图导出成 Word）**不是坏文件**，
    而是需要走 OCR 才能拿到文字。把它报成"正文为空"会让人误以为文件损坏。

    Args:
        path: docx 路径。

    Returns:
        ``(文本 run 数, 图片数)``；读取失败返回 ``(0, 0)``。
    """
    try:
        with zipfile.ZipFile(path) as archive:
            xml: str = archive.read("word/document.xml").decode("utf-8", errors="ignore")
    except Exception:  # noqa: BLE001
        return 0, 0
    # <w:t> 是文本 run；<a:blip r:embed> 是图片引用
    text_runs: int = len(re.findall(r"<w:t[\s>]", xml))
    images: int = len(re.findall(r"<a:blip[^>]*r:embed=", xml))
    return text_runs, images


def process_document(item: DocItem, source_dir: Optional[Path] = None) -> DocResult:
    """解析并切块一篇文档（任何异常都收敛为 ``DocResult.error``）。

    Args:
        item: 待处理文档。
        source_dir: 素材根目录；给出时 ``source_file`` 记录相对路径。

    Returns:
        处理结果；失败时 ``error`` 非空、``chunks`` 为空。
    """
    source_file: str = item.path.name
    if source_dir is not None:
        try:
            source_file = str(item.path.relative_to(source_dir)).replace("\\", "/")
        except ValueError:
            source_file = str(item.path)

    def _failure(message: str, char_count: int = 0) -> DocResult:
        """构造失败结果（统一带上标题/分类/来源路径）。"""
        return DocResult(
            title=item.title,
            category=item.category,
            source_file=source_file,
            char_count=char_count,
            error=message,
        )

    try:
        text = extract_docx_text(item.path)
    except zipfile.BadZipFile:
        return _failure("文件不是合法 docx（可能已加密或损坏）")
    except KeyError:
        return _failure("docx 内缺少 word/document.xml（可能是 .doc 改名）")
    except PermissionError:
        return _failure("文件被占用或无读取权限")
    except Exception as exc:  # noqa: BLE001
        return _failure(f"解析异常：{type(exc).__name__}: {exc}")

    chunks = chunk_text(text, item.title)
    if not chunks:
        # 区分「纯图片文档」与「真空文档」——前者可用 OCR 补救，后者无价值
        text_runs, images = inspect_docx_shape(item.path)
        if images > 0 and text_runs == 0:
            return _failure(
                f"纯图片文档（{images} 张图、0 个文本 run）—— 需 OCR 才能提取文字",
                char_count=len(text),
            )
        return _failure("正文为空", char_count=len(text))

    # 封面页 / 章节分隔页：整篇只有一行标题（实测有 9 字的「AI大模型面试系列.docx」）
    # 这种块进了索引只会成为"对任何查询都有中等相似度"的噪声源，必须挡掉。
    if len(text.strip()) < MIN_DOC_CHARS:
        return _failure(
            f"正文过短（{len(text.strip())} 字 < {MIN_DOC_CHARS}），判定为封面/分隔页",
            char_count=len(text),
        )

    return DocResult(
        title=item.title,
        category=item.category,
        source_file=source_file,
        char_count=len(text),
        chunks=chunks,
    )


def run(
    *,
    source_dir: Path,
    dry_run: bool,
    force: bool,
    limit: Optional[int],
    excludes: Optional[Sequence[str]] = None,
) -> int:
    """执行导入主流程。

    Args:
        source_dir: 素材根目录。
        dry_run: 只统计不写库。
        force: 覆盖已有块（先删后插）。
        limit: 最多处理多少篇。
        excludes: 标题包含任一子串则跳过（用于排除名单/目录型长文档）。

    Returns:
        进程退出码：``0`` 全部成功，``1`` 有失败或数据库错误。
    """
    started = time.perf_counter()
    exclude_terms: List[str] = [term for term in (excludes or []) if term]
    print("=" * 78)
    print("课件语料导入" + ("　[DRY-RUN 不写库]" if dry_run else ""))
    print("=" * 78)
    print(f"素材目录：{source_dir}")
    print(f"分块参数：目标 {TARGET_CHARS} 字 / 重叠 {OVERLAP_CHARS} 字 / 批 {BATCH_ROWS} 行")
    if limit:
        print(f"限制篇数：{limit}")
    if force:
        print("模式：--force（覆盖已有块）")
    if exclude_terms:
        print(f"排除关键词：{exclude_terms}")

    try:
        items = scan_documents(source_dir, limit)
    except FileNotFoundError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 1

    if exclude_terms:
        before = len(items)
        items = [
            item for item in items
            if not any(term in item.title for term in exclude_terms)
        ]
        print(f"按关键词排除 {before - len(items)} 篇")

    stats = IngestStats(scanned=len(items))
    print(f"扫描到 {len(items)} 篇 docx\n")

    # —— DRY-RUN：只解析与统计
    if dry_run:
        by_category: Dict[str, List[int]] = {}
        image_only: List[str] = []
        too_short: List[str] = []
        oversized: List[Tuple[str, int, int]] = []
        for index, item in enumerate(items, start=1):
            result = process_document(item, source_dir)
            if result.error:
                stats.failed += 1
                stats.failures.append(f"{item.category}/{item.title}: {result.error}")
                if "纯图片文档" in result.error:
                    image_only.append(f"{item.category}/{item.title}")
                elif "封面/分隔页" in result.error:
                    too_short.append(f"{item.category}/{item.title}")
            else:
                stats.succeeded += 1
                stats.total_chars += result.char_count
                stats.total_chunks += len(result.chunks)
                bucket = by_category.setdefault(item.category, [0, 0, 0])
                bucket[0] += 1
                bucket[1] += result.char_count
                bucket[2] += len(result.chunks)
                if len(result.chunks) > SUSPICIOUS_CHUNKS:
                    oversized.append((item.title, result.char_count, len(result.chunks)))
            if index % 50 == 0 or index == len(items):
                print(f"  解析进度 {index}/{len(items)} …")
        print("\n" + "-" * 78)
        print("按分类统计（仅可抽取文本的文档）：")
        for category in sorted(by_category):
            docs, chars, chunks = by_category[category]
            print(f"  {category:<22} 文档 {docs:>4} 篇　{chars:>9,} 字　预计 {chunks:>5} 块")
        print("\n" + "=" * 78)
        print(f"文档总数　：{stats.scanned}")
        print(f"可入库　　：{stats.succeeded}")
        print(f"无法入库　：{stats.failed}")
        print(f"  ├ 纯图片文档（需 OCR）　：{len(image_only)} 篇")
        print(f"  └ 封面/分隔页（<{MIN_DOC_CHARS} 字）：{len(too_short)} 篇")
        print(f"总字数　　：{stats.total_chars:,}")
        print(f"预计块数　：{stats.total_chunks:,}")
        if image_only:
            print(f"\n★ {len(image_only)} 篇纯图片文档不会被导入（正文在图片里）。")
            print("  若要纳入，需先走 OCR（POST /v1/ocr，每篇约 20 张整页图）。")
            print("  前 10 篇示例：")
            for line in image_only[:10]:
                print(f"    - {line}")
        if oversized:
            print(f"\n⚠ {len(oversized)} 篇切块数 > {SUSPICIOUS_CHUNKS}，疑似「名单/目录型」长文档。")
            print("  这类文档的块对任何查询都有中等相似度，会挤占 top-K，建议用 --exclude 排除：")
            for title, chars, chunk_n in sorted(oversized, key=lambda x: -x[2])[:10]:
                print(f"    - {title}（{chars:,} 字 → {chunk_n} 块）")
        other_failures = [
            f for f in stats.failures
            if "纯图片文档" not in f and "封面/分隔页" not in f
        ]
        if other_failures:
            print(f"\n其他失败（{len(other_failures)} 篇，前 20 条）：")
            for line in other_failures[:20]:
                print(f"  - {line}")
        print(f"\n耗时　　　：{time.perf_counter() - started:.1f}s")
        print("★ DRY-RUN 未写库。确认数字无误后去掉 --dry-run 再执行。")
        return 0

    # —— 真实写入
    connection = connect_db()
    if not table_exists(connection, "courseware"):
        print(
            "[错误] courseware 表不存在。请先执行：\n"
            "  mysql -uroot -p<DB_PASSWORD> exam_prep < sql/migration-courseware.sql",
            file=sys.stderr,
        )
        connection.close()
        return 1
    subject_ids = resolve_subject_ids(connection)
    print(f"科目解析：{subject_ids}")

    exam_category_ids: Dict[str, int] = {}
    with connection.cursor() as cursor:
        for name, sid in subject_ids.items():
            cursor.execute("SELECT exam_category_id FROM subject WHERE id = %s", (sid,))
            row = cursor.fetchone()
            exam_category_ids[name] = int(row[0]) if row else 1

    try:
        for index, item in enumerate(items, start=1):
            result = process_document(item, source_dir)
            if result.error:
                stats.failed += 1
                stats.failures.append(f"{item.category}/{item.title}: {result.error}")
                print(f"  [{index}/{len(items)}] 跳过 {item.title} —— {result.error}")
                continue

            subject_name = SUBJECT_MAP[item.category]
            subject_id = subject_ids[subject_name]
            exam_category_id = exam_category_ids[subject_name]

            # 先 upsert 拿到 courseware.id，再决定是否清空旧块（顺序不能反：
            # 清空需要 id，而 id 要由 upsert 产生）
            courseware_id = upsert_courseware(
                connection, result, subject_id, force=force
            )
            if force:
                delete_chunks_of(connection, courseware_id)

            affected = insert_chunks(
                connection, courseware_id, result, subject_id, exam_category_id
            )
            connection.commit()

            stats.succeeded += 1
            stats.total_chars += result.char_count
            stats.total_chunks += len(result.chunks)
            stats.inserted_chunks += affected

            if index % 20 == 0 or index == len(items):
                print(
                    f"  [{index}/{len(items)}] 累计切块 {stats.total_chunks:,}"
                    f"　（{item.title[:28]}）"
                )
    except KeyboardInterrupt:
        print("\n[中断] 用户取消，已提交的部分保留。", file=sys.stderr)
        connection.rollback()
        connection.close()
        return 1
    except Exception as exc:  # noqa: BLE001
        connection.rollback()
        connection.close()
        print(f"\n[错误] 写入过程异常：{type(exc).__name__}: {exc}", file=sys.stderr)
        print("  已回滚当前事务。已完成的部分已逐篇提交，重跑本脚本是幂等的。", file=sys.stderr)
        return 1

    connection.close()

    print("\n" + "=" * 78)
    print(f"文档总数　　：{stats.scanned}")
    print(f"成功　　　　：{stats.succeeded}")
    print(f"失败　　　　：{stats.failed}")
    print(f"总字数　　　：{stats.total_chars:,}")
    print(f"切块总数　　：{stats.total_chunks:,}")
    print(f"DB 影响行数　：{stats.inserted_chunks:,}"
          f"（MySQL 口径：新增记1/更新记2，重复执行约为块数2倍；块数以上行为准）")
    if stats.failures:
        print(f"\n失败清单（前 20 条）：")
        for line in stats.failures[:20]:
            print(f"  - {line}")
    print(f"\n耗时　　　　：{time.perf_counter() - started:.1f}s")
    print("\n下一步（向量化，二选一）：")
    print("  A. Java 侧：POST /api/admin/ai/reindex       （推荐，会自动分批推送）")
    print(f"  B. 手工：分 {max(1, (stats.total_chunks + 499) // 500)} 批调 POST /v1/index/sync（单批上限 500）")
    print("  核对待向量化块数：")
    print("    SELECT COUNT(*) FROM ai_chunk WHERE source_type='COURSEWARE' AND vector_synced=0;")
    print("=" * 78)
    return 0 if stats.failed == 0 else 1


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    """解析命令行参数。

    Args:
        argv: 参数列表；``None`` 时取 ``sys.argv[1:]``。

    Returns:
        解析后的参数命名空间。
    """
    parser = argparse.ArgumentParser(
        description="把 Word 课件导入 courseware + ai_chunk（幂等，支持 dry-run）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python ingest_courseware.py --dry-run\n"
            "  python ingest_courseware.py --limit 5\n"
            "  python ingest_courseware.py\n"
            "  python ingest_courseware.py --force\n"
        ),
    )
    parser.add_argument(
        "--source-dir", type=Path, default=DEFAULT_SOURCE_DIR,
        help=f"素材根目录（默认：{DEFAULT_SOURCE_DIR}）",
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="只统计不写库（先跑这个确认数字）"
    )
    parser.add_argument(
        "--force", action="store_true", help="覆盖已有块（先删该课件的旧块再插入）"
    )
    parser.add_argument(
        "--limit", type=int, default=None, help="最多处理 N 篇（小样验证）"
    )
    parser.add_argument(
        "--exclude", action="append", default=None, metavar="关键词",
        help="标题包含该关键词的文档跳过；可重复使用多次（如 --exclude 公司落地篇）",
    )
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """脚本入口。

    Args:
        argv: 命令行参数。

    Returns:
        进程退出码。
    """
    args = parse_args(argv)
    if args.limit is not None and args.limit <= 0:
        print("[错误] --limit 必须为正整数", file=sys.stderr)
        return 1
    return run(
        source_dir=args.source_dir,
        dry_run=bool(args.dry_run),
        force=bool(args.force),
        limit=args.limit,
        excludes=args.exclude,
    )


if __name__ == "__main__":
    raise SystemExit(main())
