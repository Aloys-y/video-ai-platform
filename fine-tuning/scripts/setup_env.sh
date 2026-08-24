#!/bin/bash
# ============================================================================
# 环境初始化脚本 - 4x RTX 4090 (Ada Lovelace, Compute 8.9)
# 首次使用时执行一次即可
# ============================================================================
set -e

# ---- Conda 环境配置 ----
CONDA_ENV="${CONDA_ENV:-videoai-finetune}"

# 自动检测 conda 路径
CONDA_SH=""
if [ -n "${CONDA_EXE}" ]; then
    # 从 CONDA_EXE 反推 conda.sh（如 /home/cyy/anaconda3/bin/conda → .../etc/profile.d/conda.sh）
    CONDA_DIR="$(dirname "$(dirname "${CONDA_EXE}")")"
    CONDA_SH="${CONDA_DIR}/etc/profile.d/conda.sh"
elif [ -f "${HOME}/anaconda3/etc/profile.d/conda.sh" ]; then
    CONDA_SH="${HOME}/anaconda3/etc/profile.d/conda.sh"
elif [ -f "${HOME}/miniconda3/etc/profile.d/conda.sh" ]; then
    CONDA_SH="${HOME}/miniconda3/etc/profile.d/conda.sh"
elif [ -f "/opt/anaconda3/etc/profile.d/conda.sh" ]; then
    CONDA_SH="/opt/anaconda3/etc/profile.d/conda.sh"
fi

if [ -n "${CONDA_SH}" ] && [ -f "${CONDA_SH}" ]; then
    source "${CONDA_SH}"
else
    echo "ERROR: 未找到 conda，请确认 anaconda/miniconda 已安装"
    echo "手动指定: CONDA_SH=/path/to/conda.sh bash scripts/setup_env.sh"
    exit 1
fi

echo "=== 创建 Conda 环境: ${CONDA_ENV} (Python 3.11, CUDA 12.4) ==="
if conda env list | grep -q "^${CONDA_ENV}\s"; then
    echo "Conda 环境已存在，跳过创建"
else
    conda create -n "${CONDA_ENV}" python=3.11 -y
fi
conda activate "${CONDA_ENV}"

echo "=== 检查 CUDA 环境 ==="
nvidia-smi

echo ""
echo "=== 安装 PyTorch 2.5.1 (CUDA 12.4) ==="
# pin 死版本，避免 PyTorch 自动升级到 cu130 导致驱动不兼容
pip install torch==2.5.1 torchvision==0.20.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cu124

echo ""
echo "=== 安装 LLaMA-Factory 及其依赖 ==="
cd "$(dirname "$0")/.."

if [ -d "LLaMA-Factory" ]; then
    echo "LLaMA-Factory 已存在，尝试 git pull 更新..."
    (cd LLaMA-Factory && git pull) || echo "  [警告] git pull 失败（网络问题），使用现有版本继续"
else
    # 国内用 gitee 镜像，GitHub 直连经常超时
    if git clone https://gitee.com/mirrors/LLaMA-Factory.git 2>/dev/null; then
        echo "  [OK] gitee 镜像 clone 成功"
    else
        git clone https://github.com/hiyouga/LLaMA-Factory.git || {
            echo "ERROR: LLaMA-Factory clone 失败，请手动下载后放入 fine-tuning/LLaMA-Factory/"
            exit 1
        }
    fi
fi
cd LLaMA-Factory

# 核心依赖 + DeepSpeed + Flash-Attention
pip install -e ".[torch,metrics,deepspeed]"

echo ""
echo "=== 检查 CUDA Toolkit (nvcc) ==="
# flash-attn 编译需要 nvcc >= 11.7
NEED_TOOLKIT=false
if ! command -v nvcc &>/dev/null; then
    NEED_TOOLKIT=true
else
    NVCC_VER=$(nvcc --version 2>/dev/null | grep -oP 'release \K[0-9]+\.[0-9]+' | head -1)
    if [ "$(echo "$NVCC_VER < 11.7" | bc -l 2>/dev/null || echo 1)" = "1" ]; then
        NEED_TOOLKIT=true
        echo "  系统 nvcc 版本: ${NVCC_VER:-unknown} (需要 >= 11.7)"
    fi
fi

if [ "$NEED_TOOLKIT" = true ]; then
    echo "  通过 conda 安装 CUDA Toolkit 12.4（仅在当前环境生效）..."
    conda install -c nvidia cuda-toolkit=12.4 -y
fi

# nvcc 所在路径
echo "  nvcc: $(which nvcc)"
nvcc --version | grep "release"

echo ""
echo "=== 安装 Flash Attention 2 ==="
# Ada Lovelace (Compute 8.9) 支持 Flash Attention 2，显著加速+省显存
# 编译需要较长时间（5-15分钟）
pip install flash-attn --no-build-isolation

echo ""
echo "=== 安装 bitsandbytes (4-bit 量化) ==="
# 0.45+ 版本支持 Ada Lovelace 架构
pip install bitsandbytes>=0.45.0

# vLLM 依赖较新的 torch，训练完成后再单独装到独立环境
# pip install vllm  ← 暂不安装，serve.sh 会提示怎么处理

echo ""
echo "=== 安装 qwen-vl-utils（Qwen VL 工具） ==="
pip install qwen-vl-utils

echo ""
echo "=== 安装 decord（视频解码，LLaMA-Factory 依赖） ==="
pip install decord

echo ""
echo "=== 安装 dashscope（知识蒸馏脚本依赖，可选） ==="
pip install dashscope

echo ""
echo "=== 验证安装 ==="
python -c "
import torch
print(f'Python:   $(python --version)')
print(f'Conda env: ${CONDA_ENV}')
print(f'PyTorch:  {torch.__version__}')
print(f'CUDA:     {torch.version.cuda}')
print(f'GPU count: {torch.cuda.device_count()}')
for i in range(torch.cuda.device_count()):
    mem = torch.cuda.get_device_properties(i).total_memory / 1024**3
    print(f'  GPU {i}: {torch.cuda.get_device_name(i)}, VRAM: {mem:.0f} GB')
"

python -c "import flash_attn; print(f'Flash Attention: {flash_attn.__version__}')" 2>/dev/null || echo "Flash Attention not detected (optional)"
python -c "import bitsandbytes; print(f'bitsandbytes: {bitsandbytes.__version__}')" 2>/dev/null || echo "bitsandbytes not detected"

echo ""
echo "=========================================="
echo "  环境初始化完成"
echo "  Conda 环境: ${CONDA_ENV}"
echo "  激活命令:   conda activate ${CONDA_ENV}"
echo "=========================================="
echo ""
echo "下一步:"
echo "  1) 把视频和分析结果填入 data/my_video_dataset.jsonl"
echo "  2) bash scripts/train.sh"
