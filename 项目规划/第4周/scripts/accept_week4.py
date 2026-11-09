"""第4周：训练完成度验收（B1–B6）。

与第3周的关系
-------------
第3周 A1–A6 回答的是"跑得起来、能恢复"；本周 B1–B6 回答"这次正式训练到底完成了多少、产物能不能用"。
日志解析直接复用 `项目规划/第3周/scripts/accept_week3.py` 的三个函数
（`normalize_log_text` / `parse_log_dicts` / `load_trainer_state` / `loss_series`），避免两套解析打架。

六项判据
--------
B1 完成度      global_step 是否达到预期步数（135）；不足记 **PARTIAL** 并给出覆盖率
B2 样本覆盖    实际消耗样本 = global_step × 16，与 2156 的比值；显式计入 drop_last 尾部丢弃
B3 产物完整    adapter 四件 + train_metrics.json + 日志；adapter 体积≈LoRA 参数量×2；mtime 晚于训练开始
B4 指标合理    loss 无 NaN/Inf、末 5 步均值 < 首 5 步均值、无异常跳变；eval_loss（若有）与 train 同量级
B5 可加载      adapter 能被 peft 识别（配置 + safetensors 张量键/形状校验）；训练进行中则 SKIP
B6 归档        artifacts_manifest.json 存在且逐文件 sha256 与磁盘一致

状态口径
--------
PASS / FAIL / PARTIAL / WARN / SKIP
* **PARTIAL 不算 FAIL**，但**不能进入正式模型比较**（训练未跑满，指标不可比）
* 退出码：有 FAIL → 1；只有 PARTIAL → 0，但打印醒目警告

用法
----
    python 项目规划/第4周/scripts/accept_week4.py \
        --log 项目规划/第4周/evidence/train_formal_v1.log \
        --ckpt-dir training/output/qwen2.5-3b-exam-lora-v1 \
        --expect-steps 135 --samples 2156 --batch 1 --accum 16 \
        --json-out 项目规划/第4周/evidence/accept_week4.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[3]
W3 = ROOT / "项目规划" / "第3周" / "scripts"
sys.path.insert(0, str(W3))

from accept_week3 import (  # noqa: E402  —— 复用第3周解析，保证两套验收口径一致
    load_trainer_state,
    loss_series,
    normalize_log_text,
    parse_log_dicts,
)

# ---------------------------------------------------------------- 判据常量
EXPECT_STEPS = 135
SAMPLES = 2156
BATCH = 1
ACCUM = 16

#: adapter 体积判据：PEFT 默认以 **fp32** 落盘（4 字节/参数），不是 fp16。
#: 3B r=16：29,933,568 × 4 = 119,734,272 B ≈ 114.2MiB —— 实测 119,801,528B 与之吻合。
TRAINABLE_PARAMS = 29_933_568
BYTES_PER_PARAM = 4.0
ADAPTER_TOLERANCE = 0.10      # ±10%
ADAPTER_MTIME_TOLERANCE = 3600  # 与同批存档文件的时间差上限（秒）
LOSS_MIN_POINTS = 5           # 少于 5 个 loss 点无法判趋势
EVAL_MAGNITUDE_RATIO = 2.0    # eval_loss 与 train_loss 同量级：比值不超过 2 倍
LOSS_SPIKE_RATIO = 3.0        # 单步 loss 相对前后中位数超 3 倍视为异常跳变

ADAPTER_REQUIRED = (
    "adapter_model.safetensors",
    "adapter_config.json",
)
ADAPTER_SOFT = ("tokenizer.json", "tokenizer_config.json", "chat_template.jinja")


@dataclass
class Item:
    name: str
    status: str  # PASS / FAIL / PARTIAL / WARN / SKIP
    detail: str
    evidence: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {"项": self.name, "结果": self.status, "说明": self.detail, "证据": self.evidence}


# ---------------------------------------------------------------- 工具
def read_safetensors_header(path: Path) -> tuple[int, int, int] | None:
    """只读 safetensors 头，返回 (张量数, 参数量, 数据字节数)，不加载权重。"""
    import struct

    try:
        with path.open("rb") as fh:
            head = struct.unpack("<Q", fh.read(8))[0]
            header = json.loads(fh.read(head))
    except Exception:
        return None
    itemsize = {"F32": 4, "BF16": 2, "F16": 2, "I8": 1, "U8": 1, "F8_E4M3": 1, "F8_E5M2": 1}
    tensors = params = data_bytes = 0
    for key, info in header.items():
        if key == "__metadata__":
            continue
        numel = 1
        for dim in info.get("shape", []):
            numel *= int(dim)
        lo, hi = info.get("data_offsets", [0, 0])
        tensors += 1
        params += numel
        data_bytes += int(hi) - int(lo)
        _ = itemsize.get(info.get("dtype", "F32"), 4)
    return tensors, params, data_bytes


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def step_of_log(log_dicts: list[dict[str, Any]], index: int, logging_steps: int, base: int = 0) -> int:
    """日志里的 loss 字典可能没有 step 字段，用 logging_steps 推算。"""
    item = log_dicts[index]
    if item.get("step"):
        return int(item["step"])
    return base + (index + 1) * logging_steps


def parse_mem_lines(text: str) -> list[tuple[int, float, float]]:
    """解析 `[mem] step=5 peak_alloc=3.58G peak_reserved=3.72G`。"""
    out: list[tuple[int, float, float]] = []
    for hit in re.finditer(
        r"\[mem\]\s*step=(\d+)\s+peak_alloc=([\d.]+)\s*([GM])B?\s+peak_reserved=([\d.]+)\s*([GM])B?",
        text,
    ):
        step = int(hit.group(1))
        alloc = float(hit.group(2)) * (1024 if hit.group(3) == "G" else 1)
        reserved = float(hit.group(4)) * (1024 if hit.group(5) == "G" else 1)
        out.append((step, alloc, reserved))
    return out


def infer_logging_steps(log_dicts: list[dict[str, Any]], steps_seen: int) -> int:
    """日志里没写 logging_steps 时，用「loss 条数 vs 已跑步数」反推。"""
    if log_dicts and log_dicts[0].get("step"):
        steps = [int(d["step"]) for d in log_dicts if d.get("step")]
        if len(steps) >= 2:
            return max(1, steps[-1] - steps[-2])
    if log_dicts and steps_seen:
        return max(1, round(steps_seen / len(log_dicts)))
    return 5


# ---------------------------------------------------------------- B1 完成度
def check_b1(steps_seen: int, expect_steps: int, samples: int, batch: int, accum: int) -> Item:
    per_step = batch * accum
    consumed = steps_seen * per_step
    coverage = consumed / samples if samples else 0.0
    evidence = [
        f"global_step={steps_seen}，预期 {expect_steps}",
        f"已消耗样本 {consumed} / {samples} = {coverage * 100:.1f}%",
    ]
    if steps_seen > expect_steps * 1.5:
        return Item(
            "B1 完成度",
            "WARN",
            f"实测 {steps_seen} 步远大于预期 {expect_steps}，核对 --expect-steps 是否传错",
            evidence,
        )
    if steps_seen >= expect_steps:
        return Item("B1 完成度", "PASS", f"{steps_seen}/{expect_steps} 步，已跑满", evidence)
    if steps_seen == 0:
        return Item("B1 完成度", "FAIL", "一步都没跑（global_step=0）", evidence)
    return Item(
        "B1 完成度",
        "PARTIAL",
        f"{steps_seen}/{expect_steps} 步（{coverage * 100:.1f}% epoch）——部分完成，不可用于正式模型比较",
        evidence,
    )


# ---------------------------------------------------------------- B2 样本覆盖
def check_b2(steps_seen: int, samples: int, batch: int, accum: int, drop_last: bool) -> Item:
    per_step = batch * accum
    consumed = steps_seen * per_step
    coverage = consumed / samples if samples else 0.0
    tail = samples % per_step if drop_last else 0
    evidence = [
        f"每步消耗 {per_step} 条（batch {batch} × accum {accum}）",
        f"累计消耗 {consumed} 条，覆盖 {coverage * 100:.2f}%",
        f"drop_last={drop_last} → 尾部丢弃 {tail} 条" if drop_last else "drop_last=False → 尾部未丢弃",
    ]
    # 覆盖率 >100% 说明步数或样本数配错，属于配置矛盾
    if coverage > 1.02:
        return Item("B2 样本覆盖", "FAIL", f"消耗样本 {consumed} > 总量 {samples}，步数/样本配置矛盾", evidence)
    if steps_seen == 0:
        return Item("B2 样本覆盖", "FAIL", "无消耗（global_step=0）", evidence)
    if coverage < 0.999:
        return Item(
            "B2 样本覆盖",
            "PARTIAL",
            f"覆盖 {coverage * 100:.2f}%，未跑满一轮（缺 {samples - consumed} 条）",
            evidence,
        )
    return Item("B2 样本覆盖", "PASS", f"覆盖 {coverage * 100:.2f}%，" + (f"尾部按 drop_last 丢 {tail} 条" if tail else "全部样本参与"), evidence)


# ---------------------------------------------------------------- B3 产物完整
def check_b3(
    ckpt_dir: Path,
    log_path: Path,
    args_params: int = TRAINABLE_PARAMS,
    bytes_per_param: float = BYTES_PER_PARAM,
) -> Item:
    evidence: list[str] = []
    problems: list[str] = []

    if not ckpt_dir.exists():
        return Item("B3 产物完整", "FAIL", f"目录不存在：{ckpt_dir}")

    missing = [name for name in ADAPTER_REQUIRED if not (ckpt_dir / name).exists()]
    if missing:
        problems.append(f"缺必需文件 {', '.join(missing)}")
    soft_missing = [name for name in ADAPTER_SOFT if not (ckpt_dir / name).exists()]
    if soft_missing:
        evidence.append(f"提示：缺 {', '.join(soft_missing)}（不影响加载，但会影响可移植性）")

    if not (ckpt_dir / "train_metrics.json").exists():
        problems.append("缺 train_metrics.json（无法证明训练样本数与步数）")
    else:
        try:
            metrics = json.loads((ckpt_dir / "train_metrics.json").read_text(encoding="utf-8"))
            evidence.append(f"train_metrics={json.dumps(metrics, ensure_ascii=False)[:120]}")
        except Exception as exc:
            problems.append(f"train_metrics.json 解析失败：{exc}")

    if not log_path.exists():
        problems.append("训练日志不存在")

    adapter = ckpt_dir / "adapter_model.safetensors"
    if adapter.exists():
        size_bytes = adapter.stat().st_size
        size_mb = size_bytes / 1024 / 1024
        header = read_safetensors_header(adapter)
        if header:
            tensors, params, data_bytes = header
            evidence.append(
                f"adapter={size_mb:.1f}MB，safetensors 头：{tensors} 个张量 / "
                f"{params:,} 参数 / {data_bytes / 1024 / 1024:.1f}MB 数据"
                f"（{data_bytes / max(1, params):.2f} 字节/参数）"
            )
            bpp = data_bytes / params if params else 0
            if bpp <= 2.5:
                evidence.append(f"提示：权重以 {bpp:.2f} 字节/参数 落盘（fp16/bf16），非 fp32")
        else:
            evidence.append(f"adapter={size_mb:.1f}MB（safetensors 头读取失败，退回按参数量估算）")
            params = 0

        expected_bytes = (params or args_params) * bytes_per_param
        expected_mb = expected_bytes / 1024 / 1024
        low, high = expected_mb * (1 - ADAPTER_TOLERANCE), expected_mb * (1 + ADAPTER_TOLERANCE)
        evidence.append(
            f"期望体积 = {params or args_params:,} 参数 × {bytes_per_param} 字节 = "
            f"{expected_mb:.1f}MB（容差 ±{ADAPTER_TOLERANCE * 100:.0f}% → [{low:.1f}, {high:.1f}]MB）"
        )
        if not (low <= size_mb <= high):
            problems.append(
                f"adapter 体积 {size_mb:.1f}MB 超出 [{low:.1f}, {high:.1f}]MB，"
                "可能是 rank/层数配错、量化或写入不完整"
            )

        # mtime 参照：同目录的 state/optimizer/scheduler（同一次 save 写入），不再拿任意日志比
        siblings = [
            (ckpt_dir / name).stat().st_mtime
            for name in ("trainer_state.json", "optimizer.pt", "scheduler.pt")
            if (ckpt_dir / name).exists()
        ]
        adapter_mtime = adapter.stat().st_mtime
        if siblings:
            ref = min(siblings)
            drift = adapter_mtime - ref
            evidence.append(
                f"adapter mtime 与同目录存档文件相差 {drift / 60:+.1f} 分钟"
                f"（参照 trainer_state/optimizer/scheduler）"
            )
            if abs(drift) > ADAPTER_MTIME_TOLERANCE:
                problems.append(
                    f"adapter 与同批存档文件时间相差 {drift / 3600:+.1f} 小时，疑似旧产物残留"
                )
        else:
            evidence.append("同目录无 trainer_state/optimizer/scheduler，跳过 mtime 一致性检查（WARN）")

    if problems:
        return Item("B3 产物完整", "FAIL", "；".join(problems), evidence)
    return Item("B3 产物完整", "PASS", "adapter 四件 + train_metrics + 日志齐全，体积与时间戳合理", evidence)


# ---------------------------------------------------------------- B4 指标合理
def check_b4(losses: list[float], eval_losses: list[float]) -> Item:
    if not losses:
        return Item("B4 指标合理", "FAIL", "日志/状态里没有任何 loss，无法评估")
    evidence = [f"loss 点数 {len(losses)}，首={losses[0]:.4f}，末={losses[-1]:.4f}"]
    problems: list[str] = []

    bad = [v for v in losses if v != v or v in (float("inf"), float("-inf"))]
    if bad:
        problems.append(f"出现 NaN/Inf {len(bad)} 个")
    if len(losses) < LOSS_MIN_POINTS:
        return Item(
            "B4 指标合理",
            "SKIP",
            f"loss 点只有 {len(losses)} 个（<{LOSS_MIN_POINTS}），不足以判趋势",
            evidence,
        )

    head = sum(losses[:5]) / 5
    tail = sum(losses[-5:]) / 5
    evidence.append(f"首5均值={head:.4f}，末5均值={tail:.4f}，变化 {(tail - head) / head * 100:+.1f}%")
    if tail >= head:
        problems.append(f"末 5 步均值 {tail:.4f} 未低于首 5 步均值 {head:.4f}（没有学到东西或发散）")

    # 异常尖峰：单点相对相邻中位数超过 3 倍
    spikes = 0
    for idx in range(2, len(losses) - 2):
        neighbours = sorted(losses[idx - 2: idx] + losses[idx + 1: idx + 3])
        median = (neighbours[1] + neighbours[2]) / 2 if len(neighbours) >= 4 else neighbours[0]
        if median > 0 and losses[idx] / median > LOSS_SPIKE_RATIO:
            spikes += 1
    if spikes:
        evidence.append(f"异常尖峰（>邻域中位数 {LOSS_SPIKE_RATIO}×）：{spikes} 处")
        problems.append(f"存在 {spikes} 处 loss 尖峰")

    if eval_losses:
        eval_mean = sum(eval_losses) / len(eval_losses)
        ratio = eval_mean / tail if tail else 0
        evidence.append(f"eval_loss 均值={eval_mean:.4f}，与末 5 步 train 比={ratio:.2f}")
        if ratio > EVAL_MAGNITUDE_RATIO:
            problems.append(f"eval_loss 是 train 的 {ratio:.1f} 倍，量级不一致（过拟合或数据分布差异过大）")
    else:
        evidence.append("无 eval_loss（未开 eval 或尚未 eval）")

    if problems:
        return Item("B4 指标合理", "FAIL", "；".join(problems), evidence)
    return Item("B4 指标合理", "PASS", f"loss 下降 {head:.4f}→{tail:.4f}，无 NaN/Inf/尖峰", evidence)


# ---------------------------------------------------------------- B5 可加载
def check_b5(ckpt_dir: Path, training_active: bool, full_load: bool) -> Item:
    adapter = ckpt_dir / "adapter_model.safetensors"
    config_path = ckpt_dir / "adapter_config.json"
    if not adapter.exists() or not config_path.exists():
        return Item("B5 可加载", "FAIL", "缺 adapter_model.safetensors 或 adapter_config.json")

    evidence: list[str] = []
    problems: list[str] = []
    try:
        from peft import PeftConfig

        cfg = PeftConfig.from_pretrained(str(ckpt_dir))
        evidence.append(
            f"PeftConfig 解析成功：r={getattr(cfg, 'r', '?')} alpha={getattr(cfg, 'lora_alpha', '?')} "
            f"targets={len(getattr(cfg, 'target_modules', []) or [])} 个"
        )
    except Exception as exc:
        return Item("B5 可加载", "FAIL", f"PeftConfig 解析失败：{exc}", evidence)

    # 张量键与形状校验：不加载权重、不占显存，只读 safetensors 头
    try:
        from safetensors import safe_open

        with safe_open(str(adapter), framework="pt") as fh:
            keys = list(fh.keys())
        lora_a = [k for k in keys if "lora_A" in k]
        lora_b = [k for k in keys if "lora_B" in k]
        evidence.append(f"safetensors 张量 {len(keys)} 个，其中 lora_A {len(lora_a)} / lora_B {len(lora_b)}")
        if not lora_a or not lora_b:
            problems.append("safetensors 里没有 lora_A/lora_B 张量，不是有效的 LoRA 权重")
        elif len(lora_a) != len(lora_b):
            problems.append(f"lora_A({len(lora_a)}) 与 lora_B({len(lora_b)}) 数量不一致")
        else:
            targets = getattr(cfg, "target_modules", []) or []
            if targets:
                hit = {name for name in targets for key in keys if f".{name}." in key or key.startswith(name)}
                missing_target = [name for name in targets if not any(f".{name}." in key for key in keys)]
                evidence.append(f"target_modules 命中 {len(hit)}/{len(targets)}")
                if missing_target:
                    problems.append(f"这些 target_module 在权重里找不到：{missing_target}")
    except Exception as exc:
        problems.append(f"safetensors 读取失败：{exc}")

    if problems:
        return Item("B5 可加载", "FAIL", "；".join(problems), evidence)

    if full_load:
        if training_active:
            return Item("B5 可加载", "SKIP", "训练进行中，跳过完整 PeftModel 加载（避免抢显存）", evidence)
        try:
            import torch
            from peft import PeftModel
            from transformers import AutoModelForCausalLM

            cfg = PeftConfig.from_pretrained(str(ckpt_dir))
            base = AutoModelForCausalLM.from_pretrained(
                cfg.base_model_name_or_path, torch_dtype=torch.float16, device_map="cpu", low_cpu_mem_usage=True
            )
            model = PeftModel.from_pretrained(base, str(ckpt_dir))
            evidence.append(f"PeftModel 加载成功，参数 {sum(p.numel() for p in model.parameters()) / 1e6:.1f}M")
            del model, base
        except Exception as exc:
            return Item("B5 可加载", "FAIL", f"PeftModel 加载失败：{exc}", evidence)
        return Item("B5 可加载", "PASS", "配置+张量+完整加载均通过", evidence)

    if training_active:
        evidence.append("训练进行中：只做了配置与张量校验，未做完整 PeftModel 加载")
        return Item("B5 可加载", "PASS", "配置与 LoRA 张量校验通过（完整加载因训练进行中跳过）", evidence)
    return Item("B5 可加载", "PASS", "配置与 LoRA 张量校验通过（未做完整加载，需 --full-load）", evidence)


# ---------------------------------------------------------------- B6 归档
def check_b6(manifest_path: Path) -> Item:
    if not manifest_path.exists():
        return Item("B6 归档", "FAIL", f"归档清单不存在：{manifest_path}（等 train-export 产出后复核）")
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return Item("B6 归档", "FAIL", f"清单解析失败：{exc}")

    # 清单里的路径通常是相对 run_dir 的（manifest 自带 run_dir/base 字段）
    base_dir = ROOT
    if isinstance(data, dict):
        for key in ("run_dir", "base_dir", "base", "root"):
            value = data.get(key)
            if isinstance(value, str) and value:
                candidate = Path(value)
                base_dir = candidate if candidate.is_absolute() else ROOT / candidate
                break

    entries: list[tuple[str, str]] = []
    if isinstance(data, dict):
        for key, value in data.items():
            if isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value):
                entries.append((key, value))
            elif isinstance(value, dict) and value.get("sha256"):
                entries.append((key, value["sha256"]))
        for item in data.get("files", []) or []:
            if isinstance(item, dict) and item.get("path") and item.get("sha256"):
                entries.append((item["path"], item["sha256"]))
    if not entries:
        return Item("B6 归档", "FAIL", "清单里没有可识别的 path→sha256 条目")

    evidence: list[str] = []
    problems: list[str] = []
    evidence.append(f"清单基准目录：{base_dir}")
    for rel, expected in entries:
        path = Path(rel)
        if not path.is_absolute():
            path = base_dir / rel
            if not path.exists() and (ROOT / rel).exists():
                path = ROOT / rel
        if not path.exists():
            problems.append(f"{rel} 不存在")
            continue
        actual = sha256(path)
        ok = actual == expected
        evidence.append(f"{rel}: {'一致' if ok else f'不一致 {actual[:12]}≠{expected[:12]}'}")
        if not ok:
            problems.append(f"{rel} sha 不一致")

    if problems:
        return Item("B6 归档", "FAIL", "；".join(problems), evidence[:10])
    return Item("B6 归档", "PASS", f"{len(entries)} 个文件 sha 全部一致", evidence[:10])


# ---------------------------------------------------------------- 主流程
def main() -> int:
    parser = argparse.ArgumentParser(description="第4周训练完成度验收（B1–B6）")
    parser.add_argument("--log", default="项目规划/第4周/evidence/train_formal_v1.log")
    parser.add_argument("--ckpt-dir", default="training/output/qwen2.5-3b-exam-lora-v1")
    parser.add_argument("--expect-steps", type=int, default=EXPECT_STEPS)
    parser.add_argument("--samples", type=int, default=SAMPLES)
    parser.add_argument("--batch", type=int, default=BATCH)
    parser.add_argument("--accum", type=int, default=ACCUM)
    parser.add_argument("--drop-last", action="store_true", default=True)
    parser.add_argument("--no-drop-last", dest="drop_last", action="store_false")
    parser.add_argument("--trainable-params", type=int, default=TRAINABLE_PARAMS,
                        help="可训练参数量（3B r=16 为 29,933,568）")
    parser.add_argument("--bytes-per-param", type=float, default=BYTES_PER_PARAM,
                        help="adapter 每参数字节数（PEFT 默认 fp32 = 4.0）")
    parser.add_argument("--manifest", default="项目规划/第4周/evidence/artifacts_manifest.json")
    parser.add_argument("--active-window", type=int, default=300, help="日志在 N 秒内更新过即视为训练进行中")
    parser.add_argument("--full-load", action="store_true", help="允许完整 PeftModel 加载（会读基座权重）")
    parser.add_argument("--json-out", default="")
    args = parser.parse_args()

    log_path = Path(args.log)
    ckpt_dir = Path(args.ckpt_dir)
    if not log_path.exists():
        print(f"[FATAL] 日志不存在：{log_path}")
        return 1

    text = normalize_log_text(log_path)
    log_dicts = parse_log_dicts(text)
    state, state_path = load_trainer_state(ckpt_dir)

    steps_seen = 0
    if state and state.get("global_step") is not None:
        steps_seen = int(state["global_step"])
    if not steps_seen:
        # 只认「分母 == 预期步数」的进度条：数据集预处理也有 2156/2156 这种条，
        # 直接取 max 会把 2156 条样本误当成 2156 步。
        bars = [(int(a), int(b)) for a, b in re.findall(r"\|\s*(\d+)/(\d+) \[", text)]
        train_bars = [cur for cur, total in bars if total == args.expect_steps]
        if train_bars:
            steps_seen = max(train_bars)
        elif bars:
            steps_seen = bars[-1][0]
            print(f"[warn] 没有分母={args.expect_steps} 的进度条，退化为最后一条进度 {steps_seen}")
    if not steps_seen and log_dicts:
        steps_seen = len(log_dicts)

    logging_steps = infer_logging_steps(log_dicts, steps_seen)
    series = loss_series(state) if state else []
    losses = [value for _, value in series] if series else [
        float(d["loss"]) for d in log_dicts if d.get("loss") is not None
    ]
    eval_losses: list[float] = []
    if state:
        eval_losses = [float(e["eval_loss"]) for e in (state.get("log_history") or []) if "eval_loss" in e]

    mem = parse_mem_lines(text)
    import time as _time

    training_active = (_time.time() - log_path.stat().st_mtime) < args.active_window

    items = [
        check_b1(steps_seen, args.expect_steps, args.samples, args.batch, args.accum),
        check_b2(steps_seen, args.samples, args.batch, args.accum, args.drop_last),
        check_b3(ckpt_dir, log_path, args.trainable_params, args.bytes_per_param),
        check_b4(losses, eval_losses),
        check_b5(ckpt_dir, training_active, args.full_load),
        check_b6(Path(args.manifest)),
    ]

    print("=" * 78)
    print(f"第4周训练完成度验收 · log={log_path.name} · ckpt={ckpt_dir.name}")
    print("=" * 78)
    print(f"[info] steps_seen={steps_seen}/{args.expect_steps}，loss 点 {len(losses)} 个，"
          f"logging_steps≈{logging_steps}，训练进行中={training_active}")
    if state_path:
        print(f"[info] trainer_state 取自 {state_path}")
    else:
        print("[warn] 未找到 trainer_state.json（步数以日志进度条为准）")
    if mem:
        peak = max(m[1] for m in mem)
        print(f"[info] 显存峰值（日志 [mem] 行）peak_alloc={peak:.0f}MB，记录点 {len(mem)} 个")

    for item in items:
        print(f"[{item.status:7s}] {item.name}: {item.detail}")
        for line in item.evidence:
            print(f"          · {line}")

    failed = [i.name for i in items if i.status == "FAIL"]
    partial = [i.name for i in items if i.status == "PARTIAL"]
    skipped = [i.name for i in items if i.status == "SKIP"]
    print("-" * 78)
    if failed:
        print(f"总体：FAIL（不可用于结项）：{'、'.join(failed)}")
    elif partial:
        print(f"总体：PARTIAL（训练未跑满，不可用于正式模型比较）：{'、'.join(partial)}")
    else:
        print("总体：PASS（可用于结项）")
    if skipped:
        print(f"未验证/跳过：{'、'.join(skipped)}")
    if training_active:
        artifact_fails = [i.name for i in items if i.status == "FAIL" and i.name in ("B3 产物完整", "B5 可加载", "B6 归档")]
        if artifact_fails:
            print(f"[提示] 训练仍在进行（日志 {args.active_window}s 内有更新），"
                  f"{'、'.join(artifact_fails)} 的 FAIL 属预期：产物要等存档/收尾后才落盘。")

    payload = {
        "log": str(log_path),
        "ckpt_dir": str(ckpt_dir),
        "steps_seen": steps_seen,
        "expect_steps": args.expect_steps,
        "training_active": training_active,
        "peak_alloc_mb": max((m[1] for m in mem), default=None),
        "items": [i.as_dict() for i in items],
        "failed": failed,
        "partial": partial,
        "skipped": skipped,
    }
    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[json] {out}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
