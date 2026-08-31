#!/usr/bin/env python3
"""Evaluate the real RAG retrieval endpoint against a frozen JSONL dataset.

The evaluator intentionally calls /admin/rag/retrieve-test instead of mocking
Embedding or Milvus. Each run writes a machine-readable JSON report and a short
Markdown summary so retrieval changes can be compared with the same dataset.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_DATASET = "rag-data/eval/retrieval_gold_v1.jsonl"
DEFAULT_OUTPUT_DIR = "docs/rag-experiments"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate RAG retrieval through the real Admin API")
    parser.add_argument("--api", default=os.getenv("VIDEOAI_API_URL", "http://localhost:8080/api"))
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--label", default="vector-baseline")
    parser.add_argument("--token", default=os.getenv("VIDEOAI_ADMIN_TOKEN"))
    parser.add_argument("--email", default=os.getenv("VIDEOAI_ADMIN_EMAIL"))
    parser.add_argument("--password", default=os.getenv("VIDEOAI_ADMIN_PASSWORD"))
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument("--k", type=int, default=6)
    return parser.parse_args()


def post_json(url: str, payload: dict[str, Any], timeout: float, token: str | None = None) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            decoded = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} from {url}: {detail[:500]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Cannot reach {url}: {exc.reason}") from exc
    if not decoded.get("success"):
        raise RuntimeError(f"API rejected request to {url}: {decoded.get('message', decoded)}")
    return decoded["data"]


def resolve_token(args: argparse.Namespace) -> str:
    if args.token:
        return args.token
    if not args.email or not args.password:
        raise RuntimeError(
            "Set VIDEOAI_ADMIN_TOKEN, or both VIDEOAI_ADMIN_EMAIL and VIDEOAI_ADMIN_PASSWORD"
        )
    data = post_json(
        f"{args.api.rstrip('/')}/auth/login",
        {"email": args.email, "password": args.password},
        args.timeout,
    )
    token = data.get("token")
    if not token:
        raise RuntimeError("Login succeeded but response did not contain a token")
    return str(token)


def load_dataset(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            case = json.loads(line)
            case_id = str(case.get("id", "")).strip()
            query = str(case.get("query", "")).strip()
            answerable = bool(case.get("answerable", True))
            relevant_titles = case.get("relevant_titles", [])
            if (not case_id or not query or not isinstance(relevant_titles, list)
                    or (answerable and not relevant_titles)):
                raise ValueError(f"Invalid case at {path}:{line_no}")
            if case_id in seen_ids:
                raise ValueError(f"Duplicate case id '{case_id}' at {path}:{line_no}")
            seen_ids.add(case_id)
            case["answerable"] = answerable
            case["relevant_titles"] = relevant_titles
            cases.append(case)
    if not cases:
        raise ValueError(f"Dataset is empty: {path}")
    return cases


def normalize(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip()).casefold()


def relevance_vector(hits: list[dict[str, Any]], relevant_titles: list[str], k: int) -> tuple[list[int], set[str]]:
    gold = {normalize(title) for title in relevant_titles}
    found: set[str] = set()
    relevance: list[int] = []
    for hit in hits[:k]:
        title = normalize(hit.get("title"))
        # Entity-level metrics count one relevant title once. Multiple chunks from
        # the same card must not make nDCG exceed 1 or inflate precision.
        is_relevant = title in gold and title not in found
        relevance.append(1 if is_relevant else 0)
        if is_relevant:
            found.add(title)
    while len(relevance) < k:
        relevance.append(0)
    return relevance, found


def pollution_kind(hit: dict[str, Any]) -> str | None:
    """Classify frozen L01 cards that must not enter PC gameplay context."""
    card_code = normalize(hit.get("cardCode"))
    if card_code.endswith("-mobile"):
        return "mobile"
    if card_code.endswith("-character"):
        return "lore"
    if card_code == "wraith-id":
        return "auxiliary"
    return None


def ndcg_at_k(relevance: list[int], relevant_count: int, k: int) -> float:
    dcg = sum(rel / math.log2(rank + 2) for rank, rel in enumerate(relevance[:k]))
    ideal_hits = min(relevant_count, k)
    idcg = sum(1.0 / math.log2(rank + 2) for rank in range(ideal_hits))
    return dcg / idcg if idcg else 0.0


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return float(ordered[index])


def git_revision() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], text=True, stderr=subprocess.DEVNULL
        ).strip()
    except Exception:
        return "unknown"


def dataset_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_label(label: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9._-]+", "-", label.strip()).strip("-")
    return value or "rag-eval"


def evaluate(args: argparse.Namespace) -> tuple[dict[str, Any], int]:
    dataset_path = Path(args.dataset)
    cases = load_dataset(dataset_path)
    token = resolve_token(args)
    endpoint = f"{args.api.rstrip('/')}/admin/rag/retrieve-test"
    evaluated: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []

    for position, case in enumerate(cases, start=1):
        started = time.perf_counter()
        try:
            data = post_json(endpoint, {"query": case["query"]}, args.timeout, token)
            client_latency_ms = round((time.perf_counter() - started) * 1000, 2)
            hits = data.get("hits") or []
            answerable = bool(case["answerable"])
            relevance, found = relevance_vector(hits, case["relevant_titles"], args.k)
            relevant_sections = [normalize(value) for value in case.get("relevant_sections", [])]
            section_relevance = []
            if relevant_sections:
                gold_titles = {normalize(title) for title in case["relevant_titles"]}
                for hit in hits[: args.k]:
                    heading = normalize(hit.get("headingPath"))
                    section_relevance.append(
                        1 if normalize(hit.get("title")) in gold_titles
                        and any(section in heading for section in relevant_sections) else 0
                    )
            polluting_hits = [
                {"rank": rank, "card_code": hit.get("cardCode"), "kind": pollution_kind(hit)}
                for rank, hit in enumerate(hits[: args.k], start=1)
                if pollution_kind(hit) is not None
            ]
            first_rank = next((index + 1 for index, rel in enumerate(relevance) if rel), None)
            gold_count = len({normalize(title) for title in case["relevant_titles"]})
            evaluated.append({
                "id": case["id"],
                "query": case["query"],
                "category": case.get("category", "uncategorized"),
                "answerable": answerable,
                "relevant_titles": case["relevant_titles"],
                "hit_at_k": (1.0 if found else 0.0) if answerable else None,
                "entity_hit_at_1": float(bool(relevance and relevance[0])) if answerable else None,
                "recall_at_k": (len(found) / gold_count) if answerable else None,
                "precision_at_k": (sum(relevance) / args.k) if answerable else None,
                "reciprocal_rank": (1.0 / first_rank if first_rank else 0.0) if answerable else None,
                "ndcg_at_k": ndcg_at_k(relevance, gold_count, args.k) if answerable else None,
                "retrieval_rejected": not hits,
                "correct_rejection": (not answerable) and not hits,
                "false_positive": (not answerable) and bool(hits),
                "max_returned_score": hits[0].get("score") if hits else None,
                "mobile_polluted": any(hit["kind"] == "mobile" for hit in polluting_hits),
                "excluded_content_polluted": bool(polluting_hits),
                "polluting_hits": polluting_hits,
                "relevant_sections": case.get("relevant_sections", []),
                "section_hit_at_k": 1.0 if any(section_relevance) else (0.0 if relevant_sections else None),
                "section_precision_at_k": (
                    sum(section_relevance) / len(hits[: args.k])
                    if relevant_sections and hits[: args.k] else (0.0 if relevant_sections else None)
                ),
                "server_latency_ms": data.get("latencyMs"),
                "client_latency_ms": client_latency_ms,
                "context_chars": data.get("contextChars"),
                "expanded_query": data.get("expandedQuery"),
                "hits": [
                    {
                        "rank": rank,
                        "card_code": hit.get("cardCode"),
                        "title": hit.get("title"),
                        "score": hit.get("score"),
                        "heading_path": hit.get("headingPath"),
                        "relevant": relevance[rank - 1] == 1,
                    }
                    for rank, hit in enumerate(hits[: args.k], start=1)
                ],
                "runtime_config": {
                    "candidate_top_k": data.get("topK"),
                    "final_top_k": data.get("finalTopK"),
                    "max_chunks_per_card": data.get("maxChunksPerCard"),
                    "max_context_chars": data.get("maxContextChars"),
                    "min_score": data.get("minScore"),
                    "legend_pc_gameplay_filter_enabled": data.get("legendPcGameplayFilterEnabled"),
                    "legend_alias_enhancement_enabled": data.get("legendAliasEnhancementEnabled"),
                    "collection_name": data.get("collectionName"),
                    "returned_hit_count": data.get("hitCount"),
                },
            })
            outcome = f"rank={first_rank or '-'}" if answerable else ("rejected" if not hits else "false-positive")
            print(f"[{position:02d}/{len(cases)}] {case['id']}: {outcome}")
        except Exception as exc:
            errors.append({"id": str(case["id"]), "error": str(exc)})
            print(f"[{position:02d}/{len(cases)}] {case['id']}: ERROR {exc}", file=sys.stderr)

    metric_names = (
        "hit_at_k", "entity_hit_at_1", "recall_at_k", "precision_at_k",
        "reciprocal_rank", "ndcg_at_k",
    )
    answerable_cases = [item for item in evaluated if item["answerable"]]
    no_answer_cases = [item for item in evaluated if not item["answerable"]]
    summary_metrics = {
        metric: statistics.fmean(item[metric] for item in answerable_cases) if answerable_cases else 0.0
        for metric in metric_names
    }
    categories: dict[str, dict[str, Any]] = {}
    for category in sorted({item["category"] for item in evaluated}):
        category_cases = [item for item in evaluated if item["category"] == category]
        category_answerable = [item for item in category_cases if item["answerable"]]
        category_no_answer = [item for item in category_cases if not item["answerable"]]
        categories[category] = {
            "cases": len(category_cases),
            "answerable_cases": len(category_answerable),
            "no_answer_cases": len(category_no_answer),
            "no_answer_rejection_rate": (
                statistics.fmean(float(item["retrieval_rejected"]) for item in category_no_answer)
                if category_no_answer else None
            ),
            **{
                metric: (statistics.fmean(item[metric] for item in category_answerable)
                         if category_answerable else None)
                for metric in metric_names
            },
        }
    server_latencies = [float(item["server_latency_ms"]) for item in evaluated if item["server_latency_ms"] is not None]
    client_latencies = [float(item["client_latency_ms"]) for item in evaluated]
    context_lengths = [float(item["context_chars"]) for item in evaluated if item["context_chars"] is not None]
    mobile_pollution_rate = (
        statistics.fmean(float(item["mobile_polluted"]) for item in evaluated) if evaluated else 0.0
    )
    excluded_content_pollution_rate = (
        statistics.fmean(float(item["excluded_content_polluted"]) for item in evaluated) if evaluated else 0.0
    )
    section_cases = [item for item in evaluated if item["section_hit_at_k"] is not None]

    report = {
        "schema_version": 2,
        "run": {
            "label": args.label,
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            "git_revision": git_revision(),
            "api": args.api,
            "dataset": str(dataset_path.as_posix()),
            "dataset_sha256": dataset_sha256(dataset_path),
            "k": args.k,
        },
        "summary": {
            "total_cases": len(cases),
            "successful_cases": len(evaluated),
            "failed_cases": len(errors),
            "answerable_cases": len(answerable_cases),
            "no_answer_cases": len(no_answer_cases),
            **summary_metrics,
            "no_answer_rejection_rate": (
                statistics.fmean(float(item["retrieval_rejected"]) for item in no_answer_cases)
                if no_answer_cases else None
            ),
            "no_answer_false_positive_rate": (
                statistics.fmean(float(item["false_positive"]) for item in no_answer_cases)
                if no_answer_cases else None
            ),
            "mobile_pollution_rate": mobile_pollution_rate,
            "excluded_content_pollution_rate": excluded_content_pollution_rate,
            "section_cases": len(section_cases),
            "section_hit_at_k": (
                statistics.fmean(item["section_hit_at_k"] for item in section_cases) if section_cases else None
            ),
            "section_precision_at_k": (
                statistics.fmean(item["section_precision_at_k"] for item in section_cases) if section_cases else None
            ),
            "server_latency_ms": {
                "p50": percentile(server_latencies, 0.50),
                "p95": percentile(server_latencies, 0.95),
                "mean": statistics.fmean(server_latencies) if server_latencies else 0.0,
            },
            "client_latency_ms": {
                "p50": percentile(client_latencies, 0.50),
                "p95": percentile(client_latencies, 0.95),
                "mean": statistics.fmean(client_latencies) if client_latencies else 0.0,
            },
            "context_chars": {
                "p50": percentile(context_lengths, 0.50),
                "p95": percentile(context_lengths, 0.95),
                "mean": statistics.fmean(context_lengths) if context_lengths else 0.0,
                "max": max(context_lengths) if context_lengths else 0.0,
            },
            "categories": categories,
        },
        "errors": errors,
        "cases": evaluated,
    }
    return report, 0 if not errors else (2 if not evaluated else 1)


def markdown_report(report: dict[str, Any], json_name: str) -> str:
    run = report["run"]
    summary = report["summary"]
    def metric(value: Any) -> str:
        return "-" if value is None else f"{value:.4f}"

    lines = [
        f"# RAG 实验记录：{run['label']}",
        "",
        "> 本文件由 `scripts/rag_eval.py` 自动生成；指标来自真实 Admin API、Embedding 和 Milvus，不能手工改写为更好看的结果。",
        "",
        "## 运行信息",
        "",
        f"- 时间（UTC）：`{run['timestamp_utc']}`",
        f"- Git revision：`{run['git_revision']}`",
        f"- 数据集：`{run['dataset']}`",
        f"- 数据集 SHA-256：`{run['dataset_sha256']}`",
        f"- 评测 K：`{run['k']}`",
        f"- 原始结果：`{json_name}`",
        "",
        "## 汇总指标",
        "",
        "| 指标 | 结果 |",
        "|---|---:|",
        f"| 成功/总数 | {summary['successful_cases']}/{summary['total_cases']} |",
        f"| Hit@{run['k']} | {summary['hit_at_k']:.4f} |",
        f"| Entity Hit@1 | {summary['entity_hit_at_1']:.4f} |",
        f"| Recall@{run['k']} | {summary['recall_at_k']:.4f} |",
        f"| Precision@{run['k']} | {summary['precision_at_k']:.4f} |",
        f"| MRR@{run['k']} | {summary['reciprocal_rank']:.4f} |",
        f"| nDCG@{run['k']} | {summary['ndcg_at_k']:.4f} |",
        f"| Mobile 污染率 | {summary['mobile_pollution_rate']:.4f} |",
        f"| 排除内容污染率 | {summary['excluded_content_pollution_rate']:.4f} |",
        f"| 服务端延迟 P50 | {summary['server_latency_ms']['p50']:.1f} ms |",
        f"| 服务端延迟 P95 | {summary['server_latency_ms']['p95']:.1f} ms |",
        f"| 上下文字符 P50 | {summary['context_chars']['p50']:.1f} |",
        f"| 上下文字符 P95 | {summary['context_chars']['p95']:.1f} |",
        f"| 上下文字符最大值 | {summary['context_chars']['max']:.1f} |",
        "",
        "## 分类指标",
        "",
        "| 类别 | 样本数 | Hit@K | Entity Hit@1 | Recall@K | MRR@K | nDCG@K |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    if summary.get("no_answer_cases"):
        insert_at = next(index for index, line in enumerate(lines) if line == "## 分类指标")
        lines[insert_at:insert_at] = [
            f"| 无答案样本 | {summary['no_answer_cases']} |",
            f"| 无答案拒绝率 | {summary['no_answer_rejection_rate']:.4f} |",
            f"| 无答案误召回率 | {summary['no_answer_false_positive_rate']:.4f} |",
            "",
        ]
    if summary.get("section_cases"):
        insert_at = next(index for index, line in enumerate(lines) if line == "## 分类指标")
        lines[insert_at:insert_at] = [
            f"| Section Hit@{run['k']} | {summary['section_hit_at_k']:.4f} |",
            f"| Section Precision@{run['k']} | {summary['section_precision_at_k']:.4f} |",
            "",
        ]
    for category, metrics in summary["categories"].items():
        lines.append(
            f"| {category} | {metrics['cases']} | {metric(metrics['hit_at_k'])} | "
            f"{metric(metrics['entity_hit_at_1'])} | {metric(metrics['recall_at_k'])} | "
            f"{metric(metrics['reciprocal_rank'])} | {metric(metrics['ndcg_at_k'])} |"
        )
    lines.extend([
        "",
        "## 逐条结果",
        "",
        "| ID | 类别 | RR | Recall | 首条命中 |",
        "|---|---|---:|---:|---|",
    ])
    for case in report["cases"]:
        if case["answerable"]:
            first = next((hit["title"] for hit in case["hits"] if hit["relevant"]), "MISS")
            lines.append(
                f"| {case['id']} | {case['category']} | {case['reciprocal_rank']:.3f} | "
                f"{case['recall_at_k']:.3f} | {str(first).replace('|', '\\|')} |"
            )
        else:
            outcome = "REJECT" if case["retrieval_rejected"] else "FALSE_POSITIVE"
            lines.append(f"| {case['id']} | {case['category']} | - | - | {outcome} |")
    if report["errors"]:
        lines.extend(["", "## 失败", ""])
        for error in report["errors"]:
            lines.append(f"- `{error['id']}`：{error['error']}")
    lines.extend(["", "## 结论", "", "- 待人工填写：本轮只记录事实，不在没有对照实验时宣称提升。", ""])
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    if args.k <= 0:
        raise ValueError("--k must be positive")
    report, exit_code = evaluate(args)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    stem = f"{stamp}-{safe_label(args.label)}"
    json_path = output_dir / f"{stem}.json"
    md_path = output_dir / f"{stem}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(markdown_report(report, json_path.name), encoding="utf-8")
    print(f"JSON report: {json_path}")
    print(f"Markdown report: {md_path}")
    return exit_code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"rag_eval failed: {exc}", file=sys.stderr)
        sys.exit(2)
