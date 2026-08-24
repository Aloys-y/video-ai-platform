#!/bin/bash
# ============================================================================
# 部署基座模型（未微调）—— 先验证部署管线能跑通
# 用 LLaMA-Factory API 模式（transformers 推理），不依赖 vLLM，兼容训练环境
# ============================================================================
set -e

# ---- Conda 环境（复用训练环境，不搞第二个） ----
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
cd "${PROJECT_DIR}/LLaMA-Factory"

# 国内 HuggingFace 镜像
export HF_ENDPOINT=${HF_ENDPOINT:-https://hf-mirror.com}

echo "=============================================="
echo "  部署基座模型: Qwen2.5-VL-7B-Instruct"
echo "  后端:        transformers (不依赖 vLLM)"
echo "  GPU:         GPU 0,1"
echo "  API:         http://0.0.0.0:8000/v1"
echo "  Conda:       ${CONDA_ENV}"
echo "=============================================="

# LLaMA-Factory 内置 API 服务，OpenAI 兼容格式
export API_PORT=8000

# load_in_8bit 把模型从 16GB 压到 8GB，确保不溢出到 CPU
CUDA_VISIBLE_DEVICES=0,1 llamafactory-cli api \
    --model_name_or_path Qwen/Qwen2.5-VL-7B-Instruct \
    --template qwen2_vl \
    --load_in_8bit true

echo ""
echo "=== API 服务已启动 ==="
echo "测试: curl http://localhost:8000/v1/chat/completions -H 'Content-Type: application/json' -d '{\"model\":\"qwen2.5-vl-7b-instruct\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"
