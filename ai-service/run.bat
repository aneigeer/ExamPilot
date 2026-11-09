@echo off
chcp 65001 >nul
REM ============================================================================
REM  AI 微服务启动脚本（主理人执行）
REM
REM  用法：双击本文件，或在 CMD 中执行  cd ai-service && run.bat
REM
REM  启动前请确认：
REM    1) 已执行 install.bat（虚拟环境在 ..\.venv-ai）
REM    2) Qdrant 已启动：docker compose -f ..\docker-compose.ai.yml up -d
REM       （Qdrant 未启动也能起，只是 /v1/search 返回 503，Java 侧降级 ngram）
REM ============================================================================

setlocal
cd /d "%~dp0"

if not exist "..\.venv-ai\Scripts\python.exe" (
    echo [错误] 找不到虚拟环境 ..\.venv-ai，请先执行 install.bat
    pause
    exit /b 1
)

call ..\.venv-ai\Scripts\activate.bat

REM 读取端口，允许外部覆盖（默认 8000）
if "%PORT%"=="" set PORT=8000

REM ============================================================================
REM  安全前置检查：内部令牌（只提示，不阻塞启动）
REM
REM  /v1/agent/* 是 fail-closed：未配置 AI_INTERNAL_TOKEN 时一律 401，
REM  Agent 工具链（查错题本/学情/学习计划）会整体失败；
REM  但 AI 问答、检索、OCR 等其余接口照常可用，因此这里只告警、不退出。
REM
REM  用 python 读配置而不是读 %AI_INTERNAL_TOKEN%：令牌一般写在 .env 里，
REM  CMD 环境变量是空的，直接判断环境变量会误报。
REM ============================================================================
python -c "import sys; from app.config import get_settings; sys.exit(0 if get_settings().agent.internal_token else 2)"
if errorlevel 2 (
    echo.
    echo ============================================================
    echo  [错误] 未配置 AI_INTERNAL_TOKEN
    echo ------------------------------------------------------------
    echo  后果：/v1/agent/* 一律返回 401，Agent 工具链会失败
    echo        （AI 问答 / 检索 / OCR 不受影响，服务照常启动）
    echo.
    echo  修复：在 ai-service\.env 写入
    echo          AI_INTERNAL_TOKEN=与Java相同的令牌值
    echo        并确保 Java 的 app.ai.internal-token 完全同值，然后重启两侧。
    echo.
    echo  本地仅调 Python 时可设 AGENT_ALLOW_ANONYMOUS=1
    echo        （关闭鉴权，启动日志会打 ERROR，切勿用于生产）
    echo ============================================================
    echo.
)

echo.
echo ============================================================
echo   启动 AI 微服务  http://localhost:%PORT%
echo   Swagger 文档   http://localhost:%PORT%/docs
echo   健康检查       http://localhost:%PORT%/v1/health
echo   停止服务       Ctrl + C
echo ============================================================
echo.

rem 默认仅监听回环（学生端走 Java，不直连 AI）。跨机调试再设 AI_HOST=0.0.0.0
if "%AI_HOST%"=="" set AI_HOST=127.0.0.1
python -m uvicorn app.main:app --host %AI_HOST% --port %PORT%

echo.
echo 服务已退出。
pause
endlocal
