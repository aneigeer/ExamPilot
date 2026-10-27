@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PY=%~dp0.venv-ai\Scripts\python.exe
if not exist "%PY%" (
  echo [ERROR] venv python not found: %PY%
  pause
  exit /b 1
)

echo ============================================
echo   Ingest OS-course PPTX into knowledge base
echo   source: C:\Users\15241\Downloads\第*章*.pptx
echo ============================================
echo.

rem --- check AI service on :8000 ---
netstat -ano | findstr ":8000" | findstr "LISTENING" >nul 2>&1
if errorlevel 1 (
  echo [1/3] AI service not running, starting it...
  start "AI-Service" /min "%PY%" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --app-dir "%~dp0ai-service"
  echo       waiting 25s for model/embedding init...
  timeout /t 25 /nobreak >nul
) else (
  echo [1/3] AI service already listening on :8000
)

echo [2/3] Ingesting PPTX (dry-run first)...
"%PY%" "%~dp0ai-service\tools\ingest_pptx.py" --dry-run
if errorlevel 1 goto fail

echo.
echo [3/3] Writing to database + vectorizing...
"%PY%" "%~dp0ai-service\tools\ingest_pptx.py"
if errorlevel 1 goto fail

echo.
echo [DONE] Check the app: Courseware list, or ask AI "死锁的四个必要条件"
pause
exit /b 0

:fail
echo.
echo [FAILED] See messages above. Nothing was published if dry-run failed.
pause
exit /b 1
