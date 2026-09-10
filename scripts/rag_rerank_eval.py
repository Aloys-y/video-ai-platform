#!/usr/bin/env python3
"""Evaluate a remote pretrained reranker on the frozen RAG candidate pool.

The script deliberately reranks the original dense TopK candidates without
heading-path deduplication, so the experiment isolates reranking quality.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_DATASET = "rag-data/eval/legend_zh_player_hard_v2.jsonl"
DEFAULT_OUTPUT_DIR = "docs/rag-experiments"
DEFAULT_RERANK_URL = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate a pretrained Chinese reranker")
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--api", default="http://localhost:8080/api")
    parser.add_argument("--email", default=os.getenv("VIDEOAI_ADMIN_EMAIL"))
    parser.add_argument("--password", default=os.getenv("VIDEOAI_ADMIN_PASSWORD"))
    parser.add_argument("--token", default=os.getenv("VIDEOAI_ADMIN_TOKEN"))
    parser.add_argument("--rerank-api-key", default=os.getenv("DASHSCOPE_API_KEY"))
    parser.add_argument("--rerank-url", default=DEFAULT_RERANK_URL)
    parser.add_argument("--rerank-model", default="qwen3-rerank")
    parser.add_argument("--candidate-k", type=int, default=20)
    parser.add_argument("--final-k", type=int, default=3)
    parser.add_argument("--max-per-card", type=int, default=2)
    parser.add_argument("--dev-cases", type=int, default=40)
    parser.add_argument("--threshold-step", type=float, default=0.01)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--label", default="qwen3-rerank-no-dedupe")
    return parser.parse_args()


def normalize(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "")).lower()


def read_dataset(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        case = json.loads(line)
        if not case.get("id") or not case.get("query"):
            raise ValueError(f"Invalid case at {path}:{line_no}")
        case["answerable"] = bool(case.get("answerable", True))
        cases.append(case)
    return cases


def request_json(
        url: str,
        payload: dict[str, Any],
        timeout: float,
        headers: dict[str, str] | None = None,
        retries: int = 3,
) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        request_headers.update(headers)
    for attempt in range(retries):
        request = urllib.request.Request(url, data=body, headers=request_headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code not in (429, 500, 502, 503, 504) or attempt + 1 == retries:
                raise RuntimeError(f"HTTP {exc.code} from {url}: {detail[:500]}") from exc
        except urllib.error.URLError as exc:
            if attempt + 1 == retries:
                raise RuntimeError(f"Cannot reach {url}: {exc.reason}") from exc
        time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def resolve_token(args: argparse.Namespace) -> str:
    if args.token:
        return str(args.token)
    if not args.email or not args.password:
        raise RuntimeError("Set VIDEOAI_ADMIN_TOKEN or VIDEOAI_ADMIN_EMAIL/VIDEOAI_ADMIN_PASSWORD")
    response = request_json(
        f"{args.api.rstrip('/')}/auth/login",
        {"email": args.email, "password": args.password},
        args.timeout,
    )
    if not response.get("success") or not response.get("data", {}).get("token"):
        raise RuntimeError(f"Login failed: {response.get('message', response)}")
    return str(response["data"]["token"])


def retrieve(args: argparse.Namespace, token: str, query: str) -> dict[str, Any]:
    response = request_json(
        f"{args.api.rstrip('/')}/admin/rag/retrieve-test",
        {"query": query},
        args.timeout,
        {"Authorization": f"Bearer {token}"},
    )
    if not response.get("success"):
        raise RuntimeError(f"Retrieve failed: {response.get('message', response)}")
    return response["data"]


def document_text(candidate: dict[str, Any]) -> str:
    return "\n".join((
        f"英雄：{candidate.get('title') or ''}",
        f"章节：{candidate.get('headingPath') or ''}",
        f"正文：{candidate.get('contentText') or ''}",
    ))


def rerank(
        args: argparse.Namespace,
        query: str,
        candidates: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], float]:
    documents = [document_text(candidate) for candidate in candidates]
    started = time.perf_counter()
    response = request_json(
        args.rerank_url,
        {
            "model": args.rerank_model,
            "query": query,
            "documents": documents,
            "top_n": len(documents),
            "instruct": (
                "Given an Apex Legends gameplay question, retrieve passages that directly answer "
                "the question. Prefer exact ability mechanics over passages that are only topically related."
            ),
        },
        args.timeout,
        {"Authorization": f"Bearer {args.rerank_api_key}"},
    )
    latency_ms = round((time.perf_counter() - started) * 1000, 2)
    results = response.get("results")
    if not isinstance(results, list) or len(results) != len(candidates):
        raise RuntimeError(f"Unexpected rerank response: {str(response)[:500]}")
    reranked: list[dict[str, Any]] = []
    for result in results:
        index = int(result["index"])
        candidate = dict(candidates[index])
        candidate["rerankScore"] = float(result["relevance_score"])
        candidate["originalRank"] = index + 1
        reranked.append(candidate)
    return reranked, latency_ms


def select_candidates(
        candidates: list[dict[str, Any]],
        threshold: float,
        final_k: int,
        max_per_card: int,
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    per_card: dict[str, int] = {}
    for candidate in candidates:
        if float(candidate.get("rerankScore") or 0.0) < threshold:
            continue
        card_code = normalize(candidate.get("cardCode"))
        if per_card.get(card_code, 0) >= max_per_card:
            continue
        selected.append(candidate)
        per_card[card_code] = per_card.get(card_code, 0) + 1
        if len(selected) >= final_k:
            break
    return selected


def select_dense_baseline(
        candidates: list[dict[str, Any]],
        threshold: float,
        final_k: int,
        max_per_card: int,
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    per_card: dict[str, int] = {}
    for candidate in candidates:
        if float(candidate.get("score") or 0.0) < threshold:
            continue
        card_code = normalize(candidate.get("cardCode"))
        if per_card.get(card_code, 0) >= max_per_card:
            continue
        selected.append(candidate)
        per_card[card_code] = per_card.get(card_code, 0) + 1
        if len(selected) >= final_k:
            break
    return selected


def case_metrics(case: dict[str, Any], selected: list[dict[str, Any]]) -> dict[str, float]:
    titles = {normalize(value) for value in case.get("relevant_titles", [])}
    sections = [normalize(value) for value in case.get("relevant_sections", [])]
    title_relevance = [normalize(hit.get("title")) in titles for hit in selected]
    section_relevance = [
        title_relevance[index]
        and any(section in normalize(hit.get("headingPath")) for section in sections)
        for index, hit in enumerate(selected)
    ]
    first_title_rank = next((index + 1 for index, value in enumerate(title_relevance) if value), None)
    return {
        "hit_at_k": float(any(title_relevance)),
        "entity_hit_at_1": float(bool(title_relevance and title_relevance[0])),
        "section_hit_at_k": float(any(section_relevance)),
        "section_precision_at_k": (
            sum(section_relevance) / len(selected) if selected else 0.0
        ),
        "reciprocal_rank": 1.0 / first_title_rank if first_title_rank else 0.0,
    }


def summarize(
        cases: list[dict[str, Any]],
        selector,
) -> dict[str, Any]:
    answerable = [case for case in cases if case["answerable"]]
    negatives = [case for case in cases if not case["answerable"]]
    answer_metrics = [case_metrics(case, selector(case)) for case in answerable]
    returned_counts = [len(selector(case)) for case in cases]
    return {
        "cases": len(cases),
        "answerable_cases": len(answerable),
        "no_answer_cases": len(negatives),
        "hit_at_k": statistics.fmean(item["hit_at_k"] for item in answer_metrics) if answer_metrics else 0.0,
        "entity_hit_at_1": (
            statistics.fmean(item["entity_hit_at_1"] for item in answer_metrics) if answer_metrics else 0.0
        ),
        "section_hit_at_k": (
            statistics.fmean(item["section_hit_at_k"] for item in answer_metrics) if answer_metrics else 0.0
        ),
        "section_precision_at_k": (
            statistics.fmean(item["section_precision_at_k"] for item in answer_metrics)
            if answer_metrics else 0.0
        ),
        "reciprocal_rank": (
            statistics.fmean(item["reciprocal_rank"] for item in answer_metrics) if answer_metrics else 0.0
        ),
        "no_answer_rejection_rate": (
            statistics.fmean(float(not selector(case)) for case in negatives) if negatives else None
        ),
        "mean_returned_count": statistics.fmean(returned_counts) if returned_counts else 0.0,
    }


def choose_threshold(
        dev_cases: list[dict[str, Any]],
        args: argparse.Namespace,
) -> tuple[float, list[dict[str, Any]]]:
    points: list[dict[str, Any]] = []
    steps = int(math.floor(1.0 / args.threshold_step))
    for index in range(steps + 1):
        threshold = round(index * args.threshold_step, 6)
        summary = summarize(
            dev_cases,
            lambda case, t=threshold: select_candidates(
                case["rerankedCandidates"], t, args.final_k, args.max_per_card
            ),
        )
        rejection = float(summary["no_answer_rejection_rate"] or 0.0)
        section_hit = float(summary["section_hit_at_k"])
        summary["threshold"] = threshold
        summary["balanced_success"] = statistics.fmean((section_hit, rejection))
        points.append(summary)
    best = max(
        points,
        key=lambda point: (
            point["balanced_success"],
            point["no_answer_rejection_rate"] or 0.0,
            point["section_hit_at_k"],
            -point["threshold"],
        ),
    )
    return float(best["threshold"]), points


def compact_candidate(candidate: dict[str, Any], rank: int) -> dict[str, Any]:
    return {
        "rank": rank,
        "original_rank": candidate.get("originalRank"),
        "vector_id": candidate.get("vectorId"),
        "card_code": candidate.get("cardCode"),
        "title": candidate.get("title"),
        "heading_path": candidate.get("headingPath"),
        "dense_score": candidate.get("score"),
        "rerank_score": candidate.get("rerankScore"),
    }


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return round(ordered[index], 2)


def main() -> int:
    args = parse_args()
    if not args.rerank_api_key:
        raise RuntimeError("Set DASHSCOPE_API_KEY or pass --rerank-api-key")
    dataset_path = Path(args.dataset)
    dataset = read_dataset(dataset_path)
    if not 0 < args.dev_cases < len(dataset):
        raise ValueError("--dev-cases must split the dataset into non-empty dev and test sets")
    token = resolve_token(args)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    dataset_sha256 = hashlib.sha256(dataset_path.read_bytes()).hexdigest()
    checkpoint_path = output_dir / f"{args.label}-checkpoint.json"
    evaluated: list[dict[str, Any]] = []
    if checkpoint_path.exists():
        checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
        if (checkpoint.get("dataset_sha256") != dataset_sha256
                or checkpoint.get("rerank_model") != args.rerank_model):
            raise RuntimeError(f"Checkpoint does not match this run: {checkpoint_path}")
        evaluated = list(checkpoint.get("cases") or [])
        print(f"Resume checkpoint: {len(evaluated)}/{len(dataset)} cases")
    rerank_latencies: list[float] = [
        float(case.get("rerankLatencyMs") or 0.0) for case in evaluated
    ]

    for position, source_case in enumerate(dataset[len(evaluated):], start=len(evaluated) + 1):
        data = retrieve(args, token, str(source_case["query"]))
        raw_candidates = list(data.get("rawCandidates") or [])[: args.candidate_k]
        if not raw_candidates:
            reranked_candidates: list[dict[str, Any]] = []
            latency_ms = 0.0
        else:
            reranked_candidates, latency_ms = rerank(
                args, str(source_case["query"]), raw_candidates
            )
        rerank_latencies.append(latency_ms)
        dense_min_score = float(data.get("minScore") or 0.62)
        case = {
            **source_case,
            # 在线默认链路可能已经启用 Reranker；基准必须始终由原始向量候选离线重建，
            # 不能直接使用随线上策略变化的 hits。
            "baselineHits": select_dense_baseline(
                raw_candidates, dense_min_score, args.final_k, args.max_per_card
            ),
            "rerankedCandidates": reranked_candidates,
            "rerankLatencyMs": latency_ms,
            "runtimeConfig": {
                "collection": data.get("collectionName"),
                "embeddingHeadingPathEnabled": data.get("embeddingHeadingPathEnabled"),
                "denseMinScore": dense_min_score,
                "denseTopK": data.get("topK"),
                "onlineRerankEnabled": data.get("rerankEnabled"),
            },
        }
        evaluated.append(case)
        checkpoint_path.write_text(json.dumps({
            "dataset_sha256": dataset_sha256,
            "rerank_model": args.rerank_model,
            "cases": evaluated,
        }, ensure_ascii=False), encoding="utf-8")
        print(
            f"[{position:03d}/{len(dataset)}] {case['id']}: "
            f"candidates={len(raw_candidates)}, rerank={latency_ms:.0f}ms"
        )

    dev_cases = evaluated[: args.dev_cases]
    test_cases = evaluated[args.dev_cases :]
    threshold, threshold_points = choose_threshold(dev_cases, args)

    baseline_selector = lambda case: case["baselineHits"][: args.final_k]
    ranking_selector = lambda case: select_candidates(
        case["rerankedCandidates"], 0.0, args.final_k, args.max_per_card
    )
    tuned_selector = lambda case: select_candidates(
        case["rerankedCandidates"], threshold, args.final_k, args.max_per_card
    )
    report = {
        "schema_version": 1,
        "run": {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            "label": args.label,
            "dataset": str(dataset_path),
            "dataset_sha256": dataset_sha256,
            "rerank_model": args.rerank_model,
            "rerank_url": args.rerank_url,
            "candidate_k": args.candidate_k,
            "final_k": args.final_k,
            "max_per_card": args.max_per_card,
            "heading_path_deduplication": False,
            "dev_cases": args.dev_cases,
            "test_cases": len(test_cases),
            "selected_threshold": threshold,
            "threshold_selection": "Maximize mean(dev SectionHit@K, dev rejection rate)",
        },
        "summary": {
            "baseline_overall": summarize(evaluated, baseline_selector),
            "rerank_ranking_only_overall": summarize(evaluated, ranking_selector),
            "rerank_tuned_dev": summarize(dev_cases, tuned_selector),
            "rerank_tuned_test": summarize(test_cases, tuned_selector),
            "baseline_test": summarize(test_cases, baseline_selector),
            "rerank_latency_ms": {
                "mean": round(statistics.fmean(rerank_latencies), 2),
                "p50": percentile(rerank_latencies, 0.50),
                "p95": percentile(rerank_latencies, 0.95),
            },
        },
        "threshold_points": threshold_points,
        "cases": [],
    }
    for case in evaluated:
        selected = tuned_selector(case)
        report["cases"].append({
            "id": case["id"],
            "query": case["query"],
            "category": case.get("category"),
            "answerable": case["answerable"],
            "relevant_titles": case.get("relevant_titles", []),
            "relevant_sections": case.get("relevant_sections", []),
            "rerank_latency_ms": case["rerankLatencyMs"],
            "runtime_config": case["runtimeConfig"],
            "baseline_hits": [
                compact_candidate(hit, rank)
                for rank, hit in enumerate(case["baselineHits"][: args.final_k], start=1)
            ],
            "reranked_candidates": [
                compact_candidate(hit, rank)
                for rank, hit in enumerate(case["rerankedCandidates"], start=1)
            ],
            "selected_hits": [
                compact_candidate(hit, rank)
                for rank, hit in enumerate(selected, start=1)
            ],
        })

    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_path = output_dir / f"{timestamp}-{args.label}.json"
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report: {output_path}")
    print(f"Selected dev threshold: {threshold:.2f}")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
