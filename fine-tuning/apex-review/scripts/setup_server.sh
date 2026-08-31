#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "此安装脚本只支持 Linux/WSL2。"
  exit 1
fi

python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip setuptools wheel
python -m pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cu124
python -m pip install -r requirements.txt

echo "环境安装完成。下一步：source .venv/bin/activate"
echo "然后运行：python scripts/preflight.py --config config.yaml --load-model"
