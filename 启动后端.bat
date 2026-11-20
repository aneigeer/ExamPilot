@echo off
chcp 65001 >nul
title 备考知识库 - 一键启动（本地 / API 双模）
echo.
echo [*] 正在调用 start.ps1 启动全栈服务（支持传参，例如：启动后端.bat -Mode api -Provider dashscope -ApiKey sk-xxx）
echo.
powershell -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
echo.
pause
