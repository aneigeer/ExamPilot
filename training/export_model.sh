#!/usr/bin/env bash
# 一键导出：等基座下载完 -> 合并 LoRA -> 转 GGUF -> 量化 Q4_0 -> 导入 Ollama
set -euo pipefail
cd "$(dirname "$0")/.."

PY=".venv-ai/Scripts/python.exe"
BASE_GLOB="C:/models/models/Qwen--Qwen2.5-7B-Instruct/snapshots/master"
LORA="training/output/qwen2.5-7b-exam-lora-v3"
MERGED="C:/models/exam-merged"
F16="C:/models/exam-f16.gguf"
Q4="C:/models/exam-q4_0-v3.gguf"

echo "[1/5] 等待基座下载完成..."
while true; do
  total=$(ls "$BASE_GLOB"/*.safetensors 2>/dev/null | wc -l || true)
  incomplete=$(ls "$BASE_GLOB"/*.incomplete 2>/dev/null | wc -l || true)
  if [ "$total" -ge 4 ] && [ "$incomplete" -eq 0 ]; then break; fi
  sleep 20
done
echo "    基座就绪：$(ls "$BASE_GLOB"/*.safetensors | wc -l) 个分片"

echo "[2/5] 合并 LoRA -> $MERGED"
$PY training/merge_lora.py --base "$BASE_GLOB" --lora "$LORA" --out "$MERGED"

echo "[3/5] 转 F16 GGUF -> $F16"
$PY tools/llama/conv_b6000.py "$MERGED" --outfile "$F16" --outtype f16

echo "[4/5] 量化 Q4_0 -> $Q4"
$PY tools/quantize_gguf.py --src "$F16" --dst "$Q4" --qtype q4_0

echo "[5/5] 导入 Ollama"
sed "s|^FROM .*|FROM $Q4|" training/Modelfile.exam-prep-7b > training/Modelfile.exam-prep-7b-q4
ollama create exam-prep-7b -f training/Modelfile.exam-prep-7b-q4
ollama list | head -5
echo "DONE"
