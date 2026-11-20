<#
.SYNOPSIS
    一键停止由 start.ps1 启动的 Java 后端 (:8080) 与 AI 微服务 (:8000)
#>
param(
    [int]$BackendPort = 8080,
    [int]$AiPort = 8000
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$pidFile = Join-Path $root 'runtime\service-pids.json'
$stopped = New-Object System.Collections.Generic.List[string]

if (Test-Path $pidFile) {
    $info = Get-Content $pidFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($key in @('backendPid', 'aiPid')) {
        $procId = $info.$key
        if ($procId) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
                $stopped.Add("$key (PID=$procId)")
            }
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

# 兜底：若端口仍被残留 java/python 进程占用，按端口清理
foreach ($port in @($BackendPort, $AiPort)) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -in @('java', 'python', 'python3', 'uvicorn')) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            $stopped.Add("port:$port ($($proc.ProcessName) PID=$($proc.Id))")
        }
    }
}

if ($stopped.Count -gt 0) {
    Write-Host ("已停止: " + ($stopped -join ', ')) -ForegroundColor Green
} else {
    Write-Host '未发现运行中的后端或 AI 微服务进程。' -ForegroundColor Yellow
}
