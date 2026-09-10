"""验收 JSONL -> 逐调用账本；按官网标价计算，不推断未知用量或账户优惠。"""
import argparse
import csv
import json
from decimal import Decimal
from pathlib import Path

PRICE_SOURCE = "https://help.aliyun.com/zh/model-studio/model-pricing"
VL_SOURCE = "https://help.aliyun.com/zh/model-studio/qwen3-vl-flash"


def number(data, *names):
    for name in names:
        value = data.get(name)
        if isinstance(value, (int, float)):
            return Decimal(str(value))
    return None


def price(call, seen_asr):
    usage = call.get("usage") or {}
    model, operation = call.get("model", ""), call["operation"]
    row = {key: call.get(key, "") for key in ("callId", "taskId", "attemptNo", "service", "operation", "model", "status", "startedAt", "elapsedMs", "requestId", "remoteTaskId")}
    row.update(input_tokens="", output_tokens="", audio_seconds="", list_price_cny="", note="")
    if call["status"] != "RETURNED":
        row["note"] = "未取得完整回执，用量与扣费未知；不按零费用计算"
        return row
    if "Asr" in call["service"]:
        if operation == "query" and call.get("remoteStatus") == "SUCCEEDED":
            seconds = number(usage, "duration")
            task = call.get("remoteTaskId")
            if task in seen_asr:
                row.update(list_price_cny="0", note="同一 ASR 任务重复查询，不重复累计转写用量")
            elif seconds is not None and model == "fun-asr-2025-11-07":
                seen_asr.add(task)
                row.update(audio_seconds=str(seconds), list_price_cny=str(seconds * Decimal("0.00022")), note="按音频秒计费；此行归集该 ASR 任务的费用，非查询请求收费")
            else:
                row["note"] = "ASR 用量或模型价格未知"
        else:
            row.update(list_price_cny="0", note="提交/状态查询/结果下载；ASR 费用归集到成功任务回执，不重复计费")
        return row
    inputs = number(usage, "input_tokens", "inputTokens", "prompt_tokens")
    outputs = number(usage, "output_tokens", "outputTokens", "completion_tokens")
    if model in ("text-embedding-v3", "qwen3-rerank"):
        if inputs is None:
            inputs = number(usage, "total_tokens", "totalTokens", "tokens")
        outputs = Decimal(0)
        if inputs is not None:
            row["list_price_cny"] = str(inputs * Decimal("0.5") / 1000000)
            row["note"] = "华北2标价 0.5元/百万输入Token；未抵扣免费额度"
    elif model == "qwen3-vl-flash" and inputs is not None and outputs is not None:
        multiplier = Decimal(1 if inputs <= 32768 else 2 if inputs <= 131072 else 4)
        input_rate, output_rate = Decimal("0.15") * multiplier, Decimal("1.5") * multiplier
        row["list_price_cny"] = str((inputs * input_rate + outputs * output_rate) / 1000000)
        row["note"] = f"华北2普通调用标价：输入{input_rate}、输出{output_rate}元/百万Token；未扣缓存/免费额度"
    row.update(input_tokens="" if inputs is None else str(inputs), output_tokens="" if outputs is None else str(outputs))
    if row["list_price_cny"] == "":
        row["note"] = "厂商未报告可核算用量或价格未配置，费用未知"
    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    calls = {}
    for line in (args.directory / "external-calls.jsonl").read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        calls[event["callId"]] = event
    seen_asr = set()
    rows = [price(call, seen_asr) for call in calls.values()]
    if not rows:
        raise SystemExit("没有外部调用记录")
    with (args.directory / "call-costs.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    total = sum((Decimal(row["list_price_cny"]) for row in rows if row["list_price_cny"] != ""), Decimal(0))
    summary = {"price_checked_on": "2026-09-10", "region": "华北2（北京）", "currency": "CNY", "known_list_price_total": str(total),
               "unknown_usage_call_count": sum(row["list_price_cny"] == "" for row in rows), "external_call_count": len(rows),
               "price_sources": [PRICE_SOURCE, VL_SOURCE], "account_deduction": "未查询账户账单；标价不等于实际扣费", "calls": rows}
    (args.directory / "cost-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key != "calls"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
