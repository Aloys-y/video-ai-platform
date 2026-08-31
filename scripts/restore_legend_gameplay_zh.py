#!/usr/bin/env python3
"""Restore Chinese gameplay summaries from the original scraped Legend pages.

This is a deterministic recovery step: source fields come from Git, while
Chinese ability names are resolved from the already translated card body.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path


CLASS_NAMES = {
    "Assault": "突击型",
    "Controller": "控制型",
    "Recon": "侦察型",
    "Skirmisher": "游击型",
    "Support": "支援型",
}

FIELD_NAMES = {
    "Class": "职业",
    "Tactical Ability": "战术技能",
    "Passive Ability": "被动技能",
    "Ultimate Ability": "终极技能",
    "Passive Perk": "被动特性",
}

# The scraped infobox is stale for these two fields; the translated ability
# sections contain their current names and are the authoritative local source.
ABILITY_OVERRIDES = {
    ("Ash", "File:Charged Knock.svgCharged Knock"): "判决标记（Marked for Death）",
    ("Caustic", "Nox Vision"): "实地研究（Field Research）",
    ("Rampart", "Modded Loader"): "战斗改装师（Battle Modder）",
}


def git_source_path(stem: str) -> str:
    if stem == "Bangalore":
        return "rag-data/data/lore/Bangalore.md"
    return f"rag-data/data/legends/{stem}.md"


def read_original(stem: str) -> str:
    result = subprocess.run(
        ["git", "show", f"HEAD:{git_source_path(stem)}"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.stdout.replace("\r\n", "\n")


def extract_gameplay_rows(raw: str) -> list[tuple[str, str]]:
    lines = raw.splitlines()
    try:
        start = lines.index("Gameplay") + 1
        end = lines.index("Real-world Info", start)
    except ValueError as exc:
        raise ValueError("original page has no Gameplay infobox") from exc
    rows = []
    for line in lines[start:end]:
        if " | " not in line:
            continue
        field, value = line.split(" | ", 1)
        if field in FIELD_NAMES:
            rows.append((field, value.strip()))
    return rows


def translated_ability_names(current: str) -> dict[str, str]:
    names: dict[str, str] = {}
    lines = current.splitlines()
    # Headings are authoritative. This avoids accidentally treating an earlier
    # prose mention such as “使用维度裂隙（Dimensional Rift）” as the name.
    for line in lines:
        match = re.fullmatch(r"###\s+(.+?)（([^（）\n]+)）\s*", line.strip())
        if match:
            names[match.group(2).strip()] = (
                f"{match.group(1).strip()}（{match.group(2).strip()}）"
            )
    # Sparrow's three ability blocks do not have H3 headings in the scraped
    # page, but each translated name still occupies a standalone line.
    for line in lines:
        match = re.fullmatch(r"([^|#\n]{1,80}?)（([^（）\n]+)）\s*", line.strip())
        if not match:
            continue
        chinese = match.group(1).strip()
        english = match.group(2).strip()
        if chinese and re.search(r"[\u4e00-\u9fff]", chinese):
            names.setdefault(english, f"{chinese}（{english}）")
    return names


def build_section(stem: str, raw: str, current: str) -> str:
    rows = extract_gameplay_rows(raw)
    names = translated_ability_names(current)
    rendered = ["## 玩法概览"]
    required = {"Class", "Tactical Ability", "Passive Ability", "Ultimate Ability"}
    seen = set()
    for field, value in rows:
        seen.add(field)
        if field == "Class":
            translated = CLASS_NAMES.get(value)
        else:
            translated = ABILITY_OVERRIDES.get((stem, value), names.get(value))
        if not translated:
            raise ValueError(f"cannot resolve {field}: {value}")
        rendered.append(f"{FIELD_NAMES[field]} | {translated}")
    missing = required - seen
    if missing:
        raise ValueError("missing fields: " + ", ".join(sorted(missing)))
    return "\n".join(rendered)


def restore(path: Path, apply: bool) -> bool:
    current = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    marker = "\n## 技能\n"
    if marker not in current:
        raise ValueError("missing ## 技能 insertion point")
    section = build_section(path.stem, read_original(path.stem), current)
    pattern = re.compile(r"(?ms)^## (?:玩法概览|玩法|Gameplay)\n.*?(?=\n## )")
    existing = list(pattern.finditer(current))
    if existing:
        if len(existing) == 1 and existing[0].group(0).rstrip() == section:
            print(f"SKIP {path.name}: already restored")
            return False
        without_overviews = current
        for match in reversed(existing):
            without_overviews = without_overviews[:match.start()] + without_overviews[match.end():]
        updated = without_overviews.replace(marker, f"\n{section}\n\n## 技能\n", 1)
    else:
        updated = current.replace(marker, f"\n{section}\n\n## 技能\n", 1)
    if apply:
        temp = path.with_suffix(path.suffix + ".gameplay.tmp")
        temp.write_text(updated, encoding="utf-8", newline="\n")
        temp.replace(path)
        print(f"RESTORED {path.name}")
    else:
        print(f"WOULD RESTORE {path.name}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Restore zh-CN Legend gameplay summaries")
    parser.add_argument("--dir", default="rag-data/data/legends")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    paths = sorted(Path(args.dir).glob("*.md"))
    if len(paths) != 28:
        print(f"expected 28 PC Legend files, found {len(paths)}", file=sys.stderr)
        return 2
    changed = 0
    failed = 0
    for path in paths:
        try:
            changed += restore(path, args.apply)
        except Exception as exc:
            failed += 1
            print(f"FAIL {path.name}: {exc}", file=sys.stderr)
    print(f"Finished: files={len(paths)}, changed={changed}, failed={failed}, apply={args.apply}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
