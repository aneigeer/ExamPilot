@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PY=.venv-ai\Scripts\python.exe
set OUT=training\output\qwen2.5-7b-exam-lora-v3b

echo ============================================
echo  v3 训练（干净数据版，从 0 跑 50 步）
echo  预计 50 分钟，窗口关掉会中断，请勿关闭
echo ============================================
echo.

%PY% training\train_qlora.py ^
  --train training\data\sft_train_behavior.jsonl ^
  --out %OUT% ^
  --epochs 1 ^
  --max-length 640 ^
  --lr 3e-4 ^
  --max-steps 50 ^
  --save-steps 10

echo.
echo ============================================
echo  训练结束。产物目录：%OUT%
echo  看到 adapter_model.safetensors 即成功
echo ============================================
dir /b %OUT%
pause
