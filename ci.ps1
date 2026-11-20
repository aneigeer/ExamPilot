# ============================================================
# 一键 CI 验证与构建流水线（ci.ps1）
# 步骤：
#   1. 校验管理端静态页一致性（admin-web/index.html == static/admin/index.html）
#   2. 后端全量单测（mvn -B test）
#   3. AI 服务全量单测（pytest -q）
#   4. 后端打包（mvn -B package -DskipTests）并输出 JAR SHA256
# ============================================================
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path 'D:\bigplan\tools\jdk21')) {
    $env:JAVA_HOME = 'D:\bigplan\tools\jdk21'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
$mvn = if (Test-Path 'D:\bigplan\tools\maven\bin\mvn.cmd') { 'D:\bigplan\tools\maven\bin\mvn.cmd' } else { 'mvn' }
$python = Join-Path $root '.venv-ai\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = 'python' }

Write-Host '[1/4] 校验管理端静态页 SHA256 一致性...' -ForegroundColor Cyan
$h1 = (Get-FileHash (Join-Path $root 'admin-web\index.html') -Algorithm SHA256).Hash
$h2 = (Get-FileHash (Join-Path $root 'backend\src\main\resources\static\admin\index.html') -Algorithm SHA256).Hash
if ($h1 -ne $h2) {
    throw "admin-web/index.html ($h1) 与 static/admin/index.html ($h2) 不一致，请先同步！"
}
Write-Host "  [OK] 管理端页面 SHA256: $h1"

if (-not $SkipTests) {
    Write-Host '[2/4] 运行 Java 后端全量单测 (mvn -B test)...' -ForegroundColor Cyan
    Push-Location (Join-Path $root 'backend')
    try {
        & $mvn -B test
        if ($LASTEXITCODE -ne 0) { throw "Java 单测失败 (exit=$LASTEXITCODE)" }
    } finally { Pop-Location }

    Write-Host '[3/4] 运行 AI 微服务全量单测 (pytest -q)...' -ForegroundColor Cyan
    Push-Location (Join-Path $root 'ai-service')
    try {
        & $python -m pytest -q
        if ($LASTEXITCODE -ne 0) { throw "Python 单测失败 (exit=$LASTEXITCODE)" }
    } finally { Pop-Location }
} else {
    Write-Host '[2-3/4] 已按参数跳过单测 (-SkipTests)' -ForegroundColor Yellow
}

Write-Host '[4/4] 打包后端构建产物 (mvn -B package -DskipTests)...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'backend')
try {
    & $mvn -B package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "打包失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

$jar = Join-Path $root 'backend\target\exam-prep-backend-1.0.0-SNAPSHOT.jar'
$jarHash = (Get-FileHash $jar -Algorithm SHA256).Hash
Write-Host "  [OK] JAR SHA256: $jarHash" -ForegroundColor Green
Write-Host 'CI 流水线全部通过！' -ForegroundColor Green
