﻿# ============================================================
# 数据库备份恢复演练（只读生产库，验证「备份真的能恢复」）
#
# 为什么需要它：
#   backup.bat 只做了「备份」，从来没验证过「恢复」。
#   43 张表 + 5 MB 初始化 SQL，误跑一次迁移脚本的恢复成本很高，
#   所以必须有一条**可重复执行**的演练：备份 → 恢复到临时库 → 逐表比对行数 → 销毁临时库。
#   全程不写生产库（只读 + 只操作 exam_prep_restore_* 临时库）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File 恢复演练.ps1
#   powershell -ExecutionPolicy Bypass -File 恢复演练.ps1 -KeepDump
#
# 参数：
#   -MysqlBin  MySQL bin 目录（默认本机安装路径）
#   -User/-Password  数据库账号（默认与 application-local.yml 一致）
#   -Database  业务库名（默认 exam_prep）
#   -KeepDump  保留备份文件（默认演练后删除，避免堆积）
# ============================================================
[CmdletBinding()]
param(
    [string]$MysqlBin = 'D:\bigplan\tools\mysql-8.0.46\bin',
    [string]$User = 'root',
    [string]$Password = '',
    [string]$Database = 'exam_prep',
    [switch]$KeepDump
)

$ErrorActionPreference = 'Stop'
$mysql = Join-Path $MysqlBin 'mysql.exe'
$mysqldump = Join-Path $MysqlBin 'mysqldump.exe'

# ★ 口令走 MYSQL_PWD 环境变量，而不是命令行 -p：
#   命令行传口令会触发「Using a password on the command line interface can be insecure」
#   告警，在 $ErrorActionPreference='Stop' 下会被当成致命错误直接中断演练。
# 口令优先取参数，其次环境变量 DB_PASSWORD；都没有就直接报错，不用弱默认值
if (-not $Password) { $Password = $env:DB_PASSWORD }
if (-not $Password) { throw '请设置环境变量 DB_PASSWORD，或用 -Password 传入' }
$env:MYSQL_PWD = $Password

# ★ 必须显式设 UTF-8，否则 PowerShell 5.1 会把管道里的中文按 OEM 代码页转码，
#   恢复时中文字段变成 '???????'，直接撞唯一键（实测报 college.uk_college_name 重复）。
#   ★ 且必须是**不带 BOM** 的 UTF-8：带 BOM 时 mysql 会把它当成 SQL 开头，
#   报 "error in your SQL syntax ... near ''"（实测踩到）。
#   注意 $OutputEncoding 管「写进管道」，[Console]::OutputEncoding 管原生进程输出，两者都要设。
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom

foreach ($exe in @($mysql, $mysqldump)) {
    if (-not (Test-Path $exe)) {
        Write-Host "[FAIL] 找不到 $exe，请用 -MysqlBin 指定 MySQL bin 目录" -ForegroundColor Red
        exit 1
    }
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$restoreDb = "exam_prep_restore_$stamp"
$dumpDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'backup'
if (-not (Test-Path $dumpDir)) { New-Item -ItemType Directory -Path $dumpDir | Out-Null }
$dumpFile = Join-Path $dumpDir "${Database}_$stamp.sql"

# ★ 启动自清理：上一次演练若中途失败，会留下 exam_prep_restore_* 临时库。
#   不清理会在 MySQL 里越堆越多（实测两次失败就留下 2 个 9.85 MB 的库）。
#   这里只动名字严格匹配 exam_prep_restore_ 前缀的库，绝不碰业务库。
$stale = & $mysql -u $User --default-character-set=utf8mb4 -N -B `
    -e "SHOW DATABASES LIKE 'exam_prep_restore%';" 2>$null
foreach ($db in $stale) {
    if ($db -and $db -like 'exam_prep_restore*') {
        & $mysql -u $User --default-character-set=utf8mb4 -e "DROP DATABASE IF EXISTS ``$db``;" 2>$null
        Write-Host "  （已清理上次残留的临时库：$db）"
    }
}

function Invoke-Mysql {
    param([string]$Sql, [string]$Db)
    $args = @('-u', $User, '--default-character-set=utf8mb4', '-N', '-B')
    if ($Db) { $args += @('-D', $Db) }
    $args += @('-e', $Sql)
    $out = & $mysql @args 2>$null
    return $out
}

Write-Host ''
Write-Host '================ 数据库备份恢复演练 ================' -ForegroundColor Cyan
Write-Host "  源库：$Database    临时恢复库：$restoreDb"
Write-Host ''

# ---------- ① 备份 ----------
Write-Host '① 备份（mysqldump）...' -NoNewline
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $mysqldump -u $User --default-character-set=utf8mb4 `
    --single-transaction --routines --events $Database > $dumpFile 2>$null
$sw.Stop()
$dumpMs = $sw.ElapsedMilliseconds
$dumpMb = [math]::Round((Get-Item $dumpFile).Length / 1MB, 2)
Write-Host " 完成（${dumpMs} ms，$dumpMb MB）"

# ---------- ② 建临时库并恢复 ----------
Write-Host '② 恢复到临时库...' -NoNewline
Invoke-Mysql -Sql "DROP DATABASE IF EXISTS ``$restoreDb``; CREATE DATABASE ``$restoreDb`` DEFAULT CHARACTER SET utf8mb4;" | Out-Null
$sw.Restart()
# 直接管道喂给 mysql，避免 -e 传超大 SQL
Get-Content $dumpFile -Raw -Encoding UTF8 | & $mysql -u $User `
    --default-character-set=utf8mb4 -D $restoreDb 2>$null | Out-Null
$sw.Stop()
$restoreMs = $sw.ElapsedMilliseconds
Write-Host " 完成（${restoreMs} ms）"

# ---------- ③ 逐表比对行数 ----------
Write-Host '③ 逐表比对行数（源库 vs 恢复库）...'
$tables = Invoke-Mysql -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$Database' AND table_type='BASE TABLE' ORDER BY table_name;"
$diff = @()
$checked = 0
foreach ($t in $tables) {
    if (-not $t) { continue }
    $src = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM ``$t``;" -Db $Database | Select-Object -First 1)
    $dst = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM ``$t``;" -Db $restoreDb | Select-Object -First 1)
    $checked++
    if ("$src" -ne "$dst") {
        $diff += "$t：源=$src 恢复=$dst"
    }
}

# ---------- ④ 销毁临时库 ----------
Write-Host '④ 销毁临时库...' -NoNewline
Invoke-Mysql -Sql "DROP DATABASE IF EXISTS ``$restoreDb``;" | Out-Null
Write-Host ' 完成'
if (-not $KeepDump) {
    Remove-Item $dumpFile -Force
    Write-Host "   （备份文件已清理，加 -KeepDump 可保留）"
}

# ---------- 结论 ----------
Write-Host ''
Write-Host '================ 结论 ================' -ForegroundColor Cyan
Write-Host ("  比对表数：{0}    不一致：{1}" -f $checked, $diff.Count)
Write-Host ("  备份耗时：{0} ms    恢复耗时：{1} ms    备份体积：{2} MB" -f $dumpMs, $restoreMs, $dumpMb)
if ($diff.Count -gt 0) {
    Write-Host '  [FAIL] 以下表行数不一致：' -ForegroundColor Red
    $diff | ForEach-Object { Write-Host "         $_" }
    exit 1
}
Write-Host '  [ OK ] 备份可完整恢复：所有表行数逐一吻合' -ForegroundColor Green
