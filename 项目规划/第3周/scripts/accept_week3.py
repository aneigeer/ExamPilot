"""第3周训练验收：判断「训练真的跑完了」且「检查点真能恢复」，不看"跑完了"三个字。

六项检查（判据/阈值见 项目规划/第3周/05_验收方案.md）：

    A1 短跑完成      达到预期步数且有完成标记（train_runtime / train_loss）或退出码 0
    A2 步数对账      global_step == ceil(样本数 / (batch × accum))，误差必须为 0
    A3 检查点完整    adapter_model.safetensors + optimizer.pt(≥60MB) + trainer_state.json + scheduler.pt
    A4 恢复正确      恢复后 global_step 等于存档值；恢复点前后 loss 不跳变（>50% 判 FAIL）
    A5 显存与步速    记录峰值显存与步均耗时；Shared Usage>512MB 或步速>2×基线判 WARN
    A6 可复现        同配置两次短跑，前 N 步 loss 序列逐点一致（容差可配）

设计取舍
--------
* **以 trainer_state.json 为主证据，日志为辅**：HF Trainer 的 log_history 里有
  step/loss/learning_rate，比正则抓 stdout 稳。日志只用来取"退出码、耗时、显存、Shared Usage"。
* **解析按行正则 + JSON 兜底**：日志可能带 tqdm 的 `\\r`、进度条、彩色控制码，先规范化再匹配。
* **不做任何"猜测式"通过**：拿不到证据的项一律 FAIL 并说明缺什么，不给"大概没问题"。

用法
----
    python 项目规划/第3周/scripts/accept_week3.py \
        --log 项目规划/第3周/evidence/shortrun_a.log \
        --ckpt-dir training/output/qwen2.5-7b-exam-lora-v3 \
        --samples 200 --batch 1 --accum 16 \
        --json-out 项目规划/第3周/evidence/accept_week3.json

    # 恢复跑（A4）与复现跑（A6）
    --resume-log ... --saved-step 4 --run2-log ...

退出码：0 = 六项无 FAIL（WARN 不影响退出码）；1 = 有 FAIL。
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------- 判据常量
#: A3 判据：optimizer 与 adapter 的**相对**比例 + 绝对下限。
#: 绝对阈值不可移植：7B(r=16) optimizer≈82MB、3B(r=16) optimizer≈58.6MB，
#: 同一个 60MB 线会把正常的 3B 检查点判死。比例判据则两边都成立：
#:   7B: 82/77 ≈ 1.07    3B: 58.6/57 ≈ 1.03    残件 0.286/77 ≈ 0.004
OPTIMIZER_MIN_RATIO = 0.5
OPTIMIZER_MIN_ABS_BYTES = 10 * 1024 * 1024   # 下限兜底：286KB 那种残件在任何模型上都不该过
SHARED_MEM_WARN_MB = 512                     # Windows 共享内存回退阈值
SLOWDOWN_WARN_FACTOR = 2.0                   # 步速 >2× 基线判 WARN
LOSS_JUMP_FAIL = 0.50                        # 恢复点前后 loss 相对跳变 >50% 判 FAIL
DEFAULT_LOSS_TOL = 0.01                      # A6 逐点容差（绝对）

ANSI_PAT = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


@dataclass
class Result:
    name: str
    status: str  # PASS / FAIL / WARN / SKIP
    detail: str
    evidence: list[str] = field(default_factory=list)

    @property
    def failed(self) -> bool:
        return self.status == "FAIL"

    def as_dict(self) -> dict[str, Any]:
        return {"项": self.name, "结果": self.status, "说明": self.detail, "证据": self.evidence}


# ---------------------------------------------------------------- 日志解析
def normalize_log_text(path: Path) -> str:
    """去掉 ANSI 与 tqdm 的 \\r 覆盖，统一成行。进度条只保留最后一次刷新。"""
    raw = path.read_text(encoding="utf-8", errors="replace")
    raw = ANSI_PAT.sub("", raw)
    lines: list[str] = []
    for phys in raw.split("\n"):
        # tqdm 用 \r 刷新同一行，只保留最后一段
        parts = [p for p in phys.split("\r") if p.strip()]
        if parts:
            lines.append(parts[-1])
    return "\n".join(lines)


def parse_log_dicts(text: str) -> list[dict[str, Any]]:
    """抽出日志里的 Python dict 字面量（HF Trainer 的 {'loss': ...} 行）。"""
    out: list[dict[str, Any]] = []
    for match in re.finditer(r"\{[^{}]*'loss'[^{}]*\}", text):
        blob = match.group(0)
        fixed = blob.replace("'", '"').replace("nan", "NaN")
        try:
            obj = json.loads(fixed)
        except Exception:
            obj = {}
            for key in ("loss", "epoch", "learning_rate", "grad_norm", "step"):
                hit = re.search(rf"'{key}'\s*:\s*([0-9.eE+-]+)", blob)
                if hit:
                    obj[key] = float(hit.group(1))
            step_hit = re.search(r"'step'\s*:\s*(\d+)", blob)
            if step_hit:
                obj["step"] = int(step_hit.group(1))
        if obj:
            out.append(obj)
    return out


def last_number(text: str, pattern: str) -> float | None:
    hits = re.findall(pattern, text)
    return float(hits[-1]) if hits else None


# ---------------------------------------------------------------- trainer_state
def load_trainer_state(ckpt_dir: Path) -> tuple[dict[str, Any] | None, Path | None]:
    """优先用根目录，其次用编号最大的 checkpoint-*。"""
    candidates: list[Path] = []
    if (ckpt_dir / "trainer_state.json").exists():
        candidates.append(ckpt_dir)
    for sub in sorted(ckpt_dir.glob("checkpoint-*"), key=lambda p: _ckpt_no(p)):
        if (sub / "trainer_state.json").exists():
            candidates.append(sub)
    if not candidates:
        return None, None
    chosen = candidates[-1]
    try:
        return json.loads((chosen / "trainer_state.json").read_text(encoding="utf-8")), chosen
    except Exception:
        return None, chosen


def _ckpt_no(path: Path) -> int:
    hit = re.search(r"checkpoint-(\d+)", path.name)
    return int(hit.group(1)) if hit else -1


def loss_series(state: dict[str, Any]) -> list[tuple[int, float]]:
    out: list[tuple[int, float]] = []
    for item in state.get("log_history") or []:
        if "loss" in item and "step" in item:
            out.append((int(item["step"]), float(item["loss"])))
    return out


# ---------------------------------------------------------------- 各项检查
def check_a1(steps_seen: int, expect_steps: int | None, text: str, exit_code: int | None) -> Result:
    done_mark = bool(re.search(r"'train_runtime'|'train_loss'|\[done\]|训练完成", text))
    evidence = [f"日志中最大 step={steps_seen}", f"完成标记={done_mark}", f"退出码={exit_code}"]
    if expect_steps is None:
        return Result("A1 短跑完成", "FAIL", "未提供 --expect-steps/--samples，无法判断是否跑完", evidence)
    if steps_seen < expect_steps:
        return Result("A1 短跑完成", "FAIL", f"只跑到 {steps_seen}/{expect_steps} 步", evidence)
    if exit_code not in (None, 0):
        return Result("A1 短跑完成", "FAIL", f"退出码非 0：{exit_code}", evidence)
    if exit_code is None and not done_mark:
        return Result(
            "A1 短跑完成",
            "FAIL",
            "步数达标但既无退出码也无完成标记，无法证明正常结束（可能是被 kill）",
            evidence,
        )
    return Result("A1 短跑完成", "PASS", f"{steps_seen}/{expect_steps} 步，正常结束", evidence)


def check_a2(steps_seen: int, expect_steps: int | None, samples: int, batch: int, accum: int) -> Result:
    calc = math.ceil(samples / max(1, batch * accum)) if samples else None
    evidence = [f"样本 {samples} ÷ (batch {batch} × accum {accum}) = {calc}", f"实际 global_step={steps_seen}"]
    if calc is None and expect_steps is None:
        return Result("A2 步数对账", "FAIL", "未提供 --samples 或 --expect-steps", evidence)

    # 短跑模式：显式给了 --expect-steps 就以它为准（--max-steps 截断时公式值不适用）
    if expect_steps is not None:
        if calc is not None and expect_steps != calc:
            evidence.append(
                f"短跑模式：--expect-steps={expect_steps}，按公式满 epoch 应为 {calc} 步（以显式值为准）"
            )
        if steps_seen != expect_steps:
            return Result("A2 步数对账", "FAIL", f"实际 {steps_seen} 步 ≠ 预期 {expect_steps} 步", evidence)
        return Result("A2 步数对账", "PASS", f"{steps_seen} 步 = 显式预期，误差 0", evidence)

    if steps_seen != calc:
        return Result("A2 步数对账", "FAIL", f"实际 {steps_seen} 步 ≠ 应有 {calc} 步", evidence)
    return Result("A2 步数对账", "PASS", f"{steps_seen} 步 = {samples}/({batch}×{accum})，误差 0", evidence)


def check_a3(ckpt_dir: Path) -> Result:
    required = ("adapter_model.safetensors", "optimizer.pt", "trainer_state.json", "scheduler.pt")
    ckpts = sorted(ckpt_dir.glob("checkpoint-*"), key=_ckpt_no)
    targets: list[Path] = list(ckpts)
    notes: list[str] = []

    root_has_adapter = (ckpt_dir / "adapter_model.safetensors").exists()
    root_has_state = (ckpt_dir / "trainer_state.json").exists()
    if ckpts:
        # HF 惯例：根目录只放最终 adapter，优化器状态在各 checkpoint-* 里。
        # 所以有 checkpoint-* 时根目录不作为"检查点"判定；但若根目录自称有 state，也要一起查。
        if root_has_state:
            targets.append(ckpt_dir)
        elif root_has_adapter:
            notes.append(f"{ckpt_dir.name}: 根目录仅有最终 adapter（无 state/optimizer），按惯例不计为检查点")
    elif root_has_adapter:
        # 没有 checkpoint-* 时，根目录就是唯一产物，必须四件齐全
        targets.append(ckpt_dir)

    if not targets:
        return Result("A3 检查点完整", "FAIL", f"{ckpt_dir} 下没有任何检查点产物", notes)

    evidence: list[str] = []
    problems: list[str] = []
    for target in targets:
        missing = [name for name in required if not (target / name).exists()]
        sizes = {}
        for name in required:
            path = target / name
            sizes[name] = path.stat().st_size if path.exists() else 0
        line = (
            f"{target.name}: adapter={sizes['adapter_model.safetensors'] / 1024 / 1024:.1f}MB "
            f"optimizer={sizes['optimizer.pt'] / 1024 / 1024:.1f}MB "
            f"ratio={(sizes['optimizer.pt'] / sizes['adapter_model.safetensors']):.2f}"
            if sizes["adapter_model.safetensors"] else
            f"{target.name}: adapter=缺失 optimizer={sizes['optimizer.pt'] / 1024 / 1024:.1f}MB"
        )
        line += (
            f" state={'有' if sizes['trainer_state.json'] else '缺'}"
            f" sched={'有' if sizes['scheduler.pt'] else '缺'}"
        )
        evidence.append(line)

        if missing:
            problems.append(f"{target.name} 缺 {', '.join(missing)}")
        opt_size = sizes["optimizer.pt"]
        adapter_size = sizes["adapter_model.safetensors"]
        if opt_size:
            if opt_size < OPTIMIZER_MIN_ABS_BYTES:
                problems.append(
                    f"{target.name} optimizer.pt 仅 {opt_size / 1024:.0f}KB"
                    f"（<{OPTIMIZER_MIN_ABS_BYTES // 1024 // 1024}MB 绝对下限，视为残件）"
                )
            elif adapter_size and opt_size / adapter_size < OPTIMIZER_MIN_RATIO:
                problems.append(
                    f"{target.name} optimizer/adapter = {opt_size / adapter_size:.2f}"
                    f" < {OPTIMIZER_MIN_RATIO}（优化器状态缺失或不完整）"
                )

    if problems:
        return Result("A3 检查点完整", "FAIL", "；".join(problems), evidence + notes)
    return Result(
        "A3 检查点完整",
        "PASS",
        f"{len(targets)} 个检查点四件齐全，optimizer/adapter 达 {OPTIMIZER_MIN_RATIO} 且 >10MB",
        evidence + notes,
    )


def check_a4(
    resume_ckpt: Path | None,
    saved_step: int | None,
    resume_state: dict[str, Any] | None,
    resume_losses: list[tuple[int, float]],
    saved_losses: list[tuple[int, float]],
) -> Result:
    evidence: list[str] = []
    problems: list[str] = []

    if saved_step is None and saved_losses:
        saved_step = saved_losses[-1][0]
    if saved_step is None:
        return Result("A4 恢复正确", "FAIL", "无法确定保存时的 global_step")

    if resume_state is not None:
        got = int(resume_state.get("global_step") or -1)
        evidence.append(f"最终 trainer_state.global_step={got}，存档时={saved_step}")
        if got < saved_step:
            problems.append(f"恢复后 global_step {got} < 存档值 {saved_step}（回退了）")
        elif got == saved_step:
            problems.append(f"恢复后 global_step 仍为 {saved_step}，没有继续训练")
    else:
        evidence.append("未提供 --resume-ckpt-dir，global_step 未交叉验证（仅凭日志判定）")

    # 恢复后必须真的有新步数产生
    progressed = [step for step, _ in resume_losses if step > saved_step]
    if not progressed:
        problems.append(f"恢复跑日志里没有 >{saved_step} 步的 loss，无法证明继续训练")
    else:
        evidence.append(f"恢复后新增 loss 点 {len(progressed)} 个（step {progressed[0]}…{progressed[-1]}）")

    # loss 连续性：取 <= saved_step 的最后一点 与 > saved_step 的第一点
    before = [value for step, value in saved_losses if step <= saved_step]
    after = [value for step, value in resume_losses if step > saved_step]
    if not before or not after:
        problems.append("loss 序列不足以判断连续性（缺恢复前或恢复后数据）")
    else:
        prev, nxt = before[-1], after[0]
        jump = abs(nxt - prev) / max(1e-9, abs(prev))
        evidence.append(f"恢复点前 loss={prev:.4f}，恢复后 loss={nxt:.4f}，相对跳变={jump * 100:.1f}%")
        if jump > LOSS_JUMP_FAIL:
            problems.append(f"loss 跳变 {jump * 100:.1f}% > {LOSS_JUMP_FAIL * 100:.0f}%")

    if problems:
        return Result("A4 恢复正确", "FAIL", "；".join(problems), evidence)
    if resume_state is None:
        return Result(
            "A4 恢复正确",
            "WARN",
            "日志证明续训且 loss 连续，但缺最终 trainer_state 交叉验证 global_step",
            evidence,
        )
    return Result("A4 恢复正确", "PASS", "global_step 未回退且继续推进，loss 在恢复点连续", evidence)


def check_a5(
    text: str,
    step_seconds: float | None,
    baseline_seconds: float | None,
) -> Result:
    shared = last_number(text, r"Shared Usage[:= ]+([0-9.]+)\s*(?:MB|MiB)")
    peak_alloc = last_number(text, r"max_memory_allocated[:= ]+([0-9.]+)")
    evidence = [
        f"Shared Usage={shared if shared is not None else '未记录'} MB",
        f"峰值显存(max_memory_allocated)={peak_alloc if peak_alloc is not None else '未记录'}",
        f"步均耗时={f'{step_seconds:.1f}s' if step_seconds else '未记录'}",
    ]
    warns: list[str] = []
    if shared is not None and shared > SHARED_MEM_WARN_MB:
        warns.append(f"Shared Usage {shared:.0f}MB > {SHARED_MEM_WARN_MB}MB（Windows 共享内存回退，会掉速数倍）")
    if step_seconds and baseline_seconds and step_seconds > baseline_seconds * SLOWDOWN_WARN_FACTOR:
        warns.append(
            f"步速 {step_seconds:.1f}s > 基线 {baseline_seconds:.1f}s × {SLOWDOWN_WARN_FACTOR}"
        )
    if step_seconds is None:
        return Result("A5 显存与步速", "FAIL", "日志里没有可用的步耗时，无法评估", evidence)
    if warns:
        return Result("A5 显存与步速", "WARN", "；".join(warns), evidence)
    return Result("A5 显存与步速", "PASS", "无共享内存回退，步速正常", evidence)


def check_a6(
    run1: list[tuple[int, float]],
    run2: list[tuple[int, float]],
    tol: float,
) -> Result:
    if not run1 or not run2:
        return Result("A6 可复现", "SKIP", "缺少两次运行的 loss 序列（用 --run2-log 提供第二次）")
    n = min(len(run1), len(run2))
    diffs = [abs(run1[i][1] - run2[i][1]) for i in range(n)]
    worst = max(diffs)
    evidence = [
        f"比较前 {n} 个 loss 点，最大绝对差 {worst:.6f}（容差 {tol}）",
        f"run1 前3点={[round(run1[i][1], 4) for i in range(min(3, n))]}",
        f"run2 前3点={[round(run2[i][1], 4) for i in range(min(3, n))]}",
    ]
    if worst > tol:
        return Result("A6 可复现", "FAIL", f"同配置两次跑 loss 不一致，最大差 {worst:.6f} > {tol}", evidence)
    return Result("A6 可复现", "PASS", f"前 {n} 步 loss 逐点一致（最大差 {worst:.6f}）", evidence)


# ---------------------------------------------------------------- 主流程
def estimate_step_seconds(text: str, steps_seen: int) -> float | None:
    """步均耗时：优先 train_runtime/steps，其次日志里最后一个 it/s。"""
    runtime = last_number(text, r"'train_runtime'\s*:\s*([0-9.]+)")
    if runtime and steps_seen:
        return runtime / steps_seen
    per_it = last_number(text, r"([0-9.]+)s/it")
    if per_it:
        return per_it
    rate = last_number(text, r"([0-9.]+)it/s")
    if rate and rate > 0:
        return 1.0 / rate
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="第3周训练验收（A1–A6）")
    parser.add_argument("--log", required=True, help="主训练日志")
    parser.add_argument("--ckpt-dir", required=True, help="LoRA 输出目录（含 checkpoint-*）")
    parser.add_argument("--samples", type=int, default=0, help="训练样本数")
    parser.add_argument("--batch", type=int, default=1)
    parser.add_argument("--accum", type=int, default=16)
    parser.add_argument("--expect-steps", type=int, default=None)
    parser.add_argument("--exit-code", type=int, default=None, help="训练进程退出码（有就传）")
    parser.add_argument("--resume-log", default="", help="恢复跑的日志（A4）")
    parser.add_argument("--resume-ckpt-dir", default="", help="恢复跑结束后的产物目录（用于取最终 global_step）")
    parser.add_argument("--saved-step", type=int, default=None)
    parser.add_argument("--run2-log", default="", help="第二次同配置短跑日志（A6）")
    parser.add_argument("--baseline-step-seconds", type=float, default=None, help="基线步耗时（A5）")
    parser.add_argument("--loss-tol", type=float, default=DEFAULT_LOSS_TOL)
    parser.add_argument("--json-out", default="")
    args = parser.parse_args()

    log_path = Path(args.log)
    if not log_path.exists():
        print(f"[FATAL] 日志不存在：{log_path}")
        return 1
    text = normalize_log_text(log_path)
    log_dicts = parse_log_dicts(text)

    ckpt_dir = Path(args.ckpt_dir)
    state, state_path = load_trainer_state(ckpt_dir)
    series = loss_series(state) if state else []

    # 步数：优先 trainer_state.global_step，其次日志里最大的 step，最后损失点数
    steps_seen = 0
    if state and state.get("global_step") is not None:
        steps_seen = int(state["global_step"])
    if not steps_seen and series:
        steps_seen = series[-1][0]
    if not steps_seen and log_dicts:
        steps = [int(d.get("step", 0)) for d in log_dicts if d.get("step")]
        steps_seen = max(steps) if steps else len(log_dicts)

    expect_steps = args.expect_steps
    if expect_steps is None and args.samples:
        expect_steps = math.ceil(args.samples / max(1, args.batch * args.accum))

    step_seconds = estimate_step_seconds(text, steps_seen)

    results: list[Result] = [
        check_a1(steps_seen, expect_steps, text, args.exit_code),
        check_a2(steps_seen, args.expect_steps, args.samples, args.batch, args.accum),
        check_a3(ckpt_dir),
    ]

    if args.resume_log:
        # 证据分工：存档步数来自 checkpoint 的 trainer_state；恢复后的 loss 来自恢复跑日志。
        # 注意不要拿同一份 trainer_state 既当"前"又当"后"——那样永远连续，等于自证。
        resume_text = normalize_log_text(Path(args.resume_log))
        resume_dicts = [d for d in parse_log_dicts(resume_text) if "loss" in d]
        start = (args.saved_step or 0) + 1
        resume_series: list[tuple[int, float]] = []
        for i, item in enumerate(resume_dicts):
            step = int(item["step"]) if item.get("step") else start + i
            resume_series.append((step, float(item["loss"])))
        if not resume_series and state:
            resume_series = loss_series(state)  # 兜底：日志无 loss 时退回 trainer_state
        resume_state = None
        if args.resume_ckpt_dir:
            resume_state, _ = load_trainer_state(Path(args.resume_ckpt_dir))
        results.append(
            check_a4(
                Path(args.resume_log),
                args.saved_step,
                resume_state,
                resume_series,
                [item for item in series if args.saved_step is None or item[0] <= args.saved_step],
            )
        )
    else:
        results.append(Result("A4 恢复正确", "SKIP", "未提供 --resume-log，本轮未验证恢复"))

    results.append(check_a5(text, step_seconds, args.baseline_step_seconds))

    if args.run2_log:
        text2 = normalize_log_text(Path(args.run2_log))
        state2, _ = load_trainer_state(ckpt_dir)
        series2 = loss_series(state2) if state2 else []
        if not series2:
            series2 = [(int(d.get("step", i + 1)), float(d["loss"]))
                       for i, d in enumerate(parse_log_dicts(text2)) if "loss" in d]
        results.append(check_a6(series, series2, args.loss_tol))
    else:
        results.append(Result("A6 可复现", "SKIP", "未提供 --run2-log，本轮未做两次对比"))

    print("=" * 74)
    print(f"第3周训练验收 · log={log_path.name} · ckpt={ckpt_dir}")
    print("=" * 74)
    if state_path:
        print(f"[info] trainer_state 取自 {state_path.name}，log_history 中 loss 点 {len(series)} 个")
    else:
        print("[warn] 未找到 trainer_state.json，A2/A4/A6 将缺少主证据")
    for item in results:
        print(f"[{item.status:4s}] {item.name}: {item.detail}")
        for line in item.evidence:
            print(f"        · {line}")

    failed = [r.name for r in results if r.failed]
    print("-" * 74)
    print("总体：" + ("FAIL（不可用于结项）：" + "、".join(failed) if failed else "PASS（可进入结项）"))
    skipped = [r.name for r in results if r.status == "SKIP"]
    if skipped:
        print(f"未验证：{'、'.join(skipped)}（补对应日志后重跑）")

    payload = {
        "log": str(log_path),
        "ckpt_dir": str(ckpt_dir),
        "samples": args.samples,
        "steps_seen": steps_seen,
        "expect_steps": expect_steps,
        "step_seconds": step_seconds,
        "results": [r.as_dict() for r in results],
        "failed": failed,
        "skipped": skipped,
    }
    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[json] {out}")

    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
