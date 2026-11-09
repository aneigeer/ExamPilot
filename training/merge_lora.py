"""把 LoRA adapter 合并回基座，产出可被 Ollama 导入的全精度模型目录。

用法：
    python training/merge_lora.py --lora training/output/qwen2.5-7b-exam-lora \
        --out D:/models/qwen2.5-7b-exam-merged
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

from train_qlora import find_model_dir


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="")
    parser.add_argument("--lora", default=str(Path(__file__).parent / "output" / "qwen2.5-7b-exam-lora"))
    parser.add_argument("--out", default="D:/models/qwen2.5-7b-exam-merged")
    args = parser.parse_args()

    base_dir = Path(args.base) if args.base else find_model_dir()
    out = Path(args.out)
    print(f"[merge] base={base_dir}\n[merge] lora={args.lora}\n[merge] out={out}", flush=True)

    tokenizer = AutoTokenizer.from_pretrained(str(base_dir), trust_remote_code=True)
    # CPU + bf16 合并：避免 8GB 显存装不下全精度 7B
    base = AutoModelForCausalLM.from_pretrained(
        str(base_dir), dtype=torch.bfloat16, device_map="cpu", trust_remote_code=True,
    )
    model = PeftModel.from_pretrained(base, args.lora, device_map="cpu")
    merged = model.merge_and_unload()
    merged.save_pretrained(str(out), safe_serialization=True, max_shard_size="4GB")
    tokenizer.save_pretrained(str(out))

    # 复制必要的配置文件，保证 Ollama / llama.cpp 能识别架构
    for name in ("config.json", "generation_config.json", "tokenizer_config.json", "tokenizer.json"):
        src = out / name
        if not src.exists() and (base_dir / name).exists():
            shutil.copy2(base_dir / name, out / name)

    size_gb = sum(f.stat().st_size for f in out.rglob("*.safetensors")) / 1024**3
    print(json.dumps({"merged_dir": str(out), "size_gb": round(size_gb, 2)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
