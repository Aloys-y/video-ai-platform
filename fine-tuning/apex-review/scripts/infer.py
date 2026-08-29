from __future__ import annotations

import argparse
from pathlib import Path

import torch
from peft import PeftModel
from qwen_vl_utils import process_vision_info

from common import build_messages, extract_json_object, load_yaml, read_jsonl, write_jsonl
from modeling import load_base_model, load_processor


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="运行基座或 adapter 推理，输出可评测 JSONL")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--adapter", help="不传则为基座 B1；传入则独立重载 QLoRA adapter")
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config_path = Path(args.config).resolve()
    config = load_yaml(config_path)
    dataset_path = Path(args.input).resolve()
    records = read_jsonl(dataset_path)
    if args.limit:
        records = records[: args.limit]

    processor_source = Path(args.adapter).resolve() if args.adapter else None
    processor = load_processor(config, processor_source)
    model = load_base_model(config)
    if args.adapter:
        model = PeftModel.from_pretrained(model, str(processor_source), is_trainable=False)
    model.eval()

    results: list[dict] = []
    for index, record in enumerate(records, 1):
        messages = build_messages(record, dataset_path, include_answer=False)
        text = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        images, videos, video_kwargs = process_vision_info(messages, return_video_kwargs=True)
        inputs = processor(
            text=[text], images=images, videos=videos, padding=True, return_tensors="pt", **video_kwargs
        ).to(model.device)
        with torch.inference_mode():
            generated = model.generate(
                **inputs,
                max_new_tokens=config["generation"]["max_new_tokens"],
                do_sample=config["generation"]["do_sample"],
            )
        trimmed = generated[:, inputs.input_ids.shape[1] :]
        raw = processor.batch_decode(trimmed, skip_special_tokens=True)[0]
        prediction, parse_error = extract_json_object(raw)
        results.append(
            {
                "clip_id": record["clip_id"],
                "source_match_id": record["source_match_id"],
                "prediction": prediction,
                "parse_error": parse_error,
                "raw_text": raw,
                "model_variant": "adapter" if args.adapter else "base",
            }
        )
        print(f"[{index}/{len(records)}] {record['clip_id']} parse={'ok' if prediction else 'failed'}")
    write_jsonl(args.output, results)


if __name__ == "__main__":
    main()
