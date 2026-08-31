#!/usr/bin/env python3
"""Export the read-only LEGEND knowledge coverage audit from the Admin API."""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export LEGEND knowledge snapshot and coverage audit")
    parser.add_argument("--api", default=os.getenv("VIDEOAI_API_URL", "http://localhost:8080/api"))
    parser.add_argument("--token", default=os.getenv("VIDEOAI_ADMIN_TOKEN"))
    parser.add_argument("--email", default=os.getenv("VIDEOAI_ADMIN_EMAIL"))
    parser.add_argument("--password", default=os.getenv("VIDEOAI_ADMIN_PASSWORD"))
    parser.add_argument("--output-dir", default="docs/rag-experiments")
    parser.add_argument("--label", default="legend-snapshot-v1")
    parser.add_argument("--timeout", type=float, default=60.0)
    return parser.parse_args()


def request_json(url: str, timeout: float, token: str | None = None,
                 payload: dict[str, Any] | None = None) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    data = None
    method = "GET"
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            decoded = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} from {url}: {detail[:500]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Cannot reach {url}: {exc.reason}") from exc
    if not decoded.get("success"):
        raise RuntimeError(f"API rejected request: {decoded.get('message', decoded)}")
    return decoded["data"]


def resolve_token(args: argparse.Namespace) -> str:
    if args.token:
        return args.token
    if not args.email or not args.password:
        raise RuntimeError(
            "Set VIDEOAI_ADMIN_TOKEN, or both VIDEOAI_ADMIN_EMAIL and VIDEOAI_ADMIN_PASSWORD"
        )
    data = request_json(
        f"{args.api.rstrip('/')}/auth/login",
        args.timeout,
        payload={"email": args.email, "password": args.password},
    )
    token = data.get("token")
    if not token:
        raise RuntimeError("Login response did not contain a token")
    return str(token)


def markdown_summary(audit: dict[str, Any], json_name: str, csv_name: str) -> str:
    mismatches = [card for card in audit.get("cards", []) if not card["coverageStatus"].startswith("MATCHED")]
    lines = [
        "# LEGEND 知识快照审计",
        "",
        "> 本报告由真实 MySQL 与 Milvus 只读审计生成，不修改卡片或向量。",
        "",
        "## 汇总",
        "",
        f"- 集合：`{audit['collectionName']}`",
        f"- Embedding：`{audit['embeddingProvider']}/{audit['embeddingModel']}`，"
        f"{audit['embeddingDimension']}维，text_type=`{audit['embeddingTextType']}`",
        "",
        "| 项目 | 数量 |",
        "|---|---:|",
        f"| 卡片总数 | {audit['totalCards']} |",
        f"| 已启用卡片 | {audit['enabledCards']} |",
        f"| Mobile卡片 | {audit['mobileCards']} |",
        f"| 玩法卡片（启发式） | {audit['gameplayCards']} |",
        f"| MySQL chunks | {audit['mysqlChunkCount']} |",
        f"| Milvus vectors | {audit['milvusVectorCount']} |",
        f"| Milvus 孤儿卡片 | {audit['orphanVectorCards']} |",
        f"| Milvus 孤儿向量 | {audit['orphanVectorCount']} |",
        f"| 覆盖一致卡片 | {audit['matchedCards']} |",
        f"| 覆盖异常卡片 | {audit['mismatchCards']} |",
        "",
        f"- 原始JSON：`{json_name}`",
        f"- 卡片清单：`{csv_name}`",
        "",
        "## 覆盖异常",
        "",
    ]
    if not mismatches:
        lines.append("- 无")
    else:
        for card in mismatches:
            lines.append(
                f"- `{card['cardCode']}` {card['title']}：{card['coverageStatus']}，"
                f"MySQL={card['mysqlChunkCount']}，Milvus={card['milvusVectorCount']}"
            )
    for card_code, count in audit.get("orphanVectorsByCard", {}).items():
        lines.append(f"- `{card_code}`：ORPHAN_VECTOR，Milvus={count}，MySQL卡片不存在")
    lines.extend([
        "",
        "## L01结论",
        "",
        "- 待根据本次审计结果填写是否可以冻结该快照。",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    token = resolve_token(args)
    audit = request_json(
        f"{args.api.rstrip('/')}/admin/knowledge/audit/legends",
        args.timeout,
        token=token,
    )

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    stem = f"{stamp}-{args.label}"
    json_path = output_dir / f"{stem}.json"
    csv_path = output_dir / f"{stem}.csv"
    md_path = output_dir / f"{stem}.md"

    json_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cards = audit.get("cards", [])
    fieldnames = list(cards[0].keys()) if cards else []
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        if fieldnames:
            writer.writeheader()
            writer.writerows(cards)
    md_path.write_text(markdown_summary(audit, json_path.name, csv_path.name), encoding="utf-8")

    print(json.dumps({key: audit[key] for key in (
        "totalCards", "enabledCards", "mobileCards", "gameplayCards",
        "mysqlChunkCount", "milvusVectorCount", "orphanVectorCards", "orphanVectorCount",
        "matchedCards", "mismatchCards"
    )}, ensure_ascii=False, indent=2))
    print(f"JSON snapshot: {json_path}")
    print(f"CSV inventory: {csv_path}")
    print(f"Markdown summary: {md_path}")
    return 0 if audit.get("mismatchCards") == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"rag_legend_audit failed: {exc}", file=sys.stderr)
        sys.exit(2)
