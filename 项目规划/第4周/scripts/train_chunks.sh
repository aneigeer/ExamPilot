#!/usr/bin/env bash
# 分段推进：每次只跑到下一个 5 的倍数，正常退出，再续下一次。
# 针对「进程只能活几分钟」的环境：短进程不会被外部杀掉。
set -u

ROOT="D:/大学生多考试备考知识库与题库系统"
cd "$ROOT" || exit 1

PY=".venv-ai/Scripts/python.exe"
OUT="training/output/qwen2.5-3b-exam-lora-v1"
LOGDIR="项目规划/第4周/evidence"
BASE="C:/models/models/Qwen--Qwen2.5-3B-Instruct/snapshots/master"
FINAL=135

mkdir -p "$LOGDIR"

for target in $(seq 80 5 $FINAL); do
  CKPT=$(ls -d "$OUT"/checkpoint-* 2>/dev/null | sort -V | tail -1)
  step_now=$(basename "$CKPT" | sed 's/checkpoint-//')
  if [ "$step_now" -ge "$FINAL" ]; then
    echo "[chunk] already at $step_now >= $FINAL" | tee -a "$LOGDIR/chunk.log"
    exit 0
  fi
  if [ "$step_now" -ge "$target" ]; then
    continue
  fi

  LOG="$LOGDIR/chunk_${target}.log"
  rm -f "C:/Users/15241/.cache/huggingface/datasets/"*.lock 2>/dev/null

  HF_DATASETS_CACHE="$ROOT/training/.hf_cache" \
  TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=4 \
  "$PY" training/train_qlora.py \
    --model "$BASE" \
    --train training/data/sft_train_behavior.jsonl \
    --val training/data/sft_val.jsonl \
    --max-length 640 \
    --max-steps "$target" \
    --save-steps 5 \
    --logging-steps 5 \
    --eval-strategy no \
    --vram-limit-gb 7.0 \
    --resume "$CKPT" \
    --out "$OUT" > "$LOG" 2>&1

  if grep -q "done:" "$LOG"; then
    echo "[chunk] target=$target OK (from $step_now)" | tee -a "$LOGDIR/chunk.log"
  else
    last=$(tr '\r' '\n' < "$LOG" | grep -oE "[0-9]+/[0-9]+ \[" | tail -1 | cut -d/ -f1)
    echo "[chunk] target=$target DIED at ${last:-?}" | tee -a "$LOGDIR/chunk.log"
    sleep 5
  fi
done

echo "[chunk] finished" | tee -a "$LOGDIR/chunk.log"
