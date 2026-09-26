@echo off
rem 每日数据库备份（Windows 计划任务 02:00 调用）
rem 用法：backup.bat   （需 mysqldump 在 PATH，或修改 MYSQLDUMP 为绝对路径）

set MYSQLDUMP="D:\bigplan\tools\mysql-8.0.46\bin\mysqldump.exe"
set DB=exam_prep
set USER=root
set PWD=123456
set DIR=%~dp0backup
set KEEP=7

if not exist "%DIR%" mkdir "%DIR%"

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set DT=%%I
set STAMP=%DT:~0,8%

%MYSQLDUMP% -u%USER% -p%PWD% --databases %DB% > "%DIR%\%DB%_%STAMP%.sql"

rem 循环保留最近 %KEEP% 份
for /f "skip=%KEEP% delims=" %%F in ('dir /b /o-n "%DIR%\%DB%_*.sql"') do del "%DIR%\%%F"

echo 备份完成：%DIR%\%DB%_%STAMP%.sql
