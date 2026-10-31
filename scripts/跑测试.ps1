# ============================================================
#  跑后端测试（带并发检测）
#
#  背景：2026-10-31 连续出现两次「假失败」——两个 `mvn clean test` 同时指向同一个
#        backend/target/：clean 删 target/classes 时另一个 JVM 正在 Class.forName，
#        于是报一堆 ClassNotFoundException / TypeNotPresentException（还夹着 "168 tests"
#        这种「用例没跑完」的假象）。真跑一次就好，但排查花了半小时。
#        本脚本把这个坑固化成工具：**先查有没有别的 Maven 在跑，有就拒绝**。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Tests AgentStreamServiceTest
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Clean          # 显式 clean
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Force          # 明知有并发仍要跑（不推荐）
#
#  纪律（与 README「测试与构建纪律」一致）：
#    ① 日常用 `mvn -B test`（增量），clean 只在怀疑 stale 时用；
#    ② 跑 clean 前先确认没有别的 Maven 在跑；
#    ③ 报「全绿」必须带命令原文与 `Tests run` 行 —— 本脚本会把两者都打出来。
# ============================================================
param(
    [switch]$Clean,
    [switch]$Force,
    [string]$Tests = "",
    [string]$Mvn = ""
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $root 'backend'

# ---------- 0. 定位 mvn ----------
if ([string]::IsNullOrWhiteSpace($Mvn)) {
    $candidates = @(
        'D:\bigplan\tools\maven\bin\mvn.cmd',
        'mvn.cmd'
    )
    foreach ($c in $candidates) {
        try {
            $cmd = Get-Command $c -ErrorAction Stop
            $Mvn = $cmd.Source
            break
        } catch { }
    }
}
if ([string]::IsNullOrWhiteSpace($Mvn)) {
    throw "找不到 mvn.cmd，请用 -Mvn 指定绝对路径"
}

# ---------- 1. 并发检测（核心价值） ----------
$others = @()
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*classworlds.launcher.Launcher*' -and $_.ProcessId -ne $PID } |
    ForEach-Object {
        $others += [pscustomobject]@{
            Pid     = $_.ProcessId
            Started = $_.CreationDate
            Args    = ($_.CommandLine -replace '.*Launcher\s*', '').Trim()
        }
    }

if ($others.Count -gt 0) {
    Write-Host "⚠️ 检测到 $($others.Count) 个正在运行的 Maven（会与本脚本抢 backend/target）：" -ForegroundColor Yellow
    foreach ($o in $others) {
        Write-Host ("   pid={0}  启动={1}  参数={2}" -f $o.Pid, $o.Started, $o.Args)
    }
    Write-Host ""
    Write-Host "两个 Maven 同时指向同一个 target/ 时，clean 会删掉另一个 JVM 正在加载的 class →" -ForegroundColor Yellow
    Write-Host "报一堆 ClassNotFoundException（且用例数变少）。这不是代码问题，是并发污染。" -ForegroundColor Yellow
    if (-not $Force) {
        Write-Host ""
        Write-Host "已中止。等对方跑完，或确认无碍后加 -Force 重跑。" -ForegroundColor Red
        exit 2
    }
    Write-Host "已按 -Force 继续（结果可能不可信）。" -ForegroundColor Yellow
}

# ---------- 2. 组装命令 ----------
$mvnArgs = @('-B')
if ($Clean) { $mvnArgs += 'clean' }
$mvnArgs += 'test'
$mvnArgs += @('-DargLine=-Xmx384m', '-Dsurefire.reuseForks=true', '-DforkCount=1')
if (-not [string]::IsNullOrWhiteSpace($Tests)) { $mvnArgs += "-Dtest=$Tests" }

$cmdLine = "`"$Mvn`" " + ($mvnArgs -join ' ')
Write-Host "==== 命令原文（报「全绿」时请连这一行一起贴）====" -ForegroundColor Cyan
Write-Host "cd `"$backend`""
Write-Host $cmdLine
Write-Host ""

$env:MAVEN_OPTS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
$log = Join-Path ([System.IO.Path]::GetTempPath()) ("mvn-test-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".log")

Push-Location $backend
try {
    & $Mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

# ---------- 3. 汇总（只打关键行，便于贴报告） ----------
Write-Host ""
Write-Host "==== 结果摘要 ====" -ForegroundColor Cyan
$summary = Select-String -Path $log -Pattern 'Tests run:.*(Failures|Errors)|BUILD (SUCCESS|FAILURE)' -Encoding UTF8
if ($summary) {
    $summary | ForEach-Object { Write-Host $_.Line.Trim() }
} else {
    Write-Host "（未匹配到汇总行，请查看完整日志：$log）" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "exit code = $code"
Write-Host "完整日志 = $log"
if ($code -ne 0) {
    Write-Host "构建失败：请先看日志里的第一处 'ERROR' / '<<< FAILURE' / '<<< ERROR'，不要用 -DskipTests 绕。" -ForegroundColor Red
}
exit $code
