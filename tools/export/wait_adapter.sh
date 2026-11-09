#!/usr/bin/env bash
# 轮询训练输出，等待可用 adapter。规则：
#   1) 顶层 adapter_model.safetensors 存在 -> 用它（训练正常结束）
#   2) 否则取 checkpoint-<N> 中 N 最大且含 adapter_model.safetensors 的目录
# 每 60 秒一次，最多 90 分钟。找到后把路径写到第三个参数文件，并打印。
set -u
OUT_DIR="${1:-training/output/qwen2.5-7b-exam-lora-v3}"
RESULT_FILE="${2:-C:/models/.adapter_path.txt}"
MAX_WAIT="${3:-5400}"
START=$(date +%s)

pick() {
  local top="$OUT_DIR/adapter_model.safetensors"
  if [ -f "$top" ]; then echo "$OUT_DIR"; return 0; fi
  local best="" bestn=-1 d n
  for d in "$OUT_DIR"/checkpoint-*; do
    [ -d "$d" ] || continue
    [ -f "$d/adapter_model.safetensors" ] || continue
    n="${d##*checkpoint-}"
    case "$n" in ''|*[!0-9]*) continue;; esac
    if [ "$n" -gt "$bestn" ]; then bestn="$n"; best="$d"; fi
  done
  [ -n "$best" ] && { echo "$best"; return 0; }
  return 1
}

while :; do
  if p=$(pick); then
    mkdir -p "$(dirname "$RESULT_FILE")"
    printf '%s' "$p" > "$RESULT_FILE"
    echo "FOUND $p  ($(date '+%H:%M:%S'))"
    ls -la "$p" | head -20
    exit 0
  fi
  now=$(date +%s)
  if [ $((now - START)) -ge "$MAX_WAIT" ]; then
    echo "TIMEOUT after ${MAX_WAIT}s, still nothing usable in $OUT_DIR"
    ls -la "$OUT_DIR" 2>&1
    exit 2
  fi
  echo "[$(date '+%H:%M:%S')] waiting... $(ls -d "$OUT_DIR"/checkpoint-* 2>/dev/null | tr '\n' ' ')"
  sleep 60
done
