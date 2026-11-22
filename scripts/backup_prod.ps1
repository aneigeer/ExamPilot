<#
.SYNOPSIS
    生产级 MySQL + Qdrant 自动化备份脚本（生成 gzip SQL + Qdrant 快照 + SHA256 清单 + 自动轮转）
.EXAMPLE
    .\scripts\backup_prod.ps1
    .\scripts\backup_prod.ps1 -OutRoot "runtime\backups" -RetainDays 7
#>
param(
    [string]$OutRoot = "runtime\backups",
    [string]$QdrantUrl = "http://127.0.0.1:6333",
    [string]$Collection = "exam_chunks",
    [int]$RetainDays = 7
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$py = Join-Path $root '.venv-ai\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }

$outBase = if ([System.IO.Path]::IsPathRooted($OutRoot)) { $OutRoot } else { Join-Path $root $OutRoot }
$stamp = (Get-Date).ToString('yyyyMMdd_HHmmss')
$backupDir = Join-Path $outBase "backup_$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

Write-Host "[Backup] 开始生产备份 -> $backupDir" -ForegroundColor Cyan

$pyScript = @'
import datetime, gzip, hashlib, json, os, pathlib, sys, urllib.request
urllib.request.getproxies = lambda: {}
os.environ["NO_PROXY"] = "*"
import httpx, pymysql

root = pathlib.Path(sys.argv[1])
backup_dir = pathlib.Path(sys.argv[2])
qdrant_url = sys.argv[3].rstrip("/")
collection = sys.argv[4]

cfg = {}
secrets_file = root / "config" / "secrets.local.yml"
if secrets_file.is_file():
    for line in secrets_file.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if s and not s.startswith("#") and ":" in s:
            k, v = s.split(":", 1)
            cfg[k.strip()] = v.strip().strip('"').strip("'")

db_host = os.environ.get("DB_HOST") or cfg.get("mysql.host", "127.0.0.1")
db_port = int(os.environ.get("DB_PORT") or cfg.get("mysql.port", "3306"))
db_user = os.environ.get("DB_USERNAME") or cfg.get("mysql.user", "root")
db_pass = os.environ.get("DB_PASSWORD") or os.environ.get("MYSQL_ROOT_PASSWORD") or cfg.get("mysql.root.password", "")
db_name = os.environ.get("DB_NAME", "exam_prep")

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(65536), b""):
            h.update(chunk)
    return h.hexdigest().upper()

artifacts = []

# 1. MySQL 逻辑备份（DDL + 核心业务/AI 表数据，流式写入 .sql.gz）
sql_gz = backup_dir / f"{db_name}.sql.gz"
conn = pymysql.connect(
    host=db_host, port=db_port, user=db_user, password=db_pass,
    database=db_name, charset="utf8mb4"
)
table_count = 0
row_count = 0
with conn.cursor() as cur, gzip.open(sql_gz, "wt", encoding="utf-8") as gz:
    gz.write(f"-- ExamPrep Production Backup ({datetime.datetime.now().isoformat()})\n")
    gz.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS = 0;\n\n")
    cur.execute("SHOW FULL TABLES WHERE Table_type = 'BASE TABLE'")
    tables = [r[0] for r in cur.fetchall()]
    for tbl in tables:
        table_count += 1
        cur.execute(f"SHOW CREATE TABLE `{tbl}`")
        ddl = cur.fetchone()[1]
        gz.write(f"-- Table: {tbl}\nDROP TABLE IF EXISTS `{tbl}`;\n{ddl};\n\n")
        # 对超大分块表 ai_chunk 仅备份近期或前 2000 条样本用于快速恢复校验，其余核心表全量导出
        limit_clause = " LIMIT 2000" if tbl == "ai_chunk" else ""
        cur.execute(f"SELECT * FROM `{tbl}`{limit_clause}")
        rows = cur.fetchall()
        if rows:
            cols = [d[0] for d in cur.description]
            col_list = ", ".join(f"`{c}`" for c in cols)
            for r in rows:
                row_count += 1
                vals = []
                for v in r:
                    if v is None:
                        vals.append("NULL")
                    elif isinstance(v, (int, float)):
                        vals.append(str(v))
                    elif isinstance(v, (bytes, bytearray)):
                        vals.append("0x" + bytes(v).hex())
                    else:
                        vals.append(conn.escape(str(v)))
                gz.write(f"INSERT INTO `{tbl}` ({col_list}) VALUES ({', '.join(vals)});\n")
            gz.write("\n")
    gz.write("SET FOREIGN_KEY_CHECKS = 1;\n")
conn.close()

artifacts.append({
    "name": sql_gz.name,
    "type": "mysql_dump_gzip",
    "size_bytes": sql_gz.stat().st_size,
    "sha256": sha256_file(sql_gz),
    "tables": table_count,
    "rows_exported": row_count,
})
print(f"[Backup] MySQL 备份完成: {sql_gz.name} (tables={table_count}, rows={row_count}, size={sql_gz.stat().st_size} B)")

# 2. Qdrant Snapshot 备份（若服务端可达则创建并下载快照，不可达则记录状态）
qdrant_status = "SKIPPED"
with httpx.Client(timeout=120.0, trust_env=False) as client:
    try:
        info_r = client.get(f"{qdrant_url}/collections/{collection}")
        if info_r.status_code == 200:
            pts = info_r.json().get("result", {}).get("points_count", 0)
            snap_r = client.post(f"{qdrant_url}/collections/{collection}/snapshots")
            if snap_r.status_code == 200:
                snap_name = snap_r.json()["result"]["name"]
                snap_file = backup_dir / snap_name
                with client.stream("GET", f"{qdrant_url}/collections/{collection}/snapshots/{snap_name}") as dl:
                    with snap_file.open("wb") as fp:
                        for chunk in dl.iter_bytes(chunk_size=65536):
                            fp.write(chunk)
                artifacts.append({
                    "name": snap_file.name,
                    "type": "qdrant_snapshot",
                    "collection": collection,
                    "points_count": pts,
                    "size_bytes": snap_file.stat().st_size,
                    "sha256": sha256_file(snap_file),
                })
                qdrant_status = "SUCCESS"
                print(f"[Backup] Qdrant 快照完成: {snap_file.name} (points={pts}, size={snap_file.stat().st_size} B)")
    except Exception as exc:
        qdrant_status = f"DEGRADED ({type(exc).__name__})"
        print(f"[Backup] Qdrant 快照跳过: {qdrant_status}")

manifest = {
    "created_at": datetime.datetime.now().isoformat(),
    "database": db_name,
    "qdrant_collection": collection,
    "qdrant_status": qdrant_status,
    "artifacts": artifacts,
}
manifest_path = backup_dir / "manifest.json"
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[Backup] 清单已写入: {manifest_path}")
'@

$pyScript | & $py - $root $backupDir $QdrantUrl $Collection
if ($LASTEXITCODE -ne 0) { throw "备份执行失败 (exit=$LASTEXITCODE)" }

# 3. 过期备份轮转清理
if ($RetainDays -gt 0 -and (Test-Path $outBase)) {
    $cutoff = (Get-Date).AddDays(-$RetainDays)
    Get-ChildItem -Path $outBase -Directory -Filter "backup_*" | Where-Object {
        $_.CreationTime -lt $cutoff
    } | ForEach-Object {
        Write-Host "[Backup] 清理过期备份目录: $($_.FullName)" -ForegroundColor Yellow
        Remove-Item -Path $_.FullName -Recurse -Force
    }
}

Write-Host "[Backup] 生产备份全部完成: $backupDir" -ForegroundColor Green
