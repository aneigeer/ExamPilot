# ============================================================
#  跑后端测试（带并发检测）
#
#  背景：2026-10-31 连续出现两次「假失败」——两个 `mvn clean test` 同时指向同一个
#        backend/target/：clean 删 target/classes 时另一个 JVM 正在 Class.forName，
#        于是报一堆 ClassNotFoundException / TypeNotPresentException（还夹着 "168 tests"
#        这种「用例没跑完」的假象）。真跑一次就好，但排查花了半小时。
#        本脚本把这个坑固化成工具：**先查有没有别的 Maven 在跑、以及有没有正在运行的后端实例**，
#        有就拒绝。第二种（运行中的 java -jar）是 20:47 又踩到的一次：clean 删掉 target/classes 后，
#        别人的 8081 复验因为懒加载失败报了 ClassNotFoundException（症状：/internal/** 正常但 JWT 链 500）。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Tests AgentStreamServiceTest
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Clean          # 显式 clean
#    powershell -ExecutionPolicy Bypass -File .\scripts\跑测试.ps1 -Force          # 明知有并发仍要跑（不推荐）
#
#  纪律（与 README「测试与构建纪律」一致）：
#    ① 日常用 `mvn -B test`（增量），clean 只在怀疑 stale 时用；
#    ② 跑 clean 前先确认没有别的 Maven 在跑、也没有正在运行的后端实例（8080/8081）；
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

# ---------- 1b. 运行中的后端实例检测（20:47 那次真实事故） ----------
# ★ 为什么必须查这个：`mvn clean` 会删 target/classes，而**已启动的**后端是
#   java -jar 进程，class 是懒加载的——删掉之后它再触发某个类（如 LoginUser）
#   就 ClassNotFoundException，表现为「大面积 500，但 /internal/** 正常」。
#   我第一次只查了 Maven Launcher，没查运行中的应用，于是在别人起 8081 复验时
#   跑了 clean test，把人家的验证打成了假失败。
$apps = @()
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*exam-prep-backend*' -and $_.ProcessId -ne $PID } |
    ForEach-Object {
        $apps += [pscustomobject]@{
            Pid     = $_.ProcessId
            Started = $_.CreationDate
        }
    }
$ports = @()
foreach ($p in @(8080, 8081, 8082)) {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue
    if ($conn) { $ports += "$p(pid=$($conn[0].OwningProcess))" }
}

if ($apps.Count -gt 0 -or $ports.Count -gt 0) {
    Write-Host "⚠️ 检测到正在运行的后端实例：$($apps.Count) 个进程 / 监听端口：$($ports -join ', ')" -ForegroundColor Yellow
    foreach ($a in $apps) { Write-Host ("   pid={0}  启动={1}" -f $a.Pid, $a.Started) }
    Write-Host ""
    Write-Host "mvn clean / 重编译会删除并重写 target/classes，正在运行的应用会因懒加载失败" -ForegroundColor Yellow
    Write-Host "报 ClassNotFoundException（典型症状：/internal/** 正常但 JWT 过滤器链 500）。" -ForegroundColor Yellow
    if (-not $Force) {
        Write-Host ""
        Write-Host "已中止：请先停掉这些实例（或在没有 clean 的情况下用 -Clean 之外的方式跑）。" -ForegroundColor Red
        exit 3
    }
    Write-Host "已按 -Force 继续（对方正在跑的验证会被打废）。" -ForegroundColor Yellow
}


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
