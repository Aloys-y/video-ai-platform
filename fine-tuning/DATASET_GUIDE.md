# 微调数据集制作指南

## 各条个料

```
fine-tuning/
├── data/
│   ├── videos/                    ← 视频文件放这里
│   │   ├── sample_001.mp4
│   │   ├── sample_002.mp4
│   │   └── ...
│   └── my_video_dataset.jsonl     ← 你唯一要编辑的文件
├── train_config.yaml              ← 不用动
├── ds_config.json                 ← 不用动
├── dataset_info.json              ← 不用动
└── scripts/
    ├── setup_env.sh               ← 已跑过，不用再跑
    └── train.sh                   ← 数据准备好后跑这个
```

---

## 数据格式

一条数据就是一段 JSON，每条一行（JSONL 文件是一个 JSON 数组）：

```json
[
  {
    "videos": [
      "/data/cyy/proj/DoVideoAI/fine-tuning/data/videos/sample_001.mp4"
    ],
    "messages": [
      {
        "role": "user",
        "content": "<video>请分析这个视频的战术配合"
      },
      {
        "role": "assistant",
        "content": "【视频类型】FPS游戏高光集锦\n【场景描述】Apex Legends 世界边缘地图，玩家使用 R-301 卡宾枪在对战。\n【关键事件】\n- 00:15 遭遇敌方小队\n- 00:45 完成首次击倒\n- 01:20 队友包抄配合\n【分析总结】01:20 的队友配合是最精彩的部分，利用了地形高低差优势。"
      }
    ]
  }
]
```

### 铁规则

1. **视频路径用绝对路径** — 服务器完整路径，不是相对路径
2. **`<video>` 标在 user 消息最前面** — 模型靠它定位视频 embedding 的位置
3. **assistant 回复格式保持统一** — 全部用同样的结构，别一条一个花样
4. **assistant 回复只写你期望 AI 输出的内容** — 你写什么风格，模型就学什么风格

---

## 标注内容（你写什么）

你写的东西就是模型要学的东西。建议输出结构：

```
【视频类型】xxx
【场景描述】描述视频里的场景、人物、动作
【关键事件】
- 时间点: 具体发生了什么
- 时间点: 具体发生了什么
【分析总结】整体的分析结论
```

或者你自己定格式——怎么定都行，**但定了就每条都得是这个格式**。

---

## 数据量与效果

| 数量 | 效果 |
|------|------|
| 50 条 | 跑得通，输出基本对方向 |
| 100 条 | 质量明显提升 |
| 200 条 | 稳定高质量 |

起步做 50-100 条，先训一版看效果。

---

## 操作流程

```bash
# 1. 把视频 scp/rsync 到 data/videos/
# 2. 编辑 data/my_video_dataset.jsonl，按模板格式填标注
# 3. 一行启动
bash scripts/train.sh
# 4. 合并 + 部署
bash scripts/merge.sh
bash scripts/serve.sh
# 5. 接入 DoVideoAI
#    ai.provider=openai-compatible
#    ai.openai-compatible.base-url=http://localhost:8000/v1
#    ai.openai-compatible.model=my-finetuned-model
```

---

## 踩坑提醒

| 问题 | 原因 | 解法 |
|------|------|------|
| 训练 loss 不降 | 数据量太少或格式不统一 | 先凑 50 条，固定格式重训 |
| 微调后输出总是一个样 | 数据里 assistant 回复用的同一个模板 | 多写几个不同表达 |
| 模型输出跟视频完全不搭 | `video_max_pixels` 设太低 | train_config.yaml 已设好 262144 |
| LLaMA-Factory 报错 | 字段名不对 | 用上面模板格式，别改字段名 |
