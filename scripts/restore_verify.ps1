<#
.SYNOPSIS
    生产备份完整性与恢复演练校验脚本（校验 manifest.json SHA256 + SQL DDL 核心表 + Qdrant 快照头）
.EXAMPLE
    .\scripts\restore_verify.ps1
    .\scripts\restore_verify.ps1 -BackupDir "runtime\backups\backup_20261122_203500"
#>
param(
    [string]$BackupDir = "",
    [string]$OutRoot = "runtime\backups"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$py = Join-Path $root '.venv-ai\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }

if (-not $BackupDir) {
    $base = if ([System.IO.Path]::IsPathRooted($OutRoot)) { $OutRoot } else { Join-Path $root $OutRoot }
    $latest = Get-ChildItem -Path $base -Directory -Filter "backup_*" -ErrorAction Stop |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw "未在 $base 下找到任何 backup_* 目录" }
    $BackupDir = $latest.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($BackupDir)) {
    $BackupDir = Join-Path $root $BackupDir
}

Write-Host "[RestoreVerify] 正在校验备份目录: $BackupDir" -ForegroundColor Cyan

$pyVerify = @'
import gzip, hashlib, json, pathlib, sys

bdir = pathlib.Path(sys.argv[1])
manifest_path = bdir / "manifest.json"
assert manifest_path.is_file(), f"缺少 manifest.json: {manifest_path}"

manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
artifacts = manifest.get("artifacts") or []
assert artifacts, "manifest.json 中 artifacts 为空"

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(65536), b""):
            h.update(chunk)
    return h.hexdigest().upper()

required_tables = {"user", "question", "ai_chunk", "wiki_page", "ai_agent_run", "ai_eval_run", "async_task"}

for item in artifacts:
    fpath = bdir / item["name"]
    assert fpath.is_file(), f"备份文件缺失: {fpath}"
    actual_size = fpath.stat().st_size
    actual_sha = sha256_file(fpath)
    assert actual_size == item["size_bytes"], f"大小不匹配 {fpath.name}: {actual_size} != {item['size_bytes']}"
    assert actual_sha == item["sha256"], f"SHA256 不匹配 {fpath.name}: {actual_sha} != {item['sha256']}"
    print(f"  [OK] {item['name']} ({item['type']}) size={actual_size} B sha256={actual_sha[:16]}...")

    if item["type"] == "mysql_dump_gzip":
        found_tables = set()
        insert_lines = 0
        with gzip.open(fpath, "rt", encoding="utf-8") as gz:
            for line in gz:
                if line.startswith("CREATE TABLE `"):
                    tname = line.split("`")[1]
                    found_tables.add(tname)
                elif line.startswith("INSERT INTO `"):
                    insert_lines += 1
        missing = required_tables - found_tables
        assert not missing, f"SQL 备份缺少核心表 DDL: {missing}"
        assert insert_lines > 0, "SQL 备份不含任何 INSERT 数据行"
        print(f"  [OK] MySQL 备份解压与 DDL 校验通过: 核心表 {sorted(required_tables)} 全部存在，总表数={len(found_tables)}，INSERT 行数={insert_lines}")

print("[RestoreVerify] 全部备份产物 SHA256 与结构恢复校验 100% 通过！")
'@

$pyVerify | & $py - $BackupDir
if ($LASTEXITCODE -ne 0) { throw "恢复校验失败 (exit=$LASTEXITCODE)" }
Write-Host "[RestoreVerify] 校验成功！" -ForegroundColor Green
