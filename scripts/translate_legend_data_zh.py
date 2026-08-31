#!/usr/bin/env python3
"""Translate the curated PC Legend corpus to Simplified Chinese in place.

The script is resumable: files whose frontmatter already declares zh-CN are
skipped. A file is replaced atomically only after the model output passes the
basic Markdown structure checks.
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib import error, request


LEGEND_NAMES = {
    "Alter": "变幻", "Ash": "艾许", "Axle": "艾瑟儿", "Ballistic": "弹道",
    "Bangalore": "班加罗尔", "Bloodhound": "寻血猎犬", "Catalyst": "催化姬",
    "Caustic": "腐蚀", "Conduit": "导线管", "Crypto": "密客", "Fuse": "暴雷",
    "Gibraltar": "直布罗陀", "Horizon": "地平线", "Lifeline": "命脉",
    "Loba": "罗芭", "Mad Maggie": "疯玛吉", "Mirage": "幻象",
    "Newcastle": "纽卡斯尔", "Octane": "动力小子", "Pathfinder": "探路者",
    "Rampart": "兰伯特", "Revenant": "亡灵", "Seer": "希尔",
    "Sparrow": "飞雀", "Valkyrie": "瓦尔基里", "Vantage": "万蒂奇",
    "Wattson": "沃特森", "Wraith": "恶灵",
}

SYSTEM_PROMPT = """你是 Apex Legends PC 端玩法资料的专业本地化编辑。请将输入 Markdown 完整翻译为简体中文。

硬性要求：
1. 逐句翻译，绝不摘要、删减、补写或修正原文事实。
2. 保留所有 Markdown 标题层级、表格分隔符、列表标记、按键、数字、单位和标点结构。
3. 英雄名使用给定术语表；技能标题写成“中文名（English Name）”，正文中的技能名优先使用中文。
4. 游戏内专有名词采用中国大陆玩家常用译名；没有稳定译名时首次写“中文（English）”。
5. 不翻译 URL、H.U.D. 等技术缩写。只输出翻译后的 Markdown，不要解释，不要代码围栏。

英雄术语表：
变幻 Alter；艾许 Ash；艾瑟儿 Axle；弹道 Ballistic；班加罗尔 Bangalore；寻血猎犬 Bloodhound；
催化姬 Catalyst；腐蚀 Caustic；导线管 Conduit；密客 Crypto；暴雷 Fuse；直布罗陀 Gibraltar；
地平线 Horizon；命脉 Lifeline；罗芭 Loba；疯玛吉 Mad Maggie；幻象 Mirage；纽卡斯尔 Newcastle；
动力小子 Octane；探路者 Pathfinder；兰伯特 Rampart；亡灵 Revenant；希尔 Seer；飞雀 Sparrow；
瓦尔基里 Valkyrie；万蒂奇 Vantage；沃特森 Wattson；恶灵 Wraith。"""


def split_frontmatter(raw: str) -> tuple[dict[str, str], str]:
    normalized = raw.replace("\r\n", "\n")
    match = re.match(r"\A---\n(.*?)\n---\n+(.*)\Z", normalized, re.S)
    if not match:
        raise ValueError("missing or invalid YAML frontmatter")
    metadata: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        metadata[key.strip()] = value.strip().strip('"').strip("'")
    return metadata, match.group(2).strip()


def render_frontmatter(title_zh: str, title_en: str, source: str) -> str:
    return (
        "---\n"
        f'title: "{title_zh}"\n'
        f'title_en: "{title_en}"\n'
        "topic: legends\n"
        "language: zh-CN\n"
        'categories: ["Apex英雄", "PC端", "玩法资料"]\n'
        f"source: {source}\n"
        "---"
    )


def call_model(
    api_key: str,
    endpoint: str,
    model: str,
    body: str,
    retries: int,
    title_zh: str,
    title_en: str,
) -> str:
    payload = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": (
                    f"当前英雄是 {title_zh}（{title_en}）。"
                    f"输出第一行必须严格为：# {title_zh}（{title_en}）\n\n{body}"
                ),
            },
        ],
        "temperature": 0.1,
        "max_tokens": 16384,
    }, ensure_ascii=False).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            req = request.Request(
                endpoint,
                data=payload,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                method="POST",
            )
            with request.urlopen(req, timeout=300) as response:
                data = json.loads(response.read().decode("utf-8"))
            content = data["choices"][0]["message"]["content"].strip()
            if content.startswith("```"):
                content = re.sub(r"^```(?:markdown)?\s*", "", content)
                content = re.sub(r"\s*```$", "", content)
            return content.strip()
        except (error.URLError, error.HTTPError, KeyError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(min(30, 2 ** attempt))
    raise RuntimeError(f"model call failed after {retries} attempts: {last_error}")


def validate_translation(source: str, translated: str, title_zh: str) -> None:
    if not translated.startswith(f"# {title_zh}"):
        raise ValueError(f"translated H1 must be '# {title_zh}'")
    if translated.count("### ") != source.count("### "):
        raise ValueError("H3 count changed during translation")
    if "## 技能" not in translated and "## 能力" not in translated:
        raise ValueError("translated Abilities heading is missing")
    if len(translated) < len(source) * 0.35:
        raise ValueError("translated output is unexpectedly short")
    source_numbers = re.findall(r"\d+(?:\.\d+)?", source)
    translated_numbers = re.findall(r"\d+(?:\.\d+)?", translated)
    if len(translated_numbers) < len(source_numbers) * 0.9:
        raise ValueError("too many numeric values disappeared during translation")


def translate_file(path: Path, api_key: str, endpoint: str, model: str, retries: int) -> None:
    raw = path.read_text(encoding="utf-8")
    metadata, body = split_frontmatter(raw)
    if metadata.get("language") == "zh-CN":
        print(f"SKIP {path.name}: already zh-CN")
        return
    title_en = metadata.get("title", path.stem)
    title_zh = LEGEND_NAMES.get(title_en)
    if not title_zh:
        raise ValueError(f"no Chinese Legend name mapping for {title_en}")
    print(f"TRANSLATE {path.name}: {title_en} -> {title_zh}", flush=True)
    translated = call_model(api_key, endpoint, model, body, retries, title_zh, title_en)
    validate_translation(body, translated, title_zh)
    result = render_frontmatter(title_zh, title_en, metadata.get("source", ""))
    result += "\n\n" + translated.rstrip() + "\n"
    temp_path = path.with_suffix(path.suffix + ".zh.tmp")
    temp_path.write_text(result, encoding="utf-8", newline="\n")
    temp_path.replace(path)
    print(f"OK {path.name}: {len(body)} -> {len(translated)} chars", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Translate curated PC Legend Markdown to zh-CN")
    parser.add_argument("--dir", default="rag-data/data/legends")
    parser.add_argument("--file", action="append", help="translate only this filename; repeatable")
    parser.add_argument("--model", default=os.getenv("DASHSCOPE_TRANSLATION_MODEL", "qwen-plus"))
    parser.add_argument(
        "--endpoint",
        default=os.getenv(
            "DASHSCOPE_CHAT_ENDPOINT",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        ),
    )
    parser.add_argument("--retries", type=int, default=4)
    args = parser.parse_args()

    api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        print("DASHSCOPE_API_KEY is required", file=sys.stderr)
        return 2
    root = Path(args.dir)
    paths = [root / name for name in args.file] if args.file else sorted(root.glob("*.md"))
    expected = set(LEGEND_NAMES)
    actual = {path.stem for path in root.glob("*.md")}
    if actual != expected:
        print("PC roster mismatch; missing=" + ",".join(sorted(expected - actual)), file=sys.stderr)
        print("unexpected=" + ",".join(sorted(actual - expected)), file=sys.stderr)
        return 2
    failed = 0
    for path in paths:
        try:
            translate_file(path, api_key, args.endpoint, args.model, args.retries)
        except Exception as exc:  # continue so a later rerun only retries failed files
            failed += 1
            print(f"FAIL {path.name}: {exc}", file=sys.stderr, flush=True)
    print(f"Finished: total={len(paths)}, failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
