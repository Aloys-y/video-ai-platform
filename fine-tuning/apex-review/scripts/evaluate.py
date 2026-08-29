from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean
from typing import Any

import jsonschema

from common import read_jsonl


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="评测 JSON 合法率、复盘标签与事件时间")
    parser.add_argument("--references", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--schema", required=True)
    parser.add_argument("--tiou-threshold", type=float, default=0.3)
    parser.add_argument("--output", help="保存 metrics.json")
    return parser.parse_args()


def tiou(left: dict[str, Any], right: dict[str, Any]) -> float:
    intersection = max(0.0, min(left["end_sec"], right["end_sec"]) - max(left["start_sec"], right["start_sec"]))
    union = max(left["end_sec"], right["end_sec"]) - min(left["start_sec"], right["start_sec"])
    return intersection / union if union > 0 else float(left["start_sec"] == right["start_sec"])


def match_events(gold: list[dict], predicted: list[dict], threshold: float):
    candidates = sorted(
        (
            (tiou(gold_item, pred_item), gold_index, pred_index)
            for gold_index, gold_item in enumerate(gold)
            for pred_index, pred_item in enumerate(predicted)
            if gold_item.get("type") == pred_item.get("type")
        ),
        reverse=True,
    )
    used_gold: set[int] = set()
    used_pred: set[int] = set()
    matches: list[tuple[int, int, float]] = []
    for score, gold_index, pred_index in candidates:
        if score < threshold:
            break
        if gold_index not in used_gold and pred_index not in used_pred:
            used_gold.add(gold_index)
            used_pred.add(pred_index)
            matches.append((gold_index, pred_index, score))
    return matches


def prf(true_positive: int, predicted: int, gold: int) -> dict[str, float]:
    precision = true_positive / predicted if predicted else 0.0
    recall = true_positive / gold if gold else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"precision": precision, "recall": recall, "f1": f1}


def main() -> None:
    args = parse_args()
    schema = json.loads(Path(args.schema).read_text(encoding="utf-8"))
    # 评测的是 answer 子结构，而训练样本 schema 的根节点是完整 record。
    answer_schema = {"$ref": "#/$defs/review", "$defs": schema["$defs"]}
    validator = jsonschema.Draft202012Validator(answer_schema)
    references = {record["clip_id"]: record for record in read_jsonl(args.references)}
    predictions = {record["clip_id"]: record for record in read_jsonl(args.predictions)}
    missing_ids = sorted(set(references) - set(predictions))
    extra_ids = sorted(set(predictions) - set(references))

    valid = 0
    review_tp = review_pred = review_gold = 0
    event_tp = event_pred = event_gold = 0
    temporal_scores: list[float] = []
    center_errors: list[float] = []
    severity_correct = severity_total = 0
    per_clip: list[dict] = []

    for clip_id, reference in references.items():
        prediction = predictions.get(clip_id, {}).get("prediction")
        schema_errors = [] if isinstance(prediction, dict) else ["prediction 为空或不是对象"]
        if isinstance(prediction, dict):
            schema_errors.extend(error.message for error in validator.iter_errors(prediction))
        if not schema_errors:
            valid += 1
        prediction = prediction if isinstance(prediction, dict) else {}
        gold_answer = reference["answer"]

        gold_review_keys = {
            (item["category"], item["verdict"]): item for item in gold_answer.get("reviews", [])
        }
        pred_review_keys = {
            (item.get("category"), item.get("verdict")): item
            for item in prediction.get("reviews", [])
            if isinstance(item, dict)
        }
        common = set(gold_review_keys) & set(pred_review_keys)
        review_tp += len(common)
        review_gold += len(gold_review_keys)
        review_pred += len(pred_review_keys)
        for key in common:
            severity_total += 1
            severity_correct += int(gold_review_keys[key].get("severity") == pred_review_keys[key].get("severity"))

        gold_events = gold_answer.get("events", [])
        pred_events = [item for item in prediction.get("events", []) if isinstance(item, dict)]
        safe_pred_events = [
            item
            for item in pred_events
            if all(isinstance(item.get(key), (int, float)) for key in ("start_sec", "end_sec"))
        ]
        matches = match_events(gold_events, safe_pred_events, args.tiou_threshold)
        event_tp += len(matches)
        event_gold += len(gold_events)
        event_pred += len(pred_events)
        for gold_index, pred_index, score in matches:
            temporal_scores.append(score)
            gold_center = (gold_events[gold_index]["start_sec"] + gold_events[gold_index]["end_sec"]) / 2
            pred_center = (safe_pred_events[pred_index]["start_sec"] + safe_pred_events[pred_index]["end_sec"]) / 2
            center_errors.append(abs(gold_center - pred_center))
        per_clip.append({"clip_id": clip_id, "schema_errors": schema_errors, "event_matches": len(matches)})

    total = len(references)
    metrics = {
        "samples": total,
        "schema_valid_rate": valid / total if total else 0.0,
        "missing_prediction_ids": missing_ids,
        "extra_prediction_ids": extra_ids,
        "review_category_verdict_micro": prf(review_tp, review_pred, review_gold),
        "review_severity_accuracy_on_matched": severity_correct / severity_total if severity_total else 0.0,
        "event_type_temporal_micro": prf(event_tp, event_pred, event_gold),
        "matched_event_mean_tiou": mean(temporal_scores) if temporal_scores else 0.0,
        "matched_event_center_mae_seconds": mean(center_errors) if center_errors else None,
        "event_hallucination_rate": (event_pred - event_tp) / event_pred if event_pred else 0.0,
        "event_omission_rate": (event_gold - event_tp) / event_gold if event_gold else 0.0,
        "per_clip": per_clip,
    }
    rendered = json.dumps(metrics, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
