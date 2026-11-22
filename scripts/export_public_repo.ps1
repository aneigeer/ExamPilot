# ============================================================
# 导出 GitHub 公开展示版（脱敏、去大文件、去本机凭据）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/export_public_repo.ps1 -OutDir ../exam-prep-public
# 安全：本脚本只读当前仓库、只写目标目录，绝不执行 git push。
# ============================================================
param(
    [string]$OutDir = "../exam-prep-public"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = [System.IO.Path]::GetFullPath((Join-Path $root $OutDir))
if ($target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "输出目录不能位于仓库内部：$target"
}
if (Test-Path $target) {
    throw "输出目录已存在，请更换 -OutDir 或手动清理后再执行：$target"
}
New-Item -ItemType Directory -Path $target | Out-Null

$excludeDirs = @(
    '.git', '.workbuddy', '项目规划', 'runtime', 'output', 'node_modules',
    '.gradle', 'build', 'target', '.venv-ai', '.idea', '.vscode',
    'data', 'models', 'uploads', 'logs', 'backup', 'training'
)
$excludeFiles = @(
    '*.bat', '*.log', '*.jks', '*.keystore', 'server_cert.pem',
    'application-local.yml', '.env', '.env.*', 'secrets.local.yml',
    '*.xlsx', '*.zip', '*.7z', 'data-guokao.sql'
)

Write-Host "[1/4] 复制仓库（排除凭据/大文件/本机脚本）..." -ForegroundColor Cyan
$copyArgs = @($root, $target, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:1', '/W:1')
$copyArgs += '/XD'
$copyArgs += $excludeDirs
$copyArgs += '/XF'
$copyArgs += $excludeFiles
& robocopy @copyArgs | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy 失败（exit=$LASTEXITCODE）" }

# 二次清理：任何层级都不允许出现本地凭据
$localSecrets = @(Get-ChildItem $target -Recurse -File -Force |
    Where-Object { $_.Name -in @('application-local.yml', '.env', 'secrets.local.yml', 'server_cert.pem') })
if ($localSecrets.Count -gt 0) {
    $localSecrets | Remove-Item -Force
}

Write-Host "[2/4] 扫描 >5MB 大文件..." -ForegroundColor Cyan
$large = Get-ChildItem $target -Recurse -File | Where-Object { $_.Length -gt 5MB }
if ($large) {
    $large | ForEach-Object { Write-Host ("  [大文件] " + $_.FullName) }
    throw "存在超过 5MB 的文件，请补充排除规则"
}

Write-Host "[3/4] 扫描密钥 / 手机号 / 邮箱..." -ForegroundColor Cyan
$secretPatterns = @(
    'sk-[A-Za-z0-9]{20,}',
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'AI_INTERNAL_TOKEN\s*=\s*[A-Za-z0-9_\-]{20,}'
)
$piiPatterns = @(
    '(?<!\d)1[3-9]\d{9}(?!\d)',
    '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
)
$hits = @()
$textExt = @('.java','.py','.js','.html','.css','.yml','.yaml','.xml','.md','.sql','.json','.properties','.gradle','.ps1','.sh')
$scanSkip = @('\src\test\', '\tests\', '\docs\', '\scripts\qa\', 'secrets.example.yml')

Get-ChildItem $target -Recurse -File | ForEach-Object {
    $file = $_
    if (-not ($textExt -contains $file.Extension.ToLower())) { return }
    foreach ($pat in $scanSkip) {
        if ($file.FullName -like "*$pat*") { return }
    }
    $content = Get-Content $file.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $content) { return }
    foreach ($p in $secretPatterns) {
        if ($content -match $p) { $hits += "密钥疑似命中：$($file.FullName) :: $p" }
    }
    foreach ($p in $piiPatterns) {
        if ($content -match $p) { $hits += "PII 疑似命中：$($file.FullName) :: $p" }
    }
}
if ($hits.Count -gt 0) {
    $hits | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    throw "导出目录存在敏感信息，已中止（不会执行 git push）"
}

Write-Host "[4/4] 写出导出清单..." -ForegroundColor Cyan
$files = Get-ChildItem $target -Recurse -File
$manifest = Join-Path $target 'EXPORT_MANIFEST.txt'
"导出时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content $manifest -Encoding UTF8
"文件数：$($files.Count)" | Add-Content $manifest -Encoding UTF8
"总大小：$([math]::Round(($files | Measure-Object Length -Sum).Sum / 1MB, 2)) MB" | Add-Content $manifest -Encoding UTF8
Add-Content $manifest -Encoding UTF8 "敏感扫描：0 命中（密钥/手机号/邮箱/>5MB）"

Write-Host "  [OK] 导出目录：$target" -ForegroundColor Green
Write-Host "  [OK] 文件数：$($files.Count)" -ForegroundColor Green
exit 0
