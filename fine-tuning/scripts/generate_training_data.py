#!/usr/bin/env python3
"""
知识蒸馏数据生成脚本
用强模型（DashScope Qwen3-VL API）批量分析视频，生成微调训练数据。

使用方式:
  1. 把视频放到 data/videos/
  2. 设置 DASHSCOPE_API_KEY 环境变量
  3. python scripts/generate_training_data.py
  4. 检查输出 data/my_video_dataset.jsonl，修正不满意的标注
  5. bash scripts/train.sh

原理：用 235B 大模型生成高质量标注 → 人工审核 → 喂给 7B 模型微调
      花几十块 API 费用，省几天人工标注时间
"""

import os
import json
import sys
import time
from pathlib import Path
from typing import Optional

# ---------- 配置 ----------
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", "your-api-key-here")
DASHSCOPE_MODEL = "qwen3-vl-plus"       # API 模型（235B级别）
OUTPUT_FILE = "data/my_video_dataset.jsonl"
VIDEO_DIR = "data/videos"
FPS = 1.0                                # 视频抽帧率
MAX_RETRIES = 3                          # API 失败重试次数

# 分析提示词 —— 可以自定义来匹配你的业务场景
ANALYSIS_PROMPT = """请详细分析这个视频内容，按以下格式输出：

【视频类型】判断视频的类型和主题
【场景描述】描述画面中的场景、人物、动作
【关键事件】
- 时间点: 事件描述
- 时间点: 事件描述
（请列出至少3个关键时间点）
【高光时刻】指出最精彩或最重要的片段，并解释原因
【整体评估】画面质量、内容价值等方面的评价"""


def analyze_video(video_path: str) -> Optional[str]:
    """调用 DashScope API 分析视频"""
    try:
        from dashscope import MultiModalConversation
        from dashscope import MultiModalConversationParam
        from dashscope.common import MultiModalMessage, Role

        messages = [MultiModalMessage.builder()
            .role(Role.USER.value())
            .content([
                {"video": video_path, "fps": FPS},
                {"text": ANALYSIS_PROMPT}
            ]).build()]

        param = MultiModalConversationParam.builder() \
            .apiKey(DASHSCOPE_API_KEY) \
            .model(DASHSCOPE_MODEL) \
            .messages(messages) \
            .build()

        for attempt in range(MAX_RETRIES):
            try:
                result = MultiModalConversation().call(param)
                content_list = result.get_output().get_choices()[0].get_message().get_content()
                if content_list:
                    return content_list[0].get("text", "")
            except Exception as e:
                print(f"  Attempt {attempt + 1}/{MAX_RETRIES} failed: {e}")
                if attempt < MAX_RETRIES - 1:
                    time.sleep(10 * (attempt + 1))

        return None
    except ImportError:
        print("ERROR: dashscope SDK not installed. Run: pip install dashscope")
        sys.exit(1)


def main():
    video_dir = Path(VIDEO_DIR)
    if not video_dir.exists():
        print(f"ERROR: Video directory not found: {VIDEO_DIR}")
        print("Please create it and put your videos inside.")
        sys.exit(1)

    videos = sorted(
        p for p in video_dir.iterdir()
        if p.suffix.lower() in (".mp4", ".avi", ".mov", ".mkv", ".webm")
    )
    if not videos:
        print(f"ERROR: No video files found in {VIDEO_DIR}")
        sys.exit(1)

    print(f"Found {len(videos)} videos")
    print(f"Using model: {DASHSCOPE_MODEL}")
    print(f"Output: {OUTPUT_FILE}")
    print()

    # 加载已有数据（支持增量生成）
    existing_data = []
    existing_videos = set()
    output_path = Path(OUTPUT_FILE)
    if output_path.exists():
        try:
            existing_data = json.loads(output_path.read_text(encoding="utf-8"))
            for item in existing_data:
                if "videos" in item and item["videos"]:
                    existing_videos.add(item["videos"][0])
            print(f"Loaded {len(existing_data)} existing entries (will skip already processed videos)")
        except Exception:
            pass

    new_count = 0
    for i, video in enumerate(videos):
        video_str = str(video.absolute())
        if video_str in existing_videos:
            print(f"[{i+1}/{len(videos)}] SKIP (already done): {video.name}")
            continue

        print(f"[{i+1}/{len(videos)}] Analyzing: {video.name} ...", end=" ", flush=True)
        analysis = analyze_video(video_str)

        if analysis:
            entry = {
                "videos": [video_str],
                "messages": [
                    {"role": "user", "content": f"<video>\n{ANALYSIS_PROMPT}"},
                    {"role": "assistant", "content": analysis}
                ]
            }
            existing_data.append(entry)
            existing_videos.add(video_str)
            new_count += 1

            # 每处理一条就保存（防止中断丢失）
            output_path.write_text(
                json.dumps(existing_data, ensure_ascii=False, indent=2),
                encoding="utf-8"
            )
            print(f"OK ({len(analysis)} chars)")
        else:
            print("FAILED (all retries exhausted)")

        # API 限流保护
        if i < len(videos) - 1:
            time.sleep(2)

    print()
    print(f"Done! Generated {new_count} new entries.")
    print(f"Total entries in {OUTPUT_FILE}: {len(existing_data)}")
    print()
    print("Next steps:")
    print("  1. Review the generated data for quality")
    print("  2. Fix any incorrect or low-quality annotations")
    print("  3. bash scripts/train.sh")


if __name__ == "__main__":
    main()
