"""Qwen2.5-7B-Instruct 的 QLoRA 微调（8GB 显存可用）。

策略：
- 4bit NF4 量化 + 双重量化，计算精度 bf16；
- LoRA 只训练注意力与 MLP 的线性层（r=16 / alpha=32）；
- 梯度检查点 + paged 8bit 优化器 + batch 1 + 梯度累积，把 7B 压进 8GB；
- 训练目标为「备考助教」的回答/引用/拒答/抗注入行为，另含少量 planner JSON 样本。

用法：
    python training/train_qlora.py --smoke 20      # 冒烟：跑 20 步验证显存
    python training/train_qlora.py --epochs 1      # 正式训练
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch
from datasets import load_dataset
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import TrainerCallback,  AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer

ROOT = Path(__file__).resolve().parents[1]
DATA = Path(__file__).resolve().parent / "data"
OUT = Path(__file__).resolve().parent / "output"

TARGET_MODULES = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]


def find_model_dir() -> Path:
    """在常见的模型缓存目录里找 Qwen2.5-7B 的 safetensors 目录。"""
    env = os.environ.get("MODEL_DIR")
    if env and Path(env).exists():
        return Path(env)
    for root in ("D:/models", "C:/models"):
        base = Path(root)
        if not base.exists():
            continue
        for candidate in base.rglob("config.json"):
            if "Qwen2.5-7B" in str(candidate) and (candidate.parent / "model.safetensors.index.json").exists():
                return candidate.parent
    raise SystemExit("未找到本地模型目录，请先执行 modelscope 下载或设置 MODEL_DIR")


def render(example: dict, tokenizer) -> dict:
    """把 messages 渲染成单段 text（含 chat template），供 SFTTrainer 直接吃。"""
    text = tokenizer.apply_chat_template(example["messages"], tokenize=False, add_generation_prompt=False)
    return {"text": text, "kind": example.get("kind", "unknown")}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="")
    parser.add_argument("--train", default=str(DATA / "sft_train.jsonl"))
    parser.add_argument("--val", default=str(DATA / "sft_val.jsonl"))
    parser.add_argument("--out", default=str(OUT / "qwen2.5-7b-exam-lora"))
    parser.add_argument("--epochs", type=float, default=1.0)
    parser.add_argument("--max-length", type=int, default=768)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--smoke", type=int, default=0, help=">0 时只跑这么多步，用于验证显存")
    parser.add_argument("--limit", type=int, default=0, help=">0 时按 kind 分层抽样到该条数（显存/时间受限时用）")
    parser.add_argument("--resume", default="")
    parser.add_argument("--max-steps", type=int, default=0, help=">0 时提前停止（用于分段时间预算）")
    parser.add_argument("--save-steps", type=int, default=100)
    parser.add_argument("--logging-steps", type=int, default=1, help="日志间隔（短跑时设 1 才能看到每步 loss）")
    parser.add_argument("--eval-strategy", default="no", choices=["no", "steps", "epoch"], help="短跑验证 eval 链路时设 steps")
    parser.add_argument("--eval-steps", type=int, default=2)
    parser.add_argument("--vram-limit-gb", type=float, default=6.5, help="显存硬闸上限（GB），超过即停训")
    args = parser.parse_args()

    model_dir = Path(args.model) if args.model else find_model_dir()
    print(f"[train] model={model_dir}", flush=True)
    print(
        f"[train] config max_length={args.max_length} max_steps={args.max_steps or args.epochs} "
        f"save_steps={args.save_steps} logging_steps={args.logging_steps} "
        f"eval={args.eval_strategy} lr={args.lr} vram_limit={args.vram_limit_gb}G "
        f"train={Path(args.train).name} val={Path(args.val).name}",
        flush=True,
    )

    tokenizer = AutoTokenizer.from_pretrained(str(model_dir), trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    quant = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.bfloat16,
    )
    model = AutoModelForCausalLM.from_pretrained(
        str(model_dir),
        quantization_config=quant,
        device_map={"": 0},
        dtype=torch.bfloat16,
        attn_implementation="sdpa",
    )
    model.config.use_cache = False
    # 8GB 卡上 prepare_model_for_kbit_training 会把归一化层整体升到 fp32，额外要 ~2GB，
    # 在「系统已占 2GB」的机器上必 OOM。LoRA 训练并不依赖这次升精度，改为手动开检查点。
    try:
        model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
    except (torch.OutOfMemoryError, RuntimeError) as exc:
        if "out of memory" not in str(exc).lower():
            raise
        print("[train] prepare_model_for_kbit_training OOM，回退到手动梯度检查点", flush=True)
        torch.cuda.empty_cache()
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        if hasattr(model, "enable_input_require_grads"):
            model.enable_input_require_grads()

    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=TARGET_MODULES,
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    ds = load_dataset("json", data_files={"train": args.train, "val": args.val})
    ds = ds.map(lambda ex: render(ex, tokenizer), remove_columns=ds["train"].column_names)
    if args.smoke:
        ds["train"] = ds["train"].select(range(min(args.smoke * 4, len(ds["train"]))))
    elif args.limit and args.limit < len(ds["train"]):
        # 显存/时间受限时按 kind 分层抽样，保持各类样本比例不变
        import random as _random
        _random.seed(42)
        by_kind: dict[str, list[int]] = {}
        for idx, kind in enumerate(ds["train"]["kind"]):
            by_kind.setdefault(kind, []).append(idx)
        total = len(ds["train"])
        picked: list[int] = []
        quota: dict[str, int] = {}
        for kind, idxs in by_kind.items():
            take = max(1, round(len(idxs) * args.limit / total))
            quota[kind] = min(take, len(idxs))
            picked.extend(_random.sample(idxs, quota[kind]))
        ds["train"] = ds["train"].select(sorted(picked))
        print(f"[train] 分层抽样 {len(picked)} 条：{quota}", flush=True)

    cfg = SFTConfig(
        output_dir=args.out,
        max_length=args.max_length,
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=16,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=30,
        num_train_epochs=args.epochs,
        logging_steps=args.logging_steps,
        eval_strategy=args.eval_strategy,
        eval_steps=args.eval_steps,
        save_steps=args.save_steps,
        save_total_limit=2,
        bf16=True,
        gradient_checkpointing=True,
        optim="paged_adamw_8bit",
        report_to=[],
        dataset_text_field="text",
        max_steps=args.smoke if args.smoke else (args.max_steps if args.max_steps else -1),
    )

    class VramGuard(TrainerCallback):
        """显存硬闸：峰值超过上限立即停训，避免把整机拖死。"""

        def __init__(self, limit_gb: float):
            self.limit = limit_gb * 1024 ** 3

        def on_step_end(self, args, state, control, **kw):
            used = torch.cuda.max_memory_reserved()
            if state.global_step % max(1, args.logging_steps) == 0:
                alloc = torch.cuda.max_memory_allocated() / 1024 ** 3
                print(f"[mem] step={state.global_step} peak_alloc={alloc:.2f}G peak_reserved={used / 1024 ** 3:.2f}G", flush=True)
            if used > self.limit:
                print(f"[guard] 显存峰值 {used / 1024**3:.2f} GB > 上限 {self.limit / 1024**3:.1f} GB，主动停止", flush=True)
                control.should_training_stop = True

    trainer = SFTTrainer(
        model=model,
        args=cfg,
        train_dataset=ds["train"],
        eval_dataset=ds["val"],
        processing_class=tokenizer,
        callbacks=[VramGuard(args.vram_limit_gb)],
    )
    trainer.train(resume_from_checkpoint=args.resume or None)
    trainer.save_model(args.out)
    tokenizer.save_pretrained(args.out)

    metrics = {"train_samples": len(ds["train"]), "val_samples": len(ds["val"]), "steps": trainer.state.global_step}
    Path(args.out, "train_metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[train] done:", json.dumps(metrics, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
