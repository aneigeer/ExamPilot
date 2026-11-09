#!/usr/bin/env bash
# 训练看门狗：崩溃后自动从最新 checkpoint 续跑，直到跑满或次数用尽。
# 用法：bash 项目规划/第4周/scripts/train_watchdog.sh
set -u

ROOT="D:/大学生多考试备考知识库与题库系统"
cd "$ROOT" || exit 1

PY=".venv-ai/Scripts/python.exe"
OUT="training/output/qwen2.5-3b-exam-lora-v1"
LOGDIR="项目规划/第4周/evidence"
BASE="C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master"
MAX_STEPS=135
SAVE_STEPS=5
MAX_ATTEMPT=12

mkdir -p "$OUT" "$LOGDIR"

for attempt in $(seq 1 $MAX_ATTEMPT); do
  CKPT=$(ls -d "$OUT"/checkpoint-* 2>/dev/null | sort -V | tail -1)
  LOG="$LOGDIR/train_attempt_${attempt}.log"

  ARGS=(
    --model "$BASE"
    --train training/data/sft_train_behavior.jsonl
    --val training/data/sft_val.jsonl
    --max-length 640
    --max-steps "$MAX_STEPS"
    --save-steps "$SAVE_STEPS"
    --logging-steps 5
    --eval-strategy no
    --vram-limit-gb 6.5
    --out "$OUT"
  )
  if [ -n "$CKPT" ]; then
    ARGS+=(--resume "$CKPT")
    echo "[watchdog] attempt=$attempt resume=$CKPT" | tee -a "$LOGDIR/watchdog.log"
  else
    echo "[watchdog] attempt=$attempt fresh" | tee -a "$LOGDIR/watchdog.log"
  fi

  TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4 "$PY" training/train_qlora.py "${ARGS[@]}" > "$LOG" 2>&1

  if grep -q "done:" "$LOG" 2>/dev/null; then
    echo "[watchdog] SUCCESS attempt=$attempt" | tee -a "$LOGDIR/watchdog.log"
    exit 0
  fi

  STEP=$(tr '\r' '\n' < "$LOG" | grep -oE "[0-9]+/[0-9]+ \[" | tail -1 | cut -d/ -f1)
  echo "[watchdog] attempt=$attempt died at step=${STEP:-?}" | tee -a "$LOGDIR/watchdog.log"
  sleep 10
done

echo "[watchdog] GIVE UP after $MAX_ATTEMPT attempts" | tee -a "$LOGDIR/watchdog.log"
exit 1
