#!/usr/bin/env python3
"""直接用 Qwen2.5-VL 推理本地视频文件，绕过所有 API 限制"""
import torch
from transformers import Qwen2_5_VLForConditionalGeneration, AutoProcessor, BitsAndBytesConfig
from qwen_vl_utils import process_vision_info
import sys

video_path = sys.argv[1] if len(sys.argv) > 1 else "/data/cyy/proj/DoVideoAI/test3.mp4"
prompt = sys.argv[2] if len(sys.argv) > 2 else "请详细描述这个视频的内容"

print(f"Loading model (8-bit)...")
quantization_config = BitsAndBytesConfig(load_in_8bit=True)
model = Qwen2_5_VLForConditionalGeneration.from_pretrained(
    "Qwen/Qwen2.5-VL-7B-Instruct",
    quantization_config=quantization_config,
    device_map="auto"
)
processor = AutoProcessor.from_pretrained("Qwen/Qwen2.5-VL-7B-Instruct")

print(f"Analyzing: {video_path}")
messages = [{
    "role": "user",
    "content": [
        {"type": "video", "video": video_path, "fps": 1.0, "max_pixels": 256*256, "min_pixels": 128*128},
        {"type": "text", "text": prompt}
    ]
}]

text = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
image_inputs, video_inputs, video_kwargs = process_vision_info(messages, return_video_kwargs=True)
inputs = processor(text=text, images=image_inputs, videos=video_inputs, video_kwargs=video_kwargs,
                   return_tensors="pt", padding=True).to(model.device)

print("Generating...")
with torch.no_grad():
    generated_ids = model.generate(**inputs, max_new_tokens=512)
generated_ids = [output_ids[len(input_ids):] for input_ids, output_ids in zip(inputs.input_ids, generated_ids)]
response = processor.batch_decode(generated_ids, skip_special_tokens=True)[0]
print(f"\n==== 模型输出 ====\n{response}")
