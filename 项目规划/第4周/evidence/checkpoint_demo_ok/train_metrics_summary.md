# 训练指标摘要（train_formal_v1.log）

- 模型：`C:\models\models\Qwen--Qwen2.5-3B-Instruct\snapshots\master`
- 计划步数 / 日志末步：135 / 14
- 步均耗时：44.36 s/step（按日志 elapsed / step）
- 峰值显存：alloc 3.58 G / reserved 3.72 G

| step | loss | grad_norm | lr | token_acc | num_tokens | epoch |
|---:|---:|---:|---:|---:|---:|---:|
| 9 | 3.719 | 4.344 | 2.667e-05 | 0.4841 | 37370.0 | 0.03711 |

## Checkpoints

| dir | global_step | adapter 四件 | trainer_state | optimizer | 可疑残件 |
|---|---:|---|---|---|---|
| checkpoint-2 | 2 | True | True | True | False |
