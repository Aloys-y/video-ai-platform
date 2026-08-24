#!/bin/bash
# ============================================================================
# LoRA 权重合并 + 导出 - 将 LoRA adapter 合并到基座模型
# ============================================================================
set -e

# ---- Conda 环境 ----
CONDA_ENV="${CONDA_ENV:-videoai-finetune}"
CONDA_SH=""
if [ -n "${CONDA_EXE}" ]; then
    CONDA_DIR="$(dirname "$(dirname "${CONDA_EXE}")")"
    CONDA_SH="${CONDA_DIR}/etc/profile.d/conda.sh"
elif [ -f "${HOME}/anaconda3/etc/profile.d/conda.sh" ]; then
    CONDA_SH="${HOME}/anaconda3/etc/profile.d/conda.sh"
elif [ -f "${HOME}/miniconda3/etc/profile.d/conda.sh" ]; then
    CONDA_SH="${HOME}/miniconda3/etc/profile.d/conda.sh"
fi
[ -n "${CONDA_SH}" ] && [ -f "${CONDA_SH}" ] && source "${CONDA_SH}"
conda activate "${CONDA_ENV}"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 找到最新的 checkpoint
LATEST_CKPT=$(ls -d "${PROJECT_DIR}/output/qwen2.5-vl-7b-lora/checkpoint-"* 2>/dev/null | sort -V | tail -1)

if [ -z "$LATEST_CKPT" ]; then
    echo "错误: 未找到 checkpoint，请先运行 train.sh"
    exit 1
fi

echo "==================================="
echo "  LoRA 权重合并"
echo "  Checkpoint: ${LATEST_CKPT}"
echo "  导出到:    ${PROJECT_DIR}/output/qwen2.5-vl-7b-merged"
echo "  Conda:     ${CONDA_ENV}"
echo "==================================="

# export.py 必须从 LLaMA-Factory 源码目录运行
cd "${PROJECT_DIR}/LLaMA-Factory"

CUDA_VISIBLE_DEVICES=0 python src/export.py \
    --model_name_or_path Qwen/Qwen2.5-VL-7B-Instruct \
    --adapter_name_or_path "${LATEST_CKPT}" \
    --template qwen2_vl \
    --finetuning_type lora \
    --export_dir "${PROJECT_DIR}/output/qwen2.5-vl-7b-merged" \
    --export_size 2 \
    --export_legacy_format false

echo ""
echo "=== 合并完成 ==="
echo "合并后模型: output/qwen2.5-vl-7b-merged/"
echo "模型大小: $(du -sh ${PROJECT_DIR}/output/qwen2.5-vl-7b-merged/ | cut -f1)"
echo "下一步: bash scripts/serve.sh"
