"""MySQL 连接配置的唯一入口。

## 为什么要有这个文件

以前每个脚本各写一份 `dict(host=..., password="123456")`，等于把口令**复制到 7 个文件**里，
一改就漏、一扫就是一堆明文（外部审查 R02 指出的问题）。

现在统一从环境变量读：
1. 进程环境变量 `DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME`
2. 没有则读 `ai-service/.env`（该文件已被 .gitignore 排除，不入库）
3. 仍然没有 → **直接报错并说明怎么配**，而不是悄悄用一个弱默认口令连上去

这样"仓库里没有口令"和"本机能跑"两件事就不冲突了。
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Dict

ENV_FILE = Path(__file__).resolve().parents[1] / ".env"


def _from_env_file(key: str) -> str:
    """从 ai-service/.env 读取一个键（只做最小解析，不引入依赖）。"""
    if not ENV_FILE.exists():
        return ""
    try:
        for line in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    except OSError:
        return ""
    return ""


def _get(key: str, default: str = "") -> str:
    return os.environ.get(key) or _from_env_file(key) or default


def db_config() -> Dict[str, object]:
    """返回 pymysql.connect 需要的参数；口令缺失时抛 SystemExit（带操作指引）。"""
    password = _get("DB_PASSWORD")
    if not password:
        raise SystemExit(
            "未配置数据库口令。请在 ai-service/.env 里加一行：\n"
            "    DB_PASSWORD=你的MySQL密码\n"
            "或在启动前设置环境变量 DB_PASSWORD。"
        )
    raw_port = _get("DB_PORT", "3306")
    try:
        # A-12：非法 DB_PORT 原先会抛未捕获的 ValueError，冒泡成 500
        port = int(raw_port)
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"DB_PORT 必须是整数，当前值无效：{raw_port!r}") from exc
    return dict(
        host=_get("DB_HOST", "127.0.0.1"),
        port=port,
        user=_get("DB_USER", "root"),
        password=password,
        database=_get("DB_NAME", "exam_prep"),
        charset="utf8mb4",
    )
