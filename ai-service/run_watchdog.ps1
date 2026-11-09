$root = Split-Path -Parent $PSScriptRoot
$python = Join-Path $root ".venv-ai\Scripts\python.exe"
$logDir = Join-Path $root "项目规划\第7周\evidence"
$log = Join-Path $logDir "ai_service_watchdog.log"
if (!(Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

Set-Location -LiteralPath $PSScriptRoot
while ($true) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$ts] starting ai-service" | Out-File -Append -Encoding utf8 -Path $log
    & $python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --limit-concurrency 4 *>> $log
    $code = $LASTEXITCODE
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$ts] ai-service exited code=$code" | Out-File -Append -Encoding utf8 -Path $log
    Start-Sleep -Seconds 2
}
