# 基于 Kafka 回调与任务表补偿的最终一致性方案

## 1. 背景

视频分析任务创建时需要完成两件事：

1. 在 MySQL 中保存分析任务；
2. 向 Kafka 发布任务消息，由 Worker 异步调用 AI。

普通的 Spring `@Transactional` 只保护 MySQL，不能让 MySQL 提交和普通
`KafkaTemplate.send()` 组成一个原子操作。如果在数据库事务中同步等待 Kafka，又会延长事务时间，
占用数据库连接和锁。

本文按照“业务事务与消息发送拆开、通过 Kafka 回调记录结果、后台补偿、消费端幂等”的思路，
设计一个不使用独立 `task_outbox` 表的轻量方案。

## 2. 设计目标

- 数据库事务中不执行 Kafka 网络调用；
- API 成功创建的任务不会因为一次 Kafka 发送失败而永久丢失；
- Kafka 正常时由发送回调快速记录投递结果；
- 回调未执行、进程宕机或发送结果不确定时，可以由调度器补偿；
- 允许 Kafka 消息重复，但同一执行代次不能并发重复调用 AI；
- AI 调用失败不在程序内部自动重试，由用户手动发起下一执行代次。

本方案保证的是：

```text
消息投递最终一致 + 至少一次投递 + 消费端幂等
```

不宣称 MySQL 与 Kafka 严格 Exactly-once。

## 3. 核心思路

将消息投递状态直接保存在 `analysis_task`，让任务表兼任轻量消息表：

```text
请求线程
  → MySQL事务：创建任务，dispatch_status=PENDING
  → COMMIT
  → 事务提交后发送Kafka
  → Kafka Future回调更新投递状态

后台调度器
  → 扫描PENDING/RETRY任务
  → CAS抢占为SENDING
  → 异步发送Kafka

Consumer
  → 根据taskId和executionNo原子抢占任务
  → 只有抢占成功者调用AI
```

这里没有跨组件事务，也没有在 MySQL 事务中等待 Kafka ACK。每一次数据库状态更新都是独立的短事务。

## 4. 数据模型

在 `analysis_task` 增加投递相关字段：

```sql
ALTER TABLE analysis_task
    ADD COLUMN event_id VARCHAR(64) NULL COMMENT '当前执行代次的消息事件ID',
    ADD COLUMN dispatch_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/SENDING/SENT/RETRY/DEAD',
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0 COMMENT '消息投递次数',
    ADD COLUMN next_dispatch_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '下次允许投递时间',
    ADD COLUMN dispatch_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '投递状态更新时间',
    ADD COLUMN dispatch_error VARCHAR(500) NULL COMMENT '最近一次投递错误',
    ADD INDEX idx_dispatch_ready (dispatch_status, next_dispatch_at, id);
```

任务业务状态和消息投递状态必须分开：

| 字段 | 关注的问题 | 示例状态 |
|---|---|---|
| `status` | AI 任务执行到哪一步 | `PENDING/PROCESSING/COMPLETED/FAILED` |
| `dispatch_status` | Kafka 消息是否投递成功 | `PENDING/SENDING/SENT/RETRY/DEAD` |
| `retry_count` | 用户手动发起的执行代次 | `0/1/2...` |

不能用一个 `status` 同时表达业务执行和消息投递，否则 Kafka 回调与 Consumer 并发更新时容易互相覆盖。

### 4.1 投递状态机

```text
PENDING ──抢占──> SENDING ──ACK成功──> SENT
                    │
                    ├──确定失败──> RETRY
                    │                │
                    │                └──到期后重新抢占
                    │
                    └──长时间无回调──> RETRY

RETRY超过告警阈值──> DEAD
```

`DEAD` 表示消息投递基础设施持续异常，需要告警和人工处理；它不是 AI 执行失败。

## 5. 消息结构

当前 Worker 会根据 `taskId` 重新查询数据库，因此消息采用通知型事件即可：

```json
{
  "eventId": "event_xxx",
  "taskId": "task_xxx",
  "executionNo": 0,
  "occurredAt": 1788105000000,
  "schemaVersion": 1
}
```

- Kafka key 使用 `taskId`，相同任务进入同一分区；
- `executionNo` 对应任务的 `retry_count`，用于隔离迟到的旧消息；
- `eventId` 用于链路追踪，不依赖它代替业务状态幂等；
- 视频地址、Prompt 等可从 `analysis_task` 查询，避免消息快照与数据库最新状态不一致。

## 6. 创建任务

### 6.1 数据库事务

数据库事务只创建任务，不调用 Kafka：

```java
@Transactional
public String createTask(CreateTaskCommand command) {
    AnalysisTask task = buildTask(command);
    task.setStatus("PENDING");
    task.setRetryCount(0);
    task.setEventId(idGenerator.nextEventId());
    task.setDispatchStatus("PENDING");
    task.setDispatchAttempts(0);
    task.setNextDispatchAt(LocalDateTime.now());
    taskMapper.insert(task);

    applicationEventPublisher.publishEvent(
            new TaskCommittedEvent(task.getTaskId(), task.getRetryCount()));
    return task.getTaskId();
}
```

发布的是 JVM 内部事件。它不承担可靠性，只用于事务提交后尽快触发首次发送；即使内部事件丢失，
数据库中的 `PENDING` 任务仍会被后台调度器扫描。

### 6.2 事务提交后触发发送

```java
@Async("taskDispatchExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void afterTaskCommitted(TaskCommittedEvent event) {
    dispatcher.tryDispatch(event.taskId(), event.executionNo());
}
```

`AFTER_COMMIT` 保证 Kafka 发送不会发生在数据库事务提交之前。

需要注意：异步监听器运行在另一个线程，不能回滚已经提交的创建任务事务。发送失败时必须开启新的
短事务记录失败状态，而不是试图通过回调抛异常回滚原事务。

## 7. 抢占与发送

### 7.1 CAS 抢占

事务提交后的快速发送和后台补偿可能同时选中一条任务，因此发送前必须抢占：

```sql
UPDATE analysis_task
SET dispatch_status = 'SENDING',
    dispatch_attempts = dispatch_attempts + 1,
    dispatch_updated_at = NOW(3),
    dispatch_error = NULL
WHERE task_id = #{taskId}
  AND retry_count = #{executionNo}
  AND dispatch_status IN ('PENDING', 'RETRY')
  AND next_dispatch_at <= NOW(3);
```

只有影响行数为 1 的线程可以执行 `KafkaTemplate.send()`。

### 7.2 异步发送与回调

```java
public void tryDispatch(String taskId, int executionNo) {
    if (dispatchStateService.markSending(taskId, executionNo) == 0) {
        return;
    }

    TaskExecuteMessage message = messageFactory.create(taskId, executionNo);

    kafkaTemplate.send(TASK_TOPIC, taskId, message)
            .whenCompleteAsync((result, error) -> {
                if (error == null) {
                    dispatchStateService.markSent(taskId, executionNo);
                } else {
                    dispatchStateService.markRetry(
                            taskId, executionNo, unwrap(error));
                }
            }, taskDispatchCallbackExecutor);
}
```

回调中的数据库操作由另一个 Spring Bean 执行，每个方法使用独立短事务：

```java
@Transactional
public int markSent(String taskId, int executionNo) {
    return taskMapper.markSent(taskId, executionNo);
}

@Transactional
public int markRetry(String taskId, int executionNo, Throwable error) {
    return taskMapper.markDispatchRetry(
            taskId,
            executionNo,
            truncate(error.getMessage()),
            nextBackoffTime());
}
```

回调只处理“正常收到的确定结果”。可靠性不能只依赖回调，因为进程可能在回调执行前退出。

## 8. 后台补偿调度器

### 8.1 扫描待投递任务

```sql
SELECT task_id, retry_count
FROM analysis_task
WHERE dispatch_status IN ('PENDING', 'RETRY')
  AND next_dispatch_at <= NOW(3)
ORDER BY next_dispatch_at, id
LIMIT 50;
```

调度器每秒扫描一批候选，然后逐条执行前面的 CAS 抢占。多实例同时扫描同一候选没有关系，只有一个
实例能把记录更新为 `SENDING`。

### 8.2 恢复没有回调的 SENDING

以下窗口中，Kafka 回调可能永远没有成功更新数据库：

```text
Kafka发送成功，进程在markSent之前退出
Kafka发送失败，进程在markRetry之前退出
回调执行时数据库暂时不可用
发送结果因超时而不确定
```

因此需要恢复任务：

```sql
UPDATE analysis_task
SET dispatch_status = 'RETRY',
    next_dispatch_at = NOW(3),
    dispatch_error = 'dispatch callback timeout',
    dispatch_updated_at = NOW(3)
WHERE dispatch_status = 'SENDING'
  AND dispatch_updated_at < #{deadline};
```

恢复阈值必须大于 Kafka producer 的最大投递时间：

```text
Kafka delivery.timeout.ms
    < SENDING恢复阈值
```

例如 producer 最多尝试 60 秒，可以把 `SENDING` 恢复阈值设置为 120 秒，避免底层发送尚未结束时
过早重复投递。

## 9. 各类故障如何收敛

| 故障点 | 数据库状态 | 后续处理 | 可能重复 |
|---|---|---|---|
| 创建任务事务回滚 | 没有任务 | 不发送 | 否 |
| 提交成功、首次发送前进程退出 | `PENDING` | 调度器补发 | 否 |
| Kafka 明确失败 | `RETRY` | 退避后补发 | 否 |
| Kafka 成功、`markSent` 前退出 | `SENDING` | 超时恢复并补发 | 是 |
| ACK 丢失、结果不确定 | `SENDING/RETRY` | 超时后补发 | 是 |
| `markRetry` 时数据库故障 | `SENDING` | 超时恢复 | 可能 |
| 多个 Dispatcher 同时扫描 | 同一候选 | CAS 只有一个成功 | 正常情况下否 |

无法确定第一次发送是否成功时，只能在“可能丢失”和“可能重复”之间选择。本方案选择可能重复，再由
消费端幂等消除重复业务执行。

## 10. Consumer 幂等处理

Consumer 收到消息后，先根据任务 ID 和执行代次原子抢占：

```sql
UPDATE analysis_task
SET status = 'PROCESSING',
    started_at = NOW(3),
    updated_at = NOW(3)
WHERE task_id = #{taskId}
  AND retry_count = #{executionNo}
  AND status = 'PENDING';
```

只有影响行数为 1 的 Consumer 调用 AI：

```java
public void consume(TaskExecuteMessage message, Acknowledgment ack) {
    int claimed = taskService.startProcessing(
            message.taskId(), message.executionNo());

    if (claimed == 0) {
        ack.acknowledge();
        return;
    }

    try {
        aiService.analyze(message.taskId());
        taskService.markCompleted(message.taskId(), message.executionNo());
    } catch (Exception error) {
        taskService.markFailed(message.taskId(), message.executionNo(), error);
    }

    // AI成功或已经可靠记录为FAILED后提交offset。
    ack.acknowledge();
}
```

AI 调用失败后任务进入 `FAILED`，不自动产生新 Kafka 消息。用户点击“重新分析”时：

```text
retry_count + 1
status = PENDING
生成新的event_id
dispatch_status = PENDING
```

旧执行代次的迟到消息会因为 `retry_count` 不匹配而被直接忽略。

## 11. 长任务 Consumer 配置

如果监听线程同步等待 AI，`max.poll.interval.ms` 检查的是两次 `poll()` 的最大间隔，不是 offset
提交间隔。当前业务可以采用：

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      max-poll-records: 1
      properties:
        "[max.poll.interval.ms]": 2400000 # 40分钟
```

- `max-poll-records=1` 防止同一 Consumer 一次领取多条长任务；
- 40 分钟覆盖约 30 分钟 AI 上限和安全余量；
- 仍然必须保留数据库幂等，配置只能减少重复，不能消灭重复。

如果未来任务时长进一步增长，应考虑让 Kafka Consumer 只负责快速抢占并提交消息，把执行权交给
独立任务执行器；这时必须由数据库任务状态和超时恢复承担 Worker 宕机后的续处理，不能只依靠 Kafka
重放。

## 12. 监控与告警

至少监控以下指标：

- `dispatch_status=PENDING/RETRY` 的数量；
- 最老待投递任务的年龄；
- Kafka send 成功率、失败率和 p95/p99 延迟；
- `SENDING` 超时恢复次数；
- 每个任务的 `dispatch_attempts`；
- Kafka consumer lag 和 rebalance 次数；
- 重复消息被消费端幂等拦截的次数；
- 长时间处于 `PROCESSING` 的任务数量。

关键告警不是“出现一次重试”，而是：

```text
最老待投递任务年龄持续增长
```

这意味着投递能力长期低于任务创建速率，或者 Kafka/数据库持续异常。

## 13. 测试方案

### 13.1 正常发送

验证任务事务提交后触发 send，Kafka ACK 后 `dispatch_status=SENT`。

### 13.2 Kafka 明确失败

让 `KafkaTemplate.send()` 返回异常 Future，验证：

```text
SENDING → RETRY
dispatch_attempts + 1
next_dispatch_at 按退避时间推进
```

### 13.3 提交后、首次发送前退出

关闭事务提交后的快速发送入口，只创建任务，验证调度器能够扫描 `PENDING` 并成功投递。

### 13.4 Kafka 成功、回调落库失败

让消息真实写入 Kafka，同时让 `markSent` 抛异常，等待 `SENDING` 恢复后再次发送。验证 Consumer
收到重复消息，但 AI 只调用一次。

### 13.5 多调度器竞争

两个线程同时扫描同一任务，断言只有一个 `markSending` 返回 1，只调用一次 send。

### 13.6 长任务 poll 边界

使用嵌入式 Kafka：

- `max.poll.interval=1s`、处理 2s，验证提交 offset 失败；
- `max.poll.interval=4s`、处理 2s，验证正常提交；
- 增加第二个 Consumer，验证错误配置下同一消息能够被重新读取。

## 14. 与独立 Outbox 表的取舍

| 维度 | 任务表投递状态 | 独立 Outbox 表 |
|---|---|---|
| 实现复杂度 | 较低 | 较高 |
| 表数量 | 不增加 | 增加事件表 |
| 一任务一种消息 | 适合 | 适合 |
| 一任务多个事件 | 容易耦合 | 更自然 |
| 事件历史审计 | 较弱 | 较强 |
| CDC 扩展 | 不够标准 | 更成熟 |
| 业务表写热点 | 会增加 | 与业务表分离 |

当前视频分析业务以“创建任务后发送一次执行消息”为主，任务量中低，因此任务表投递状态是合理的
轻量方案。如果未来增加计费、通知、审核、RAG 索引等多种领域事件，或者需要保留完整事件历史，
再拆成独立 Outbox 表更合适。

## 15. 面试表达

可以概括为：

> 我按照业务事务和消息事务拆开的思路，没有在 MySQL 事务里等待 Kafka。创建任务时只在数据库中
> 记录任务和投递状态，提交后通过 Kafka Future 回调记录成功或失败。考虑到进程可能在提交后、
> 回调前退出，我又用调度器扫描待投递和长时间 `SENDING` 的任务进行补偿。结果不确定时允许重复
> 发送，再通过 `taskId + executionNo + status` 的数据库条件更新保证只有一个 Consumer 能调用 AI。
> 因此这是最终一致性方案，不是跨 MySQL 和 Kafka 的强事务。对于当前一个任务一种消息的业务，
> 我直接复用任务表保存投递状态；事件种类增加后再演进为独立 Outbox 或 CDC。
