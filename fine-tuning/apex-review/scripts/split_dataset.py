from __future__ import annotations

import argparse
import random
from collections import defaultdict
from pathlib import Path

from common import read_jsonl, write_jsonl


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="按 source_match_id 分组划分数据，杜绝同局泄漏")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--val-ratio", type=float, default=0.15)
    parser.add_argument("--test-ratio", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def group_count(total: int, ratio: float) -> int:
    return max(1, min(total - 2, round(total * ratio)))


def main() -> None:
    args = parse_args()
    if args.val_ratio <= 0 or args.test_ratio <= 0 or args.val_ratio + args.test_ratio >= 1:
        raise SystemExit("val/test 比例必须大于 0，且两者之和小于 1")
    groups: dict[str, list[dict]] = defaultdict(list)
    for record in read_jsonl(args.input):
        groups[record["source_match_id"]].append(record)
    if len(groups) < 3:
        raise SystemExit("至少需要 3 个独立 source_match_id 才能划分 train/val/test")

    group_ids = sorted(groups)
    random.Random(args.seed).shuffle(group_ids)
    test_count = group_count(len(group_ids), args.test_ratio)
    remaining = len(group_ids) - test_count
    val_count = max(1, min(remaining - 1, round(len(group_ids) * args.val_ratio)))
    test_ids = set(group_ids[:test_count])
    val_ids = set(group_ids[test_count : test_count + val_count])

    splits: dict[str, list[dict]] = {"train": [], "val": [], "test": []}
    for group_id in group_ids:
        split = "test" if group_id in test_ids else "val" if group_id in val_ids else "train"
        for source in groups[group_id]:
            record = dict(source)
            record["split"] = split
            splits[split].append(record)

    output_dir = Path(args.output_dir)
    for split, records in splits.items():
        write_jsonl(output_dir / f"{split}.jsonl", records)
        distinct = len({item["source_match_id"] for item in records})
        print(f"{split}: {len(records)} clips / {distinct} matches")
    write_jsonl(output_dir / "all.jsonl", sum(splits.values(), []))


if __name__ == "__main__":
    main()
