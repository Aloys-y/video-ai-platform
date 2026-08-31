#!/usr/bin/env python3
"""Build a PC-gameplay-only Legend corpus from the scraped Wiki files.

The raw scraper can rediscover Mobile, lore, localization and helper pages. This
script makes the destructive cleanup explicit and repeatable: keep only the
official PC Legend allowlist, retain gameplay metadata plus the Abilities
section, and remove standalone lore and stale aggregate files.
"""

import argparse
from pathlib import Path


PC_LEGENDS = (
    "Alter", "Ash", "Axle", "Ballistic", "Bangalore", "Bloodhound",
    "Catalyst", "Caustic", "Conduit", "Crypto", "Fuse", "Gibraltar",
    "Horizon", "Lifeline", "Loba", "Mad Maggie", "Mirage", "Newcastle",
    "Octane", "Pathfinder", "Rampart", "Revenant", "Seer", "Sparrow",
    "Valkyrie", "Vantage", "Wattson", "Wraith",
)


def split_frontmatter(raw: str) -> tuple[str, str]:
    normalized = raw.replace("\r\n", "\n")
    if not normalized.startswith("---\n"):
        return "", normalized.strip()
    end = normalized.find("\n---\n", 4)
    if end < 0:
        raise ValueError("unterminated YAML frontmatter")
    return normalized[:end + 4].strip(), normalized[end + 5:].strip()


def curate_legend_markdown(raw: str) -> str:
    frontmatter, body = split_frontmatter(raw)
    lines = body.splitlines()
    title_index = next((i for i, line in enumerate(lines) if line.startswith("# ")), None)
    abilities_headings = {"## Abilities", "## 技能", "## 能力"}
    abilities_index = next(
        (i for i, line in enumerate(lines) if line.strip() in abilities_headings),
        None,
    )
    if title_index is None or abilities_index is None:
        raise ValueError("missing H1 title or Abilities/技能 section")

    abilities_end = next(
        (i for i in range(abilities_index + 1, len(lines)) if lines[i].startswith("## ")),
        len(lines),
    )

    gameplay_lines: list[str] = []
    gameplay_index = next(
        (i for i in range(title_index + 1, abilities_index)
         if lines[i].strip() in {
             "Gameplay", "玩法", "## Gameplay", "## 玩法", "## 玩法概览"
         }),
        None,
    )
    if gameplay_index is not None:
        gameplay_end = next(
            (i for i in range(gameplay_index + 1, abilities_index)
             if lines[i].strip() in {"Real-world Info", "现实信息", "现实世界信息"}),
            abilities_index,
        )
        gameplay_lines = [line for line in lines[gameplay_index + 1:gameplay_end] if line.strip()]

    parts = []
    if frontmatter:
        parts.append(frontmatter)
    parts.append(lines[title_index].strip())
    if gameplay_lines:
        original_heading = lines[gameplay_index].strip()
        gameplay_heading = "## 玩法概览" if "玩法" in original_heading else "## Gameplay"
        parts.append(gameplay_heading + "\n" + "\n".join(gameplay_lines))
    parts.append("\n".join(lines[abilities_index:abilities_end]).strip())
    return "\n\n".join(parts).strip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Curate PC Legend gameplay Markdown")
    parser.add_argument("--data-dir", default="rag-data/data")
    parser.add_argument("--apply", action="store_true", help="write changes and delete excluded files")
    args = parser.parse_args()

    data_dir = Path(args.data_dir).resolve()
    legends_dir = data_dir / "legends"
    lore_dir = data_dir / "lore"
    allowed = set(PC_LEGENDS)
    legend_files = sorted(legends_dir.glob("*.md"))
    keep = [path for path in legend_files if path.stem in allowed]
    remove = [path for path in legend_files if path.stem not in allowed]
    missing = sorted(allowed - {path.stem for path in keep})
    lore_files = sorted(lore_dir.glob("*.md")) if lore_dir.exists() else []
    aggregates = [path for path in (data_dir / "index.json", data_dir / "rag_corpus.jsonl") if path.exists()]

    print(f"PC Legend files: keep={len(keep)}, remove={len(remove)}, missing={len(missing)}")
    if missing:
        print("Missing required Legends: " + ", ".join(missing))
    print(f"Standalone lore files to remove: {len(lore_files)}")
    print(f"Stale aggregate files to remove: {len(aggregates)}")
    if not args.apply:
        print("Dry run only; pass --apply to modify files.")
        return 0

    for path in keep:
        curated = curate_legend_markdown(path.read_text(encoding="utf-8"))
        path.write_text(curated, encoding="utf-8", newline="\n")
    for path in remove + lore_files + aggregates:
        path.unlink()
    print(f"Curated {len(keep)} PC Legend files and deleted {len(remove) + len(lore_files) + len(aggregates)} files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
