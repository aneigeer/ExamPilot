@echo off
chcp 65001 >nul
REM ============================================================================
REM  OCR 可选依赖安装（体积 500MB-1GB，主理人按需执行）
REM
REM  ★ 不装不影响服务启动：
REM     /v1/health 的 ocr.ready=false，POST /v1/ocr 返回 503，
REM     Java 侧降级为人工录入，其余能力（embedding/检索/大模型）全部正常。
REM
REM  预计耗时：10-20 分钟
REM  预计体积：约 500MB-1GB（含首次运行自动下载的模型约 50-100MB）
REM  首次 /v1/ocr 调用还会再花 5-15 秒加载模型
REM ============================================================================

setlocal
cd /d "%~dp0"

if not exist "..\.venv-ai\Scripts\python.exe" (
    echo [错误] 找不到虚拟环境 ..\.venv-ai，请先执行 install.bat
    pause
    exit /b 1
)

call ..\.venv-ai\Scripts\activate.bat

echo.
echo ============================================================
echo   安装 PaddleOCR（可选能力，体积较大）
echo ============================================================
echo.
echo [1/3] 安装 paddlepaddle 3.3.0 CPU 版（约 200MB）...
echo       使用官方 CPU 源，PyPI 默认包不含 MKL 优化
python -m pip install paddlepaddle==3.3.0 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
if errorlevel 1 (
    echo.
    echo [提示] 官方源失败，可改用镜像源重试：
    echo   pip install paddlepaddle==3.3.0 -i https://pypi.tuna.tsinghua.edu.cn/simple
    echo   注意：镜像源版本可能是 GPU 版，体积更大但功能可用。
    pause
    exit /b 1
)

echo.
echo [2/3] 安装 paddleocr（约 300MB）...
python -m pip install paddleocr -i https://pypi.tuna.tsinghua.edu.cn/simple
if errorlevel 1 (
    echo [错误] paddleocr 安装失败，OCR 能力将不可用（其余功能正常）
    pause
    exit /b 1
)

echo.
echo [3/3] 校验 ...
python -c "import paddle; print('  paddle OK', paddle.__version__)"
python -c "import paddleocr; print('  paddleocr OK', paddleocr.__version__)"

echo.
echo ============================================================
echo   安装完成
echo.
echo   下一步：
echo   1) 启动服务 run.bat
echo   2) 第一次调 /v1/ocr 时会自动下载模型（约 50-100MB）并加载（约 5-15 秒）
echo   3) 验证：curl -X POST http://localhost:8000/v1/ocr ^
echo        -H "Content-Type: application/json" ^
echo        -d "{\"image_base64\":\"...\"}"
echo.
echo   模型缓存位置：%%USERPROFILE%%\.paddlex\official_models
echo   如需离线运行，可把该目录复制到 ai-service\models\paddle\
echo ============================================================
echo.

pause
endlocal
