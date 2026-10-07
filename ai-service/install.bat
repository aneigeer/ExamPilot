@echo off
chcp 65001 >nul
REM ============================================================================
REM  AI 微服务依赖安装脚本（主理人执行）
REM
REM  用法：双击本文件，或在 CMD 中执行  cd ai-service && install.bat
REM
REM  预计耗时：3-8 分钟（网络决定）
REM  预计体积：约 135 MB 核心依赖 + 约 28 MB embedding 模型（首次预热时下载/放置）
REM
REM  ★ 本脚本会创建虚拟环境 .venv-ai，不会污染系统 Python
REM  ★ 不安装 PyTorch（ONNX 路线，这是选型上的关键决策）
REM ============================================================================

setlocal
cd /d "%~dp0"

echo.
echo ============================================================
echo   备考知识库 - AI 微服务依赖安装
echo ============================================================
echo.

REM --- 0. 检查 Python ---
where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 python，请先安装 Python 3.11-3.13 并勾选 "Add to PATH"
    pause
    exit /b 1
)
for /f "tokens=2" %%v in ('python --version 2^>^&1') do set PY_VER=%%v
echo [0/5] 检测到 Python %PY_VER%

REM --- 1. 创建虚拟环境 ---
if exist "..\.venv-ai\Scripts\python.exe" (
    echo [1/5] 虚拟环境已存在，跳过创建：..\.venv-ai
) else (
    echo [1/5] 创建虚拟环境 ..\.venv-ai ...
    python -m venv ..\.venv-ai
    if errorlevel 1 (
        echo [错误] 虚拟环境创建失败
        pause
        exit /b 1
    )
)

call ..\.venv-ai\Scripts\activate.bat

REM --- 2. 升级 pip ---
echo [2/5] 升级 pip ...
python -m pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple

REM --- 3. 安装核心依赖 ---
echo [3/5] 安装核心依赖（约 135 MB，3-8 分钟）...
echo       若清华源失败，请改用官方源：把下面命令的 -i 参数删掉重跑
python -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
if errorlevel 1 (
    echo.
    echo [错误] 核心依赖安装失败。常见原因：
    echo   1) 网络问题 —— 换官方源重试：pip install -r requirements.txt
    echo   2) Python 版本过低 —— 需要 3.11 及以上
    echo   3) 磁盘空间不足 —— 需要至少 500 MB 可用空间
    pause
    exit /b 1
)

REM --- 4. 校验 ---
echo [4/5] 校验关键依赖 ...
python -c "import fastapi, uvicorn, pydantic, httpx; print('  核心框架 OK')"
python -c "import onnxruntime; print('  onnxruntime OK', onnxruntime.__version__)" 2>nul
if errorlevel 1 echo   [警告] onnxruntime 未装好，/v1/embed 与 /v1/search 将返回 503
python -c "import qdrant_client; print('  qdrant-client OK')" 2>nul
if errorlevel 1 echo   [警告] qdrant-client 未装好，向量检索将返回 503
python -c "import pypdf; print('  pypdf OK')" 2>nul
if errorlevel 1 echo   [警告] pypdf 未装好，PDF 文本抽取不可用

REM --- 5. 说明 ---
echo.
echo [5/5] 安装完成
echo.
echo ============================================================
echo   接下来：
echo   1) 放置 embedding 模型到  ai-service\models\bge-small-zh-v1.5\
echo        需要两个文件：model.onnx（INT8 量化）与 tokenizer.json
echo        若暂未放置，服务仍可启动，只是 /v1/embed 与 /v1/search 返回 503
echo   2) 启动 Qdrant：  docker compose -f ..\docker-compose.ai.yml up -d
echo   3) 启动服务：     run.bat
echo   4) 浏览器验证：   http://localhost:8000/docs
echo.
echo   可选：安装 OCR 依赖（500MB-1GB）请执行 install-ocr.bat
echo ============================================================
echo.

pause
endlocal
