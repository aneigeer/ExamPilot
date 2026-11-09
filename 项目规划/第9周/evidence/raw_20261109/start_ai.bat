@echo off
REM 降级演练用 AI 服务启动器（演练收尾恢复 8000 使用，非项目源码）
chcp 65001 > nul
cd /d "D:\大学生多考试备考知识库与题库系统\ai-service"
set PYTHONUNBUFFERED=1
set PYTHONIOENCODING=utf-8
"D:\大学生多考试备考知识库与题库系统\.venv-ai\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 >> "D:\大学生多考试备考知识库与题库系统\项目规划\第9周\evidence\ai_service_restored_20261109.log" 2>&1
