# Apex Legends Wiki RAG 数据集

> 当前实验范围已收敛为 PC 英雄玩法知识。`data/legends` 只保留 28 位英雄的玩法与
> 技能信息，不包含 Mobile、背景故事、皮肤和通用说明。正文统一为简体中文；英文
> 规范名只保留在元数据和技能名括注中，用于实体对齐与溯源。

基于 [Apex Legends Wiki](https://apexlegends.fandom.com/wiki/Apex_Legends_Wiki)（Fandom MediaWiki）通过 API 采集，用于 RAG（检索增强生成）场景。

## 数据规模

| 项目 | 值 |
|------|------|
| PC 英雄玩法页 | 28 |
| PC 英雄目标数 | 28 |
| 语料语言 | 简体中文（zh-CN） |
| 采集时间 | 2026-07-04 |
| 数据来源 | `https://apexlegends.fandom.com` |

## 目录结构

```
rag-data/
├── README.md                    <- 本文档
├── scraper.py                   <- 采集脚本（可重新运行更新数据）
└── data/
    ├── legends/                 <- PC 英雄玩法页，不含 Mobile/Lore
    ├── weapons/                 <- 82 个文件，武器
    ├── maps/                    <- 64 个文件，地图/点位
    ├── events/                  <- 129 个文件，活动/赛季
    ├── game_modes/              <- 37 个文件，游戏模式
    ├── gameplay/                <- 24 个文件，机制/装备
    ├── cosmetics/               <- 21 个文件，装扮/皮肤
    ├── general/                 <- 59 个文件，其他
    └── 其他主题目录              <- 当前保留但默认不导入
```

## 各主题详情

| 主题 | 页面数 | 字符数 | 内容范围 |
|------|--------|--------|----------|
| **legends** | 28 | 约 10.9 万 | PC 可玩英雄的玩法概览与技能 |
| **weapons** | 82 | 538,237 | 突击步枪、冲锋枪、霰弹枪、狙击枪、手枪、配件 |
| **maps** | 64 | 245,211 | Kings Canyon、World's Edge、Olympus 等地图及 POI |
| **events** | 129 | 327,007 | 收集活动、主题活动、赛季通行证 |
| **game_modes** | 37 | 132,037 | 大逃杀、竞技场、双排、三排、Mixtape |
| **gameplay** | 24 | 173,576 | 游戏机制、装备、护甲、Hop-Up、弹药 |
| **cosmetics** | 21 | 353,662 | 传家宝、皮肤、终结技、徽章 |
| **general** | 59 | 134,543 | 社区、彩蛋、教程、派系 |

## 数据格式

### Markdown 文件

每个 `.md` 文件包含 YAML frontmatter 元数据和正文：

```markdown
---
title: "恶灵"
title_en: "Wraith"
topic: legends
language: zh-CN
categories: ["Apex英雄", "PC端", "玩法资料"]
source: https://apexlegends.fandom.com/wiki/Wraith
---

# 恶灵（Wraith）

## 技能
### 进入虚空（Into the Void）
描述 | 通过安全的虚空空间快速位移，规避所有伤害。
```

## 使用方式

### 整理与翻译

```powershell
# 从原始抓取结果中只保留 PC 英雄玩法信息
python scripts/curate_pc_legend_data.py --apply

# 使用百炼文本模型转换为简体中文；已完成文件会自动跳过
$env:DASHSCOPE_API_KEY = "your-key"
python scripts/translate_legend_data_zh.py

# 旧数据曾缺失玩法概览时，从原始 Git 版本确定性恢复
python scripts/restore_legend_gameplay_zh.py --apply

# 检查 28 张名单和 zh-CN 门禁，不写数据库
python scripts/import_rag_data.py --dry-run
```

`import_rag_data.py` 默认只读取 `data/legends`。名单不完整、混入非 PC 英雄或任一
文件未声明 `language: zh-CN` 时，导入会直接拒绝执行。

### 重新采集

```bash
cd rag-data
pip install requests beautifulsoup4
python scraper.py
```

采集脚本特点：
- 从 28 个根分类递归发现页面
- 使用 MediaWiki `parse` API 获取完整 HTML 渲染内容
- BeautifulSoup 清洗 HTML → 纯文本
- 5 线程并发，自动限速
- 自动跳过图片/音频/皮肤等非文本子分类

### 采集原理

```
根分类 (28个)
  └→ 递归发现子分类（跳过图片/音频类）
       └→ 收集所有页面 ID
            └→ action=parse API 获取完整 HTML（并发 x5）
                 └→ BeautifulSoup 清洗（去导航/样式/脚本）
                      └→ 输出 Markdown + JSONL
```

## 数据质量说明

- **parse API vs explaintext**：使用 `action=parse` 而非 `action=query&prop=extracts`，内容量提升 2.5 倍（英雄页面提升 6 倍），因为 parse API 会完整渲染 Infobox、表格等结构化数据
- **短页面**：少量 Event/Map 页面在原 wiki 上即为 stub（<500 字符），这是数据源本身的特性
- **分类准确度**：基于页面标题 + 所属分类关键词自动归类，偶有边界情况（如角色武器页可能归入 weapons）
- **外链图片**：图片和外部链接已剔除，只保留纯文本内容
