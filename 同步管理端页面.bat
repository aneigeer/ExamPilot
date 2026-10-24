@echo off
chcp 65001 >nul
REM ============================================================================
REM  同步管理端静态页面：admin-web（源码） -> backend/src/main/resources/static/admin
REM
REM  背景：管理端是「零构建单页应用」，由后端同源托管，因此同一份页面存在两处。
REM        必须保持一致，否则会出现「改了源码却没生效」或反过来的经典问题。
REM  做法：以 admin-web 为准，镜像覆盖后端静态目录。
REM ============================================================================

setlocal
cd /d "%~dp0"

echo 正在同步 admin-web -> backend\src\main\resources\static\admin ...
robocopy "admin-web" "backend\src\main\resources\static\admin" /MIR /NJH /NJS /NDL /NP
set RC=%ERRORLEVEL%

REM robocopy 退出码 0-7 表示成功（1=有文件被复制），>=8 才是真失败
if %RC% GEQ 8 (
    echo [错误] 同步失败，robocopy 退出码 %RC%
    pause
    exit /b 1
)

echo 同步完成。请重新构建/重启后端以生效。
pause
endlocal
