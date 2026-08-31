#!/bin/bash
# ============================================================================
# 训练启动脚本 - 4x RTX 4090 QLoRA 微调
# 使用 DeepSpeed ZeRO-2 + DDP 跨4卡并行
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

# ---- 国内服务器 HuggingFace 镜像 ----
export HF_ENDPOINT=https://hf-mirror.com

# ---- 4x 4090 环境变量优化 ----
# 4090 无 NVLink，通过 PCIe 通信，关闭 P2P 避免跨卡直连问题
export NCCL_P2P_DISABLE=1
export NCCL_IB_DISABLE=1
export NCCL_SOCKET_IFNAME=^lo,docker
export NCCL_DEBUG=WARN

# 项目根目录（fine-tuning/）
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "==================================="
echo "  Qwen2.5-VL-7B QLoRA 微调"
echo "  GPU:  0,1,2 (3x RTX 4090)"
echo "  模型: Qwen2.5-VL-7B-Instruct"
echo "  方法: QLoRA (4-bit) + DeepSpeed ZeRO-2"
echo "  Conda: ${CONDA_ENV}"
echo "==================================="

# ---- 检查 GPU ----
echo ""
echo "GPU 状态:"
nvidia-smi --query-gpu=index,name,memory.total,memory.free --format=csv,noheader

# ---- 启动训练 ----
# train.py 必须从 LLaMA-Factory 源码目录运行
cd "${PROJECT_DIR}/LLaMA-Factory"

CUDA_VISIBLE_DEVICES=0,1,2 python src/train.py \
    --stage sft \
    --config "${PROJECT_DIR}/train_config.yaml" \
    --dataset_dir "${PROJECT_DIR}"

echo ""
echo "=== 训练完成 ==="
echo "LoRA 权重保存在: output/qwen2.5-vl-7b-lora/"
echo "下一步: bash scripts/merge.sh"
