<#
.SYNOPSIS
    一键启动全栈服务（支持本地 Ollama 模式 与 在线 API 模式双模切换）
.EXAMPLE
    # 1. 本地模型模式（默认，使用本机 Ollama，零 API 费用）
    .\start.ps1
    .\start.ps1 -Mode local -Model exam-prep-7b

    # 2. 在线 API 模式 —— 阿里云百炼（DashScope）
    .\start.ps1 -Mode api -Provider dashscope -ApiKey "sk-xxx" -Model "qwen-plus"

    # 3. 在线 API 模式 —— 智谱 GLM
    .\start.ps1 -Mode api -Provider zhipu -ApiKey "xxx" -Model "glm-4-flash"

    # 4. 在线 API 模式 —— 任意 OpenAI 兼容端点（DeepSeek / SiliconFlow / OpenAI / Kimi）
    .\start.ps1 -Mode api -Provider openai -BaseUrl "https://api.deepseek.com/v1" -ApiKey "sk-xxx" -Model "deepseek-chat"

    # 5. 双模自动模式（配了 LLM_API_KEY 走云端，没配回退本地 Ollama）
    .\start.ps1 -Provider auto

    # 6. 仅启动 Java 后端（不启 Python AI 微服务）或强制重新打包
    .\start.ps1 -SkipAi
    .\start.ps1 -Rebuild
#>
param(
    [ValidateSet('local', 'api')]
    [string]$Mode = 'local',

    [ValidateSet('', 'ollama', 'openai', 'dashscope', 'zhipu', 'auto', 'extractive')]
    [string]$Provider = '',

    [string]$BaseUrl = '',
    [string]$ApiKey = '',
    [string]$Model = '',
    [int]$BackendPort = 8080,
    [int]$AiPort = 8000,
    [switch]$SkipAi,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

# 某些宿主会注入 Path / PATH / path 多个大小写变体；
# Windows PowerShell 5.1 的 Start-Process 构建环境字典时会因重复键直接抛异常。
# 这里统一收敛为一个 Path，避免一键启动在干净机器上偶发失败。
$pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
foreach ($key in @('Path', 'PATH', 'path')) {
    [Environment]::SetEnvironmentVariable($key, $null, 'Process')
}
if ($pathValue) { [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process') }
$root = $PSScriptRoot
$runtimeDir = Join-Path $root 'runtime'
if (-not (Test-Path $runtimeDir)) { New-Item -ItemType Directory -Path $runtimeDir | Out-Null }

function Resolve-Tool([string]$CmdName, [string[]]$Fallbacks) {
    $found = Get-Command $CmdName -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($p in $Fallbacks) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    return $null
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $ar = $client.BeginConnect($HostName, $Port, $null, $null)
        $ok = $ar.AsyncWaitHandle.WaitOne(800, $false)
        if ($ok -and $client.Connected) { $client.EndConnect($ar); $client.Close(); return $true }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Wait-Http200([string]$Url, [int]$TimeoutSeconds = 45) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $req = [System.Net.HttpWebRequest]::Create($Url)
            $req.Proxy = $null
            $req.Timeout = 3000
            $resp = $req.GetResponse()
            $code = [int]$resp.StatusCode
            $resp.Close()
            if ($code -ge 200 -and $code -lt 300) { return $true }
        } catch {
            # 等待服务就绪
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

# ---------- 1. 解析运行模式与 LLM 参数（兼容环境变量覆盖）----------
$effectiveProvider = if ($Provider) { $Provider } elseif ($env:LLM_PROVIDER) { $env:LLM_PROVIDER } elseif ($Mode -eq 'api') { 'openai' } else { 'ollama' }
$effectiveKey = if ($ApiKey) { $ApiKey } elseif ($env:LLM_API_KEY) { $env:LLM_API_KEY } else { '' }
$effectiveBaseUrl = if ($BaseUrl) { $BaseUrl } elseif ($env:LLM_BASE_URL) { $env:LLM_BASE_URL } else { '' }
$effectiveModel = if ($Model) { $Model } elseif ($env:LLM_MODEL) { $env:LLM_MODEL } else { '' }

if ($effectiveProvider -in @('openai', 'dashscope', 'zhipu')) {
    $Mode = 'api'
}

if ($Mode -eq 'api') {
    if (-not $effectiveKey) {
        throw "API 模式（provider=$effectiveProvider）需要提供 -ApiKey 或设置环境变量 LLM_API_KEY"
    }
    if (-not $effectiveBaseUrl) {
        switch ($effectiveProvider) {
            'dashscope' { $effectiveBaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1' }
            'zhipu'     { $effectiveBaseUrl = 'https://open.bigmodel.cn/api/paas/v4' }
            default     { $effectiveBaseUrl = 'https://api.openai.com/v1' }
        }
    }
    if (-not $effectiveModel) {
        switch ($effectiveProvider) {
            'dashscope' { $effectiveModel = 'qwen-plus' }
            'zhipu'     { $effectiveModel = 'glm-4-flash' }
            default     { $effectiveModel = 'gpt-4o-mini' }
        }
    }
} else {
    if (-not $effectiveBaseUrl) {
        $effectiveBaseUrl = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { 'http://127.0.0.1:11434/v1' }
    }
}

Write-Host '============================================================' -ForegroundColor Cyan
Write-Host "  备考知识库与题库系统 - 一键启动（模式: $Mode | provider: $effectiveProvider）" -ForegroundColor Cyan
if ($effectiveModel) {
    Write-Host "  目标模型: $effectiveModel | 端点: $effectiveBaseUrl" -ForegroundColor Cyan
} else {
    Write-Host "  模型配置: 沿用本地配置文件（端点: $effectiveBaseUrl）" -ForegroundColor Cyan
}
Write-Host '============================================================' -ForegroundColor Cyan

# ---------- 2. 探测基础工具与外部依赖 ----------
$javaFallbacks = @('D:\bigplan\tools\jdk21\bin\java.exe')
if ($env:JAVA_HOME) {
    $javaFallbacks = @((Join-Path $env:JAVA_HOME 'bin\java.exe')) + $javaFallbacks
}
$javaExe = Resolve-Tool 'java' $javaFallbacks
if (-not $javaExe) { throw '未找到 Java 21，请安装 JDK 21 或设置 JAVA_HOME' }
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path $javaExe -Parent) -Parent
}

$mvnExe = Resolve-Tool 'mvn' @('D:\bigplan\tools\maven\bin\mvn.cmd')
$pyExe = Resolve-Tool 'python' @(
    (Join-Path $root '.venv-ai\Scripts\python.exe')
)
if (Test-Path (Join-Path $root '.venv-ai\Scripts\python.exe')) {
    $pyExe = Join-Path $root '.venv-ai\Scripts\python.exe'
}

if (-not (Test-TcpPort '127.0.0.1' 3306)) {
    Write-Host '[警告] 未检测到本机 MySQL (127.0.0.1:3306)，若使用远程库请忽略，否则请先启动 MySQL' -ForegroundColor Yellow
} else {
    Write-Host '[OK] MySQL (3306) 在线' -ForegroundColor Green
}

if ($Mode -eq 'local' -and $effectiveProvider -in @('ollama', 'auto')) {
    if (Test-TcpPort '127.0.0.1' 11434) {
        Write-Host '[OK] 本地 Ollama (11434) 在线' -ForegroundColor Green
    } else {
        Write-Host '[提示] 本地 Ollama (11434) 未启动，AI 问答将自动降级为抽取式兜底（可随时执行 ollama serve 恢复）' -ForegroundColor Yellow
    }
}

# ---------- 3. 构造双端共享的 LLM 环境变量（不回显 Key）----------
$env:LLM_PROVIDER = $effectiveProvider
if ($Mode -eq 'api') {
    $env:LLM_MODE = 'api'
    $env:ALLOW_ONLINE_LLM = 'true'
    $env:LLM_API_KEY = $effectiveKey
    $env:LLM_BASE_URL = $effectiveBaseUrl
    $env:LLM_MODEL = $effectiveModel
    $env:LLM_GENERATOR_NAME = $effectiveProvider
    $env:LLM_TIMEOUT_SECONDS = '30.0'
    $env:LLM_TIMEOUT_MS = '30000'
} else {
    $env:LLM_MODE = 'local'
    $env:ALLOW_ONLINE_LLM = 'false'
    $env:OLLAMA_BASE_URL = $effectiveBaseUrl
    if ($effectiveModel) {
        $env:OLLAMA_MODEL = $effectiveModel
        $env:LLM_MODEL = $effectiveModel
    }
    if ($effectiveProvider -eq 'auto' -and $effectiveKey) {
        $env:LLM_API_KEY = $effectiveKey
        $env:ALLOW_ONLINE_LLM = 'true'
    }
}

$pids = @{ startedAt = (Get-Date).ToString('o'); mode = $Mode; provider = $effectiveProvider }

# ---------- 4. 启动 Python AI 微服务 (:8000) ----------
if (-not $SkipAi) {
    if (Test-TcpPort '127.0.0.1' $AiPort) {
        Write-Host "[跳过] 端口 $AiPort 已有服务监听，复用现有 AI 微服务（如需切换模式请先运行 .\stop.ps1）" -ForegroundColor Yellow
    } elseif (-not $pyExe) {
        Write-Host '[警告] 未找到 Python 环境，跳过 AI 微服务（Java 后端将自动降级为 ngram 检索）' -ForegroundColor Yellow
    } else {
        Write-Host "[1/2] 启动 AI 微服务 (127.0.0.1:$AiPort)..." -ForegroundColor Cyan
        $aiOut = Join-Path $runtimeDir 'start-ai.log'
        $aiErr = Join-Path $runtimeDir 'start-ai.err.log'
        $aiProc = Start-Process -FilePath $pyExe `
            -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$AiPort") `
            -WorkingDirectory (Join-Path $root 'ai-service') `
            -RedirectStandardOutput $aiOut -RedirectStandardError $aiErr `
            -PassThru -WindowStyle Hidden
        $pids.aiPid = $aiProc.Id
        if (Wait-Http200 "http://127.0.0.1:$AiPort/v1/health" 45) {
            Write-Host "  [OK] AI 微服务就绪 (PID=$($aiProc.Id)) -> http://127.0.0.1:$AiPort/v1/health" -ForegroundColor Green
        } else {
            Write-Host "  [警告] AI 微服务在 45s 内未就绪，请检查日志：$aiErr" -ForegroundColor Yellow
        }
    }
}

# ---------- 5. 按需打包并启动 Java 后端 (:8080) ----------
$jarPath = Join-Path $root 'backend\target\exam-prep-backend-1.0.0-SNAPSHOT.jar'
if ($Rebuild -or -not (Test-Path $jarPath)) {
    if (-not $mvnExe) { throw '缺少后端 JAR 且未找到 Maven，请先安装 Maven 或手动打包' }
    Write-Host '[打包] 正在构建后端 JAR (mvn -B package -DskipTests)...' -ForegroundColor Cyan
    Push-Location (Join-Path $root 'backend')
    try {
        & $mvnExe -B package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "后端打包失败 (exit=$LASTEXITCODE)" }
    } finally { Pop-Location }
}

if (Test-TcpPort '127.0.0.1' $BackendPort) {
    Write-Host "[跳过] 端口 $BackendPort 已有服务监听（如需重启切换模型请先运行 .\stop.ps1）" -ForegroundColor Yellow
} else {
    Write-Host "[2/2] 启动 Java 后端 (:$BackendPort)..." -ForegroundColor Cyan
    $beOut = Join-Path $runtimeDir 'start-backend.log'
    $beErr = Join-Path $runtimeDir 'start-backend.err.log'
    $beArgs = @('-jar', $jarPath, "--server.port=$BackendPort", "--app.ai.llm.provider=$effectiveProvider")
    if ($Mode -eq 'api') {
        $beArgs += "--app.ai.llm.openai-base-url=$effectiveBaseUrl"
        $beArgs += "--app.ai.llm.openai-model=$effectiveModel"
        $beArgs += "--app.ai.llm.dashscope-model=$effectiveModel"
        $beArgs += "--app.ai.llm.zhipu-model=$effectiveModel"
        $beArgs += "--app.ai.llm.timeout-millis=30000"
    } elseif ($effectiveModel) {
        $beArgs += "--app.ai.llm.ollama-model=$effectiveModel"
    }
    $beProc = Start-Process -FilePath $javaExe `
        -ArgumentList $beArgs `
        -WorkingDirectory (Join-Path $root 'backend') `
        -RedirectStandardOutput $beOut -RedirectStandardError $beErr `
        -PassThru -WindowStyle Hidden
    $pids.backendPid = $beProc.Id
    if (Wait-Http200 "http://127.0.0.1:$BackendPort/api/actuator/health" 60) {
        Write-Host "  [OK] Java 后端就绪 (PID=$($beProc.Id)) -> http://127.0.0.1:$BackendPort/api/actuator/health" -ForegroundColor Green
    } else {
        Write-Host "  [失败] Java 后端在 60s 内未就绪，请检查日志：$beOut / $beErr" -ForegroundColor Red
        exit 1
    }
}

$pids | ConvertTo-Json | Set-Content -Path (Join-Path $runtimeDir 'service-pids.json') -Encoding UTF8

Write-Host ''
Write-Host '================ 启动完成 ================' -ForegroundColor Green
Write-Host "  学生端 Web : http://127.0.0.1:$BackendPort/api/student/index.html"
Write-Host "  管理端 Web : http://127.0.0.1:$BackendPort/api/admin/index.html"
Write-Host "  接口文档   : http://127.0.0.1:$BackendPort/api/swagger-ui.html"
Write-Host "  AI 能力诊断: http://127.0.0.1:$BackendPort/api/ai/capability"
Write-Host "  一键停止   : .\stop.ps1"
Write-Host '==========================================' -ForegroundColor Green
