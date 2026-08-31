#!/bin/bash
# ============================================================================
# vLLM 模型部署 - 暴露 OpenAI 兼容 API
# 使用 2-4 张 4090 做张量并行推理
# ============================================================================
set -e

# ---- Conda 环境（vLLM 专用，独立于训练环境） ----
CONDA_ENV="${CONDA_ENV:-videoai-serve}"
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

cd "$(dirname "$0")/.."
MERGED_MODEL="$(pwd)/output/qwen2.5-vl-7b-merged"

if [ ! -d "$MERGED_MODEL" ]; then
    echo "错误: 未找到合并后的模型 $MERGED_MODEL"
    echo "请先运行 scripts/merge.sh"
    exit 1
fi

# 推理用 2 张 GPU 即可（7B 模型 2x4090 足够）
# 如需全速，改为 4
TENSOR_PARALLEL=${TENSOR_PARALLEL:-2}

echo "==================================="
echo "  vLLM 模型部署"
echo "  模型:      ${MERGED_MODEL}"
echo "  服务名:     my-finetuned-model"
echo "  GPU:        GPU 0,1 (TP=${TENSOR_PARALLEL})"
echo "  API地址:    http://0.0.0.0:8000/v1"
echo "  Swagger:    http://0.0.0.0:8000/docs"
echo "  Conda:     ${CONDA_ENV}"
echo "==================================="

CUDA_VISIBLE_DEVICES=0,1,2,3 python -m vllm.entrypoints.openai.api_server \
    --model "${MERGED_MODEL}" \
    --served-model-name my-finetuned-model \
    --tensor-parallel-size "${TENSOR_PARALLEL}" \
    --gpu-memory-utilization 0.85 \
    --max-model-len 32768 \
    --host 0.0.0.0 \
    --port 8000 \
    --trust-remote-code

echo ""
echo "=== vLLM 服务已启动 ==="
echo "健康检查: curl http://localhost:8000/health"
echo "测试调用: curl http://localhost:8000/v1/chat/completions -H 'Content-Type: application/json' -d '{...}'"
