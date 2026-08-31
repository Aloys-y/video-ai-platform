#!/bin/bash
# ============================================================================
# vLLM 部署 Qwen2.5-VL-7B-Instruct
# 只需修复 config.json 的 rope_scaling 冲突，然后启动
# ============================================================================
set -e

# ---- conda 环境 ----
CONDA_DIR="$(dirname "$(dirname "${CONDA_EXE}")")"
source "${CONDA_DIR}/etc/profile.d/conda.sh"

if conda env list | grep -q "^videoai-serve "; then
    conda activate videoai-serve
else
    conda create -n videoai-serve python=3.11 -y
    conda activate videoai-serve
    pip install vllm -q
fi

# ---- 修复 config.json rope_scaling 冲突 ----
MODEL_DIR="${HOME}/.cache/huggingface/hub/models--Qwen--Qwen2.5-VL-7B-Instruct"
if [ ! -d "$MODEL_DIR" ]; then
    export HF_ENDPOINT=https://hf-mirror.com
    python3 -c "from transformers import AutoConfig; AutoConfig.from_pretrained('Qwen/Qwen2.5-VL-7B-Instruct')"
fi

SNAPSHOT=$(ls -d "${MODEL_DIR}/snapshots/"*/ 2>/dev/null | head -1)
CONFIG="${SNAPSHOT}/config.json"

echo "=== Fixing rope_scaling in: $CONFIG ==="
cp "$CONFIG" "${CONFIG}.bak"
python3 -c "
import json
with open('${CONFIG}') as f:
    cfg = json.load(f)
# 修复 text_config 里的 rope_scaling
for section in [cfg, cfg.get('text_config', {})]:
    rs = section.get('rope_scaling')
    if rs and 'rope_type' in rs and 'type' in rs:
        # 保留 legacy 'type'，删 modern 'rope_type'
        del rs['rope_type']
        print(f'removed rope_type, kept type={rs.get(\"type\")}')
with open('${CONFIG}', 'w') as f:
    json.dump(cfg, f, indent=2)
print('Config fixed.')
"

# ---- 启动 vLLM ----
echo "=== Starting vLLM on port 8000 ==="
vllm serve Qwen/Qwen2.5-VL-7B-Instruct \
    --host 0.0.0.0 \
    --port 8000 \
    --max-model-len 8192 \
    --gpu-memory-utilization 0.85 \
    --trust-remote-code
