"""
Apex Legends Wiki RAG Data Collector v3
使用 action=parse API 获取完整 HTML，再转纯文本，内容量是 explaintext 的 6~20 倍。
"""
import requests
import json
import time
import re
import sys
import bs4
from pathlib import Path
from urllib.parse import quote
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# Windows GBK 编码问题处理
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

API_BASE = "https://apexlegends.fandom.com/api.php"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RAGDataCollector/1.0 (study project)"
}
OUTPUT_DIR = Path(__file__).parent / "data"
DELAY = 0.4
CONCURRENCY = 5  # parse API 更重，降低并发
PRINT_LOCK = Lock()

ROOT_CATEGORIES = [
    "Apex_Legends_Legends", "Legends", "Characters", "Major_characters", "Minor_characters",
    "Weapons", "Maps", "Locations", "Map_features",
    "Gameplay_mechanics", "Items_in_Apex_Legends", "Attachments", "Gear", "Abilities",
    "Game_modes", "Lore", "Legend_lore", "Books", "Comics", "Animated_shorts",
    "Events", "Collection_events", "Badges", "Finishers", "Cosmetics",
    "Factions", "Easter_eggs", "Guides",
]

EXCLUDE_PATTERNS = [
    r"^Category:", r"^Template:", r"^File:", r"^Help:",
    r"^Apex Legends Wiki",
    r"/tr$", r"/pl$", r"/de$", r"/fr$", r"/es$", r"/ja$", r"/zh$",
]

SKIP_SUBCATS = re.compile(
    r"images|audio|icons|skin |skins|voice.line|holospray|banner|"
    r"avatar|badge|charm|emoji|clip|templates|hidden|candidates|"
    r"citation.needed|cleanup|copyright|documentation|formatting|"
    r"infobox|legal|meta|module|indexed.pages|disambiguation",
    re.IGNORECASE
)

def should_exclude(title):
    for pattern in EXCLUDE_PATTERNS:
        if re.search(pattern, title):
            return True
    return False

def safe_print(*args, **kwargs):
    with PRINT_LOCK:
        print(*args, **kwargs)

def api_call(params):
    resp = requests.get(API_BASE, params=params, headers=HEADERS, timeout=60)
    resp.raise_for_status()
    return resp.json()

def get_category_members(cat_name, member_type=None):
    members = []
    params = {
        "action": "query", "list": "categorymembers",
        "cmtitle": f"Category:{cat_name}", "cmlimit": 500, "format": "json",
    }
    if member_type:
        params["cmtype"] = member_type
    while True:
        try:
            data = api_call(params)
            for cm in data.get("query", {}).get("categorymembers", []):
                members.append(cm)
            if "continue" in data:
                params.update(data["continue"])
            else:
                break
        except Exception as e:
            safe_print(f"  ERROR fetching {cat_name}: {e}")
            break
        time.sleep(DELAY)
    return members

def discover_all_pages():
    all_pages = {}
    processed_cats = set()
    cats_to_process = list(ROOT_CATEGORIES)

    safe_print(f"\n{'='*60}")
    safe_print(f"PHASE 1: 发现页面 (从 {len(ROOT_CATEGORIES)} 个根分类)...")
    safe_print(f"{'='*60}")

    while cats_to_process:
        cat = cats_to_process.pop(0)
        if cat in processed_cats:
            continue
        processed_cats.add(cat)

        subcats = get_category_members(cat, member_type="subcat")
        for sc in subcats:
            sc_name = sc["title"].replace("Category:", "")
            if sc_name not in processed_cats and sc_name not in cats_to_process:
                if not SKIP_SUBCATS.search(sc_name):
                    cats_to_process.append(sc_name)
        if subcats:
            safe_print(f"  [{cat}] {len(subcats)} 个子分类, 队列 {len(cats_to_process)} 个待处理")

        pages = get_category_members(cat, member_type="page")
        added = 0
        for p in pages:
            pid = p["pageid"]
            title = p["title"]
            if should_exclude(title):
                continue
            if pid not in all_pages:
                all_pages[pid] = {"title": title, "ns": p["ns"], "categories": [cat]}
                added += 1
            else:
                all_pages[pid]["categories"].append(cat)

        safe_print(f"  [{cat}] 新增 {added} 页, 累计 {len(all_pages)} 页")
        time.sleep(DELAY)

    safe_print(f"\n发现完成: {len(all_pages)} 个唯一页面, {len(processed_cats)} 个分类")
    return all_pages

def extract_clean_text(html):
    """从 parse API 返回的 HTML 中提取干净文本。"""
    soup = bs4.BeautifulSoup(html, 'html.parser')
    main = soup.find('div', class_='mw-parser-output')
    if not main:
        return ""

    # 移除不需要的元素
    for tag in main.find_all(['style', 'script', 'noscript', 'img', 'svg']):
        tag.decompose()
    # 移除导航框、目录、编辑链接等
    unwanted_classes = ['navbox', 'navbox-styles', 'noprint', 'mw-editsection',
                        'toc', 'reflist', 'refbegin', 'catlinks', 'mw-empty-elt',
                        'ambox', 'ombox', 'mbox', 'dablink', 'shortdescription',
                        'mw-authority-control', 'sidebar', 'sisterproject',
                        'metadata', 'stub', 'portal', 'infobox-image',
                        'hiddencategories', 'printfooter', 'mw-references-wrap']
    for cls_name in unwanted_classes:
        for tag in main.find_all(class_=re.compile(cls_name)):
            tag.decompose()
    # 移除 role=navigation, note 等
    for role in ['navigation', 'note']:
        for tag in main.find_all(attrs={'role': role}):
            tag.decompose()
    # 移除 aside 元素（通常是非核心内容）
    for tag in main.find_all('aside'):
        tag.decompose()

    # 替换表格为文本
    for table in main.find_all('table'):
        rows = []
        for tr in table.find_all('tr'):
            cells = [td.get_text(strip=True) for td in tr.find_all(['th', 'td'])]
            if cells:
                rows.append(' | '.join(cells))
        if rows:
            table_text = '\n'.join(rows)
            table.replace_with(bs4.BeautifulSoup(f'\n{table_text}\n', 'html.parser'))

    # 替换列表项增加缩进
    for li in main.find_all('li'):
        text = li.get_text(strip=True)
        li.replace_with(bs4.BeautifulSoup(f'\n- {text}', 'html.parser'))

    # 标题保留层级
    for level in range(2, 7):
        for h in main.find_all(f'h{level}'):
            text = h.get_text(strip=True)
            prefix = '#' * level
            h.replace_with(bs4.BeautifulSoup(f'\n\n{prefix} {text}\n\n', 'html.parser'))

    # 段落保留换行
    for br in main.find_all('br'):
        br.replace_with('\n')
    for p in main.find_all('p'):
        p.replace_with(bs4.BeautifulSoup(f'\n{p.get_text(strip=True)}\n', 'html.parser'))
    for div in main.find_all('div'):
        div.replace_with(bs4.BeautifulSoup(f'\n{div.get_text(strip=True)}\n', 'html.parser'))

    text = main.get_text(separator='\n', strip=True)

    # 清理
    text = re.sub(r'\n{3,}', '\n\n', text)
    text = re.sub(r'\[edit\]|\[citation needed\]|\[note \d+\]', '', text)
    text = re.sub(r'^[ \t]+', '', text, flags=re.MULTILINE)
    # 移除过短行（通常是残余的样式代码）
    text = re.sub(r'^\s*\n', '\n', text, flags=re.MULTILINE)

    return text.strip()

def fetch_one_parsed_page(title):
    """使用 action=parse 获取完整页面 HTML，再提取干净文本。"""
    try:
        data = api_call({
            "action": "parse", "page": title,
            "prop": "text", "format": "json",
        })
        html = data.get("parse", {}).get("text", {}).get("*", "")
        if not html:
            return (title, None)
        text = extract_clean_text(html)
        if text and len(text) >= 100:
            return (title, {"title": title, "text": text})
    except Exception as e:
        safe_print(f"    ERROR parse '{title}': {e}")
    return (title, None)

def fetch_one_page_categories(title):
    """获取单个页面的分类。"""
    try:
        data = api_call({
            "action": "query", "prop": "categories", "cllimit": 50,
            "titles": title, "format": "json",
        })
        pages = data.get("query", {}).get("pages", {})
        for p, info in pages.items():
            cats = []
            for c in info.get("categories", []):
                name = c["title"].replace("Category:", "")
                if not SKIP_SUBCATS.search(name):
                    cats.append(name)
            return (title, cats)
    except Exception as e:
        safe_print(f"    ERROR cat '{title}': {e}")
    return (title, [])

def fetch_with_progress(items, fetch_fn, desc):
    results = {}
    total = len(items)
    completed = 0
    valid = 0

    safe_print(f"\n{'='*60}")
    safe_print(f"{desc} ({total} items)...")
    safe_print(f"{'='*60}")

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
        futures = {executor.submit(fetch_fn, item): item for item in items}
        for future in as_completed(futures):
            completed += 1
            try:
                key, value = future.result()
                if value is not None:
                    results[key] = value
                    valid += 1
            except Exception:
                pass
            if completed % 10 == 0 or completed == total:
                safe_print(f"  进度: {completed}/{total} (有效: {valid})")
            time.sleep(0.05)
    safe_print(f"  完成: {completed}/{total} (有效: {valid})")
    return results

def clean_text(text):
    text = re.sub(r'\n{4,}', '\n\n\n', text)
    return text.strip()

def determine_topic(title, categories):
    combined = (title + " " + " ".join(categories)).lower()
    # 武器优先
    if any(x in combined for x in [
        "weapon", "sniper", "rifle", "shotgun", "pistol",
        "smg", "lmg", "repeater", "bow", "carbine",
        "r-99", "r-301", "flatline", "hemlok", "devotion", "spitfire",
        "kraber", "longbow", "wingman", "peacekeeper", "mastiff",
        "eva-8", "mozambique", "p2020", "re-45", "alternator",
        "volt", "prowler", "car", "g7", "l-star", "havoc", "sentinel",
        "charge rifle", "bocek", "30-30", "turbocharger", "skullpiercer",
        "hammerpoint", "disruptor",
    ]):
        return "weapons"
    elif any(x in combined for x in [
        "map", "location", "map_feature",
        "kings canyon", "world's edge", "olympus", "storm point", "broken moon",
    ]):
        return "maps"
    elif any(x in combined for x in [
        "lore", "book", "comic", "animated_short", "story", "chapter",
        "chronicles", "side story",
    ]):
        return "lore"
    elif any(x in combined for x in [
        "event", "collection", "battle pass", "season",
        "limited time", "takeover", "themed event",
    ]):
        return "events"
    elif any(x in combined for x in [
        "mode", "game_mode", "arenas", "battle royale",
        "ranked", "duos", "trios", "mixtape",
    ]):
        return "game_modes"
    elif any(x in combined for x in [
        "gameplay", "mechanic", "item", "attachment",
        "gear", "ability", "ammo", "hop-up",
    ]):
        return "gameplay"
    elif any(x in combined for x in [
        "badge", "finisher", "cosmetic", "skin ", "skins",
        "heirloom", "charm",
    ]):
        return "cosmetics"
    elif any(x in combined for x in [
        "legend lore", "playable legend", "character",
        "female legends", "male legends", "lgbt",
        "apex_legends_legends", "skirmisher", "assault class",
        "support class", "recon class", "controller class",
    ]):
        return "legends"
    elif any(x in combined for x in ["legend"]):
        weapon_kw = ["rifle", "smg", "shotgun", "pistol", "lmg", "sniper",
            "repeater", "bow", "r-99", "r-301", "flatline", "hemlok",
            "devotion", "spitfire", "kraber", "longbow", "wingman",
            "peacekeeper", "mastiff", "eva", "mozambique", "p2020",
            "re-45", "alternator", "volt", "prowler", "car",
            "charge rifle", "bocek", "g7 scout", "l-star", "sentinel"]
        if not any(x in title.lower() for x in weapon_kw):
            return "legends"
        else:
            return "weapons"
    else:
        return "general"

def save_rag_data(extracts, page_cats):
    safe_print(f"\n{'='*60}")
    safe_print(f"PHASE 4: 保存 RAG 数据...")
    safe_print(f"{'='*60}")

    topics = ["legends", "weapons", "maps", "lore", "events",
              "game_modes", "gameplay", "cosmetics", "general"]
    for topic in topics:
        (OUTPUT_DIR / topic).mkdir(parents=True, exist_ok=True)

    jsonl_path = OUTPUT_DIR / "rag_corpus.jsonl"
    index = []
    saved_count = 0

    with open(jsonl_path, "w", encoding="utf-8") as jf:
        for title, data in sorted(extracts.items(), key=lambda x: x[0]):
            text = clean_text(data["text"])
            if len(text) < 100:
                continue

            cats = page_cats.get(title, [])
            topic = determine_topic(title, cats)

            safe_name = re.sub(r'[<>:"/\\|?*]', "_", title)
            md_path = OUTPUT_DIR / topic / f"{safe_name}.md"

            frontmatter = f"""---
title: "{title}"
topic: {topic}
categories: {json.dumps(cats[:10])}
source: https://apexlegends.fandom.com/wiki/{quote(title.replace(' ', '_'))}
---

"""
            with open(md_path, "w", encoding="utf-8") as mf:
                mf.write(frontmatter)
                mf.write(f"# {title}\n\n")
                mf.write(text)

            record = {
                "title": title, "topic": topic,
                "categories": cats[:10], "text": text,
                "source_url": f"https://apexlegends.fandom.com/wiki/{quote(title.replace(' ', '_'))}",
            }
            jf.write(json.dumps(record, ensure_ascii=False) + "\n")

            index.append({
                "title": title, "topic": topic,
                "categories": cats[:5], "text_length": len(text),
                "file": f"data/{topic}/{safe_name}.md",
            })
            saved_count += 1

    with open(OUTPUT_DIR / "index.json", "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)

    safe_print(f"保存完成: {saved_count} 个页面")
    safe_print(f"\n{'='*60}")
    safe_print("各主题统计:")
    for topic in topics:
        items = [it for it in index if it["topic"] == topic]
        if items:
            total_chars = sum(it["text_length"] for it in items)
            safe_print(f"  {topic:15s}: {len(items):4d} 页面, {total_chars:>12,d} 字符")

def main():
    print("=" * 60)
    print("Apex Legends Wiki - RAG 数据收集器 v3 (parse API)")
    print("=" * 60)

    # Phase 1: 发现页面
    pages = discover_all_pages()
    if not pages:
        print("ERROR: 未发现任何页面！")
        return

    # 构建 title -> pid 映射
    titles = [info["title"] for info in pages.values()]

    # Phase 2: parse 页面内容（用 title，不用 pageid）
    extracts = fetch_with_progress(titles, fetch_one_parsed_page, "PHASE 2: 解析页面内容")
    if not extracts:
        print("ERROR: 未获取到任何内容！")
        return

    # Phase 3: 获取分类
    page_cats = fetch_with_progress(list(extracts.keys()), fetch_one_page_categories, "PHASE 3: 获取分类元数据")

    # Phase 4: 保存
    save_rag_data(extracts, page_cats)
    print(f"\n完成！数据保存在: {OUTPUT_DIR.absolute()}")

if __name__ == "__main__":
    main()
