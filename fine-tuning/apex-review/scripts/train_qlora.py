from __future__ import annotations

import argparse
import json
import platform
import subprocess
import time
from pathlib import Path
from typing import Any

import torch
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from qwen_vl_utils import process_vision_info
from torch.utils.data import Dataset
from transformers import Trainer, TrainingArguments, set_seed

from common import build_messages, load_yaml, read_jsonl
from modeling import discover_language_targets, load_base_model, load_processor


class JsonlDataset(Dataset):
    def __init__(self, path: str, max_samples: int | None = None):
        self.path = str(Path(path).resolve())
        self.records = read_jsonl(self.path)
        if max_samples is not None:
            self.records = self.records[:max_samples]

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int) -> dict[str, Any]:
        return self.records[index]


class ApexVideoCollator:
    def __init__(self, processor, dataset_path: str):
        self.processor = processor
        self.dataset_path = dataset_path

    def encode(self, messages: list[dict[str, Any]], add_generation_prompt: bool):
        text = self.processor.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=add_generation_prompt
        )
        images, videos, video_kwargs = process_vision_info(messages, return_video_kwargs=True)
        return self.processor(
            text=[text], images=images, videos=videos, padding=True, return_tensors="pt", **video_kwargs
        )

    def __call__(self, features: list[dict[str, Any]]) -> dict[str, torch.Tensor]:
        if len(features) != 1:
            raise ValueError("当前实现强制 batch_size=1；多样本视频 padding 未经验证")
        record = features[0]
        prompt = build_messages(record, self.dataset_path, include_answer=False)
        full = build_messages(record, self.dataset_path, include_answer=True)
        # prompt 侧包含 assistant 起始标记，因此 loss 只覆盖真正的答案内容。
        prompt_batch = self.encode(prompt, add_generation_prompt=True)
        full_batch = self.encode(full, add_generation_prompt=False)
        prompt_ids = prompt_batch["input_ids"][0]
        full_ids = full_batch["input_ids"][0]
        if len(prompt_ids) >= len(full_ids) or not torch.equal(full_ids[: len(prompt_ids)], prompt_ids):
            raise RuntimeError(
                f"{record['clip_id']}: prompt 不是 full input 的前缀，不能安全构造 labels"
            )
        labels = full_ids.clone()
        labels[: len(prompt_ids)] = -100
        labels[full_batch["attention_mask"][0] == 0] = -100
        for token_name in ("image_token_id", "video_token_id"):
            token_id = getattr(self.processor, token_name, None)
            if token_id is not None:
                labels[full_ids == token_id] = -100
        if int((labels != -100).sum()) == 0:
            raise RuntimeError(f"{record['clip_id']}: assistant labels 全被屏蔽")
        result = dict(full_batch)
        result["labels"] = labels.unsqueeze(0)
        return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qwen2.5-VL APEX 复盘 QLoRA")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--max-train-samples", type=int)
    parser.add_argument("--max-eval-samples", type=int)
    parser.add_argument("--epochs", type=float)
    parser.add_argument("--output-dir")
    return parser.parse_args()


def git_revision() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    except Exception:
        return None


def gradient_probe(model, batch: dict[str, torch.Tensor]) -> dict[str, int]:
    model.train()
    moved = {key: value.to(model.device) if torch.is_tensor(value) else value for key, value in batch.items()}
    loss = model(**moved).loss
    if not torch.isfinite(loss):
        raise RuntimeError(f"首批次 loss 非有限值: {loss.item()}")
    loss.backward()
    trainable = [(name, parameter) for name, parameter in model.named_parameters() if parameter.requires_grad]
    nonzero = sum(
        1
        for _, parameter in trainable
        if parameter.grad is not None and bool(torch.count_nonzero(parameter.grad).item())
    )
    model.zero_grad(set_to_none=True)
    if nonzero == 0:
        raise RuntimeError("首批次所有可训练参数梯度均为 0，停止训练")
    return {"trainable_tensors": len(trainable), "nonzero_gradient_tensors": nonzero}


def main() -> None:
    args = parse_args()
    config_path = Path(args.config).resolve()
    config = load_yaml(config_path)
    seed = int(config["project"]["seed"])
    set_seed(seed)
    torch.backends.cuda.matmul.allow_tf32 = True
    root = config_path.parent
    train_cfg = config["training"]
    train_path = str((root / train_cfg["train_file"]).resolve())
    eval_path = str((root / train_cfg["eval_file"]).resolve())
    output_dir = Path(args.output_dir or (root / train_cfg["output_dir"])).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    processor = load_processor(config)
    model = load_base_model(config)
    resolved_model_revision = getattr(model.config, "_commit_hash", None)
    model = prepare_model_for_kbit_training(
        model,
        use_gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    model.config.use_cache = False
    targets = discover_language_targets(model, config["lora"]["target_suffixes"])
    lora_cfg = config["lora"]
    model = get_peft_model(
        model,
        LoraConfig(
            r=lora_cfg["rank"],
            lora_alpha=lora_cfg["alpha"],
            lora_dropout=lora_cfg["dropout"],
            bias=lora_cfg["bias"],
            task_type="CAUSAL_LM",
            target_modules=targets,
        ),
    )
    wrong = [
        name
        for name, parameter in model.named_parameters()
        if parameter.requires_grad and ("lora_" not in name or "language_model" not in name)
    ]
    if wrong:
        raise RuntimeError(f"存在非语言层 LoRA 可训练参数: {wrong[:10]}")
    trainable_count = sum(parameter.numel() for parameter in model.parameters() if parameter.requires_grad)
    all_count = sum(parameter.numel() for parameter in model.parameters())
    print(f"LoRA 目标模块: {len(targets)}；可训练参数: {trainable_count:,}/{all_count:,}")

    train_data = JsonlDataset(train_path, args.max_train_samples)
    eval_data = JsonlDataset(eval_path, args.max_eval_samples)
    if not train_data or not eval_data:
        raise SystemExit("训练集和验证集均不能为空")
    train_collator = ApexVideoCollator(processor, train_path)
    eval_collator = ApexVideoCollator(processor, eval_path)
    probe = gradient_probe(model, train_collator([train_data[0]]))
    print(f"梯度探针通过: {probe}")

    epochs = args.epochs if args.epochs is not None else train_cfg["num_train_epochs"]
    training_args = TrainingArguments(
        output_dir=str(output_dir),
        num_train_epochs=epochs,
        learning_rate=train_cfg["learning_rate"],
        per_device_train_batch_size=train_cfg["per_device_train_batch_size"],
        per_device_eval_batch_size=train_cfg["per_device_eval_batch_size"],
        gradient_accumulation_steps=train_cfg["gradient_accumulation_steps"],
        warmup_ratio=train_cfg["warmup_ratio"],
        weight_decay=train_cfg["weight_decay"],
        max_grad_norm=train_cfg["max_grad_norm"],
        logging_steps=train_cfg["logging_steps"],
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=train_cfg["save_total_limit"],
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=True,
        tf32=True,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        optim="adamw_torch",
        lr_scheduler_type="cosine",
        remove_unused_columns=False,
        report_to=["tensorboard"],
        seed=seed,
        data_seed=seed,
    )
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_data,
        eval_dataset=eval_data,
        data_collator=train_collator,
    )
    # Trainer 只接收一个 collator；eval 数据路径不同，因此在评估时临时切换。
    original_evaluate = trainer.evaluate

    def evaluate_with_eval_collator(*evaluate_args, **evaluate_kwargs):
        previous = trainer.data_collator
        trainer.data_collator = eval_collator
        try:
            return original_evaluate(*evaluate_args, **evaluate_kwargs)
        finally:
            trainer.data_collator = previous

    trainer.evaluate = evaluate_with_eval_collator  # type: ignore[method-assign]
    started = time.time()
    result = trainer.train()
    adapter_dir = output_dir / "adapter"
    trainer.model.save_pretrained(adapter_dir, safe_serialization=True)
    # processor 最后保存，防止外部模板覆盖训练时使用的 chat template。
    processor.save_pretrained(adapter_dir)
    peak_vram = torch.cuda.max_memory_allocated() if torch.cuda.is_available() else 0
    manifest = {
        "project": config["project"]["name"],
        "base_model": config["model"]["id"],
        "requested_model_revision": config["model"].get("revision"),
        "resolved_model_revision": resolved_model_revision,
        "git_revision": git_revision(),
        "python": platform.python_version(),
        "torch": torch.__version__,
        "target_modules": targets,
        "trainable_parameters": trainable_count,
        "total_parameters": all_count,
        "gradient_probe": probe,
        "train_samples": len(train_data),
        "eval_samples": len(eval_data),
        "epochs": epochs,
        "runtime_seconds": time.time() - started,
        "peak_vram_bytes": peak_vram,
        "train_metrics": result.metrics,
    }
    (output_dir / "run_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"训练完成；adapter: {adapter_dir}")


if __name__ == "__main__":
    main()
