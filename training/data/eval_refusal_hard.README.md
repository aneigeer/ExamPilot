> **状态（2026-11-07 17:18:36，严格口径 v1.1）：判分器已对齐，可正式跑 conflicting。**
> 规则：**双值须同时表态「无法确定」才通过（team-lead 2026-11-07 裁定，严格口径 v1.1）**；单值、「应为/认为取」、「以…为准」均判失败，年份先剔除。
> 回归 11/11 通过（5 条应通过：拒答/双值+无法确定/年份冲突；6 条应拒绝：单值 800 万/双值无表态/认为取/以…为准/答案 A）。
> 数据 sha `7a8795079c7bee2c`（按 qa 建议把 `judge_rule` 改为严格口径，条目/资料未变）。


# eval_refusal_hard.jsonl 使用说明

- 文件：`training/data/eval_refusal_hard.jsonl`
- 条数：30（no_coverage 10 / weak_relevance 10 / conflicting 10）
- sha256（前 16）：`7a8795079c7bee2c`
- 生成脚本：`项目规划/第6周/scripts/build_refusal_hard.py`
- 构造说明：`项目规划/第6周/01_拒答三场景构造说明.md`

## 格式

每行：`{"messages":[system,user,assistant],"kind":"refusal","meta":{...}}`

- system：与 `eval_holdout.jsonl` 的 refusal 逐字相同（资料约束版）
- assistant：固定 `未找到相关资料`
- `meta.scene`：`no_coverage` / `weak_relevance` / `conflicting`
- `meta.qid`：题库原题 id，30/30 可查
- `meta.source`：`题库原题 qid=…；资料为构造/改写文本，未使用训练集原文`

## 两条必须遵守的使用前提

### 1. `conflicting` 需要判分器支持 `acceptable_responses`

`conflicting` 的 meta 带：

```json
"acceptable_responses": ["未找到相关资料", "指出两条资料冲突、无法唯一确定"],
"judge_rule": "指出冲突并明确表态无法唯一确定（出现「无法确定/不能确定/难以确定」等）才通过；仅罗列两个冲突值未表态判不通过；直接选某一数值判不通过"
```

但当前 `eval_v2.refused_ok()` 只认「以 `未找到相关资料` 开头」，会把「指出资料冲突」判为不合格。
**在给 `refused_ok` 增加 `acceptable_responses` 支持前，conflicting 的拒答率会被系统性压低**，不能直接与 no_coverage 的拒答率比较，更不能据此说「模型不会拒答」。

最小改法：`refused_ok(output, meta)` 里先读 `meta.get("acceptable_responses")`，命中任一模式（如包含「冲突」「矛盾」「不一致」「无法唯一确定」且没有给出单一数值结论）即返回 True。

### 2. 30 条全部是合成场景（`material_source=constructed_for_eval`）

- 可测「该拒答时是否拒答」以及三类场景的相对差异；
- **不能外推成线上拒答率**：真实检索噪声、资料长度、领域分布与构造题差异很大；
- 结论中请标注「合成场景」，并与 holdout 的 50 条真实 refusal 分开报告。

## 报告口径

请**按三类分开报**，不要合成一个 30 条的拒答率：

| scene | 条数 | 判分 |
|---|---:|---|
| no_coverage | 10 | 拒答/明确说资料未覆盖 |
| weak_relevance | 10 | 不说出资料里缺失的结论；「先免责再回答」不通过 |
| conflicting | 10 | A 级：指出冲突**并明确表态无法唯一确定**；仅罗列两值不表态判 N；B 级：直接拒答；选值/平均/编造均不通过 |

建议同时给出 A/B 占比（冲突识别率 vs 安全拒答率），否则会掩盖「会拒答但不会识别冲突」的情况。
