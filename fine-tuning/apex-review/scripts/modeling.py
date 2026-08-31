from __future__ import annotations

from pathlib import Path
from typing import Any

import torch

from common import pixel_bounds


def quantization_config(config: dict[str, Any]):
    from transformers import BitsAndBytesConfig

    quant = config["model"]["quantization"]
    return BitsAndBytesConfig(
        load_in_4bit=quant["load_in_4bit"],
        bnb_4bit_quant_type=quant["quant_type"],
        bnb_4bit_use_double_quant=quant["double_quant"],
        bnb_4bit_compute_dtype=torch.bfloat16,
    )


def load_processor(config: dict[str, Any], source: str | Path | None = None):
    from transformers import AutoProcessor

    minimum, maximum = pixel_bounds(config)
    return AutoProcessor.from_pretrained(
        str(source or config["model"]["id"]),
        revision=config["model"].get("revision"),
        min_pixels=minimum,
        max_pixels=maximum,
        trust_remote_code=config["model"].get("trust_remote_code", False),
    )


def load_base_model(config: dict[str, Any]):
    from transformers import Qwen2_5_VLForConditionalGeneration

    model_cfg = config["model"]
    return Qwen2_5_VLForConditionalGeneration.from_pretrained(
        model_cfg["id"],
        revision=model_cfg.get("revision"),
        quantization_config=quantization_config(config),
        device_map={"": 0},
        torch_dtype=torch.bfloat16,
        attn_implementation=model_cfg.get("attention", "sdpa"),
        trust_remote_code=model_cfg.get("trust_remote_code", False),
    )


def discover_language_targets(model: torch.nn.Module, suffixes: list[str]) -> list[str]:
    wanted = set(suffixes)
    targets = sorted(
        name
        for name, _ in model.named_modules()
        if "language_model.layers." in name and name.rsplit(".", 1)[-1] in wanted
    )
    if not targets:
        raise RuntimeError("未发现语言模型 LoRA 目标层；停止训练，避免误挂视觉塔")
    forbidden = [name for name in targets if "visual" in name.lower() or "merger" in name.lower()]
    if forbidden:
        raise RuntimeError(f"LoRA 目标错误地包含视觉模块: {forbidden[:5]}")
    return targets
