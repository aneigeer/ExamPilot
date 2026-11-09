@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem AI 服务 watchdog：native 崩溃后自动重启
rem 日志：项目规划\第7周\evidence\ai_service_watchdog.log

set LOG=..\项目规划\第7周\evidence\ai_service_watchdog.log
if not exist "..\项目规划\第7周\evidence" mkdir "..\项目规划\第7周\evidence"

:loop
echo [%date% %time%] starting ai-service >> "%LOG%"
..\.venv-ai\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --limit-concurrency 4 >> "%LOG%" 2>&1
echo [%date% %time%] ai-service exited code=%errorlevel% >> "%LOG%"
timeout /t 2 /nobreak >nul
goto loop
