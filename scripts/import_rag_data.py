#!/usr/bin/env python3
"""Import rag-data Markdown files into the RAG knowledge base using the Admin API.

Parses YAML frontmatter (title, topic→category, categories→tags) and calls
/preview → /batch-create for each batch.
"""

import os, json, time, requests, argparse, sys
from pathlib import Path

TOPIC_CATEGORY_MAP = {
    "legends": "LEGEND", "weapons": "WEAPON", "maps": "MAP",
    "lore": "TACTIC", "events": "PATCH", "game_modes": "MECHANIC",
    "gameplay": "MECHANIC", "cosmetics": "TACTIC", "general": "MECHANIC",
}

def parse_frontmatter(text: str):
    """Parse YAML frontmatter, return (metadata_dict, body_str)."""
    if not text.startswith("---"):
        return {}, text
    end = text.find("---", 3)
    if end < 0:
        return {}, text
    fm_text = text[3:end].strip()
    body = text[end + 3:].strip()
    meta = {}
    for line in fm_text.split("\n"):
        line = line.strip()
        if ":" not in line:
            continue
        i = line.index(":")
        key = line[:i].strip()
        val = line[i+1:].strip().strip('"').strip("'")
        if val.startswith("[") and val.endswith("]"):
            val = [v.strip().strip('"').strip("'") for v in val[1:-1].split(",") if v.strip()]
        meta[key] = val
    return meta, body

def login(base_url, email, password):
    r = requests.post(f"{base_url}/auth/login", json={
        "email": email, "password": password}, timeout=10)
    r.raise_for_status()
    data = r.json()
    if not data.get("success"):
        raise Exception(f"Login failed: {data.get('message', data)}")
    return data["data"]["token"]

def preview_files(base_url, token, file_paths, category):
    """Send files to /preview endpoint, return list of card previews with _meta attached."""
    files_payload = []
    meta_map = {}
    for fp in file_paths:
        with open(fp, "r", encoding="utf-8") as f:
            raw = f.read()
        meta, body = parse_frontmatter(raw)
        basename = os.path.basename(fp)
        meta_map[basename] = meta
        files_payload.append(("files", (basename, body, "text/markdown")))

    r = requests.post(f"{base_url}/admin/knowledge/cards/preview",
        headers={"Authorization": f"Bearer {token}"},
        files=files_payload,
        data={"defaultCategory": category, "defaultEnabled": "true",
              "defaultTimeless": "false"},
        timeout=60)
    r.raise_for_status()
    data = r.json()
    if not data.get("success"):
        raise Exception(f"Preview failed: {data.get('message', data)}")
    previews = data["data"]
    # Attach metadata
    for pv in previews:
        meta = meta_map.get(pv.get("fileName", ""), {})
        pv["_meta"] = meta
    return previews

def batch_create(base_url, token, cards):
    """Send confirmed cards to /batch-create, return result."""
    requests_list = []
    for c in cards:
        meta = c.get("_meta", {})
        yaml_title = meta.get("title", "")
        yaml_tags = meta.get("categories", [])
        existing_tags = c.get("tags", [])
        merged = list(dict.fromkeys(
            yaml_tags +
            [t for t in existing_tags if t not in ("imported", "markdown")] +
            ["imported", "markdown"]
        ))

        requests_list.append({
            "cardCode": c["cardCode"],
            "title": (yaml_title[:255] if yaml_title else c["title"]),
            "category": c["category"],
            "subjectCode": c.get("subjectCode"),
            "aliases": c.get("aliases", []),
            "tags": merged[:20],
            "contentMarkdown": c["contentMarkdown"],
            "enabled": True,
            "timeless": False,
        })

    r = requests.post(f"{base_url}/admin/knowledge/cards/batch-create",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json=requests_list, timeout=300)
    r.raise_for_status()
    data = r.json()
    if not data.get("success"):
        raise Exception(f"Batch create failed: {data.get('message', data)}")
    return data["data"]

def main():
    p = argparse.ArgumentParser(description="Import rag-data into knowledge base")
    p.add_argument("--email", default="ragadmin@videoai.com")
    p.add_argument("--pass", dest="password", default="Admin@123456")
    p.add_argument("--dir", default="rag-data/data")
    p.add_argument("--batch", type=int, default=10)
    p.add_argument("--api", default="http://localhost:8080/api")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    base_url = args.api
    root = Path(args.dir)

    # Collect files grouped by topic
    all_files = list(root.rglob("*.md"))
    by_topic = {}
    for fp in all_files:
        with open(fp, "r", encoding="utf-8") as f:
            meta, _ = parse_frontmatter(f.read())
        topic = meta.get("topic", "general")
        by_topic.setdefault(topic, []).append(fp)
    by_topic = dict(sorted(by_topic.items()))

    print(f"Found {len(all_files)} files across {len(by_topic)} topics:")
    for topic, files in by_topic.items():
        cat = TOPIC_CATEGORY_MAP.get(topic, "MECHANIC")
        print(f"  {topic}: {len(files)} files → {cat}")

    if args.dry_run:
        print("\n[Dry run complete — no data imported]")
        return 0

    print("\nLogging in...")
    token = login(base_url, args.email, args.password)

    total_ok, total_fail = 0, 0
    for topic, files in by_topic.items():
        cat = TOPIC_CATEGORY_MAP.get(topic, "MECHANIC")
        print(f"\n{'='*50}")
        print(f"  {topic} ({len(files)} files) → {cat}")
        print(f"{'='*50}")

        for i in range(0, len(files), args.batch):
            batch = files[i:i + args.batch]
            bn = i // args.batch + 1
            tn = (len(files) + args.batch - 1) // args.batch
            try:
                previews = preview_files(base_url, token, batch, cat)
                result = batch_create(base_url, token, previews)
                ok = result.get("successCount", 0)
                fail = result.get("failedCount", 0)
                total_ok += ok
                total_fail += fail
                flag = "[OK]" if fail == 0 else "[WARN]"
                print(f"  {flag} Batch {bn}/{tn}: {ok} ok, {fail} fail  [{total_ok}/{total_ok+total_fail}]")
            except Exception as e:
                total_fail += len(batch)
                print(f"  [FAIL] Batch {bn}/{tn}: {e}")
            time.sleep(1)

    print(f"\n=== DONE: {total_ok} success, {total_fail} failed ===", flush=True)
    return 0 if total_fail == 0 else 2

if __name__ == "__main__":
    sys.exit(main())
