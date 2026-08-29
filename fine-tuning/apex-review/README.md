# APEX 游戏视频复盘：Qwen2.5-VL QLoRA 实施包

这套方案训练的不是“视频摘要器”，而是能够基于 APEX 片段给出证据、判断和下一步动作的复盘模型。训练输出为严格 JSON，现有 Java 服务再把 JSON 渲染为 `对局总览 / 高光 / 走位 / 枪法 / 团战 / 道具 / 失误 / 综合评价` 八段 Markdown。

## 先给结论

第一轮租一张 24GB NVIDIA 卡即可，优先 RTX 4090/3090；系统用 Ubuntu 22.04，内存至少 48GB，磁盘至少 80GB。先跑 `Qwen2.5-VL-3B-Instruct + 4-bit NF4 QLoRA`，冻结视觉塔，只训练语言模型的 LoRA。第一轮不引入多卡、DeepSpeed、FlashAttention、TRL 或 Unsloth，减少变量。

在租卡前，至少准备：

- 4 场以上互不相同的原始对局视频；
- 12–16 个完成双人复核的片段标注，用于“小样本过拟合门禁”；
- 本目录代码和 `config.yaml`；
- 能访问 Hugging Face，或提前把基座模型同步到服务器。

如果这 12–16 条都不能明显过拟合，不进入大规模训练，也不继续烧卡。

## 总体路线

| 阶段 | 数据规模 | 目的 | 通过标准 |
|---|---:|---|---|
| S0 数据契约 | 1 条格式样本 | 验证 Schema 与脚本 | 数据校验通过 |
| S1 小样本闭环 | 12–16 片段，至少 4 局 | 排除训练管线错误 | loss 明显下降；保存后重载结果一致；JSON 合法率接近 100% |
| S2 试验集 | 100–200 片段，20–30 局 | 判断是否真的有收益 | M1 相比 B1 的结构、事件和复盘标签指标提升，人工盲评不退化 |
| S3 简历级实验 | 300–800 片段，至少 50 局 | 形成可信实验 | 固定测试集、完整运行清单、失败案例和可复现实验记录 |

这里的 B1 是“最终结构化提示词 + 未微调基座”，M1 是“同一提示词 + QLoRA adapter”。只有 M1 对 B1 的提升，才能归因于微调。现有泛化提示词可作为额外 B0，但不能拿 B0 与 M1 的差异冒充微调收益。

## 数据准备

1. 复制 `data/example_raw_manifest.jsonl` 的结构，创建自己的 `data/raw_manifest.jsonl`。示例答案明确是占位内容，禁止直接进入训练集。
2. 按 [ANNOTATION_GUIDE.md](ANNOTATION_GUIDE.md) 观看片段并填写 `answer`。
3. 抽取固定数量的帧。帧列表以 `type=video` 传给 Qwen，仍会走模型的视频输入分支，同时保证训练与评测看到完全一致的画面。

```bash
python scripts/extract_frames.py \
  --input data/raw_manifest.jsonl \
  --output data/prepared/all_unsplit.jsonl \
  --frames-root data/frames \
  --num-frames 8
```

4. 必须按完整对局分组划分，不能随机拆片段：

```bash
python scripts/split_dataset.py \
  --input data/prepared/all_unsplit.jsonl \
  --output-dir data/prepared \
  --seed 42

python scripts/validate_dataset.py \
  data/prepared/train.jsonl data/prepared/val.jsonl data/prepared/test.jsonl \
  --schema schemas/apex_review.schema.json
```

## 租卡后的执行顺序

在本目录执行：

```bash
bash scripts/setup_server.sh
source .venv/bin/activate
python scripts/preflight.py --config config.yaml --load-model
```

`preflight` 会检查锁定依赖、CUDA、BF16、显存、磁盘，并实际加载 4-bit 基座。任何一项失败都先停下，不开训练。

### 1. 先跑基线

```bash
python scripts/infer.py \
  --config config.yaml \
  --input data/prepared/test.jsonl \
  --output outputs/baseline_predictions.jsonl

python scripts/evaluate.py \
  --references data/prepared/test.jsonl \
  --predictions outputs/baseline_predictions.jsonl \
  --schema schemas/apex_review.schema.json \
  --output outputs/baseline_metrics.json
```

### 2. 小样本过拟合门禁

从 4 场以上的对局中准备 12–16 条训练片段和独立验证片段，然后：

```bash
python scripts/train_qlora.py \
  --config config.yaml \
  --max-train-samples 16 \
  --max-eval-samples 8 \
  --epochs 20 \
  --output-dir outputs/smoke
```

脚本会在正式训练前检查：

- LoRA 目标模块非空；
- 所有可训练参数都属于 `language_model` 下的 `lora_` 参数；
- 第一个多模态批次存在 assistant labels；
- 至少一组可训练参数产生非零梯度。

训练结束后保存的是 adapter，不把 adapter 合并进 4-bit 基座。

### 3. 独立重载和评测

必须启动新的 Python 进程，从磁盘重新加载基座和 adapter：

```bash
python scripts/infer.py \
  --config config.yaml \
  --adapter outputs/smoke/adapter \
  --input data/prepared/test.jsonl \
  --output outputs/smoke_predictions.jsonl

python scripts/evaluate.py \
  --references data/prepared/test.jsonl \
  --predictions outputs/smoke_predictions.jsonl \
  --schema schemas/apex_review.schema.json \
  --output outputs/smoke_metrics.json
```

小样本门禁通过后，去掉 `--max-*-samples` 和 `--epochs 20`，按 `config.yaml` 跑完整训练。

## 自动指标与人工验收

自动评测输出：

- `schema_valid_rate`：能否稳定生成规定 JSON；
- `review_category_verdict_micro`：是否识别出相同类别和正误判断；
- `event_type_temporal_micro`：事件类型及时间重叠；
- `matched_event_center_mae_seconds`：匹配事件中心点误差；
- `event_hallucination_rate / omission_rate`：多报和漏报；
- `review_severity_accuracy_on_matched`：匹配复盘项的严重程度准确率。

自动指标不能判断建议是否真正有用。固定抽取至少 30 条测试片段，隐藏模型名称，由两名熟悉 APEX 的评审分别判断：画面事实是否正确、证据是否对应、建议是否可执行、是否存在无依据推断。分歧样本需复核并保留。

盲评记录可直接使用 `evaluation/human_review_template.csv`。先随机打乱 B1/M1 输出并改成 A/B 槽位，评审完成前不要暴露模型名称。

## 显存不足时的处理顺序

不要一上来改十个参数。依次执行：

1. `vision.num_frames: 8 → 4`；
2. `vision.max_pixel_tokens: 512 → 256`；
3. 缩短训练答案和 `generation.max_new_tokens`；
4. 再次确认日志中的 LoRA 目标没有 `visual`/`merger`；
5. 仍失败再换 48GB 卡，不改成未经验证的多卡组合。

## 常见坑已经如何规避

- 不用 `target_modules=all-linear`，而是运行时只发现 `language_model.layers` 下的目标层，避免视觉塔被误训练。
- 不依赖多模态 `assistant_only_loss`/assistant mask；collator 分别编码 prompt 和完整对话，验证 token 前缀后自行屏蔽标签。
- 强制 batch size 1，避免不同视频 token 数导致未验证的批量 padding 问题；靠梯度累积获得有效 batch。
- 使用 `use_reentrant=False` 的梯度检查点，并在训练前做非零梯度探针。
- 首轮用 SDPA，暂不安装 FlashAttention，减少编译和版本兼容变量。
- adapter 保存后独立重载；不在 4-bit 基座上直接 `merge_and_unload()`。
- 训练集、验证集、测试集按 `source_match_id` 隔离。
- 版本知识留给 RAG；QLoRA 只学习复盘行为、结构、术语表达和判断模式。

## 产物与简历证据

保留以下文件后再更新简历：

- `outputs/qlora/run_manifest.json`：模型 revision、依赖、目标层、梯度探针、样本数、耗时和峰值显存；
- B1 与 M1 的 predictions/metrics；
- TensorBoard 曲线；
- 固定测试集 ID 与人工盲评结果；
- 至少 5 个失败案例及原因；
- adapter 目录及其 SHA-256。

只有训练、独立重载和基线对照全部走通后，简历才写“完成 QLoRA 微调”；在此之前应写“搭建并验证 QLoRA 微调管线”。

## 与当前 Java/RAG 服务的边界

训练模型输出 `apex_review.v1` JSON；Worker 解析并校验后，再映射为现有 Markdown。RAG 检索结果只用于补充可变的版本事实，并必须标注来源。若 JSON 校验失败，服务应保留原始输出并降级到基座模型或重试，不能把格式错误的结果直接展示为可信复盘。

本地可先验证映射结果：

```bash
python scripts/render_markdown.py \
  --input outputs/smoke_predictions.jsonl \
  --output outputs/smoke_rendered.jsonl
```

## 技术依据

- [Qwen2.5-VL-3B 官方模型卡](https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct)明确给出“图片帧列表作为视频输入”的格式。
- [Hugging Face 多模态模板文档](https://huggingface.co/docs/transformers/en/chat_templating_multimodal)说明视频输入、固定帧数和 processor 流程。
- [PEFT 量化训练文档](https://huggingface.co/docs/peft/developer_guides/quantization)给出 NF4、double quant、BF16 和 `prepare_model_for_kbit_training` 的 QLoRA 组合。
- [PyTorch 官方历史版本页](https://pytorch.org/get-started/previous-versions/)给出 Torch 2.6.0 / CUDA 12.4 的安装命令。
- Transformers 的[多模态 assistant mask 问题](https://github.com/huggingface/transformers/issues/46559)说明为何本项目不用自动 assistant mask，而执行前缀校验后自行构造 labels。
