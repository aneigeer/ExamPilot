@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title 备考知识库与题库系统 - 双击一键启动

:: 如果命令行带了参数，直接透传给 start.ps1
if not "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
    goto :end
)

echo ============================================================
echo   大学生多考试备考知识库与题库系统 - 一键启动菜单
echo ============================================================
echo   [1] 本地 Ollama 模型模式（默认，零 API 费用，5 秒后自动选择）
echo   [2] 云端 API 模式（百炼 DashScope / DeepSeek / 智谱 / OpenAI）
echo   [3] 重新打包并启动本地模式（改过 Java 代码后选此项）
echo ============================================================
choice /c 123 /n /t 5 /d 1 /m "请选择启动模式 [1/2/3]（默认 1）："
set "MODE_CHOICE=%errorlevel%"

if "%MODE_CHOICE%"=="2" goto :api_mode
if "%MODE_CHOICE%"=="3" goto :rebuild_mode

:local_mode
echo.
echo [*] 正在以【本地 Ollama 模式】启动全栈服务...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -Mode local
if errorlevel 1 goto :end
goto :open_browser

:rebuild_mode
echo.
echo [*] 正在【重新打包 + 本地模式】启动全栈服务...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -Mode local -Rebuild
if errorlevel 1 goto :end
goto :open_browser

:api_mode
echo.
echo 请选择云端服务商：
echo   [1] 阿里云百炼 (dashscope, 默认模型 qwen-plus)
echo   [2] DeepSeek   (openai 兼容, 默认模型 deepseek-chat)
echo   [3] 智谱 GLM   (zhipu, 默认模型 glm-4-flash)
echo   [4] OpenAI     (openai, 默认模型 gpt-4o-mini)
set /p "PROV_NUM=请输入序号 [1-4]（默认 1）："
if "%PROV_NUM%"=="" set "PROV_NUM=1"

set "PROVIDER=dashscope"
set "BASE_URL="
set "MODEL_NAME=qwen-plus"

if "%PROV_NUM%"=="2" (
    set "PROVIDER=openai"
    set "BASE_URL=https://api.deepseek.com/v1"
    set "MODEL_NAME=deepseek-chat"
)
if "%PROV_NUM%"=="3" (
    set "PROVIDER=zhipu"
    set "MODEL_NAME=glm-4-flash"
)
if "%PROV_NUM%"=="4" (
    set "PROVIDER=openai"
    set "BASE_URL=https://api.openai.com/v1"
    set "MODEL_NAME=gpt-4o-mini"
)

set /p "API_KEY=请输入 API Key（必填）："
if "%API_KEY%"=="" (
    echo [错误] API Key 不能为空！
    goto :end
)
set /p "INPUT_MODEL=请输入模型名（直接回车默认 %MODEL_NAME%）："
if not "%INPUT_MODEL%"=="" set "MODEL_NAME=%INPUT_MODEL%"

echo.
echo [*] 正在以【云端 API 模式 (%PROVIDER% / %MODEL_NAME%)】启动全栈服务...
if "%BASE_URL%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -Mode api -Provider "%PROVIDER%" -ApiKey "%API_KEY%" -Model "%MODEL_NAME%"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -Mode api -Provider "%PROVIDER%" -BaseUrl "%BASE_URL%" -ApiKey "%API_KEY%" -Model "%MODEL_NAME%"
)
if errorlevel 1 goto :end

:open_browser
echo.
echo [*] 服务已就绪，正在自动打开浏览器（学生端 + 管理端）...
start "" "http://127.0.0.1:8080/api/student/index.html"
start "" "http://127.0.0.1:8080/api/admin/index.html"

:end
echo.
pause
