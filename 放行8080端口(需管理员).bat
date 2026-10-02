@echo off
chcp 65001 >nul
title 放行 8080 端口（需管理员）

echo.
echo 本脚本用于在 Windows 防火墙放行 8080 端口，
echo 手机（同一 WiFi）才能连上电脑上的后端服务。
echo.

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] 当前不是管理员权限。
    echo     请右键本文件 -^> "以管理员身份运行"
    echo.
    pause
    exit /b 1
)

netsh advfirewall firewall delete rule name="ExamPrep-8080" >nul 2>&1
netsh advfirewall firewall add rule name="ExamPrep-8080" dir=in action=allow protocol=TCP localport=8080

if %errorlevel% equ 0 (
    echo [OK] 8080 端口已放行，手机现在可以连接了。
) else (
    echo [X] 放行失败，请检查是否以管理员身份运行。
)

echo.
pause
