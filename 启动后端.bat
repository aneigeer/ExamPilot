@echo off
chcp 65001 >nul
title 备考知识库 - 后端服务
cd /d "%~dp0backend"

echo.
echo ============================================
echo   备考知识库与题库系统 - 后端启动脚本
echo   启动后地址：http://你的电脑IP:8080/api/
echo   管理端页面：http://localhost:8080/api/admin/index.html
echo   接口文档：  http://localhost:8080/api/swagger-ui.html
echo ============================================
echo.

if not exist "target\exam-prep-backend-1.0.0-SNAPSHOT.jar" (
    echo [!] 没找到 jar 包，请先执行打包：
    echo     cd backend ^&^& mvn clean package -DskipTests
    echo.
    pause
    exit /b 1
)

echo [*] 正在启动，请等待出现 "Started ExamPrepApplication" ...
echo.

"D:\bigplan\tools\jdk21\bin\java.exe" -jar target\exam-prep-backend-1.0.0-SNAPSHOT.jar --server.port=8080

echo.
echo [!] 后端已停止运行。
pause
