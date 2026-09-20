# 新模型切换与片段链路验证

日期：2026-09-10。配置切换、测试和部署完成，真实任务验收记录见下文。

## 当前模型

| 环节 | 当前模型 |
| --- | --- |
| 语音转写 | qwen-audio-3.0-asr-flash-filetrans |
| 文本交战识别 | qwen3.8-flash |
| 片段视频分析 | qwen3.7-plus |
| RAG查询 | text-embedding-v3、qwen3-rerank |

Java默认值、application.yml和本地dev配置同步切换。现行价格表删除fun-asr与qwen3-vl-flash配置；旧任务账本的历史模型名称和价格快照保留，不伪造为新模型费用。

视频Provider使用百炼 `/compatible-mode/v1/chat/completions`，请求格式为 `video_url` + 文本，保留2 FPS、关闭思考、temperature=0和4096输出上限。响应解析使用 `choices[].message.content`、`id`、`prompt_tokens`/`completion_tokens`。请求前登记、返回后记账、429/5xx有限业务重试及HTTP禁止隐式重试继续生效；传输失败没有自动切换旧模型。

协议标识加入片段模型配置快照，防止接口变化后错误复用旧配置下的结果。任务编排仍只通过裁剪清单向视频Provider提供片段地址，无候选时不回退整片分析。

接口和价格参考：[Qwen3.7-Plus](https://help.aliyun.com/zh/model-studio/qwen3-7-plus)、[Qwen3.8-Flash](https://help.aliyun.com/zh/model-studio/qwen3-8-flash)。ASR沿用此前已实际验证的Filetrans接入。

## 测试和部署

- 全量 `mvn test -Dcost.mysql.acceptance=true -q`：216项，198通过、18条件跳过、失败/错误0。
- 更新兼容接口测试响应后的定向复验：视频Provider 7项、真实MySQL视频账本1项全部通过。
- 覆盖请求模型/格式、独立并发请求和超时、兼容usage归一化、失败请求计费、登记失败不发送、收尾失败不重调、发送前取消。
- 打包成功，并检查JAR内三个模型配置均已切换，旧模型默认值不存在。
- 服务日志：`logs/database-deploy-20260910-164522/`。本次部署PID：API 30132、Worker 8300、前端5980。
- 测试日志：`logs/new-model-regression.log`、`logs/new-model-video-test.log`、`logs/new-model-build.log`。

## 本次验收范围

仅使用 `D:\data\我们是冠军！EWC决赛第9场 VKG夺冠第一视角！ - 1080P 高清 - 30fps - H.264.mp4`，668,502,438字节，1,220.266秒。

原文件上传对象存储后，经本地音轨提取、ASR、文本筛选、裁剪，再分析选中片段。**没有整视频送入视频模型的对照调用，不做额外汇总调用。** 实际记录保存在 `logs/new-model-live-20260910/`。


## 实际结果

任务 `task_84991774_893ui5`，代次0，最终SUCCEEDED。16:49:35.049开始，16:57:51.701完成，约8分17秒，包含从对象存储下载原视频、提取音轨等处理，不包含前端上传。

8个片段共333.230秒，占原视频约27.31%，全部分析成功。视频输入合计209,012Token，输出8,231Token。本次没有整视频分析对照，因此不声称测得Token或金额节省比例。

14次调用全部SUCCEEDED，无重试调用、无未知费用、无RUNNING费用行。账本、API本次/累计、阶段和片段费用全部一致，按冻结价格用Python Decimal逐笔重算通过。

| 阶段 | 调用数 | 标价估算/元 |
| --- | ---: | ---: |
| Qwen-Audio 3.0 | 1 | 0.1513600000 |
| Qwen3.8-Flash | 3 | 0.0120524000 |
| Qwen3.7-Plus | 8 | 0.4838720000 |
| RAG向量化 | 1 | 0.0000115000 |
| RAG重排 | 1 | 0.0040150000 |
| 合计 | 14 | **0.6513109000** |

ASR计量为厂商usage报告的688秒，不能用20分钟原视频长度直接替代该字段；金额仅为标价估算，不是账户实扣。所有阶段合计229,309输入Token、9,067输出Token，ASR秒数单独计算。

### 逐次用量

子任务编号从0开始，界面片段编号从1开始。按实际请求开始顺序排列。

| 阶段 | 子任务 | 输入Token | 输出Token | 费用/元 |
| --- | ---: | ---: | ---: | ---: |
| ASR | 0 | — | — | 0.1513600000 |
| TEXT_SCREEN | 0 | 3823 | 364 | 0.0040412000 |
| TEXT_SCREEN | 1 | 4011 | 176 | 0.0036840000 |
| TEXT_SCREEN | 2 | 4410 | 296 | 0.0043272000 |
| RAG_EMBEDDING | 0 | 23 | — | 0.0000115000 |
| RAG_RERANK | 0 | 8030 | — | 0.0040150000 |
| VIDEO_ANALYSIS | 3 | 22711 | 974 | 0.0532140000 |
| VIDEO_ANALYSIS | 0 | 35185 | 1130 | 0.0794100000 |
| VIDEO_ANALYSIS | 2 | 19741 | 836 | 0.0461700000 |
| VIDEO_ANALYSIS | 1 | 21523 | 844 | 0.0497980000 |
| VIDEO_ANALYSIS | 4 | 20335 | 982 | 0.0485260000 |
| VIDEO_ANALYSIS | 5 | 27463 | 1130 | 0.0639660000 |
| VIDEO_ANALYSIS | 6 | 30433 | 1117 | 0.0698020000 |
| VIDEO_ANALYSIS | 7 | 31621 | 1218 | 0.0729860000 |

### 验收证据

- `logs/new-model-live-20260910/calls-latest.json`：实际调用模型、Token/秒数、状态和价格快照。
- `costs-latest.json`、`reconciliation.json`：实际接口结果和逐笔重算结果，complete=true。
- `scope-check.json`：8笔视频调用均匹配本次裁剪子任务；片段对象键全部不同于原视频对象键。代码路径使用清单中的片段对象生成模型URL，没有原视频回退分支。
- `playback-check.json`：8段播放接口能生成地址，但对象存储Range请求全部HTTP403；Backblaze明确返回 `AccessDenied: Cannot download file, download bandwidth or transaction (Class B) cap exceeded`。模型分析阶段已成功读取并分析片段，但验收后的播放受存储额度限制，不能宣称播放验收通过。未调整存储付费额度，需要额度恢复后复验。
- `output/playwright/new-model-fees.png`：真实前端费用明细；页面显示完整费用与8/8完成结果。

本次未做人工作战事件标注，链路成功不等于战斗区间召回和画面分析准确率已验证。不额外调用模型进行质量对照。服务保持运行，未推送或合并本轮修改。
