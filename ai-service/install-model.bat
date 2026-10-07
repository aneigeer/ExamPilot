@echo off
chcp 65001 >nul
REM ============================================================================
REM  bge-small-zh-v1.5 ONNX INT8 模型下载脚本（主理人执行）
REM
REM  用途：把 embedding 模型放进 ai-service\models\bge-small-zh-v1.5\
REM        文件来源：Xenova/bge-small-zh-v1.5（BAAI 官方 ONNX 导出仓库）
REM  下载量：约 24 MB（INT8 量化版）+ tokenizer.json 约 11 MB
REM
REM  国内网络：优先走 hf-mirror.com 镜像，失败自动回退 huggingface.co
REM ============================================================================

setlocal
cd /d "%~dp0"
set DIR=models\bge-small-zh-v1.5
set MIRROR=https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main
set ORIG=https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main

echo.
echo ============================================================
echo   下载 bge-small-zh-v1.5 ONNX INT8 模型（约 35 MB）
echo ============================================================
echo.

if not exist "%DIR%" mkdir "%DIR%"

REM ---------- 1. tokenizer.json ----------
if exist "%DIR%\tokenizer.json" (
    echo [跳过] tokenizer.json 已存在
) else (
    echo [1/3] 下载 tokenizer.json ...
    curl -L --connect-timeout 15 -o "%DIR%\tokenizer.json" "%MIRROR%/tokenizer.json"
    if errorlevel 1 (
        echo       镜像失败，改用官方源 ...
        curl -L --connect-timeout 15 -o "%DIR%\tokenizer.json" "%ORIG%/tokenizer.json"
    )
)

REM ---------- 2. config.json ----------
if exist "%DIR%\config.json" (
    echo [跳过] config.json 已存在
) else (
    echo [2/3] 下载 config.json ...
    curl -L --connect-timeout 15 -o "%DIR%\config.json" "%MIRROR%/config.json"
    if errorlevel 1 curl -L --connect-timeout 15 -o "%DIR%\config.json" "%ORIG%/config.json"
)

REM ---------- 3. ONNX INT8 权重 ----------
REM 注意：HuggingFace 上量化文件名为 model_quantized.onnx（24MB），
REM       而 ai-service 代码约定读 model.onnx，下载后必须重命名。
if exist "%DIR%\model.onnx" (
    echo [跳过] model.onnx 已存在
) else (
    echo [3/3] 下载 ONNX 权重（INT8 量化版，约 24 MB）...
    curl -L --connect-timeout 15 -o "%DIR%\model_quantized.onnx" "%MIRROR%/onnx/model_quantized.onnx"
    if errorlevel 1 (
        echo       镜像失败，改用官方源（可能较慢）...
        curl -L --connect-timeout 15 -o "%DIR%\model_quantized.onnx" "%ORIG%/onnx/model_quantized.onnx"
    )
    if exist "%DIR%\model_quantized.onnx" move /y "%DIR%\model_quantized.onnx" "%DIR%\model.onnx" >nul
)

echo.
echo ============================================================
echo   下载结果
echo ============================================================
dir /-C "%DIR%"
echo.

REM ---------- 校验 ----------
set OK=1
if not exist "%DIR%\model.onnx" set OK=0
if not exist "%DIR%\tokenizer.json" set OK=0

REM model.onnx 必须大于 10MB，若只有几 KB 说明下载的是错误页
for %%A in ("%DIR%\model.onnx") do set SIZE=%%~zA
if %SIZE% LSS 10000000 (
    echo [错误] model.onnx 只有 %SIZE% 字节，明显不是模型文件（可能下到了错误页）
    set OK=0
)

echo.
if "%OK%"=="1" (
    echo [成功] 模型文件已就绪
    echo.
    echo 下一步：双击 run.bat 启动 AI 服务
    echo 验证：浏览器打开 http://127.0.0.1:8000/v1/health
    echo        应看到 embedding.ready=true, dim=512
) else (
    echo [失败] 模型文件不完整，请检查网络后重新运行本脚本
    echo.
    echo 手动下载备用地址：
    echo   %MIRROR%/onnx/model_quantized.onnx   （下载后重命名为 model.onnx）
    echo   %MIRROR%/tokenizer.json
    echo   %MIRROR%/config.json
    echo 放到：%%CD%%\%DIR%
)

echo.
pause
