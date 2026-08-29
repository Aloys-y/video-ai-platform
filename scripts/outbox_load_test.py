#!/usr/bin/env python3
"""Outbox -> Kafka -> Worker 全链路压测驱动（仅使用 Python 标准库）。"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import pathlib
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1))
    return round(ordered[index], 2)


def request_json(method: str, url: str, token: str, payload: dict | None = None,
                 run_id: str | None = None, timeout: float = 10.0) -> dict:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "X-Load-Test-Token": token,
    }
    if body is not None:
        headers["Content-Type"] = "application/json"
    if run_id is not None:
        headers["X-Load-Test-Run-Id"] = run_id
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        response_body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code}: {response_body}") from error
    if result.get("code") not in (0, 200):
        raise RuntimeError(f"API error: {result}")
    return result.get("data", result)


def main() -> int:
    parser = argparse.ArgumentParser(description="运行 Outbox 全链路缩时压测")
    parser.add_argument("--base-url", default="http://localhost:8080/api")
    parser.add_argument("--token", default="local-outbox-load-test")
    parser.add_argument("--run-id", default=datetime.now().strftime("lt%Y%m%d%H%M%S"))
    parser.add_argument("--tasks", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=20,
                        help="压测客户端并发，不是 Worker consumer concurrency")
    parser.add_argument("--submit-rate", type=float, default=0,
                        help="每秒提交数；0 表示不主动限速")
    parser.add_argument("--completion-timeout", type=float, default=180)
    parser.add_argument("--poll-interval", type=float, default=1)
    parser.add_argument("--output-dir", default="architecture/load-test/results")
    args = parser.parse_args()

    if args.tasks <= 0 or args.concurrency <= 0:
        parser.error("--tasks and --concurrency must be positive")

    tasks_url = args.base_url.rstrip("/") + "/load-test/tasks"
    report_url = args.base_url.rstrip("/") + "/load-test/report"
    submit_latencies: list[float] = []
    submit_errors: list[dict] = []
    lock = threading.Lock()

    def submit(sequence: int) -> None:
        started = time.perf_counter()
        try:
            request_json("POST", tasks_url, args.token,
                         {"runId": args.run_id, "sequence": sequence})
            latency_ms = (time.perf_counter() - started) * 1000
            with lock:
                submit_latencies.append(latency_ms)
        except Exception as error:  # noqa: BLE001 - 压测需要汇总所有客户端错误
            with lock:
                submit_errors.append({"sequence": sequence, "error": str(error)})

    print(f"[load-test] run={args.run_id} tasks={args.tasks} clientConcurrency={args.concurrency}")
    wall_started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = []
        next_submit_at = time.perf_counter()
        for sequence in range(args.tasks):
            if args.submit_rate > 0:
                wait_seconds = next_submit_at - time.perf_counter()
                if wait_seconds > 0:
                    time.sleep(wait_seconds)
                next_submit_at += 1.0 / args.submit_rate
            futures.append(executor.submit(submit, sequence))
        concurrent.futures.wait(futures)
    submit_elapsed = time.perf_counter() - wall_started

    print(f"[load-test] submitted={len(submit_latencies)} errors={len(submit_errors)} "
          f"elapsed={submit_elapsed:.2f}s")
    if submit_errors:
        print(json.dumps(submit_errors[:10], ensure_ascii=False, indent=2), file=sys.stderr)

    deadline = time.monotonic() + args.completion_timeout
    report: dict = {}
    while time.monotonic() < deadline:
        report = request_json("GET", report_url, args.token, run_id=args.run_id)
        total = int(report.get("total", 0))
        terminal = int(report.get("terminal", 0))
        statuses = report.get("taskStatuses", {})
        print(f"[load-test] progress={terminal}/{total} statuses={statuses}")
        if total == len(submit_latencies) and terminal == total:
            break
        time.sleep(args.poll_interval)

    wall_elapsed = time.perf_counter() - wall_started
    client_summary = {
        "accepted": len(submit_latencies),
        "errors": len(submit_errors),
        "submissionElapsedSeconds": round(submit_elapsed, 3),
        "submissionThroughputPerSecond": round(len(submit_latencies) / max(submit_elapsed, 0.001), 2),
        "latencyMs": {
            "min": round(min(submit_latencies), 2) if submit_latencies else 0,
            "avg": round(statistics.fmean(submit_latencies), 2) if submit_latencies else 0,
            "p50": percentile(submit_latencies, 0.50),
            "p95": percentile(submit_latencies, 0.95),
            "p99": percentile(submit_latencies, 0.99),
            "max": round(max(submit_latencies), 2) if submit_latencies else 0,
        },
    }
    result = {
        "runId": args.run_id,
        "requestedTasks": args.tasks,
        "clientConcurrency": args.concurrency,
        "submitRate": args.submit_rate,
        "wallElapsedSeconds": round(wall_elapsed, 3),
        "client": client_summary,
        "server": report,
        "submissionErrors": submit_errors,
    }

    output_dir = pathlib.Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{args.run_id}.json"
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"[load-test] result={output_path.resolve()}")

    timed_out = int(report.get("terminal", 0)) != len(submit_latencies)
    failed = int(report.get("taskStatuses", {}).get("FAILED", 0)) > 0
    return 1 if submit_errors or timed_out or failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
