<#
.SYNOPSIS
    ai-service AI 微服务 watchdog v2（外部 PowerShell 长驻版）。

.DESCRIPTION
    与 run_watchdog.ps1 的区别（v1 在 IDE 后台/非交互终端不生效的原因）：

    1. v1 用 `*>> $log` 把原生命令的 stderr 并入 PowerShell 错误流。uvicorn 的日志
       走 stderr，PowerShell 会把它包装成 NativeCommandError；一旦调用方（IDE 包装器）
       设置了 $ErrorActionPreference='Stop'，循环在第一次日志输出时就被终止。
       v2 用 Start-Process 的文件重定向，完全绕开 PowerShell 错误流。
    2. v1 依赖 $PSScriptRoot；IDE 若用 `Get-Content | Invoke-Expression` 或
       `powershell -Command` 方式执行，该变量为空，`Set-Location` 直接报错退出。
       v2 对脚本路径做多级回退，并在路径缺失时打印明确错误。
    3. v1 无单实例保护：IDE 里重复点运行会有两个 watchdog 抢 8000 端口，
       uvicorn 启动即失败，陷入“秒退-重启”死循环。v2 用命名互斥量拦截。
    4. v1 不记录 pid / 退出码 / 端口就绪耗时，无法出验收证据。v2 全部落盘。
    5. IDE 后台终端随任务结束回收整个进程树；watchdog 必须放在独立的外部
       PowerShell 窗口（或 Start-Process 脱离），否则天然无法长驻。
    6. v2.1（2026-11-09）修复 R1：宿主环境可能同时存在 Path / PATH / path 三份
       同名环境变量，Start-Process 构造环境字典时会抛
       "已添加项。字典中的关键字:'Path'所添加的关键字:'PATH'" 并让脚本 26ms 秒退。
       现在拉起子进程前先由 Repair-ProcessPath 合并为单一 Path，并保留
       ProcessStartInfo 兜底路径。同时新增 -Port 参数（默认 8000），互斥量按端口区分，
       可在 8001 等隔离端口上做自愈演练而不影响线上 8000。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File "D:\大学生多考试备考知识库与题库系统\ai-service\run_watchdog_v2.ps1"

.EXAMPLE
    # 隔离演练：在 8001 上起一个受监督实例，不影响正在运行的 8000
    powershell -NoProfile -ExecutionPolicy Bypass -File "D:\大学生多考试备考知识库与题库系统\ai-service\run_watchdog_v2.ps1" -Port 8001

.NOTES
    停止方式：在窗口按 Ctrl+C，或在 ai-service 目录下创建 .watchdog_stop 文件。
#>
param(
    [string]$LogDir = "",
    [int]$Port = 8000,
    [int]$RestartDelayMs = 100,
    [int]$MaxRestarts = 0,
    [int]$ReadyTimeoutSeconds = 20
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# —— 路径解析：$PSScriptRoot 为空时回退到脚本自身的完整路径
$ScriptDir = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    Write-Host "[FATAL] 无法定位脚本目录：请用 -File 方式调用本脚本（不要用 Get-Content | Invoke-Expression）。" -ForegroundColor Red
    exit 1
}
$ScriptDir = (Resolve-Path -LiteralPath $ScriptDir).Path
$ProjectRoot = Split-Path -Parent $ScriptDir

$Python = Join-Path $ProjectRoot '.venv-ai\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $Python)) {
    Write-Host "[FATAL] 未找到虚拟环境解释器：$Python（请先执行 ai-service\install.bat）" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $LogDir = Join-Path $ProjectRoot '项目规划\第7周\evidence'
}
if (-not (Test-Path -LiteralPath $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

$WatchdogLog = Join-Path $LogDir 'ai_service_watchdog_v2.log'
$StdoutLog   = Join-Path $LogDir 'ai_service_v2_stdout.log'
$StderrLog   = Join-Path $LogDir 'ai_service_v2_stderr.log'
$StopFile    = Join-Path $ScriptDir '.watchdog_stop'

$BaseUrl = "http://127.0.0.1:$Port"

function Write-WatchdogLog([string]$Message) {
    $ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'
    $line = "[$ts] $Message"
    Add-Content -LiteralPath $WatchdogLog -Value $line -Encoding utf8
    Write-Host $line
}

function Test-PortOpen([int]$TargetPort) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $TargetPort)
        $client.Close()
        return $true
    } catch {
        return $false
    } finally {
        if ($client) { $client.Dispose() }
    }
}

function Wait-Health([int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "$BaseUrl/v1/health" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return $true }
        } catch {
            # 服务尚未就绪，继续等
        }
        Start-Sleep -Milliseconds 150
    }
    return $false
}

# —— 环境变量归一（修复 R1）：本机/宿主可能同时存在 Path、PATH、path 三份同名键。
#    Windows 环境块区分大小写存储，而 Start-Process 构造环境字典用的是大小写不敏感
#    集合，遇到重复键会抛 "已添加项。字典中的关键字:'Path'所添加的关键字:'PATH'"
#    并使脚本 26ms 内秒退（2026-11-09 演练实测）。这里合并为单一 Path 后再拉起子进程。
function Repair-ProcessPath {
    $snapshot = [Environment]::GetEnvironmentVariables()
    $pathKeys = @($snapshot.Keys | Where-Object { $_ -ieq 'Path' })
    if ($pathKeys.Count -le 1) { return 0 }

    $segments = New-Object System.Collections.Generic.List[string]
    foreach ($key in $pathKeys) {
        foreach ($segment in ("$($snapshot[$key])" -split ';')) {
            if ($segment -and -not $segments.Contains($segment)) { $segments.Add($segment) }
        }
    }
    foreach ($key in $pathKeys) {
        [Environment]::SetEnvironmentVariable($key, $null, 'Process')
    }
    [Environment]::SetEnvironmentVariable('Path', ($segments -join ';'), 'Process')
    return $pathKeys.Count
}

# —— 单实例互斥：避免两个 watchdog 抢同一端口导致秒退死循环。
#    互斥量按端口区分，便于在 8001 等隔离端口上同时做自愈演练而不影响 8000。
$MutexName = "Global\ExamprepAiServiceWatchdogV2_$Port"
$Mutex = New-Object System.Threading.Mutex($false, $MutexName)
if (-not $Mutex.WaitOne(0)) {
    Write-Host "[FATAL] 已有另一个 watchdog 在运行（$MutexName），本次直接退出。" -ForegroundColor Yellow
    exit 2
}

# —— 端口已被占用时不要盲目重启：那会把端口交给一个不受监管的进程
if (Test-PortOpen $Port) {
    Write-WatchdogLog "ABORT port=$Port 已被占用；请先停止已有 uvicorn/服务，再启动 watchdog"
    $Mutex.ReleaseMutex()
    exit 3
}

Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue

$env:PYTHONUNBUFFERED = '1'
$env:PYTHONIOENCODING = 'utf-8'
Set-Location -LiteralPath $ScriptDir

# —— 拉起子进程前先修掉重复的 Path/PATH 键（R1），否则 Start-Process 必然抛异常秒退
$dupKeyCount = Repair-ProcessPath
if ($dupKeyCount -gt 1) {
    Write-WatchdogLog "ENV_NORMALIZED duplicate_path_keys=$dupKeyCount -> merged into single 'Path'"
}

$restartCount = 0
$lastExitAt = $null

Write-WatchdogLog "START watchdog pid=$PID port=$Port python=$Python logdir=$LogDir restart_delay_ms=$RestartDelayMs"
Write-WatchdogLog "HINT 本脚本需在独立 PowerShell 窗口长驻（IDE 后台终端会被回收）"

try {
    while ($true) {
        if (Test-Path -LiteralPath $StopFile) { Write-WatchdogLog 'STOP stop-file detected'; break }
        if ($lastExitAt) { $restartCount++ }

        $launchAt = Get-Date
        Write-WatchdogLog "LAUNCH uvicorn port=$Port restart_no=$restartCount"

        # —— Start-Process 的 -Redirect* 在每次拉起时会清空目标文件：
        #    先把上一轮（很可能就是崩溃那一轮）的输出留档，再重新重定向，避免崩溃现场被覆盖。
        if (Test-Path -LiteralPath $StdoutLog) {
            Move-Item -LiteralPath $StdoutLog -Destination "$StdoutLog.prev" -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $StderrLog) {
            Move-Item -LiteralPath $StderrLog -Destination "$StderrLog.prev" -Force -ErrorAction SilentlyContinue
        }

        $argList = @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$Port", '--limit-concurrency', '4')

        # —— 首选 Start-Process（可把 stdout/stderr 直接重定向到文件，便于留崩溃现场）
        $proc = $null
        try {
            $proc = Start-Process -FilePath $Python `
                -ArgumentList $argList `
                -WorkingDirectory $ScriptDir `
                -NoNewWindow -PassThru `
                -RedirectStandardOutput $StdoutLog `
                -RedirectStandardError $StderrLog
        } catch {
            # —— 兜底（方案 B）：环境里仍有无法归一的重复键时，绕开 Start-Process 的环境字典构造
            Write-WatchdogLog "WARN Start-Process 失败（$($_.Exception.Message)），改用 ProcessStartInfo 兜底；本轮日志走当前控制台"
            $null = Repair-ProcessPath
            try {
                $psi = New-Object System.Diagnostics.ProcessStartInfo
                $psi.FileName = $Python
                $psi.Arguments = ($argList -join ' ')
                $psi.WorkingDirectory = $ScriptDir
                $psi.UseShellExecute = $false
                $psi.CreateNoWindow = $true
                $proc = New-Object System.Diagnostics.Process
                $proc.StartInfo = $psi
                $null = $proc.Start()
            } catch {
                Write-WatchdogLog "FATAL 无法拉起 uvicorn（Start-Process 与 ProcessStartInfo 均失败）：$($_.Exception.Message)"
                break
            }
        }

        Write-WatchdogLog "STARTED pid=$($proc.Id) restart_no=$restartCount"

        # —— 端口就绪耗时：从拉起子进程到该端口可连接
        $portReadyMs = -1
        $deadline = $launchAt.AddSeconds($ReadyTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if ($proc.HasExited) { break }
            if (Test-PortOpen $Port) {
                $portReadyMs = [int]((Get-Date) - $launchAt).TotalMilliseconds
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if ($portReadyMs -ge 0) {
            $healthOk = Wait-Health 5
            Write-WatchdogLog "PORT_READY pid=$($proc.Id) ready_ms=$portReadyMs health200=$healthOk"
        } else {
            Write-WatchdogLog "PORT_TIMEOUT pid=$($proc.Id) after_s=$ReadyTimeoutSeconds"
        }

        # —— 监督循环：可响应 stop-file 与 Ctrl+C（200ms 轮询，保证崩溃后 3s 内拉起）
        while (-not $proc.HasExited) {
            Start-Sleep -Milliseconds 200
            if (Test-Path -LiteralPath $StopFile) {
                Write-WatchdogLog "STOP stop-file detected, terminating pid=$($proc.Id)"
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                break
            }
        }

        $proc.Refresh()
        $code = $null
        try { $null = $proc.WaitForExit(1000); $code = $proc.ExitCode } catch { $code = 'unknown' }
        $exitAt = Get-Date
        $uptimeSec = [math]::Round(($exitAt - $launchAt).TotalSeconds, 1)
        $exitCodeFmt = if ($null -eq $code -or $code -eq 'unknown') { 'unknown' } else { ('0x{0:X8} / {0}' -f [int64]$code) }
        Write-WatchdogLog "EXIT pid=$($proc.Id) code=$exitCodeFmt uptime_s=$uptimeSec"
        $lastExitAt = $exitAt

        if (Test-Path -LiteralPath $StopFile) { Write-WatchdogLog 'STOP stop-file detected after exit'; break }
        if ($MaxRestarts -gt 0 -and $restartCount -ge $MaxRestarts) {
            Write-WatchdogLog "STOP max-restarts=$MaxRestarts reached"
            break
        }
        Start-Sleep -Milliseconds $RestartDelayMs
    }
} finally {
    if (Test-Path -LiteralPath $StopFile) { Remove-Item -LiteralPath $StopFile -Force -ErrorAction SilentlyContinue }
    Write-WatchdogLog 'WATCHDOG_EXIT'
    $Mutex.ReleaseMutex()
    $Mutex.Dispose()
}
