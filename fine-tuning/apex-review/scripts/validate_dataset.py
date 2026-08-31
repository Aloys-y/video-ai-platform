from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

import jsonschema

from common import read_jsonl, resolve_media_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="校验 APEX 复盘数据结构、媒体与跨集泄漏")
    parser.add_argument("files", nargs="+", help="一个或多个 JSONL")
    parser.add_argument("--schema", required=True)
    parser.add_argument("--skip-media-check", action="store_true")
    return parser.parse_args()


def media_error(frame: str, dataset_path: Path) -> str | None:
    import cv2

    path = resolve_media_path(frame, dataset_path)
    if not path.is_file():
        return f"帧不存在: {path}"
    image = cv2.imread(str(path))
    if image is None or image.size == 0:
        return f"帧不可读: {path}"
    return None


def main() -> None:
    args = parse_args()
    import json

    schema = json.loads(Path(args.schema).read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)
    errors: list[str] = []
    clip_locations: dict[str, str] = {}
    match_splits: dict[str, set[str]] = defaultdict(set)
    all_match_ids: set[str] = set()
    total = 0

    for file_name in args.files:
        path = Path(file_name).resolve()
        implied_split = path.stem if path.stem in {"train", "val", "test"} else None
        for line_no, record in enumerate(read_jsonl(path), 1):
            total += 1
            location = f"{path}:{line_no}"
            for error in validator.iter_errors(record):
                field = ".".join(str(part) for part in error.absolute_path) or "$"
                errors.append(f"{location} [{field}] {error.message}")
            clip_id = record.get("clip_id")
            if clip_id in clip_locations:
                errors.append(f"{location} clip_id 重复；首次出现于 {clip_locations[clip_id]}")
            elif clip_id:
                clip_locations[clip_id] = location
            split = record.get("split") or implied_split
            match_id = record.get("source_match_id")
            if match_id:
                all_match_ids.add(match_id)
            if split and match_id:
                match_splits[match_id].add(split)
            duration = record.get("duration_sec")
            if isinstance(duration, (int, float)):
                timestamps = record.get("frame_timestamps_sec", [])
                if len(timestamps) != len(record.get("frames", [])):
                    errors.append(f"{location} frames 与 frame_timestamps_sec 数量不一致")
                if timestamps != sorted(timestamps):
                    errors.append(f"{location} frame_timestamps_sec 不是递增顺序")
                if timestamps and timestamps[-1] > duration:
                    errors.append(f"{location} 帧时间戳超出片段时长 {duration}s")
                for event_index, event in enumerate(record.get("answer", {}).get("events", [])):
                    if event.get("start_sec", 0) > event.get("end_sec", 0):
                        errors.append(f"{location} events[{event_index}] start_sec 大于 end_sec")
                    if event.get("end_sec", 0) > duration:
                        errors.append(f"{location} events[{event_index}] 超出片段时长 {duration}s")
            if not args.skip_media_check:
                for frame in record.get("frames", []):
                    issue = media_error(frame, path)
                    if issue:
                        errors.append(f"{location} {issue}")

    for match_id, splits in match_splits.items():
        if len(splits) > 1:
            errors.append(f"source_match_id={match_id} 跨集合泄漏: {sorted(splits)}")
    if errors:
        print("校验失败：")
        for error in errors[:100]:
            print(f"- {error}")
        if len(errors) > 100:
            print(f"...另有 {len(errors) - 100} 条")
        raise SystemExit(1)
    print(f"校验通过：{total} 条样本，{len(all_match_ids)} 场独立对局，无跨集泄漏。")


if __name__ == "__main__":
    main()
