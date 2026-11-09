#!/usr/bin/env bash
# 重启后执行：从最新 checkpoint 续跑到 135 步（1 epoch），完成后打印验收摘要。
# 用法：bash 项目规划/第4周/scripts/resume_to_135.sh
set -u

ROOT="D:/大学生多考试备考知识库与题库系统"
cd "$ROOT" || exit 1

PY=".venv-ai/Scripts/python.exe"
OUT="training/output/qwen2.5-3b-exam-lora-v1"
BASE="C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master"
LOG="项目规划/第4周/evidence/train_final_to135.log"

CKPT=$(ls -d "$OUT"/checkpoint-* 2>/dev/null | sort -V | tail -1)
STEP=$(basename "$CKPT" | sed 's/checkpoint-//')
echo "[resume] 从 $CKPT（step=$STEP）继续，目标 135"

rm -f "C:/Users/15241/.cache/huggingface/datasets/"*.lock 2>/dev/null

HF_DATASETS_CACHE="$ROOT/training/.hf_cache" \
TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4 \
"$PY" training/train_qlora.py \
  --model "$BASE" \
  --train training/data/sft_train_behavior.jsonl \
  --val training/data/sft_val.jsonl \
  --max-length 640 \
  --max-steps 135 \
  --save-steps 5 \
  --logging-steps 5 \
  --eval-strategy no \
  --vram-limit-gb 7.0 \
  --resume "$CKPT" \
  --out "$OUT" > "$LOG" 2>&1

if grep -q "done:" "$LOG"; then
  echo "[resume] 完成"
else
  echo "[resume] 中断，最后进度：$(tr '\r' '\n' < "$LOG" | grep -oE '[0-9]+/135 \[' | tail -1)"
  echo "[resume] 再执行一次本脚本即可继续"
  exit 1
fi
