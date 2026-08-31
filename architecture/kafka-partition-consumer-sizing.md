# Kafka 分区、Consumer Group 与 Worker 部署参数设计

## 1. 文档目标

本文记录 DoVideoAI 视频分析任务的 Kafka 拓扑设计，回答以下问题：

- Topic 应该配置多少个分区。
- 一个 Worker 为什么可以启动多个 Consumer。
- 生产环境应该增加 Worker 副本，还是只增加单进程 concurrency。
- Consumer Group 应该如何划分。
- `taskId` 分区路由已经保证顺序后，为什么仍然需要数据库状态机和原子条件更新。

本文讨论的主链路是：

```text
analysis_task + task_outbox
        ↓
videoai.task.analyze Topic
        ↓
videoai-worker-group
        ↓
多个 Worker 进程 × 每进程多个 Consumer
        ↓
单次 AI 视频分析
```

## 2. 四个概念的职责边界

### 2.1 Partition：并行处理的车道

一个 Topic 可以拆成多个 Partition。Kafka 只保证单个 Partition 内记录有序，不保证不同 Partition 之间的全局顺序。

在同一个 Consumer Group 内，一个 Partition 同一时刻只会交给一个 Consumer。因此 Partition 数决定这个消费组的理论并行上限。

### 2.2 Consumer：真正的消费执行槽位

Consumer 是加入消费组、获得 Partition、执行 `poll()` 并提交 offset 的客户端实例。

Spring Kafka 的 `concurrency=3` 会在一个 Worker 进程中创建 3 个独立 Consumer，而不是让同一个 KafkaConsumer 被 3 个线程并发调用。

### 2.3 Consumer Group：决定分工还是广播

相同 Group ID 的 Consumer 共同分担消息：

```text
Worker-1 Consumer-1 ┐
Worker-1 Consumer-2 ├─ videoai-worker-group
Worker-2 Consumer-3 ┤
Worker-2 Consumer-4 ┘
```

每条记录在这个消费组中只会分配给一个 Consumer。

不同 Group ID 各自维护 offset，因此每个组都会收到一份完整消息。例如：

```text
videoai-worker-group   → 执行 AI 分析
videoai-monitor-group  → 收集事件和指标
```

所有执行视频分析的 Worker 必须使用相同的 `videoai-worker-group`。不能为每个 Worker 实例生成独立 Group ID，否则每个实例都会执行同一条 AI 任务。

### 2.4 Worker：Consumer 的部署载体

Worker 是完整的 JVM/Spring Boot 服务进程。一个 Worker 可以承载多个 Consumer，共享 Spring 容器、数据库连接池、Redis 客户端和配置。

Worker 副本解决高可用和跨机器扩容；进程内 Consumer concurrency 解决单进程资源利用率。生产环境通常组合使用二者，而不是二选一。

## 3. 三个核心公式

```text
Consumer 总数 = Worker 副本数 × 每个 Worker 的 concurrency

实际并行消费数 = min(Topic Partition 数, Consumer 总数)

维持无持续积压所需并发数 ≈ 每小时任务到达量 × 平均处理分钟数 ÷ 60
```

例子：每小时进入 12 个任务，平均处理时间 30 分钟：

```text
所需并发数 ≈ 12 × 30 ÷ 60 = 6
```

至少需要 6 个 Partition 和 6 个有效 Consumer，才能在平均意义上跟上任务进入速度。实际生产还应为高峰、长尾任务和故障降级预留容量。

## 4. DoVideoAI 当前默认拓扑

当前默认参数：

```yaml
videoai:
  kafka:
    task-topic:
      partitions: ${VIDEOAI_KAFKA_TASK_PARTITIONS:6}
      replicas: ${VIDEOAI_KAFKA_TASK_REPLICAS:1}
  worker:
    concurrency: ${VIDEOAI_WORKER_CONCURRENCY:3}
```

Kafka 长任务参数：

```yaml
spring:
  kafka:
    consumer:
      max-poll-records: ${KAFKA_CONSUMER_MAX_POLL_RECORDS:1}
      properties:
        "[max.poll.interval.ms]": ${KAFKA_CONSUMER_MAX_POLL_INTERVAL_MS:2400000}
```

推荐的初期生产部署是：

```text
Task Topic：6 Partitions
Worker：2 个副本
每个 Worker：concurrency=3
Consumer Group：videoai-worker-group
```

正常情况下：

```text
Consumer 总数 = 2 × 3 = 6
实际并行任务数 = min(6, 6) = 6
```

一个 Worker 故障后，剩余 3 个 Consumer 会接管全部 6 个 Partition。系统仍然可用，但实际同时处理任务数暂时下降到 3。

需要注意：应用配置只能定义单个 Worker 的 concurrency，Worker 副本数由 Docker Compose、Kubernetes、云平台或进程管理器决定，不应该写死在 Java 代码里。

## 5. 为什么选择“2 个 Worker × 每个 3 Consumer”

### 5.1 只部署一个 Worker、内部开多个 Consumer

优点：

- JVM、Spring 容器和连接池开销较小。
- 对以网络等待为主的 AI 调用，资源利用率较高。
- 部署简单。

缺点：

- Worker 进程故障时，内部 Consumer 全部退出。
- 发布重启时所有消费能力同时中断。
- 单进程 concurrency 过高会放大内存、临时文件、连接池和外部 AI 并发压力。

### 5.2 多部署 Worker，但每个 concurrency=1

优点：

- 故障隔离粒度小。
- 容易横向扩缩容。

缺点：

- 每增加一个消费槽位就要启动完整 JVM。
- Spring 容器、连接池和基础客户端重复占用资源。
- 部署和运维对象更多。

### 5.3 多 Worker + 适量进程内 concurrency

这是更常见的折中：多个 Worker 提供高可用，每个 Worker 用少量 Consumer 提高资源利用率。

`2 × 3` 只是当前规模下的起点，不是永久参数。如果 AI 厂商只允许 4 个并发，应收敛为 4 个有效 Consumer；如果压测和成本预算允许 10 个并发，可以逐步调整为 10 个 Partition 和例如 `2 × 5`。

## 6. Partition 数的决策维度

不能只凭消息数量设置 Partition，应同时考虑：

1. **任务到达速度**：每小时有多少视频任务。
2. **平均和 P99 耗时**：不是只看平均值，长尾会占用 Consumer 很久。
3. **AI 并发额度**：QPS 不等于并发数，持续 30 分钟的请求需要单独控制在途数量。
4. **成本预算**：更多 Consumer 可能意味着更多同时发生的 AI 计费调用。
5. **Worker 资源**：内存、HTTP 连接、数据库连接、临时文件和网络带宽。
6. **积压 SLA**：允许用户等待多久，峰值积压需要多快消化。
7. **扩容空间**：Consumer 数增加到超过 Partition 数后不会再提高并行度。
8. **顺序要求**：全局顺序只能使用单 Partition；本项目只需要单个 taskId 维度的顺序。

Partition 可以增加但不能减少。增加 Partition 后，Key 到 Partition 的取模结果可能变化，因此扩分区前后同一个 Key 的新消息不一定继续落在原 Partition。扩分区应作为受控变更执行，而不是频繁动态调整。

## 7. Partition 数和副本数不是一回事

```text
Partition 数    → 决定并行度和吞吐上限
Replication 数  → 决定数据高可用能力
```

本地 Docker 只有一个 Kafka Broker，所以默认副本数为 1。

三 Broker 生产集群通常可以设置：

```text
VIDEOAI_KAFKA_TASK_PARTITIONS=6
VIDEOAI_KAFKA_TASK_REPLICAS=3
```

如果 Topic 已经以单副本创建，后续只修改 `NewTopic` 的 replicas 不会自动完成副本迁移，需要使用 Kafka Partition Reassignment 工具执行副本重分配。

## 8. 显式创建 Topic

项目通过 `KafkaTopicConfig` 声明 `NewTopic`，不再依赖 Broker 自动创建 Topic 的默认分区数。

启动时 Spring Boot 提供的 KafkaAdmin 会：

- Topic 不存在：按照配置创建。
- Topic 已存在但 Partition 少于配置：增加 Partition。
- Topic Partition 已经更多：不会减少。

生产环境也可以选择关闭应用建 Topic 权限，改由 Terraform、Helm、Ansible 或 Kafka 运维脚本提前创建；此时同一组参数仍应作为基础设施配置的单一事实来源。

首次把已有单 Partition Topic 扩到 6 个 Partition 前，应：

1. 查看 Topic 当前 Partition、副本和消息积压。
2. 确认所有消息都带稳定的 `taskId` Key。
3. 确认状态机和执行代次校验已经上线。
4. 在低峰期扩分区并滚动启动 Worker。
5. 观察 Rebalance、Consumer Lag、提交失败和重复消息拦截数量。

## 9. `taskId` 分区路由能保证什么

生产者使用 `taskId` 作为 Kafka Key：

```text
send(videoai.task.analyze, taskId, taskMessage)
```

在 Partition 数不变、使用相同分区器的前提下，同一个 `taskId` 会路由到同一个 Partition。Kafka 可以保证这些记录按照写入该 Partition 的 offset 顺序交付。

它解决的是“传输顺序”问题，例如：

```text
task-1 executionNo=0  offset=10
task-1 executionNo=0  offset=11（重复）
task-1 executionNo=1  offset=12（用户手动重试）
```

Consumer 会按照 `10 → 11 → 12` 读取。

## 10. 为什么仍然必须保留状态机原子更新

结论：必须保留。Key 路由、Kafka 顺序、业务幂等分别解决不同问题，不能互相替代。

### 10.1 顺序不等于只执行一次

即使两条重复消息严格按顺序到达：

```text
消息A → 执行一次AI
消息A重复 → 再执行一次AI
```

如果没有状态判断，顺序完全正确，AI 仍然执行了两次。`startProcessing` 的条件更新：

```sql
UPDATE analysis_task
SET status = 'PROCESSING'
WHERE task_id = ?
  AND status = 'QUEUED'
  AND retry_count = ?
```

保证只有第一个竞争者能把 `QUEUED` 改成 `PROCESSING`。后续重复消息更新行数为 0，因此不会再次进入 AI 调用。

### 10.2 Kafka 顺序是写入顺序，不是业务代次顺序

可能出现以下情况：

```text
executionNo=0 第一次发送成功
Outbox 标记 SENT 前数据库连接中断
executionNo=0 被调度器再次发送
用户后来手动创建 executionNo=1
旧 Outbox 因恢复流程再次发送 executionNo=0
```

Kafka 仍然忠实保持它们的实际写入顺序，但旧执行消息可能在新的业务执行之后出现。消息中的 `executionNo` 与数据库 `retry_count` 比较，才能判断它是不是过期消息。

### 10.3 Rebalance 时可能出现业务执行重叠

Kafka 保证同一时刻一个 Partition 只分配给组内一个有效 Consumer，但旧 Consumer 可能已经在外部执行 30 分钟 AI 调用：

```text
Consumer-A 获得消息并将数据库改为 PROCESSING
Consumer-A 超过 poll 间隔或网络隔离
Partition 被分配给 Consumer-B
Consumer-B 重新收到未提交消息
Consumer-A 的 AI 调用仍在进行
```

此时 Kafka 的 Partition 所有权已经转移，但旧进程的外部副作用不会被 Kafka 自动取消。Consumer-B 必须通过数据库条件更新看到任务已经是 `PROCESSING`，从而停止重复调用 AI。

### 10.4 提交 offset 与数据库更新不是同一事务

可能发生：

```text
AI 调用成功
数据库写入 COMPLETED 成功
Kafka offset 提交失败
消息被重新投递
```

重新投递时，终态检查会识别 `COMPLETED` 并直接确认消息。没有终态状态机，重新投递就可能再次调用 AI。

### 10.5 扩 Partition 会改变 Key 映射

Partition 数从 1 增加到 6 后，后续同一个 Key 的分区计算结果可能变化。数据库状态机是跨 Partition、跨 Consumer、跨部署变更的最终业务防线。

## 11. 三层可靠性模型

```text
第一层：taskId Kafka Key
作用：尽量维持同任务消息的分区内传输顺序

第二层：Consumer Group + 手动 ack
作用：正常情况下由一个 Consumer 处理，业务完成后再提交 offset

第三层：状态机 + executionNo + SQL 条件更新
作用：阻止重复执行、旧执行覆盖新执行、迟到结果污染当前状态
```

第一层和第二层降低冲突出现的概率，第三层保证业务结果在异常情况下仍然正确。

## 12. 扩缩容决策表

| Topic Partitions | Worker 副本 | 每实例 concurrency | Consumer 总数 | 有效并行数 | 说明 |
|---:|---:|---:|---:|---:|---|
| 1 | 1 | 5 | 5 | 1 | 4 个 Consumer 空闲，无高可用 |
| 6 | 1 | 3 | 3 | 3 | 有并发，无进程级高可用 |
| 6 | 2 | 3 | 6 | 6 | 当前建议的初期生产拓扑 |
| 6 | 3 | 3 | 9 | 6 | 3 个 Consumer 空闲，可用于故障接管但不增加吞吐 |
| 10 | 2 | 5 | 10 | 10 | 需要确认 AI 并发额度、资源和成本 |

## 13. 监控与调整依据

上线后至少观察：

- Consumer Lag 及持续时间。
- `PROCESSING` 任务数量和处理时长 P50/P95/P99。
- AI 同时在途调用数，而不只是请求 QPS。
- Consumer Group Rebalance 次数。
- `CommitFailedException` 数量。
- 状态机拦截的重复消息、过期 executionNo 消息数量。
- 单 Worker CPU、内存、HTTP 连接、数据库连接和临时存储。

扩容顺序建议：

1. Consumer 总数小于 Partition 数：先增加 Worker 或 concurrency。
2. Consumer 总数已经等于 Partition 数：确认 AI 容量后再增加 Partition。
3. 优先增加 Worker 副本保证高可用，再适度增加单进程 concurrency。
4. 长任务避免频繁自动扩缩容，减少不必要的 Rebalance。

## 14. 当前参数结论

当前默认值定位为“初期生产起点”：

```text
6 Partitions
2 个 Worker 副本（部署层配置）
每个 Worker concurrency=3
同一个 videoai-worker-group
max.poll.records=1
max.poll.interval.ms=40 分钟
```

这些数字不是经验常量。后续必须用实际任务到达量、AI 平均/P99 耗时、外部并发额度和成本数据重新计算。

## 15. 参考资料

- [Apache Kafka：Consumer Group 与 Partition 分配](https://kafka.apache.org/08/implementation/distribution/)
- [Apache Kafka：Consumer 配置](https://kafka.apache.org/38/configuration/consumer-configs/)
- [Spring Kafka：Message Listener Container 并发](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- [Spring Kafka：Topic 创建与 Partition 增加](https://docs.spring.io/spring-kafka/reference/kafka/configuring-topics.html)
- [Spring Kafka：Listener 线程安全](https://docs.spring.io/spring-kafka/reference/kafka/thread-safety.html)
