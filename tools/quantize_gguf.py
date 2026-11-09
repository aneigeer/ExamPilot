"""把 F16 GGUF 量化成 Q4_K（不依赖 llama-quantize 二进制）。

背景：本机 github.com 不可达，拿不到 llama.cpp 的预编译 llama-quantize；
Ollama 0.40 的 `--quantize` 只支持 int4/int8/nvfp4，且 safetensors 导入走 MLX 不认 Qwen2。
所以这里直接用 gguf 库做张量级量化：2D 且末维能被 256 整除的权重转 Q4_K，其余（norm/bias）保持 F32。

用法：
    python tools/quantize_gguf.py --src in-f16.gguf --dst out-q4k.gguf
    python tools/quantize_gguf.py --self-test
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import gguf
from gguf import GGMLQuantizationType as QT
from gguf import GGUFReader, GGUFValueType, GGUFWriter, quants

#: 这些张量保持原精度（量化收益小、误差敏感）
KEEP_F32 = ()   # 连 embedding 一起量化，否则 7B 的 embed 两项就占 4.4GB(F32)


QTYPE = QT.Q4_0


def should_quantize(name: str, data: np.ndarray) -> bool:
    if data.ndim != 2:
        return False
    if data.dtype not in (np.float16, np.float32):
        return False
    if data.shape[-1] % 32 != 0:   # Q4_0/Q8_0 的块大小 32
        return False
    return True


def copy_metadata(reader: GGUFReader, writer: GGUFWriter) -> None:
    for name, field in reader.fields.items():
        if name.startswith("GGUF.") or name.startswith("split.") or name == "general.architecture":
            continue
        vtype = field.types[0]
        sub = field.types[1] if vtype == GGUFValueType.ARRAY and len(field.types) > 1 else None
        try:
            writer.add_key_value(name, field.contents(), vtype, sub)
        except Exception as exc:  # 个别字段类型特殊，跳过但记录
            print(f"  [warn] 跳过元数据 {name}: {exc}", file=sys.stderr)


def quantize(src: Path, dst: Path, copy_only: bool = False) -> dict:
    reader = GGUFReader(str(src))
    arch = reader.get_field("general.architecture")
    if arch is None:
        raise SystemExit("GGUF 缺少 general.architecture")

    writer = GGUFWriter(str(dst), arch=str(arch.contents()))
    copy_metadata(reader, writer)

    stats = {"total": 0, "quantized": 0, "kept": 0, "src_bytes": 0, "dst_bytes": 0}
    for tensor in reader.tensors:
        data = np.asarray(tensor.data)
        stats["total"] += 1
        stats["src_bytes"] += data.nbytes
        name = tensor.name
        if copy_only:
            writer.add_tensor(name, data, raw_dtype=tensor.tensor_type)
            stats["kept"] += 1
            stats["dst_bytes"] += data.nbytes
        elif should_quantize(name, data) and name not in KEEP_F32:
            q = quants.quantize(data.astype(np.float32), QTYPE)
            writer.add_tensor(name, q, raw_dtype=QTYPE)
            stats["quantized"] += 1
            stats["dst_bytes"] += q.nbytes
        else:
            payload = data if data.dtype in (np.float16, np.float32) else data.astype(np.float32)
            writer.add_tensor(name, payload)
            stats["kept"] += 1
            stats["dst_bytes"] += payload.nbytes

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()

    stats["ratio"] = round(stats["src_bytes"] / max(1, stats["dst_bytes"]), 2)
    return stats


def self_test() -> None:
    """合成一个 GGUF：先在内存验证量化误差，再写盘读回验证结构。"""
    tmp = Path("tools/llama/_selftest")
    tmp.mkdir(parents=True, exist_ok=True)
    src, dst = tmp / "toy-f16.gguf", tmp / "toy-q4k.gguf"

    rng = np.random.default_rng(0)
    w = GGUFWriter(str(src), arch="llama")
    w.add_name("toy")
    w.add_context_length(4096)
    weight = rng.normal(0, 0.02, (512, 256)).astype(np.float16)
    w.add_tensor("blk.0.attn_q.weight", weight)
    w.add_tensor("blk.0.attn_norm.weight", rng.normal(1, 0.01, (512,)).astype(np.float16))
    w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()

    # 1) 内存内量化-反量化，验证误差
    q = quants.quantize(weight.astype(np.float32), QTYPE)
    deq = quants.dequantize(q, QTYPE)
    cos = float(np.dot(weight.ravel().astype(np.float32), deq.ravel()) /
                (np.linalg.norm(weight.astype(np.float32)) * np.linalg.norm(deq) + 1e-9))
    print(f"内存自检：{QTYPE.name} 余弦相似度 {cos:.4f}（>0.99 正常）")

    # 2) 写盘-读回，验证结构与类型
    st = quantize(src, dst)
    print("self-test 量化统计:", st)
    b = GGUFReader(str(dst))
    t = next(x for x in b.tensors if x.name.endswith("attn_q.weight"))
    n = next(x for x in b.tensors if x.name.endswith("attn_norm.weight"))
    ok = t.tensor_type == QTYPE and n.tensor_type == QT.F32
    print(f"结构自检：权重 {t.tensor_type.name} / norm {n.tensor_type.name} -> {'OK' if ok else 'FAIL'}")
    if cos < 0.98 or not ok:
        raise SystemExit("自检失败")
    print("自检通过")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src")
    parser.add_argument("--dst")
    parser.add_argument("--qtype", default="q4_0", choices=["q4_0", "q5_0", "q8_0"])
    parser.add_argument("--copy", action="store_true", help="原样重写（只清理 split 元数据）")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    global QTYPE
    QTYPE = {"q4_0": QT.Q4_0, "q5_0": QT.Q5_0, "q8_0": QT.Q8_0}[args.qtype]
    if args.self_test:
        self_test()
        return
    if not (args.src and args.dst):
        raise SystemExit("需要 --src 与 --dst，或使用 --self-test")
    stats = quantize(Path(args.src), Path(args.dst), copy_only=args.copy)
    print("量化完成:", stats, f"-> {args.dst}")


if __name__ == "__main__":
    main()
