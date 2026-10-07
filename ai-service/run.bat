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

echo.
echo ============================================================
echo   启动 AI 微服务  http://localhost:%PORT%
echo   Swagger 文档   http://localhost:%PORT%/docs
echo   健康检查       http://localhost:%PORT%/v1/health
echo   停止服务       Ctrl + C
echo ============================================================
echo.

python -m uvicorn app.main:app --host 0.0.0.0 --port %PORT%

echo.
echo 服务已退出。
pause
endlocal
