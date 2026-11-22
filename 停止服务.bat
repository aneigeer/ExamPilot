@echo off
chcp 65001 >nul
title 备考知识库与题库系统 - 双击一键停止
echo.
echo [*] 正在停止 Java 后端 (:8080) 与 Python AI 微服务 (:8000)...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
echo.
pause
