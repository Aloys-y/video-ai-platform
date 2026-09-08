# P2：媒体准备与云端转写

2026-09-07。P2 提供可调用的 Worker 服务组件；由 P3/P4 完成筛选和并行分析后，在 P5 统一接入 `TaskProcessor`。当前没有把“完成转写”当作整局分析完成，也没有改变 Kafka ACK 时机。

## 已实现的流程

```text
prepareTranscript(taskId, executionNo, sourceObjectKey)
  → 校验当前 PROCESSING 执行
  → 原片流式下载到独立临时目录，探测轨道/时长并计算 SHA256
  → 保存本代配置、输入摘要和媒体信息
  → 对齐音视频零点，提取单声道16kHz音轨，默认每30分钟一段
  → 音轨上传OSS，完整分段清单在一个数据库事务中保存
  → 每段先记录SUBMITTING，再提交云端并立即保存远端ID
  → 有限时轮询，下载结果，校验句段时间戳并补回该段偏移
  → 各段转写与脱敏响应存OSS，数据库保存对象键及实际用量
  → 汇总带时间戳的转写JSON，绑定到本代执行记录

prepareSegments(taskId, executionNo, sourceObjectKey, ranges)
  → 校验有序区间、数量和时长预算
  → 核对原片摘要，顺序裁剪、上传全部片段
  → 冻结PreparedSegment清单并保存OSS引用
```

结果仍通过 P1 的 `analysis_execution` 查询。新增 `analysis_asr_part` 保存每段音轨的起止时间、对象键、远端任务ID、转写结果引用、用量和复用来源；它不承担任务领取。`analysis_execution.asr_task_id` 仅保留首次提交的第一段ID，恢复和审计以分段表为准。

## 失败与恢复

- 同一代次已保存的转写直接读取；已知远端ID继续查询，成功音轨不再次提交。
- `SUBMITTING` 表示请求可能已被云端接受，自动重提被禁止。若进程在收到ID但写库前退出，需要在云端核对后补齐记录；没有声称跨数据库/云API可以原子提交。
- 用户新建重试代次时，只有同任务、相同输入摘要和完整 P2 配置快照的音轨清单可复用。已成功结果沿用；处理中任务续查；确定 FAILED/CANCELED 才允许在新代次重新提交。UNKNOWN/提交不明不会盲目再次计费。
- 复用已有结果时，本代用量为空，沿 `reused_execution_no` 查询历史费用；新代次继续完成旧请求时记录厂商用量及来源，统计时应按远端ID去重。
- 取消或旧代次不能保存新结果。已提交的远端ID即使父任务刚取消仍会记录，用于审计，不推进父任务终态。
- 无音轨返回 `NO_AUDIO`；有效响应但没有语音返回 `NO_SPEECH`。接口失败、缺少时间戳、子任务失败均不当作“没有语音”。后续报告如何展示由 P3/P6 接入。
- 临时目录在正常/异常退出时清理，超时会终止 FFmpeg 进程并持续排空其输出。进程被强杀后的残留目录和未绑定的OSS孤立对象仍需运维清理，当前不新增后台任务扫描器。

## 时间与资源限制

时间坐标以第一帧视频为零点；音轨迟到补静音、提前则裁掉视频零点前的部分。容器播放起点与视频首帧不同的素材记录 `videoLeadMs`，裁剪时补回该偏移；用户播放器跳转也应加该值。普通录屏起点通常一致。

默认：原片最多10GiB，单工作区14GiB，保留512MiB磁盘余量，本机最多2个工作区，单媒体进程600秒。视频最长12小时，单音轨段最长30分钟、最多64段；片段最多100个、每段180秒、总计30分钟。JSON和云端响应上限16MiB。越界明确失败，不静默丢片段。

FFmpeg 转码为 H.264/AAC，最大宽度1280、CRF28；不用关键帧直接复制冒充精确裁剪。音轨/裁剪视频均在磁盘处理，下载缓冲64KiB；模型JSON和转写文字可进入内存。

## 配置与运行

Worker 默认 YAML 已增加 `analysis.media` 和 `analysis.asr`。云端使用 Fun-ASR 固定版本，凭据优先 `ASR_API_KEY`，未指定时复用 `ai.dashscope.api-key`。模型、API地域和媒体参数进入执行快照，密钥及临时签名URL不进入快照。

本机完整 FFmpeg 路径如下，启动 Worker 或运行媒体测试前配置环境变量；部署到 Linux 时使用对应可执行文件：

```powershell
$env:FFMPEG_PATH = 'D:/software/tools/oopz/ffmpeg.exe'
$env:FFPROBE_PATH = 'D:/software/dev/Trae/Trae CN/resources/app/bin/ffprobe.exe'
mvn -o -q test
```

`P2LiveSmokeTest` 默认跳过，只有显式设置 `P2_LIVE_SMOKE=true` 并提供测试所需环境才运行；凭据不通过命令参数传递。它查询已有 ASR、裁剪真实视频、上传下载后校验哈希并清理本次对象，不新提交付费转写。

服务入口只接受 OSS object key。调用前应由原消费者建立当前 PROCESSING 状态，任务模式为 `AUDIO_PREFILTER`；P2 不自动修改任务模式，不新增 HTTP/Kafka 执行入口。

## 迁移与实测

全项目回归：启用本机FFmpeg和真实服务冒烟后执行 `mvn -o -q test`，70项测试全部通过，失败、错误、跳过均为0。真实服务摘要保存在已忽略的 `logs/p2-live-smoke.json`。

空库已同步 `schema.sql`；已有库按 V1.5 后执行 [V1.6](D:/data/proj/javaProj/vibeCoding/DoVideoAI/sql/V1.6__asr_parts.sql)，项目不会自动执行版本SQL。2026-09-07 16:48 已按现有 YAML 对 `video_ai` 执行 V1.6，新增表匹配现库 `utf8mb4_0900_ai_ci`；主键、外键及3个检查约束已核对，表内没有测试业务行。

已验证：真实 FFmpeg 音视频正负偏移、分段、裁剪和超时终止；MyBatis 提交占位与代次保护；模拟云端请求头隔离、缺失时间戳/失败子任务拒绝、断点恢复与防重复提交。真实百炼查询成功得到48句；真实3秒片段约1.06MB，OSS往返哈希一致，超限下载被拒绝。未新增付费 ASR 调用。

厂商接口依据：[Fun-ASR HTTP API](https://help.aliyun.com/zh/model-studio/fun-asr-recorded-speech-recognition-http-api)。单次提交只支持一个URL，因此每个音轨段单独保存远端ID；成功状态还需检查子任务结果。

后续 P3 将扩展当前 P2 配置快照，保存筛选模型和提示词版本；旧快照不覆盖。P5 接入时需根据实测总耗时统一配置消费者轮询间隔、任务超时及容量，不能直接沿用短任务超时。
