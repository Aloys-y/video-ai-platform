from __future__ import annotations

import json
import random
from pathlib import Path
from typing import Any, Iterable

SYSTEM_PROMPT = """你是一位严谨的 Apex 英雄复盘教练。你必须只依据给定画面分析，不能猜测画面无法确认的英雄、枪械、敌方数量、队友意图或版本数值。
输出只能是一个 JSON 对象，不要输出 Markdown 或代码围栏。字段固定为：
schema_version="apex_review.v1"；clip_summary 字符串；result 为 win_fight/lose_fight/disengage/rotation/loot/exploration/unknown 之一；
events 数组元素含 start_sec、end_sec、type、observation、impact、confidence；
reviews 数组元素含 category、verdict、severity、evidence、reason、suggestion；
positive_actions 字符串数组；uncertainties 字符串数组。
event.type 只能是 engagement/knock/elimination/damage_taken/ability_use/heal/shield_swap/reload/loot/rotation/revive/respawn/ring/third_party/other。
review.category 只能是 aim/positioning/cover/movement/resource/ability/information/rotation/teamfight/decision；verdict 只能是 good/mistake/neutral；severity 为 1 到 3。
每条复盘结论必须给出时间点或明确画面动作作为 evidence，并给出下一次能执行的 suggestion。无法确认的信息放入 uncertainties。"""


def load_yaml(path: str | Path) -> dict[str, Any]:
    import yaml

    with Path(path).open("r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with Path(path).open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no} 不是合法 JSON: {exc}") from exc
    return records


def write_jsonl(path: str | Path, records: Iterable[dict[str, Any]]) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


def seed_everything(seed: int) -> None:
    random.seed(seed)
    try:
        import numpy as np

        np.random.seed(seed)
    except ImportError:
        pass


def resolve_media_path(value: str, dataset_path: str | Path) -> Path:
    path = Path(value)
    if path.is_absolute():
        return path
    return (Path(dataset_path).resolve().parent / path).resolve()


def as_file_uri(path: Path) -> str:
    return path.resolve().as_uri()


def answer_text(record: dict[str, Any]) -> str:
    return json.dumps(record["answer"], ensure_ascii=False, separators=(",", ":"))


def build_messages(
    record: dict[str, Any], dataset_path: str | Path, include_answer: bool
) -> list[dict[str, Any]]:
    frame_urls = [
        as_file_uri(resolve_media_path(frame, dataset_path)) for frame in record["frames"]
    ]
    timestamps = record["frame_timestamps_sec"]
    time_context = (
        f"片段时长 {record['duration_sec']:.2f} 秒；输入帧按顺序对应片段内时间："
        + ", ".join(f"{value:.2f}s" for value in timestamps)
        + "。时间判断只能落在这些观测附近，不要假装连续看到了未提供的画面。\n"
    )
    user_content: list[dict[str, Any]] = [
        {"type": "video", "video": frame_urls},
        {"type": "text", "text": time_context + record["instruction"]},
    ]
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": [{"type": "text", "text": SYSTEM_PROMPT}]},
        {"role": "user", "content": user_content},
    ]
    if include_answer:
        messages.append(
            {"role": "assistant", "content": [{"type": "text", "text": answer_text(record)}]}
        )
    return messages


def extract_json_object(text: str) -> tuple[dict[str, Any] | None, str | None]:
    cleaned = text.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[7:]
    elif cleaned.startswith("```"):
        cleaned = cleaned[3:]
    if cleaned.endswith("```"):
        cleaned = cleaned[:-3]
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end <= start:
        return None, "未找到 JSON 对象"
    try:
        value = json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError as exc:
        return None, str(exc)
    if not isinstance(value, dict):
        return None, "JSON 根节点不是对象"
    return value, None


def pixel_bounds(config: dict[str, Any]) -> tuple[int, int]:
    vision = config["vision"]
    patch_area = 28 * 28
    return vision["min_pixel_tokens"] * patch_area, vision["max_pixel_tokens"] * patch_area
