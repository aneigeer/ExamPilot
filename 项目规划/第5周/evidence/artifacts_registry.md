# 产物登记表（2026-11-06T23:50:29+08:00）

共 **20** 个产物，合计 **42.08 GB**。

| kind | 名称/路径 | 大小 | sha256(16) | mtime | 来源 |
|---|---|---:|---|---|---|
| adapter | `D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b-exam-lora-v1/adapter_model.safetensors` | 114.25 MB | `3aed26e718267fcf` | 2026-11-06T12:35:21+08:00 | run=D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b, global_step=135, status=COMPLETE, base_model=C:\models\models\Qwen--Qwen2.5-3B-Instruct\sna, lora={'r': 16, 'alpha': 32, 'dropout': 0.05, 'targe, data_sha_expected=1fb1510e0a36 |
| adapter | `D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b-exam-lora-v1/checkpoint-130/adapter_model.safetensors` | 114.25 MB | `9b67fc2eecf14579` | 2026-11-06T12:33:59+08:00 | run=D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b, global_step=135, status=COMPLETE, base_model=C:\models\models\Qwen--Qwen2.5-3B-Instruct\sna, lora={'r': 16, 'alpha': 32, 'dropout': 0.05, 'targe, data_sha_expected=1fb1510e0a36 |
| adapter | `D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b-exam-lora-v1/checkpoint-135/adapter_model.safetensors` | 114.25 MB | `3aed26e718267fcf` | 2026-11-06T12:35:21+08:00 | run=D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b, global_step=135, status=COMPLETE, base_model=C:\models\models\Qwen--Qwen2.5-3B-Instruct\sna, lora={'r': 16, 'alpha': 32, 'dropout': 0.05, 'targe, data_sha_expected=1fb1510e0a36 |
| merged | `C:/models/exam-3b-v1-merged` | 5.75 GB | `-` | 2026-11-06T12:37:20+08:00 | base_model=C:/models/models/Qwen--Qwen2.5-3B-Instruct/sna, adapter=D:/大学生多考试备考知识库与题库系统/training/output/qwen2.5-3b, tool=training/merge_lora.py, merge_dtype=bfloat16 (CPU), shards=2 |
| merged/file | `model-00001-of-00002.safetensors` | 3.72 GB（sha 3.0s） | `7e16c8ed44046897` | 2026-11-06T12:37:18+08:00 | 见所属 merged |
| merged/file | `model-00002-of-00002.safetensors` | 2.03 GB（sha 1.7s） | `6f2af9122b35cb56` | 2026-11-06T12:37:20+08:00 | 见所属 merged |
| merged/file | `model.safetensors.index.json` | 35.21 KB | `d0977c46b2cf8685` | 2026-11-06T12:37:20+08:00 | 见所属 merged |
| gguf_f16 | `C:/models/exam-3b-v1-f16.gguf` | 5.75 GB（sha 5.2s） | `c08390efdb735929` | 2026-11-06T12:37:59+08:00 | source_merged=C:/models/exam-3b-v1-merged, tool=tools/llama/conv_b6000.py, outtype=f16, tool_sha256=2e73750c607b61ff |
| gguf_q4_0 | `C:/models/exam-3b-v1-q4_0.gguf` | 1.62 GB（sha 1.6s） | `da55d937600c0cc3` | 2026-11-06T12:39:03+08:00 | source_gguf=C:/models/exam-3b-v1-f16.gguf, tool=tools/quantize_gguf.py, qtype=q4_0, tool_sha256=c8aa513c989c7ebc, ratio_vs_source=3.55, source_bytes=6178316704 |
| gguf_baseline_f16 | `C:/models/qwen2.5-3b-instruct-f16.gguf` | 5.75 GB（sha 5.3s） | `7d1ef0e92695a817` | 2026-11-06T12:46:49+08:00 | source_model=C:/models/models/Qwen--Qwen2.5-3B-Instruct/sna, tool=tools/llama/conv_b6000.py, outtype=f16, tool_sha256=2e73750c607b61ff |
| gguf_baseline | `C:/models/qwen2.5-3b-instruct-q4_0.gguf` | 1.62 GB（sha 1.4s） | `354a0c1fe9f96395` | 2026-11-06T12:47:31+08:00 | source_gguf=C:/models/qwen2.5-3b-instruct-f16.gguf, tool=tools/quantize_gguf.py, qtype=q4_0, tool_sha256=c8aa513c989c7ebc, ratio_vs_source=3.55, source_bytes=6178317184 |
| gguf_q8_0 | `C:/models/exam-3b-v1-q8_0.gguf` | 3.06 GB（sha 3.0s） | `9b4998d14d7e8711` | 2026-11-06T21:43:03+08:00 | source_gguf=C:/models/exam-3b-v1-f16.gguf, tool=tools/quantize_gguf.py, qtype=q8_0, tool_sha256=c8aa513c989c7ebc, ratio_vs_source=1.88, source_bytes=6178316704 |
| gguf_baseline_q8_0 | `C:/models/qwen2.5-3b-instruct-q8_0.gguf` | 3.06 GB（sha 2.8s） | `eefea94d6b6c1544` | 2026-11-06T22:53:11+08:00 | source_gguf=C:/models/qwen2.5-3b-instruct-f16.gguf, tool=tools/quantize_gguf.py, qtype=q8_0, tool_sha256=c8aa513c989c7ebc, ratio_vs_source=1.88, source_bytes=6178317184 |
| modelfile | `D:/大学生多考试备考知识库与题库系统/training/Modelfile.exam-prep-3b-v1` | 985 B | `43e3d209d95c9c28` | 2026-11-05T22:41:43+08:00 | from=C:/models/exam-3b-v1-q4_0.gguf, ollama_model=exam-prep-3b-v1, template=由 GGUF 自带 chat_template 提供（未写 TEMPLATE） |
| modelfile | `D:/大学生多考试备考知识库与题库系统/training/Modelfile.baseline-3b-q4_0` | 308 B | `382b3961b3be5c8f` | 2026-11-06T12:47:42+08:00 | from=C:/models/qwen2.5-3b-instruct-q4_0.gguf, ollama_model=qwen2.5-3b-instruct-q4_0-baseline, template=由 GGUF 自带 chat_template 提供（未写 TEMPLATE） |
| modelfile | `D:/大学生多考试备考知识库与题库系统/training/Modelfile.exam-prep-3b-v1-f16` | 198 B | `087dd0b757e2ebd5` | 2026-11-06T21:36:36+08:00 | from=C:/models/exam-3b-v1-f16.gguf, ollama_model=exam-prep-3b-v1-f16, template=由 GGUF 自带 chat_template 提供（未写 TEMPLATE） |
| modelfile | `D:/大学生多考试备考知识库与题库系统/training/Modelfile.exam-prep-3b-v1-q8` | 101 B | `b777a28a9f837767` | 2026-11-06T21:43:03+08:00 | from=C:/models/exam-3b-v1-q8_0.gguf, ollama_model=exam-prep-3b-v1-q8, template=由 GGUF 自带 chat_template 提供（未写 TEMPLATE） |
| modelfile | `D:/大学生多考试备考知识库与题库系统/training/Modelfile.baseline-3b-q8_0` | 181 B | `46d09fd302718a4b` | 2026-11-06T22:53:12+08:00 | from=C:/models/qwen2.5-3b-instruct-q8_0.gguf, ollama_model=qwen2.5-3b-instruct-q8_0-baseline, template=由 GGUF 自带 chat_template 提供（未写 TEMPLATE） |
| ollama_model | `exam-prep-3b-v1:latest` | 1.62 GB | `da55d937600c0cc3` | 11 hours ago | tuned-q4_0；blob sha256-da55d937600c0… |
| ollama_model | `qwen2.5-3b-instruct-q4_0-baseline:latest` | 1.62 GB | `354a0c1fe9f96395` | 11 hours ago | baseline-q4_0；blob sha256-354a0c1fe9f96… |
| ollama_model | `exam-prep-3b-v1-f16:latest` | 5.75 GB | `c08390efdb735929` | 2 hours ago | tuned-f16；blob sha256-c08390efdb735… |
| ollama_model | `exam-prep-3b-v1-q8:latest` | 3.06 GB | `9b4998d14d7e8711` | 2 hours ago | tuned-q8_0；blob sha256-9b4998d14d7e8… |
| ollama_model | `qwen2.5-3b-instruct-q8_0-baseline:latest` | 3.06 GB | `eefea94d6b6c1544` | 57 minutes ago | baseline-q8_0；blob sha256-eefea94d6b6c1… |

## 量化档对照（3B，微调 vs 基线）

| 档位 | 微调体积 | 基线体积 | f16 / 该档 ratio |
|---|---:|---:|---:|
| f16 | 5.75 GB | 5.75 GB | 1.00 |
| q8_0 | 3.06 GB | 3.06 GB | 1.88 |
| q4_0 | 1.62 GB | 1.62 GB | 3.55 |

## 一致性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| merged.params == gguf_f16.params | ✅ | merged 3,085,938,688 vs f16 3,085,938,688 |
| gguf_f16 含 output_norm.weight | ✅ | tensors=434 |
| gguf_f16.tensors == gguf_q4_0.tensors | ✅ | 434 vs 434；量化后 params 应相等：True |
| gguf_q4_0 含 output_norm.weight | ✅ | quant_types={'Q4_0': 253, 'F32': 181} |
| merged.tensors == gguf_f16.tensors | ✅ | 434 vs 434（3B 为 tie_word_embeddings=True：36 层×12 + token_embd + output_norm = 434，无独立 output.weight；7B 未绑定故为 339 = 28×12+3） |
| tuned/baseline 张量数一致 | ✅ | tuned 434 vs baseline 434 |
| gguf_q8_0.tensors == gguf_f16.tensors | ✅ | 434 vs 434 |
| gguf_q8_0.params == gguf_f16.params | ✅ | 3,085,938,688 vs 3,085,938,688 |
| gguf_q8_0 含 output_norm.weight | ✅ | quant_types={'Q8_0': 253, 'F32': 181} |
| q8_0 tuned/baseline 张量数一致 | ✅ | tuned 434 vs baseline 434 |
| ollama[tuned-q4_0].blob 与 exam-3b-v1-q4_0.gguf 同 sha（仅记录） | ℹ️ | 相同：ollama 直接复用原 GGUF blob |
| ollama[baseline-q4_0].blob 与 qwen2.5-3b-instruct-q4_0.gguf 同 sha（仅记录） | ℹ️ | 相同：ollama 直接复用原 GGUF blob |
| ollama[tuned-f16].blob 与 exam-3b-v1-f16.gguf 同 sha（仅记录） | ℹ️ | 相同：ollama 直接复用原 GGUF blob |
| ollama[tuned-q8_0].blob 与 exam-3b-v1-q8_0.gguf 同 sha（仅记录） | ℹ️ | 相同：ollama 直接复用原 GGUF blob |
| ollama[baseline-q8_0].blob 与 qwen2.5-3b-instruct-q8_0.gguf 同 sha（仅记录） | ℹ️ | 相同：ollama 直接复用原 GGUF blob |

## 环境

- python: `3.12.10`
- torch: `2.6.0+cu124`
- transformers: `5.22.1`
- peft: `0.21.2`
- trl: `1.17.1`
- gguf: `0.19.0`
- datasets: `5.1.0`
- bitsandbytes: `0.50.2`
- safetensors: `0.8.0`
- ollama: `0.40.15`
