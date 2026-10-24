"""离线评估 HTML 报告生成（自包含、无外部依赖）。"""

from __future__ import annotations

import html
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence, Tuple

REPORTS_DIR = Path(__file__).resolve().parent / "reports"


def ensure_reports_dir() -> Path:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    return REPORTS_DIR


def write_report(
    name: str,
    title: str,
    metrics: Dict[str, Any],
    columns: Sequence[Tuple[str, str]],
    rows: Iterable[Dict[str, Any]],
    note: str = "",
) -> str:
    """生成报告文件并返回绝对路径。"""
    ensure_reports_dir()
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = REPORTS_DIR / f"{name}_{stamp}.html"

    cards = "".join(
        f'<div class="card"><div class="k">{html.escape(str(k))}</div>'
        f'<div class="v">{html.escape(str(v))}</div></div>'
        for k, v in metrics.items()
    )
    head = "".join(f"<th>{html.escape(label)}</th>" for _, label in columns)
    body_rows: List[str] = []
    for row in rows:
        cells = "".join(
            f'<td class="{"fail" if row.get("_fail") else ""}">'
            f"{html.escape(str(row.get(key, '')))}</td>"
            for key, _ in columns
        )
        body_rows.append(f"<tr>{cells}</tr>")

    document = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>{html.escape(title)}</title>
<style>
  :root{{--ink:#182230;--muted:#5c6472;--line:#e7ebf2;--brand:#315fea}}
  *{{box-sizing:border-box}}
  body{{margin:0;padding:32px;background:#f4f6fa;color:var(--ink);
       font-family:"Segoe UI","Microsoft YaHei",sans-serif}}
  h1{{font-size:24px;margin:0 0 6px}}
  .meta{{color:var(--muted);font-size:13px;margin-bottom:22px}}
  .cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:22px}}
  .card{{background:#fff;border:1px solid var(--line);border-radius:12px;padding:14px 16px}}
  .k{{font-size:12px;color:var(--muted)}}
  .v{{font-size:24px;font-weight:700;color:#202d42;margin-top:6px}}
  .panel{{background:#fff;border:1px solid var(--line);border-radius:12px;padding:16px}}
  table{{width:100%;border-collapse:collapse;font-size:12px}}
  th,td{{text-align:left;padding:8px 10px;border-bottom:1px solid #eef1f6;vertical-align:top}}
  th{{background:#f8f9fc;color:#67758a;font-weight:600;position:sticky;top:0}}
  td.fail{{color:#c24145}}
  .note{{margin-top:14px;color:var(--muted);font-size:12px;white-space:pre-wrap}}
</style></head><body>
<h1>{html.escape(title)}</h1>
<div class="meta">生成时间：{datetime.now().strftime("%Y-%m-%d %H:%M:%S")} ｜ 报告：{html.escape(str(path))}</div>
<div class="cards">{cards}</div>
<div class="panel"><table><thead><tr>{head}</tr></thead>
<tbody>{''.join(body_rows)}</tbody></table></div>
<div class="note">{html.escape(note)}</div>
</body></html>"""
    path.write_text(document, encoding="utf-8")
    return str(path)
