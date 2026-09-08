# P0：云端转写与文字粗筛验证

更新：2026-09-07。状态：**用户核对三条录像候选后确认基本正确，P0 按开发样本可行性通过收口，允许进入 P1。** 独立整局录像、定量漏筛/误筛及时间对齐仍需上线前验证。初始参数已保存至 `accepted-baseline.json`。见 [首轮候选核对清单](D:/data/proj/javaProj/vibeCoding/DoVideoAI/architecture/asr-p0/first-run-review.md)。

## 选型初步结论

优先验证百炼 `fun-asr-2025-11-07`，对照 `qwen3-asr-flash-filetrans-2025-11-17`。理由是项目已有百炼接入，两个接口都提供中文录音文件异步转写和句段时间戳，适合由文字定位原视频。最终服务选择以游戏录像实测为准。

| 项目 | Fun-ASR | Qwen3 文件转写 |
| --- | --- | --- |
| 提交输入 | `input.file_urls` | `input.file_url` |
| 查询 | 提交得到 task_id，再查询任务 | 同左 |
| 时间戳 | 句段 begin_time/end_time，毫秒 | 同左，可选词级时间戳 |
| 文件限制 | 最长 12 小时、最大 2 GB | 同左 |
| 北京地域价格参考 | 0.00022 元/秒 | 0.00022 元/秒 |

来源：[录音文件识别说明](https://help.aliyun.com/zh/model-studio/non-realtime-speech-recognition-user-guide)、[Fun-ASR HTTP API](https://help.aliyun.com/zh/model-studio/fun-asr-recorded-speech-recognition-http-api)、[Qwen ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)、[模型价格](https://help.aliyun.com/zh/model-studio/model-pricing)。接口规格与价格为本次文档核对结果，账号权限、实际返回和账单仍需实测。

普通 `qwen3-asr-flash` 对话式接口不作为本次切片依据：它与文件转写接口不同，不能只拿转写文字假定已有时间戳。每小时音频按上述单价粗估 0.792 元；脚本优先记录接口 usage，估算不扣免费额度，不包含文本模型和存储费用。文档对有效语音时长与输入时长的描述存在口径差异，最终以实际账单核对。

## 已完成与待完成

- 已完成独立 Python 工具和 8 项离线契约测试；实际提取使用 `D:/software/tools/oopz/ffmpeg.exe` 完整版本。Trae 附带的 FFmpeg 6.1.1 无法输出 WAV，其 FFprobe 可用于探测。
- 已读取 `D:/data/video` 的三条开发录像，共约 24 分钟；尚缺 1 条不参与调参的验收录像。工具默认限制单条 30 分钟。
- 待人工标注交战起止区间，并抽查转写时间能否跳回原片；至少检查开头、中间、结尾及交战边界。
- 已完成 Fun-ASR 真实提交/查询/结果下载、qwen3-vl-flash 纯文字调用，输出 7 个合并候选区间。ASR 估算 ¥0.19624，文本用量 22321 tokens。待核对误筛和漏筛，之后再冻结模型与参数。

**已有真实调用和用户定性核对，尚无定量准确率结论。** 用户确认候选基本正确，初始参数作为首版基线。模拟测试只证明解析和计算逻辑，不证明语音或交战识别效果。

## 怎么运行

工具：`scripts/asr_prefilter_p0.py`，Python 3.10+，仅使用标准库。以下命令在仓库根目录执行。凭据由 `ASR_API_KEY` 或 `DASHSCOPE_API_KEY` 环境变量提供，音频签名地址由 `ASR_AUDIO_URL` 提供；不要把这些值写入命令历史或提交到 Git。

```powershell
$run = 'architecture/asr-p0/results/dev-01-fun'
python scripts/asr_prefilter_p0.py prepare --input 'D:/samples/dev-01.mp4' --run-dir $run --ffmpeg 'D:/software/tools/oopz/ffmpeg.exe' --ffprobe 'D:/software/dev/Trae/Trae CN/resources/app/bin/ffprobe.exe'
# 将生成的 audio.wav 上传到现有对象存储，配置 ASR_AUDIO_URL 后再提交。
python scripts/asr_prefilter_p0.py submit --run-dir $run
python scripts/asr_prefilter_p0.py poll --run-dir $run
# poll 每次只查询一次，隔数秒查询，直到成功；失败时先检查响应。
# duration-ms 必须取本次 media.json，以下仅以两分钟为例。
python scripts/asr_prefilter_p0.py screen --run-dir $run --duration-ms 120000
python scripts/asr_prefilter_p0.py evaluate --run-dir $run --labels 'architecture/asr-p0/results/dev-01-labels.json'
python -m unittest discover -s scripts -p test_asr_prefilter_p0.py -v
```

该 P0 工具不自动上传文件。真实素材确定后使用现有对象存储能力准备音频 URL，保证云端可读取且有效期覆盖排队与转写。不同样本、模型、重跑使用不同目录。对照模型通过 submit 的 `--model` 指定；云端地域通过 `ASR_BASE_URL`、文本接口通过 `TEXT_BASE_URL` 指定，并与密钥地域匹配。

标注格式见 `labels.example.json`，示例区间是虚构的。人工填写后改为 `human_reviewed`，验收集 split 使用 `holdout`。音轨、转写和标注放在已忽略的 `results/` 内；脱敏函数只清理密钥和 URL，**不自动消除转写中的人名等内容**，对外分享前另行检查。

## 首轮参数与判定

音轨单声道 16 kHz PCM；保留原视频时间坐标。默认 ASR 为 Fun-ASR 固定版本，不开启说话人分离；文本模型先用项目已有 `qwen3-vl-flash` 做纯文字调用，其账号兼容性待验证。每批 80 句、重叠 10 句。模型只返回句段 ID、类型和理由，程序映射时间；间隔不超过 5 秒合并，再向前扩 10 秒、向后扩 15 秒并裁至视频边界。这些是实验初值，尚未冻结。

每条录像输出：`transcript.json`、`screen-result.json`、`asr-usage.json`、逐批文本用量和 `evaluation.json`。分别记录扩展前、扩展后的交战时间覆盖率、非交战入选时长及筛中比例，避免只靠扩大区间得到好看的召回。人工另外记录“整场交战完全漏掉”的次数及原因；时间覆盖率不等于逐事件召回率。

开发集调参后固定配置，只跑一次独立验收集做结论。若静默交战普遍漏掉，应明确单靠语音文字的局限，不能把无候选说成无交战。是否进入 P1，以真实效果和可接受成本共同决定，本阶段不预设已经达标。

## P0 工具边界

提交前保存状态，已有记录禁止自动再次提交；请求超时但未拿到 task_id 时需核对云端任务，避免重复计费。Fun-ASR 顶层成功还会检查子任务。结果下载不携带 API 密钥，下载后立即落盘；查询耗时是提交至观察成功的时间，包含人工查询间隔，并非厂商精确执行耗时。

文本批次成功可复用；状态不明或解析失败需要检查原响应，不会盲目重发。提取失败残留文件需要检查后换目录重试。音视频存在非零起始偏移时工具拒绝处理，待专门验证对齐。尚未实现生产级重试、长音轨拆分、磁盘配额或后台轮询，这些属于后续阶段。
