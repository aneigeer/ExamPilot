# 备考助教垂直模型（Qwen2.5-7B QLoRA）

把通用 7B 模型微调成「只按站内资料作答、会标注引用、不会编造、且不执行资料里指令」的垂直模型。
所有训练数据来自本项目自有资产，不引入外部语料。

## 1. 硬件与可行性（本机实测）

| 项 | 值 | 说明 |
|---|---|---|
| GPU | RTX 4060 Laptop，**8GB** 显存 | 7B QLoRA 的下限，必须开梯度检查点 + paged 8bit 优化器 |
| 内存 | 32GB | 合并 LoRA 时需在 CPU 上以 bf16 载入 7B（约 15GB） |
| 训练速度 | 见 `output/*/train_metrics.json` | 单卡 batch=1、梯度累积 16，有效 batch=16 |

8GB 上跑 7B 的硬约束：`max_length=1024`、`batch=1`、`gradient_checkpointing=True`、`optim=paged_adamw_8bit`。
显存不足时按顺序降级：`--max-length 768` → 只训 `q/k/v/o_proj` → 换 Qwen2.5-3B。

## 2. 数据（`training/data/`）

由 `build_dataset.py` 从 MySQL `exam_prep` 生成，共约 4,257 条（训练 4,045 / 验证 212）：

| 类型 | 数量 | 作用 |
|---|---|---|
| `mcq` | 1,795 | 题干+选项 → 答案 + 解析（题库自带解析，已过滤标签与解析矛盾的样本） |
| `grounded` | 790 | 解析当【资料】→ 「答案 + 依据[1]」，教引用纪律 |
| `refusal` | 799 | 题目 + 跨学科语料 → 严格回「未找到相关资料」 |
| `planner` | 479 | 维持 Agent 的 JSON 协议（tool/final） |
| `injection` | 303 | 资料里夹带「忽略以上规则」→ 仍按资料作答，不执行指令（直击验收 A5-6） |
| `wiki` / `citation` | 91 | 术语定义 + 引用 |

质量闸门：剔除被其它题解析**拼接污染**的记录（约 0.6%）、标题脏（`xxx_`）的语料块、
以及「选项标签 ≠ 解析自称答案」的矛盾样本。

## 3. 流程

```bash
# 0) 依赖（已装好可跳过）
pip install torch --index-url https://download.pytorch.org/whl/cu124
pip install "transformers>=4.46" "peft>=0.13" "trl>=0.12" "datasets>=3.0" accelerate bitsandbytes modelscope

# 1) 造数据
python training/build_dataset.py          # 产出 data/sft_{train,val}.jsonl + stats.json + preview.md

# 2) 拉基座（约 15GB，落 D:/models）
python -c "from modelscope import snapshot_download; print(snapshot_download('Qwen/Qwen2.5-7B-Instruct', cache_dir='D:/models', allow_patterns=['*.json','*.safetensors','*.txt']))"

# 3) 冒烟（先确认不 OOM，再全量）
python training/train_qlora.py --smoke 20

# 4) 正式训练（1 epoch）
python training/train_qlora.py --epochs 1

# 5) 合并 + 导入 Ollama
python training/merge_lora.py --lora training/output/qwen2.5-7b-exam-lora --out D:/models/qwen2.5-7b-exam-merged
ollama create exam-prep-7b -f training/Modelfile.exam-prep-7b

# 6) 同集对比（基座 vs 微调）
python training/eval_compare.py --base qwen2.5-7b --tuned exam-prep-7b
```

## 4. 上线与回滚

切换只需改 `.env`（OpenAI 兼容协议，Ollama 的 `/v1` 即是），见 `switch_model.env.example`：

```
LLM_BASE_URL=http://127.0.0.1:11434/v1
LLM_MODEL=exam-prep-7b
LLM_API_KEY=ollama
LLM_GENERATOR_NAME=exam-prep-7b
```

回滚：把上面四项改回 GLM 配置即可，无需改代码、无需重训。

### 4.1 本机踩到的打包限制（Ollama 0.40.13）

直接 `ollama create` 导入 safetensors **走不通**，两条路都被堵：

| 尝试 | 结果 |
|---|---|
| `ollama create -f Modelfile`（FROM 合并目录） | `unsupported MLX architecture: Qwen2ForCausalLM` |
| `ollama create -q int4` | 同上（先过 MLX 转换） |
| `-q q4_K_M` | 只支持 `int4/int8/nvfp4/mxfp4/mxfp8` |
| `ollama pull qwen2.5-7b` | 代理把 blob 域名解析到 `198.18.x.x`，被拒 |
| 分片 GGUF 直接导入 | `invalid split GGUF ... must use llama.cpp split filename pattern` |

可行路径（本仓库已实现）：

```bash
# 1) 合并 LoRA
python training/merge_lora.py --lora training/output/qwen2.5-7b-exam-lora --out C:/models/merged
# 2) 转 F16 GGUF（llama.cpp 官方转换脚本，自包含版）
python tools/llama/conv_b6000.py C:/models/merged --outfile C:/models/exam-f16.gguf --outtype f16
# 3) 量化（不依赖 llama-quantize 二进制；gguf 库只实现了 Q4_0/Q5_0/Q8_0）
python tools/quantize_gguf.py --src C:/models/exam-f16.gguf --dst C:/models/exam-q4_0.gguf --qtype q4_0
# 4) 导入 Ollama（FROM 指向 GGUF 文件）
ollama create exam-prep-7b -f training/Modelfile.exam-prep-7b
```

> 为什么要自己写量化器：`github.com` 不可达拿不到 `llama-quantize.exe`；
> `gguf` 库的 K-quant（Q4_K/Q5_K）只有反量化实现，量化函数是 `NotImplementedError`，
> 所以退到 Q4_0（4.0 bpw）。对比基线是 Q4_K_M（4.5 bpw），**对微调模型是不利偏差**。

## 5. 已知边界（别当成「全能」）

- 只学了**回答行为**（引用/拒答/抗注入）与**领域题解风格**，没有新增事实知识；知识仍来自检索语料。
- 数据规模约 4k 条，属于「行为对齐」量级，不是持续预训练；领域词表未扩展。
- 抗注入是**概率性**改善，不是形式化保证；上线仍需保留 `InjectionSignalDetector` 告警与 Java 侧后置校验。
- 评测集来自同一批题库的留出样本（同分布），跨学科/跨题型泛化未验证。
