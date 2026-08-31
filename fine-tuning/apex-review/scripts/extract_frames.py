from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from common import read_jsonl, write_jsonl


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="按片段时间范围均匀抽取 APEX 视频帧")
    parser.add_argument("--input", required=True, help="原始 manifest JSONL")
    parser.add_argument("--output", required=True, help="含 frames 字段的 JSONL")
    parser.add_argument("--frames-root", required=True, help="抽帧输出目录")
    parser.add_argument("--num-frames", type=int, default=8)
    parser.add_argument("--jpeg-quality", type=int, default=92)
    return parser.parse_args()


def video_path(record: dict, manifest: Path) -> Path:
    value = Path(record["source_video_path"])
    return value if value.is_absolute() else (manifest.parent / value).resolve()


def extract_one(
    record: dict, manifest: Path, frames_root: Path, count: int, quality: int
) -> tuple[list[str], list[float]]:
    source = video_path(record, manifest)
    if not source.is_file():
        raise FileNotFoundError(f"{record['clip_id']}: 视频不存在: {source}")
    capture = cv2.VideoCapture(str(source))
    fps = capture.get(cv2.CAP_PROP_FPS)
    total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    if fps <= 0 or total_frames <= 0:
        capture.release()
        raise ValueError(f"{record['clip_id']}: 无法读取视频元数据: {source}")

    start = max(0, int(round(float(record["source_start_sec"]) * fps)))
    requested_end = int(round((float(record["source_start_sec"]) + float(record["duration_sec"])) * fps))
    end = min(total_frames, max(start + 1, requested_end))
    indices = np.linspace(start, end - 1, num=count, dtype=int)
    clip_dir = frames_root / record["clip_id"]
    clip_dir.mkdir(parents=True, exist_ok=True)
    paths: list[str] = []
    timestamps: list[float] = []
    for sequence, frame_index in enumerate(indices):
        capture.set(cv2.CAP_PROP_POS_FRAMES, int(frame_index))
        ok, frame = capture.read()
        if not ok:
            capture.release()
            raise RuntimeError(f"{record['clip_id']}: 第 {frame_index} 帧读取失败")
        path = clip_dir / f"{sequence:02d}_{frame_index:08d}.jpg"
        if not cv2.imwrite(str(path), frame, [cv2.IMWRITE_JPEG_QUALITY, quality]):
            capture.release()
            raise RuntimeError(f"{record['clip_id']}: 写入失败: {path}")
        paths.append(str(path.resolve()))
        timestamps.append(round(max(0.0, frame_index / fps - float(record["source_start_sec"])), 3))
    capture.release()
    return paths, timestamps


def main() -> None:
    args = parse_args()
    if not 4 <= args.num_frames <= 16:
        raise SystemExit("--num-frames 必须在 4 到 16 之间")
    manifest = Path(args.input).resolve()
    records = read_jsonl(manifest)
    output: list[dict] = []
    for index, record in enumerate(records, 1):
        record = dict(record)
        record["frames"], record["frame_timestamps_sec"] = extract_one(
            record, manifest, Path(args.frames_root).resolve(), args.num_frames, args.jpeg_quality
        )
        output.append(record)
        print(f"[{index}/{len(records)}] {record['clip_id']}: {len(record['frames'])} 帧")
    write_jsonl(args.output, output)
    print(f"已写入 {args.output}")


if __name__ == "__main__":
    main()
