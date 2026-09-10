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


DEFAULT_DATASET = "rag-data/eval/legend_zh_bench_v1.jsonl"
DEFAULT_OUTPUT_DIR = "docs/rag-experiments"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate RAG retrieval through the real Admin API")
    parser.add_argument("--api", default=os.getenv("VIDEOAI_API_URL", "http://localhost:8080/api"))
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--label", default="legend-zh-bench")
    parser.add_argument("--token", default=os.getenv("VIDEOAI_ADMIN_TOKEN"))
    parser.add_argument("--email", default=os.getenv("VIDEOAI_ADMIN_EMAIL"))
    parser.add_argument("--password", default=os.getenv("VIDEOAI_ADMIN_PASSWORD"))
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument("--k", type=int, default=3)
    parser.add_argument("--threshold-start", type=float, default=0.45)
    parser.add_argument("--threshold-end", type=float, default=0.75)
    parser.add_argument("--threshold-step", type=float, default=0.01)
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
            required_evidence = case.get("required_evidence", [])
            if (not case_id or not query or not isinstance(relevant_titles, list)
                    or (answerable and not relevant_titles)):
                raise ValueError(f"Invalid case at {path}:{line_no}")
            if not isinstance(required_evidence, list) or any(
                    not isinstance(item, dict)
                    or not str(item.get("title", "")).strip()
                    or not str(item.get("section", "")).strip()
                    for item in required_evidence
            ):
                raise ValueError(f"Invalid required_evidence at {path}:{line_no}")
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


def evidence_metrics(
        hits: list[dict[str, Any]], required_evidence: list[dict[str, Any]], k: int
) -> tuple[float | None, float | None, float | None]:
    if not required_evidence:
        return None, None, None
    gold = [
        (normalize(item["title"]), normalize(item["section"]))
        for item in required_evidence
    ]
    found: set[int] = set()
    returned = hits[:k]
    for hit in returned:
        title = normalize(hit.get("title"))
        heading = normalize(hit.get("headingPath"))
        for index, (gold_title, gold_section) in enumerate(gold):
            if index not in found and title == gold_title and gold_section in heading:
                found.add(index)
    coverage = len(found) / len(gold)
    complete = float(len(found) == len(gold))
    precision = len(found) / len(returned) if returned else 0.0
    return coverage, complete, precision


def pollution_kind(hit: dict[str, Any]) -> str | None:
    """Detect content that must not re-enter the PC-only Legend corpus."""
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


def compact_candidate(hit: dict[str, Any], rank: int) -> dict[str, Any]:
    return {
        "rank": rank,
        "vector_id": hit.get("vectorId"),
        "card_code": hit.get("cardCode"),
        "title": hit.get("title"),
        "score": hit.get("score"),
        "dense_score": hit.get("denseScore"),
        "lexical_score": hit.get("lexicalScore"),
        "fusion_score": hit.get("fusionScore"),
        "dense_rank": hit.get("denseRank"),
        "lexical_rank": hit.get("lexicalRank"),
        "heading_path": hit.get("headingPath"),
    }


def select_at_threshold(case: dict[str, Any], threshold: float) -> list[dict[str, Any]]:
    config = case["runtime_config"]
    max_per_card = max(1, int(config.get("max_chunks_per_card") or 1))
    final_top_k = max(1, int(config.get("final_top_k") or 1))
    per_card: dict[str, int] = {}
    selected: list[dict[str, Any]] = []
    for hit in case.get("raw_candidates", []):
        if float(hit.get("score") or 0.0) < threshold:
            continue
        card_code = normalize(hit.get("card_code"))
        if per_card.get(card_code, 0) >= max_per_card:
            continue
        selected.append(hit)
        per_card[card_code] = per_card.get(card_code, 0) + 1
        if len(selected) >= final_top_k:
            break
    return selected


def threshold_sweep(cases: list[dict[str, Any]], args: argparse.Namespace) -> dict[str, Any]:
    if any(bool(case.get("runtime_config", {}).get("hybrid_retrieval_enabled")) for case in cases):
        return {
            "available": False,
            "reason": "Hybrid RRF order cannot be reproduced by changing the dense threshold alone",
        }
    trace_cases = [case for case in cases if case.get("trace_available")]
    if len(trace_cases) != len(cases):
        return {
            "available": False,
            "reason": "API did not return rawCandidates for every successful case",
        }
    if args.threshold_step <= 0 or args.threshold_end < args.threshold_start:
        raise ValueError("Invalid threshold range")

    thresholds: list[float] = []
    value = args.threshold_start
    while value <= args.threshold_end + 1e-9:
        thresholds.append(round(value, 6))
        value += args.threshold_step

    answerable = [case for case in trace_cases if case["answerable"]]
    no_answer = [case for case in trace_cases if not case["answerable"]]
    points: list[dict[str, Any]] = []
    for threshold in thresholds:
        entity_hit_at_1: list[float] = []
        hit_at_k: list[float] = []
        section_hit_at_k: list[float] = []
        for case in answerable:
            selected = select_at_threshold(case, threshold)
            relevance, found = relevance_vector(selected, case["relevant_titles"], args.k)
            entity_hit_at_1.append(float(bool(relevance and relevance[0])))
            hit_at_k.append(float(bool(found)))
            if case.get("relevant_sections"):
                gold_titles = {normalize(title) for title in case["relevant_titles"]}
                gold_sections = [normalize(section) for section in case["relevant_sections"]]
                section_hit_at_k.append(float(any(
                    normalize(hit.get("title")) in gold_titles
                    and any(section in normalize(hit.get("heading_path")) for section in gold_sections)
                    for hit in selected[: args.k]
                )))

        rejection = [float(not select_at_threshold(case, threshold)) for case in no_answer]
        hit_rate = statistics.fmean(hit_at_k) if hit_at_k else 0.0
        rejection_rate = statistics.fmean(rejection) if rejection else 0.0
        points.append({
            "threshold": threshold,
            "entity_hit_at_1": statistics.fmean(entity_hit_at_1) if entity_hit_at_1 else 0.0,
            "hit_at_k": hit_rate,
            "section_hit_at_k": statistics.fmean(section_hit_at_k) if section_hit_at_k else None,
            "no_answer_rejection_rate": rejection_rate if no_answer else None,
            "no_answer_false_positive_rate": (1.0 - rejection_rate) if no_answer else None,
            "balanced_success": statistics.fmean([hit_rate, rejection_rate]) if no_answer else hit_rate,
        })

    # 相同指标保留更低阈值，给正例留下更大的分数余量；随后只保留非支配解。
    metric_names = ("entity_hit_at_1", "section_hit_at_k", "no_answer_rejection_rate")
    distinct_points: list[dict[str, Any]] = []
    seen_metrics: set[tuple[float, ...]] = set()
    for point in points:
        signature = tuple(float(point[name] or 0.0) for name in metric_names)
        if signature not in seen_metrics:
            seen_metrics.add(signature)
            distinct_points.append(point)

    def dominates(left: dict[str, Any], right: dict[str, Any]) -> bool:
        left_metrics = [float(left[name] or 0.0) for name in metric_names]
        right_metrics = [float(right[name] or 0.0) for name in metric_names]
        return all(a >= b for a, b in zip(left_metrics, right_metrics)) and any(
            a > b for a, b in zip(left_metrics, right_metrics)
        )

    pareto_candidates = [
        point for point in distinct_points
        if not any(dominates(other, point) for other in distinct_points if other is not point)
    ]
    return {
        "available": True,
        "start": args.threshold_start,
        "end": args.threshold_end,
        "step": args.threshold_step,
        "selection_rule": "Pareto frontier of entity_hit_at_1, section_hit_at_k and rejection_rate",
        "pareto_candidates": pareto_candidates,
        "points": points,
    }


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
            raw_candidates = data.get("rawCandidates") or []
            lexical_candidates = data.get("lexicalCandidates") or []
            fused_candidates = data.get("fusedCandidates") or []
            answerable = bool(case["answerable"])
            relevance, found = relevance_vector(hits, case["relevant_titles"], args.k)
            evidence_coverage, complete_evidence, evidence_precision = evidence_metrics(
                hits, case.get("required_evidence", []), args.k
            )
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
                "required_evidence": case.get("required_evidence", []),
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
                "evidence_coverage_at_k": evidence_coverage,
                "complete_evidence_at_k": complete_evidence,
                "evidence_precision_at_k": evidence_precision,
                "server_latency_ms": data.get("latencyMs"),
                "client_latency_ms": client_latency_ms,
                "context_chars": data.get("contextChars"),
                "expanded_query": data.get("expandedQuery"),
                "trace_available": "rawCandidates" in data,
                "raw_candidates": [
                    compact_candidate(hit, rank)
                    for rank, hit in enumerate(raw_candidates, start=1)
                ],
                "lexical_candidates": [
                    compact_candidate(hit, rank)
                    for rank, hit in enumerate(lexical_candidates, start=1)
                ],
                "fused_candidates": [
                    compact_candidate(hit, rank)
                    for rank, hit in enumerate(fused_candidates, start=1)
                ],
                "hits": [
                    {
                        "rank": rank,
                        "card_code": hit.get("cardCode"),
                        "title": hit.get("title"),
                        "score": hit.get("score"),
                        "dense_score": hit.get("denseScore"),
                        "lexical_score": hit.get("lexicalScore"),
                        "fusion_score": hit.get("fusionScore"),
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
                    "embedding_heading_path_enabled": data.get("embeddingHeadingPathEnabled"),
                    "hybrid_retrieval_enabled": data.get("hybridRetrievalEnabled"),
                    "hybrid_conditional_rescue_enabled": data.get("hybridConditionalRescueEnabled"),
                    "hybrid_conditional_rescue_min_dense_score": data.get(
                        "hybridConditionalRescueMinDenseScore"
                    ),
                    "hybrid_conditional_rescue_max_chunks": data.get(
                        "hybridConditionalRescueMaxChunks"
                    ),
                    "hybrid_lexical_union_enabled": data.get("hybridLexicalUnionEnabled"),
                    "lexical_top_k": data.get("lexicalTopK"),
                    "rrf_k": data.get("rrfK"),
                    "dense_rrf_weight": data.get("denseRrfWeight"),
                    "lexical_rrf_weight": data.get("lexicalRrfWeight"),
                    "collection_name": data.get("collectionName"),
                    "returned_hit_count": data.get("hitCount"),
                    "raw_candidate_count": data.get("rawCandidateCount"),
                    "lexical_candidate_count": data.get("lexicalCandidateCount"),
                    "fused_candidate_count": data.get("fusedCandidateCount"),
                    "score_passed_count": data.get("scorePassedCount"),
                    "diversified_count": data.get("diversifiedCount"),
                    "selected_count": data.get("selectedCount"),
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
        category_evidence = [
            item for item in category_cases if item["evidence_coverage_at_k"] is not None
        ]
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
            **{
                metric: (
                    statistics.fmean(item[metric] for item in category_evidence)
                    if category_evidence else None
                )
                for metric in (
                    "evidence_coverage_at_k",
                    "complete_evidence_at_k",
                    "evidence_precision_at_k",
                )
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
    evidence_cases = [item for item in evaluated if item["evidence_coverage_at_k"] is not None]

    report = {
        "schema_version": 4,
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
            "evidence_cases": len(evidence_cases),
            "evidence_coverage_at_k": (
                statistics.fmean(item["evidence_coverage_at_k"] for item in evidence_cases)
                if evidence_cases else None
            ),
            "complete_evidence_at_k": (
                statistics.fmean(item["complete_evidence_at_k"] for item in evidence_cases)
                if evidence_cases else None
            ),
            "evidence_precision_at_k": (
                statistics.fmean(item["evidence_precision_at_k"] for item in evidence_cases)
                if evidence_cases else None
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
        "threshold_sweep": threshold_sweep(evaluated, args),
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
    if summary.get("evidence_cases"):
        insert_at = next(index for index, line in enumerate(lines) if line == "## 分类指标")
        lines[insert_at:insert_at] = [
            f"| 必需证据覆盖率@{run['k']} | {summary['evidence_coverage_at_k']:.4f} |",
            f"| 必需证据完整命中率@{run['k']} | {summary['complete_evidence_at_k']:.4f} |",
            f"| 必需证据 Precision@{run['k']} | {summary['evidence_precision_at_k']:.4f} |",
            "",
        ]
    sweep = report.get("threshold_sweep", {})
    if sweep.get("available") and sweep.get("pareto_candidates"):
        insert_at = next(index for index, line in enumerate(lines) if line == "## 分类指标")
        sweep_lines = [
            "## 阈值扫描",
            "",
            "> 候选值使用同一次查询返回的原始候选离线模拟。脚本只给出非支配解，不替业务决定召回与拒答的权重。",
            "",
            f"- 扫描范围：`{sweep['start']:.2f}`～`{sweep['end']:.2f}`，步长 `{sweep['step']:.2f}`",
            "",
            "| 候选阈值 | Entity Hit@1 | Section Hit@K | 无答案拒绝率 |",
            "|---:|---:|---:|---:|",
        ]
        for candidate in sweep["pareto_candidates"]:
            sweep_lines.append(
                f"| {candidate['threshold']:.2f} | {candidate['entity_hit_at_1']:.4f} | "
                f"{metric(candidate['section_hit_at_k'])} | {metric(candidate['no_answer_rejection_rate'])} |"
            )
        sweep_lines.append("")
        lines[insert_at:insert_at] = sweep_lines
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
    candidates = report.get("threshold_sweep", {}).get("pareto_candidates", [])
    for candidate in candidates:
        section_value = candidate.get("section_hit_at_k")
        rejection_value = candidate.get("no_answer_rejection_rate")
        section_text = f"{section_value:.4f}" if section_value is not None else "-"
        rejection_text = f"{rejection_value:.4f}" if rejection_value is not None else "-"
        print(
            "Pareto threshold candidate: "
            f"{candidate['threshold']:.2f} "
            f"(EntityHit@1={candidate['entity_hit_at_1']:.4f}, "
            f"SectionHit@K={section_text}, Rejection={rejection_text})"
        )
    return exit_code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"rag_eval failed: {exc}", file=sys.stderr)
        sys.exit(2)
