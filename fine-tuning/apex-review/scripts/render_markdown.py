from __future__ import annotations

import argparse
from typing import Any

from common import read_jsonl, write_jsonl


SECTION_CATEGORIES = {
    "走位 & 身位控制": {"positioning", "cover", "movement", "rotation"},
    "枪法 & 预瞄": {"aim"},
    "团战决策": {"teamfight", "information", "decision"},
    "道具使用": {"resource", "ability"},
}


def review_line(item: dict[str, Any]) -> str:
    level = {1: "轻微", 2: "明显", 3: "关键"}.get(item.get("severity"), "未分级")
    return (
        f"- **{item.get('verdict', 'unknown')} / {level}**：{item.get('evidence', '')} "
        f"原因：{item.get('reason', '')} 建议：{item.get('suggestion', '')}"
    )


def render(review: dict[str, Any]) -> str:
    sections: list[str] = [
        "## 对局总览",
        f"{review.get('clip_summary', '')}\n\n结果：`{review.get('result', 'unknown')}`",
        "## 高光时刻",
        "\n".join(f"- {item}" for item in review.get("positive_actions", [])) or "- 未识别到可确认的高光。",
    ]
    for title, categories in SECTION_CATEGORIES.items():
        lines = [
            review_line(item)
            for item in review.get("reviews", [])
            if item.get("category") in categories
        ]
        sections.extend([f"## {title}", "\n".join(lines) or "- 当前片段没有足够证据。"])
    mistakes = [
        review_line(item) for item in review.get("reviews", []) if item.get("verdict") == "mistake"
    ]
    sections.extend(["## 失误复盘", "\n".join(mistakes) or "- 未识别到有充分证据的明确失误。"])
    uncertainties = review.get("uncertainties", [])
    conclusion = "需谨慎的信息：" + "；".join(uncertainties) if uncertainties else "未记录额外不确定项。"
    sections.extend(["## 综合评价", conclusion])
    return "\n\n".join(sections)


def main() -> None:
    parser = argparse.ArgumentParser(description="把 apex_review.v1 预测映射为现有八段 Markdown")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output: list[dict[str, Any]] = []
    for record in read_jsonl(args.input):
        prediction = record.get("prediction")
        if not isinstance(prediction, dict):
            output.append({**record, "markdown": None, "render_error": "prediction 不是对象"})
        else:
            output.append({**record, "markdown": render(prediction), "render_error": None})
    write_jsonl(args.output, output)


if __name__ == "__main__":
    main()
