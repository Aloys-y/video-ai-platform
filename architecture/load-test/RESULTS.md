# Outbox 全链路压测结果（2026-08-29）

## 环境与方法

- API 与 Worker：本机 Java 17，Spring Boot 3.2.4；
- 中间件：服务器上的 MySQL、Redis、Kafka、对象存储；
- Kafka：测试启动前任务 Topic 实际为 1 分区，`KafkaAdmin` 按配置扩为 6 分区；
- AI：`MockAI`，短/中/长耗时比例 60%/30%/10%，600 倍缩时；
- RAG：关闭，确认没有 Embedding 或真实视频模型调用；
- 每个主要场景突发提交 100 条任务；
- 核心时间列先从 `DATETIME` 升级为 `DATETIME(3)`，否则缩时数据没有测量意义。

所有场景均真实经过 HTTP、任务与 Outbox 本地事务、MySQL 扫描、Kafka、Consumer group、
任务状态机和完成落库。

## 实测数据

| 场景 | 投递方式 | Batch | 扫描间隔 | 在途上限 | Consumer | Outbox p95 | Kafka排队 p95 | 端到端 p95 | 完成吞吐 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `batch10i3000c3` | 串行同步 | 10 | 3000ms | 1 | 3 | 52.19s | 4.45s | 53.10s | 1.73/s |
| `batch100i3000c3` | 串行同步 | 100 | 3000ms | 1 | 3 | 27.67s | 17.64s | 44.04s | 2.03/s |
| `batch100i500c3` | 串行同步 | 100 | 500ms | 1 | 3 | 27.46s | 24.83s | 51.10s | 1.75/s |
| `batch100i500c6` | 串行同步 | 100 | 500ms | 1 | 6 | 24.51s | 4.85s | 26.93s | 3.22/s |
| `async100i500f10c6` | 有界异步 | 100 | 500ms | 10 | 6 | 11.80s | 14.79s | 26.07s | 3.36/s |
| `final50i1000f10c6` | 有界异步 | 50 | 1000ms | 10 | 6 | 17.07s | 7.39s | 22.95s | 3.60/s |

单次场景会受到任务到 Kafka 分区的随机分布、远端网络抖动影响，不能拿小数点后的差异下结论；
这里关注量级、瓶颈迁移和明显趋势。原始 JSON 由脚本生成在忽略提交的 `results/` 目录。

## 结论与参数选择

### 1. Batch 不能脱离单条发送模型讨论

串行同步时，批次 10 导致每处理 10 条就额外等待 3 秒，Outbox p95 达到 52.19 秒；批次增到
100 后下降到约 27 秒。但继续把扫描间隔从 3 秒降到 0.5 秒几乎没有改善，证明瓶颈是每条消息的
Kafka ACK 和多次远端数据库往返，不是调度器空闲等待。

### 2. 有界异步解决投递器瓶颈

在相同的 100 条、0.5 秒扫描、6 Consumer 下，有界异步把 Outbox p95 从 24.51 秒降到
11.80 秒，约下降 52%。状态仍然只在 Kafka Future 成功后改为 `SENT`；最多 10 条在途，避免
Kafka 故障时无限堆积。

异步后 Kafka 排队时间上升，说明压力已经从 Outbox 移到消费者。继续加大发送并行度不能提高
业务完成吞吐，只会加深队列，因此选择在途上限 10，而不是追求更大的生产吞吐。

### 3. 6 分区确实支持 6 路消费

相同同步投递配置下，Consumer 从 3 增至 6 后，完成吞吐从 1.75/s 提升到 3.22/s，Kafka 排队
p95 从 24.83 秒下降到 4.85 秒。基础设施层面 6 路并行有效。

但真实生产并发还受模型厂商 QPS、账号配额和成本约束。本次 Mock 测试只能证明系统能承载 6 路，
不能证明真实模型允许 6 路。因此生产默认仍保守保持 3，可在确认供应商额度后通过
`VIDEOAI_WORKER_CONCURRENCY=6` 开启。

### 4. `max.poll.interval.ms` 必须覆盖长任务 p99

安全缩时配置 4 秒下，最长 Mock 任务约 3.3 秒，没有 poll timeout。故意改为 2 秒后，30 条任务
触发 4 次 `consumer poll timeout`；虽然状态机使 30 条最终全部完成，但吞吐下降到 1.77/s，证明
rebalance 会造成停顿和潜在重复投递。

生产配置保留 40 分钟：30 分钟 AI 上限之外再留 10 分钟给对象存储、数据库、网络抖动和 JVM 停顿。

### 5. 最终默认值

```yaml
videoai:
  kafka:
    task-topic:
      partitions: 6
  worker:
    concurrency: 3 # 供应商允许时升到6
  outbox:
    dispatch-batch-size: 50
    dispatch-interval-ms: 1000
    max-in-flight: 10
    callback-threads: 10
    send-timeout-ms: 10000
    recovery-interval-ms: 60000

spring.kafka.consumer:
  max-poll-records: 1
  properties:
    max.poll.interval.ms: 2400000
```

批次 50 大于在途上限 10，为后续提高在途数量保留空间；当前每轮查询会自动取
`min(batch, availableInFlightSlots)`，不会无意义地加载 50 条。

## 正确性结果

- 本轮共完成 735 条合成任务；
- 所有运行的任务最终都进入 `COMPLETED`；
- 对应 Outbox 最终全部进入 `SENT`；
- 正常场景 `send_attempt_count=0`；
- 日志只出现 `MockAI`，没有 DashScope、Zhipu 或真实 Embedding 调用；
- 错误 poll 配置出现 rebalance 后，状态机仍阻止了任务结果被重复写入。

## 仍需补充的生产验证

本轮是容量标定和故障边界验证，不应包装成最终极限 TPS。上线前还需：

1. 每个场景重复 3～5 次，报告中位数和置信区间；
2. 运行至少 2 小时长稳测试，观察数据库连接池、堆内存、GC 和 Kafka lag；
3. 在隔离 Kafka 环境做 Broker 断连、ACK 丢失和 Worker 崩溃恢复测试；
4. 用供应商沙箱或极少量真实调用标定模型并发限制，不能用 Mock 推断厂商 QPS；
5. 生产多 Broker 下验证副本数、`min.insync.replicas` 和故障切换。
