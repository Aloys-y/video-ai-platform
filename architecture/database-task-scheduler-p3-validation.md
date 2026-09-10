# 数据库调度 P3：真实视频、费用与进程故障验收

验证日期：2026-09-10。素材：`D:\data\video\3.MP4`，83,568,002 字节，视频时长 456.199 秒。

## 真实视频链路

使用现有 dev 配置中的真实对象存储、ASR、文本模型、Embedding、Rerank 和视频模型。任务数据放在独立 MySQL 验收库；复制现有知识数据参与真实 RAG 检索。未清空原库，也未重启原有开发服务。

本轮覆盖：原文件上传对象存储 → 写入 PENDING → 生产调度器条件领取 → Worker 下载本地文件 → FFmpeg 提取音轨 → 云端 ASR → 文本筛选 → 裁剪与产物上传 → RAG → 共享线程池分析 → 保存响应、片段结果与父任务终态。为可重复控制测试，文件上传和任务创建由验收程序调用 StorageService/SQL 完成，本轮未经过浏览器分片上传与登录 HTTP 流程；API 查询、重试与取消已在 P3 第一批独立验收。

| 北京时间 | 状态 / 阶段 |
| --- | --- |
| 11:16:33.725 | PENDING |
| 11:16:35.962 | RUNNING / PREPARING_AUDIO |
| 11:16:56.629 | TRANSCRIBING |
| 11:17:21.450 | SCREENING |
| 11:17:32.735 | PREPARING_SEGMENTS |
| 11:17:53.267 | ANALYZING_SEGMENTS |
| 11:18:22.487 | SUCCEEDED |

以上为每 2 秒查询记录的首次观察时间，不冒充毫秒精确的阶段耗时。从任务创建到观察到成功约 109 秒。3 个片段全部保存成功，选择的视频总时长 187.479 秒，约占原视频 41.1%。

| 片段（展示编号） | 原视频区间 | 时长 | 结果 |
| --- | --- | --- | --- |
| 1 | 69.080–160.570 秒 | 91.490 秒 | SUCCEEDED |
| 2 | 175.350–209.110 秒 | 33.760 秒 | SUCCEEDED |
| 3 | 393.970–456.199 秒 | 62.229 秒 | SUCCEEDED |

[查看实际模型输出](validation-2026-09-10/video-3-results.md)。输出中包含菜单、结算和真人画面等非交战内容，说明仅依靠谈话文本筛选仍有误选；工程跑通不代表粗筛准确率或模型判断已经验证。

## 外部调用与费用

原始记录：[逐调用 JSONL](validation-2026-09-10/external-calls.jsonl)，[逐调用费用 CSV](validation-2026-09-10/call-costs.csv)，[结构化汇总](validation-2026-09-10/cost-summary.json)。同一调用的 STARTED 与 RETURNED 通过 callId 对应，不重复计数；重试若发生，会单独记录。

本次共 14 次外部 AI 接口请求，其中 8 笔归集了可计量服务用量，其余为 ASR 提交、轮询和结果下载，不重复计量转写费。没有未知用量的失败调用；无模型调用重试。对象存储上传下载、MySQL/Redis/Milvus 等不按 Token 收费，未把其基础设施或流量费混入 AI 标价。

采用华北2（北京）普通调用原价，不抵扣免费额度、活动或缓存优惠；这不是账户实扣账单。本次报告的缓存命中为 0。

- Fun-ASR：0.00022 元/秒；Embedding v3、Qwen3 Rerank：0.5 元/百万输入 Token。[官方价格](https://help.aliyun.com/zh/model-studio/model-pricing)
- Qwen3-VL-Flash：输入不超过 32K，输入/输出分别为 0.15/1.5 元每百万 Token；32K–128K 档分别为 0.3/3 元。每次请求分别选档，视频 Token 已包含在输入 Token 内，不能再次相加。[模型价格](https://help.aliyun.com/zh/model-studio/qwen3-vl-flash)

| 调用 | 模型 | 输入 Token | 输出 Token | ASR 秒数 | 标价费用（元） |
| --- | --- | ---: | ---: | ---: | ---: |
| ASR 转写 1 | fun-asr-2025-11-07 | 不适用 | 不适用 | 393 | 0.08646000 |
| 文本筛选批次 1 | qwen3-vl-flash | 4,779 | 202 | — | 0.00101985 |
| 文本筛选批次 2 | qwen3-vl-flash | 806 | 155 | — | 0.00035340 |
| RAG 查询向量 | text-embedding-v3 | 43 | 0 | — | 0.00002150 |
| RAG 候选重排 | qwen3-rerank | 7,706 | 0 | — | 0.00385300 |
| 视频片段 1 | qwen3-vl-flash | 55,702 | 728 | — | 0.01889460 |
| 视频片段 2 | qwen3-vl-flash | 21,844 | 188 | — | 0.00355860 |
| 视频片段 3 | qwen3-vl-flash | 38,476 | 325 | — | 0.01251780 |
| **合计** | | **129,356** | **1,598** | **393** | **0.12667875** |

合计 130,954 Token；数据库父任务 tokens_used 为 123,205，仅包含文本筛选和视频分析，其余 7,749 是 Embedding/Rerank，本报告额外补齐。ASR 不折算成虚构 Token。

音视频文件总时长与 ASR 计量时长不同：原视频约 456.199 秒，本次厂商 usage.duration 返回 393 秒，本报告按实际回执计费。官方接口说明会按判定为语音内容的时长进行计量，非语音内容不计量。[ASR 回执与计量说明](https://help.aliyun.com/zh/model-studio/fun-asr-recorded-speech-recognition-http-api)

视频模型费用合计 0.034971 元，ASR 费用为 0.08646 元。本次没有做整段直传对照调用，不能据此声称粗筛一定降低总费用。

## 进程故障验收

结果：通过。用真实 MySQL 和两个不同 PID 的 JVM，运行生产 TaskDispatchRepository / DatabaseTaskScheduler；故障用的 Work 是可阻塞探针，不调用 ASR 或模型，避免强杀导致额外未知计费。视频服务的完整 Spring 装配和真实业务执行由上面的真实视频用例覆盖。

- 租约 90 秒、续租间隔 20 秒、扫描间隔 1 秒，保持生产默认值。
- 第一个 JVM 领取探针后阻塞；等待一次实际续租，校验 lease_until 延长。
- 强制终止进程，不调用调度器 close，不通过改 SQL 缩短租约。
- 启动新的 JVM 扫描同一数据库，等待原租约自然过期。
- 检查 FAILED / EXECUTION_INTERRUPTED；探针业务标记仍为 CRASH_PROBE，说明新进程未重放旧任务。
- 使用旧 owner + 旧代次尝试写成功，仓储返回 false。
- 显式执行手动重试状态转换，新代次为 1；新进程领取后写 RECOVERY_EXECUTED，最终 SUCCEEDED。

| 最终通过轮次，北京时间 | 证据 |
| --- | --- |
| 11:28:56.408 | 强杀 PID 22392 |
| 11:28:56.414 | 启动恢复 JVM，PID 24748 |
| 11:30:16.488 | 观察到租约过期失败，未自动重放 |
| 11:30:16.746 | 显式请求手动重试 |
| 11:30:28.309 | 新代次成功；所有断言通过 |

[完整故障事件记录](validation-2026-09-10/crash.jsonl)。第一次故障轮次已通过过期失败与旧 owner 拦截，但手动重试未在测试限定的 5 秒内完成；将观察上限设为 20 秒后，第二轮约 11.6 秒完成。这里只证明观察窗口过短，尚不能把等待归因于某个网络或线程问题，也不将 1 秒扫描间隔宣称为 1 秒内一定领取。

最终单独验收命令 `DatabaseLiveFinalizeAcceptanceTest` 通过，日志 `logs/database-live-finalize.log`。

## 本轮发现并处理的问题

1. 验收导出默认 ObjectMapper 不支持 MySQL 返回的 LocalDateTime。修复验收序列化配置，从已保存结果继续导出，未重新发起付费视频任务。
2. Windows 启动子 JVM 时，完整测试类路径超过命令行长度限制。改用 Java 参数文件启动子进程。
3. 故障验收原先给远程数据库链路设置了 5 秒的手动重试完成等待，实际观察约 11.6 秒。调整的是测试观察上限，未改变生产扫描间隔或线程数。
4. Embedding/Rerank 客户端原先只返回向量/重排结果，丢弃用量回执。增加可选、线程隔离的用量观察钩子；只有验收监听时收集，不记录密钥、提示词或签名 URL。

## 可复现命令

```powershell
# 普通回归，不访问真实模型
mvn -B test
python -m unittest discover -s scripts -p test_dispatch_acceptance_cost.py

# 显式付费验收：会新建验收库并真实调用模型，不要为了重新生成报告反复执行
mvn -B test -pl video-worker -am '-Dtest=DatabaseLiveVideoAcceptanceTest' '-Ddispatch.live.acceptance=true' '-Dsurefire.failIfNoSpecifiedTests=false'

# 从最近一次已成功验收任务继续导出和故障验证，不再次调用模型
mvn -B test -pl video-worker -am '-Dtest=DatabaseLiveFinalizeAcceptanceTest' '-Ddispatch.finalize.acceptance=true' '-Dsurefire.failIfNoSpecifiedTests=false'

# 仅从 JSONL 重新计算标价
python scripts/dispatch_acceptance_cost.py <验收输出目录>
```

原始输出目录：`logs/videoai_live_it_2f49086d384e42d8a26ee83972840a50`。该随机库及对象存储产物保留，以便检查结果；`logs/database-live-latest.txt` 保存本次输出目录。

最终回归：全模块 Maven 共 159 项，142 项执行通过，17 项条件跳过，0 失败/错误；计费脚本 4 项单元测试通过。默认回归不会重复触发真实模型。日志：`logs/database-dispatch-p3-complete-tests.log`。
