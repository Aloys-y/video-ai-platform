# Apex Legends Wiki RAG 数据集

基于 [Apex Legends Wiki](https://apexlegends.fandom.com/wiki/Apex_Legends_Wiki)（Fandom MediaWiki）通过 API 采集，用于 RAG（检索增强生成）场景。

## 数据规模

| 项目 | 值 |
|------|------|
| 页面总数 | 569 |
| 总字符数 | **3,689,476** |
| 总大小 | ~4.6 MB（Markdown）/ ~3.7 MB（JSONL） |
| 采集时间 | 2026-07-04 |
| 数据来源 | `https://apexlegends.fandom.com` |

## 目录结构

```
rag-data/
├── README.md                    <- 本文档
├── scraper.py                   <- 采集脚本（可重新运行更新数据）
└── data/
    ├── legends/                 <- 91 个文件，英雄/角色
    ├── weapons/                 <- 82 个文件，武器
    ├── maps/                    <- 64 个文件，地图/点位
    ├── lore/                    <- 62 个文件，剧情/故事
    ├── events/                  <- 129 个文件，活动/赛季
    ├── game_modes/              <- 37 个文件，游戏模式
    ├── gameplay/                <- 24 个文件，机制/装备
    ├── cosmetics/               <- 21 个文件，装扮/皮肤
    ├── general/                 <- 59 个文件，其他
    ├── rag_corpus.jsonl         <- JSONL 汇总（导入 RAG）
    └── index.json               <- 全量元数据索引
```

## 各主题详情

| 主题 | 页面数 | 字符数 | 内容范围 |
|------|--------|--------|----------|
| **legends** | 91 | 644,670 | 可玩英雄、NPC、角色技能、背景故事 |
| **weapons** | 82 | 538,237 | 突击步枪、冲锋枪、霰弹枪、狙击枪、手枪、配件 |
| **maps** | 64 | 245,211 | Kings Canyon、World's Edge、Olympus 等地图及 POI |
| **lore** | 62 | 945,795 | 角色传记、剧情章节、动画短片、漫画 |
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
title: "Wraith"
topic: legends
categories: ["Apex Legends", "Apex Legends Legends", "Female Legends", ...]
source: https://apexlegends.fandom.com/wiki/Wraith
---

# Wraith

Wraith is a Skirmisher Legend. She is unlocked by default.
...
```

### JSONL 文件（rag_corpus.jsonl）

每行一个 JSON 对象，可直接导入 LangChain、LlamaIndex 等 RAG 框架：

```json
{
  "title": "Wraith",
  "topic": "legends",
  "categories": ["Apex Legends", "Apex Legends Legends", ...],
  "text": "Wraith is a Skirmisher Legend...",
  "source_url": "https://apexlegends.fandom.com/wiki/Wraith"
}
```

### 索引文件（index.json）

全量元数据索引，包含每个页面的标题、主题、分类、字符数、文件路径：

```json
[{
  "title": "Wraith",
  "topic": "legends",
  "categories": ["Apex Legends", "Apex Legends Legends", ...],
  "text_length": 21332,
  "file": "data/legends/Wraith.md"
}, ...]
```

## 使用方式

### 导入 RAG 框架

```python
# LangChain
from langchain_community.document_loaders import JSONLoader
loader = JSONLoader("data/rag_corpus.jsonl", ...)
docs = loader.load()

# LlamaIndex
from llama_index.core import SimpleDirectoryReader
docs = SimpleDirectoryReader("data/legends/").load_data()
```

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
