# Outbox 全链路缩时压测

本次实测数据、参数选择依据与限制见 [压测结果报告](RESULTS.md)。

## 目标与边界

压测覆盖以下真实链路：

```text
HTTP 并发提交
  -> API 本地事务（analysis_task + task_outbox）
  -> MySQL Outbox 扫描与抢占
  -> Kafka 真实生产和消费
  -> Consumer group / partition 分配
  -> 任务状态机原子更新
  -> Mock AI 阻塞调用
  -> MySQL COMPLETED
```

真实模型调用和 RAG Embedding 在 `loadtest` profile 中关闭，不产生 Token 费用。MinIO
只生成预签名 URL，不上传或下载真实视频。

这套测试验证异步任务基础设施容量，不代表真实大模型厂商的限流、首 Token 延迟、视频下载速度和计费表现。

## 缩时模型

默认按 600 倍压缩真实时间：

| 类型 | 占比 | 假设真实耗时 | Mock 耗时 |
|---|---:|---:|---:|
| 短任务 | 60% | 2～4 分钟 | 0.2～0.4 秒 |
| 中任务 | 30% | 8～12 分钟 | 0.8～1.2 秒 |
| 长任务 | 10% | 24～30 分钟 | 2.4～3 秒 |

生产 `max.poll.interval.ms=40 分钟` 同比例缩小为 4 秒。这样可以在几分钟内验证长任务是否会触发
consumer rebalance，同时保持“最长任务 < max.poll.interval”的相对关系。

缩时测试能验证超时与并发关系，但不能替代最终的长稳测试；JVM GC、TCP 空闲连接、Broker 保留策略等与绝对时间相关的机制仍需单独验证。

## 启动

先确认服务器 MySQL、Redis、Kafka、MinIO 可用，并已执行最新 `sql/schema.sql`。

已有数据库先执行毫秒时间精度迁移。执行器只从环境变量读取凭据：

```powershell
$env:VIDEOAI_JDBC_URL = "jdbc:mysql://数据库地址:3306/video_ai?serverTimezone=Asia/Shanghai"
$env:VIDEOAI_JDBC_USERNAME = "用户名"
$env:VIDEOAI_JDBC_PASSWORD = "密码"
java --class-path "本机mysql-connector-j.jar" scripts/JdbcSqlRunner.java `
  sql/V1.4__task_timing_millisecond_precision.sql
```

设置仅本次压测使用的 Token：

```powershell
$env:VIDEOAI_LOAD_TEST_TOKEN = "local-outbox-load-test"
```

启动 API：

```powershell
mvn -pl video-api -am spring-boot:run `
  -Dspring-boot.run.profiles=dev,loadtest `
  -Dspring-boot.run.arguments="--videoai.load-test.token=$env:VIDEOAI_LOAD_TEST_TOKEN"
```

启动 Worker：

```powershell
mvn -pl video-worker -am spring-boot:run `
  -Dspring-boot.run.profiles=dev,loadtest
```

启动日志中必须出现 `Calling MockAI API`。如果出现 DashScope、Zhipu 或 OpenAICompatible，立即停止压测，说明 profile 没有生效。

## 运行基线

```powershell
python scripts/outbox_load_test.py `
  --tasks 100 `
  --concurrency 20 `
  --token $env:VIDEOAI_LOAD_TEST_TOKEN
```

结果写入 `architecture/load-test/results/<runId>.json`，包括：

- HTTP 提交吞吐和 p50/p95/p99；
- Outbox 创建到 `SENT` 的延迟；
- Kafka 入队到 Worker 开始处理的排队延迟；
- Mock AI 实际处理耗时；
- 端到端耗时和完成吞吐；
- Outbox 重试次数、任务状态和失败数。

## 参数实验矩阵

每组实验使用新的 `runId`。修改 Worker 环境变量后需要重启 Worker。

| 实验 | 批次 | 扫描间隔 | Worker 并发 | 任务数 | 目的 |
|---|---:|---:|---:|---:|---|
| A1 | 10 | 3000 ms | 3 | 100 | 观察小批次积压 |
| A2 | 50 | 3000 ms | 3 | 100 | 同步小批次基线 |
| A3 | 100 | 3000 ms | 3 | 100 | 判断大批次收益 |
| B1 | 50 | 500 ms | 3 | 100 | 低投递延迟 |
| B2 | 50 | 1000 ms | 3 | 100 | 最终默认候选 |
| B3 | 50 | 3000 ms | 3 | 100 | 低 DB 轮询压力 |
| C1 | 50 | 1000 ms | 1 | 100 | 单 Consumer 基线 |
| C2 | 50 | 1000 ms | 3 | 100 | 生产保守默认并发 |
| C3 | 50 | 1000 ms | 6 | 100 | 验证 6 分区基础设施上限 |

对应环境变量：

```powershell
$env:VIDEOAI_OUTBOX_DISPATCH_BATCH_SIZE = "50"
$env:VIDEOAI_OUTBOX_DISPATCH_INTERVAL_MS = "1000"
$env:VIDEOAI_OUTBOX_MAX_IN_FLIGHT = "10"
$env:VIDEOAI_OUTBOX_CALLBACK_THREADS = "10"
$env:VIDEOAI_WORKER_CONCURRENCY = "3"
$env:KAFKA_CONSUMER_MAX_POLL_INTERVAL_MS = "4000"
```

## 如何从实测结果选择参数

1. 批次大小取“继续增大后 Outbox p95 不再明显下降”的最小值。
2. 扫描间隔取满足任务入队延迟 SLO 的最大值，避免无意义地频繁空查数据库。
3. Worker 并发增加到吞吐不再近似线性增长或达到分区数为止。
4. 分区数至少覆盖计划最大消费并发，并预留扩容空间；分区数不是越多越好。
5. `max.poll.interval.ms` 应大于 AI p99 + 下载/存储/数据库开销 + 安全余量。缩时实验验证相对关系，生产值仍使用真实时长校准。
6. 任一组出现 `send_attempt_count > 0`、FAILED、consumer rebalance 或数据库连接池等待，都要结合日志定位，不能只看平均值。

## 正确性判定

一次成功实验应同时满足：

- `analysis_task` 总数等于 HTTP 成功提交数；
- 每个任务恰好有一个当前执行代次的 Outbox；
- Outbox 最终全部为 `SENT`；
- 任务最终全部为 `COMPLETED`；
- 无 `max.poll.interval.ms` 导致的 consumer rebalance；
- 无真实 AI Provider 调用；
- 重复 Kafka 投递即使发生，也不能让同一任务重复进入 `PROCESSING`。
