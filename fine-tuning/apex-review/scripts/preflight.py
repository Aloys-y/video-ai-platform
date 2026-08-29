from __future__ import annotations

import argparse
import importlib.metadata
import json
import shutil
from pathlib import Path

from common import load_yaml


REQUIRED = {
    "torch": "2.6.0",
    "torchvision": "0.21.0",
    "transformers": "4.57.1",
    "accelerate": "1.7.0",
    "peft": "0.17.1",
    "bitsandbytes": "0.48.0",
    "qwen-vl-utils": "0.0.14",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="租用 GPU 后的环境与模型加载门禁")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--load-model", action="store_true", help="下载并实际加载 4-bit 基座模型")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_yaml(args.config)
    errors: list[str] = []
    versions: dict[str, str | None] = {}
    for package, expected in REQUIRED.items():
        try:
            actual = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            actual = None
        versions[package] = actual
        if actual is None or actual.split("+", 1)[0] != expected:
            errors.append(f"{package}: 期望 {expected}，实际 {actual}")

    import torch

    gpu = None
    if not torch.cuda.is_available():
        errors.append("CUDA 不可用")
    else:
        properties = torch.cuda.get_device_properties(0)
        gpu = {
            "name": properties.name,
            "vram_gib": round(properties.total_memory / 1024**3, 2),
            "bf16_supported": torch.cuda.is_bf16_supported(),
            "cuda_runtime": torch.version.cuda,
        }
        if properties.total_memory < 22 * 1024**3:
            errors.append(f"显存不足 22 GiB: {gpu['vram_gib']} GiB")
        if not torch.cuda.is_bf16_supported():
            errors.append("GPU 不支持 BF16")
    if shutil.disk_usage(Path(args.config).resolve().parent).free < 50 * 1024**3:
        errors.append("当前磁盘可用空间不足 50 GiB")

    model_loaded = False
    if args.load_model and not errors:
        from modeling import discover_language_targets, load_base_model, load_processor

        load_processor(config)
        model = load_base_model(config)
        targets = discover_language_targets(model, config["lora"]["target_suffixes"])
        model_loaded = bool(targets)
        del model
        torch.cuda.empty_cache()
    report = {"versions": versions, "gpu": gpu, "model_loaded": model_loaded, "errors": errors}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
