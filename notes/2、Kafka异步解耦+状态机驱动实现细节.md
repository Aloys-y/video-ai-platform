# Kafka 异步解耦 + 状态机驱动 实现细节（前端→后端时间线）

> 核心设计：上传完成后 Controller 仅投递 Kafka 消息即刻返回（＜50ms），Worker 异步消费处理，TaskStatus 状态机驱动全生命周期，失败自动重试 → 死信兜底。

## 1) 前端触发点与顺序

### T0 用户点击提交按钮
- 上传合并完成 → `showConfirm()` 展示确认面板
- 用户填入 Prompt（可选）、点击 `#upload-submit-btn`
- 代码：`frontend/js/upload.js:73-76`

### T1 发起 POST /upload/submit
- `submitTask()` 方法：
  - 读取 `#prompt-input` 输入框内容
  - `POST /upload/submit?prompt=xxx`
  - Header 带 `X-Upload-Id`
- 代码：`frontend/js/upload.js:198-211`

### T2 收到响应后跳转任务详情
- 接口返回 `taskId`，前端写入 `window.location.hash = #/task/{taskId}`
- 此后任务详情页轮询 `/task/{taskId}` 获取进度
- 代码：`frontend/js/upload.js:213-215`

---

## 2) 后端 Controller → Service → Kafka（任务创建链路）

### T3 Controller 入口
- `UploadController.submit()` 接收请求
- 从 `UserContext` 取当前用户，从 Header 取 `uploadId`
- 调用 `uploadService.submitTask(uploadId, prompt)`
- 代码：`video-api/src/main/java/com/videoai/api/controller/UploadController.java:58-62`

### T4 Service：创建任务 + 投递 Kafka
在 `UploadService.createAnalysisTask()`：
1. 验证上传会话存在、文件已合并完成（`storagePath != null`）
   - `UploadService.java:277-283`
2. 创建任务实体，初始状态 `PENDING`，`maxRetry=3`
   - `UploadService.java:308-320`
3. Insert 到 MySQL
   - `UploadService.java:321`
4. **异步发送 Kafka 消息**（不阻塞主线程）
   - `kafkaTemplate.send(TopicConstant.TASK_TOPIC, taskId, taskMessage)`
   - 失败只 log warning，不抛异常，后续可通过定时任务补偿
   - `UploadService.java:323-327`
5. 返回 `taskId` → Controller 返回给前端
   - 从插入 DB 到返回前端，整个过程 **＜50ms**

> **设计要点**：触发 Kafka 发送的时刻是 `analysisTaskMapper.insert(task)` 之后。利用 DB 先落地任务记录，再异步通知 Worker。即使 Kafka 发送失败，任务记录已存在 DB，Worker 可通过补偿扫描 PENDING 任务兜底。

### T5 Kafka 消息体结构
`TaskMessage` 包含以下字段：
| 字段 | 用途 |
|---|---|
| `taskId` | 作为 Kafka key，保证同一任务有序消费 |
| `uploadId` | 关联上传会话 |
| `userId` | 审计追踪 + 用户级负载均衡 |
| `videoUrl` | Worker 通过此路径生成预签名 URL |
| `retryCount` | 初始 0，每次重试 +1 |
| `timestamp` | 消息创建时间，用于监控延迟 |
| `prompt` | 用户自定义分析提示词 |

代码：`video-common/src/main/java/com/videoai/common/message/TaskMessage.java:30-118`

### T5.1 提交幂等控制（防重复创建任务）

**问题**：前端 `btn.disabled = true` 只是 UI 层面的防护，用户双击、网络超时重试、直接 curl 都能绕过。`analysis_task` 表的 `upload_id` 只有普通索引（非唯一约束，设计上允许一个上传产生多个任务），无幂等保护时每次 `/submit` 都会创建新任务，浪费 AI 调用。

**方案**：Redisson 分布式锁（`submit:lock:{uploadId}`）+ 非终态任务检查。

```java
// UploadService.submitTask()
String lockKey = RedisKey.submitLock(uploadId);
RLock lock = redissonClient.getLock(lockKey);
try {
    boolean locked = lock.tryLock(5, 30, TimeUnit.SECONDS);
    if (!locked) throw new BusinessException(ErrorCode.TASK_PROCESSING);

    // 幂等检查：同一上传已有非终态任务则直接返回
    AnalysisTask existingTask = findNonFinalTaskByUploadId(uploadId);
    if (existingTask != null) {
        return existingTask.getTaskId();  // ← 幂等返回已有 taskId
    }

    // 无任务 → 创建新任务
    return createAnalysisTask(...).getTaskId();
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

**设计要点**：
- **锁粒度**：`uploadId` 级别，锁范围仅覆盖 check + insert（微秒级），不覆盖 AI 处理
- **非终态过滤**：只拦截 `PENDING/QUEUED/PROCESSING/FAILED/RETRYING`，终态（`COMPLETED/CANCELLED/DEAD`）允许重新提交
- **为什么这里需要分布式锁而 TaskProcessor 不需要？** `submitTask` 的 check → insert 之间没有状态机兜底——两个并发请求同时看到"无任务"会各插一条。TaskProcessor 有 `UPDATE WHERE status = ?` 天然保护。场景不同，选型不同。

代码：`video-api/src/main/java/com/videoai/api/service/UploadService.java:276-320`

---

## 3) Kafka Topic 设计

### Topic 一览
| Topic | 用途 | 消费者组 |
|---|---|---|
| `videoai.task.analyze` | 任务消息，Worker 消费 | `videoai-worker-group`（并发 3） |
| `videoai.task.event` | 状态变更事件，ES 索引 | `videoai-monitor-group` |
| `videoai.task.dead` | 死信，人工介入 | `videoai-monitor-group` |

代码：`video-infrastructure/src/main/java/com/videoai/infra/kafka/topic/TopicConstant.java:20-71`

### 消费模型
- **手动 ack**：Worker 处理完成后 `ack.acknowledge()`，保证 offset 不提前提交
- **至少一次语义**：结合状态机幂等校验，最终达到精确一次效果
- **并发消费**：`concurrency = 3`，3 个消费者线程并行处理

---

## 4) Worker 消费 → 状态机驱动（核心处理链路）

### T6 Kafka 消息到达 Worker
`TaskConsumer.consume()`：
- `@KafkaListener` 监听 `videoai.task.analyze`
- 消费到 `TaskMessage`，调用 `taskProcessor.process(message)`
- `finally` 块中 **始终 ack**（不阻塞后续消息，失败由重试机制处理）
- 代码：`video-worker/src/main/java/com/videoai/worker/consumer/TaskConsumer.java:38-52`

### T7 TaskProcessor 主流程

```
process(message)
  │
  ├─ 1. 查 DB 获取任务最新状态
  ├─ 2. 幂等校验：终态任务直接 return
  ├─ 3. PENDING → QUEUED（updateStatusWithCheck 状态校验）
  ├─ 4. QUEUED/RETRYING → PROCESSING（startProcessing）
  └─ 5. doProcess() 执行 AI 分析
```

代码：`video-worker/src/main/java/com/videoai/worker/processor/TaskProcessor.java:43-86`

> **设计变更（2026-05-29）**：移除了分布式锁（Redisson tryLock）。理由：状态机的 `UPDATE WHERE status = ?` 已提供天然幂等（InnoDB 行锁保证只有一个 Worker 能成功），分布式锁在此场景下是冗余的。详见第 7.2 节。

### T7-1 幂等校验
```java
// 终态任务跳过（COMPLETED / CANCELLED / DEAD）
if (task.isFinalState()) {
    log.info("Task already in final state: {}", taskId);
    return;
}
```

### T7-2 状态转换（PENDING → QUEUED）
```sql
-- updateStatusWithCheck：带前置状态的原子更新
UPDATE analysis_task SET status = 'QUEUED', updated_at = NOW()
WHERE task_id = #{taskId} AND status = 'PENDING'
```
- 影响行数 = 0 → 说明被其他 Worker 抢先，直接 return
- 这是状态机驱动的"天然幂等"关键：**每个状态转换都校验前置状态**

代码：`video-infrastructure/src/main/java/com/videoai/infra/mysql/mapper/AnalysisTaskMapper.java:25-30`

### T7-3 状态转换（QUEUED / RETRYING → PROCESSING）
```sql
-- startProcessing：仅允许 QUEUED 或 RETRYING 转入
UPDATE analysis_task SET status = 'PROCESSING', started_at = NOW(), updated_at = NOW()
WHERE task_id = #{taskId} AND status IN ('QUEUED', 'RETRYING')
```
代码：`AnalysisTaskMapper.java:36-39`

### T8 doProcess() 实际 AI 分析
1. 进度 10% → 生成 MinIO 预签名 URL
2. 进度 20% → 调用 `aiService.analyzeVideo(presignedUrl, userPrompt)`（GLM-4V）
3. 进度 80% → 提取分析摘要
4. **成功**：`completeTask()` → status = `COMPLETED`，写入 result/summary

代码：`TaskProcessor.java:114-153`

---

## 5) 任务状态机

### 状态定义

| 状态 | code | 含义 | 终态？ |
|---|---|---|---|
| `PENDING` | 0 | 任务刚创建，等待 Worker 消费 | 否 |
| `QUEUED` | 1 | 消息已被 Worker 接收，区分"创建成功"与"已入队" | 否 |
| `PROCESSING` | 2 | Worker 正在调用 AI 分析 | 否 |
| `COMPLETED` | 3 | 分析成功完成 | **是** |
| `FAILED` | 4 | 分析失败，等待重试 | 否 |
| `RETRYING` | 5 | 正在重试中 | 否 |
| `CANCELLED` | 6 | 用户主动取消 | **是** |
| `DEAD` | 7 | 重试耗尽，最终失败 | **是** |

代码：`video-common/src/main/java/com/videoai/common/enums/TaskStatus.java:21-63`

### 状态流转图

```
                    ┌──────────────┐
                    │   PENDING    │
                    └───┬────┬─────┘
                        │    │
              Kafka消费 │    │ 用户取消
                        │    │
                 ┌──────▼──┐ │
                 │ QUEUED  │ │
                 └──┬──────┘ │
                    │        │
          Worker获取锁 │      │
                    │        │
              ┌─────▼──────┐ │
              │ PROCESSING ├─┤
              └──┬────┬────┘ │
                 │    │      │
        AI成功   │    │ 失败 │
                 │    │      │
        ┌────────▼┐ ┌─▼───┐ │
        │COMPLETED│ │FAILED│ │
        └─────────┘ └──┬──┘ │
                       │重试│
                       │未满│
                  ┌────▼──┐ │
                  │RETRYING├─┘
                  └──┬────┘
                     │重试耗尽
                 ┌───▼──┐
                 │ DEAD │
                 └──────┘

        ── 终态不可再转换 ──
```

### 状态转换合法性表（`canTransitionTo` 方法）

| 当前状态 | 允许转换到 |
|---|---|
| `PENDING` | `QUEUED`, `CANCELLED` |
| `QUEUED` | `PROCESSING`, `CANCELLED` |
| `PROCESSING` | `COMPLETED`, `FAILED`, `CANCELLED` |
| `FAILED` | `RETRYING`, `DEAD` |
| `RETRYING` | `PROCESSING`, `FAILED`, `DEAD` |
| `COMPLETED` / `CANCELLED` / `DEAD` | **不可转换（终态）** |

代码：`TaskStatus.java:93-106`

### 为什么每个状态转换都校验前置状态？—— 天然幂等
- 合并操作：将 MySQL UPDATE 的 WHERE 条件作为状态校验
- `UPDATE ... WHERE status = 'PENDING'` → 只有一个 Worker 能成功
- **不依赖 Redis 做幂等**，状态机本身就是幂等模型

---

## 6) 失败重试 & 死信机制

### T9 handleFailure 失败处理

```
handleFailure(taskId, error)
  │
  ├─ 1. failTask() → status = 'FAILED'，记录 error_message
  ├─ 2. 判断 canRetry()（retryCount < maxRetry → 默认3次）
  │
  ├─ YES（可重试）：
  │   ├─ incrementRetry() → status = 'RETRYING'，retry_count + 1
  │   ├─ 重新构建 TaskMessage（retryCount + 1）
  │   ├─ kafkaTemplate.send(TASK_TOPIC) 重新入队
  │   └─ sendTaskEvent(taskId, "RETRYING")
  │
  └─ NO（重试耗尽）：
      ├─ markAsDead() → status = 'DEAD'
      ├─ kafkaTemplate.send(DEAD_LETTER_TOPIC) 死信队列
      └─ sendTaskEvent(taskId, "DEAD")
```

代码：`TaskProcessor.java:198-239`

### 消息重新入队细节
- 重试消息 `retryCount` +1 后重新发送到**同一个 Topic** `videoai.task.analyze`
- Worker 再次消费，`startProcessing` 的 WHERE 条件包含 `RETRYING`
- 循环直至 `COMPLETED` 或 `DEAD`

### T10 DeadLetterConsumer 死信处理
- 消费 `videoai.task.dead` Topic
- 打印完整错误信息（taskId / error / retryCount / timestamp）
- 预留 `TODO`：接入告警系统（钉钉/飞书/邮件）

代码：`DeadLetterConsumer.java:29-41`

> 死信队列的面试价值：重试耗尽的任务不被静默丢弃，运维可查可修复，构成完整的容错闭环。

---

## 7) 可靠性三角——幂等、手动 ack、死信队列

### 7.1 为什么要控制幂等？

Kafka 手动 ack 模式的消息投递保证是 **at-least-once**（至少一次），同一条消息可能被重复消费。三种典型场景：

| 场景 | 发生了什么 | 后果 |
|---|---|---|
| Worker 处理成功但 ack 前进程崩溃 | offset 未提交，Rebalance 后新 Worker 重新消费 | 同一条消息被消费 2 次 |
| Kafka 网络抖动导致心跳超时 | 消费者被踢出 group，分区重分配 | 同一条消息被新消费者再消费 1 次 |
| 用户手动点"重试" | `TaskService.retryTask()` 重新 send 同一 taskId 到 Topic | 同一条消息再次入队 |

**不控幂等的后果**：同一段视频可能被 GLM-4V 分析 2 次，每次消耗 tokens（计费），结果写 DB 时两次覆盖，用户看到结果反复跳变。

### 7.2 状态机如何做到天然幂等？

**核心思路：把"幂等"下沉到 MySQL 的 UPDATE WHERE 条件里**，不依赖 Redis 去重。

```sql
-- PENDING → QUEUED：只有当前状态是 PENDING 才允许
UPDATE analysis_task SET status = 'QUEUED', updated_at = NOW()
WHERE task_id = #{taskId} AND status = 'PENDING'

-- QUEUED/RETRYING → PROCESSING：仅这两个非终态可进入
UPDATE analysis_task SET status = 'PROCESSING', started_at = NOW(), updated_at = NOW()
WHERE task_id = #{taskId} AND status IN ('QUEUED', 'RETRYING')
```

消息重复消费时：

```
消息第1次到达 → WHERE status='PENDING' 命中 → 转 QUEUED → 影响行数=1 ✓
消息第2次到达 → WHERE status='PENDING' 不命中（当前已是 QUEUED）→ 影响行数=0 → return
```

**这就是"天然幂等"**：不需要额外的去重表或分布式锁做幂等标记，状态机本身的状态转换约束就是幂等栅栏。每个 Worker 调用 `updateStatusWithCheck()` 后检查 `rows == 0`，为 0 说明已被其他 Worker 抢先处理过，直接跳过。

```java
// TaskProcessor.java:60-70
if (currentStatus == TaskStatus.PENDING) {
    int rows = analysisTaskMapper.updateStatusWithCheck(
            taskId, TaskStatus.PENDING.getCode(), TaskStatus.QUEUED.getCode());
    if (rows == 0) {
        log.info("PENDING→QUEUED transition failed (concurrent): {}", taskId);
        return;    // ← 幂等跳过
    }
}
```

再多一层防御：

```java
// TaskProcessor.java:54-58
// 终态任务直接跳过（COMPLETED / CANCELLED / DEAD）
if (task.isFinalState()) {
    log.info("Task already in final state: {}, status: {}", taskId, task.getStatus());
    return;
}
```

**两道栅栏汇总**：

```
消息到达
  ├─ 栅栏① isFinalState()       → 终态？直接 return
  └─ 栅栏② UPDATE WHERE status   → 前置状态不匹配？rows=0 → return
```

> **为什么不需要分布式锁？** InnoDB 行锁保证两个 Worker 同时执行 `UPDATE ... WHERE status = 'PENDING'` 时只有一个成功（rows=1），另一个 rows=0 直接返回。状态机本身已达 exactly-once 效果，分布式锁在此场景下是纯性能开销。而在并发冲突概率极低（Kafka rebalance 罕见）的情况下，每次白跑 2 次 Redis RTT 反而降低性能。

### 7.3 手动 ack 如何确保消息不丢失？

**自动 ack 的问题**：

```
自动 ack (enable.auto.commit=true):
  poll() 拿到消息 → 立即自动提交 offset → Worker 开始处理
                                              ↓
                                         处理中途 JVM OOM → 消息已"确认"，实际未处理 → 丢失
```

**手动 ack 的解决方案**：

```yaml
# application-dev.yml
spring:
  kafka:
    listener:
      ack-mode: manual_immediate    # 手动确认
    consumer:
      enable-auto-commit: false     # 关闭自动提交
```

```java
// TaskConsumer.java:38-52
public void consume(TaskMessage message, Acknowledgment ack) {
    try {
        taskProcessor.process(message);  // 1. 先处理
    } catch (Exception e) {
        log.error("...", e);             // 2. 处理失败只记日志，不抛
    } finally {
        ack.acknowledge();               // 3. 无论如何都 ack — 不阻塞后续消息
    }
}
```

**关键设计决策——处理失败也 ack**：

处理失败仍然 ack，不阻塞队列后续消息。那失败的消息去哪了？

→ `TaskProcessor.handleFailure()` 内部判断 `canRetry()`，如果可重试就 **重新发送一条新消息** 到同一 Topic：

```java
// TaskProcessor.java:211-226
if (task.canRetry()) {
    analysisTaskMapper.incrementRetry(taskId);          // status = 'RETRYING'
    TaskMessage retryMsg = TaskMessage.builder()
            .taskId(taskId)
            .retryCount(task.getRetryCount() + 1)       // retryCount + 1
            ...build();
    kafkaTemplate.send(TopicConstant.TASK_TOPIC, taskId, retryMsg);  // 重新入队
}
```

所以 Kafka 原始消息 ack 了（不阻塞消费者），但任务通过"失败 → RETRYING → 重新入队"继续流转。Kafka 的 offset 和业务的重试是解耦的。

### 7.4 死信队列如何兜底？

当 `retryCount >= maxRetry`（默认 3 次），不再重新入队，改为投递死信：

```java
// TaskProcessor.java:227-237
} else {
    analysisTaskMapper.markAsDead(taskId, errorMessage);    // status = 'DEAD'

    kafkaTemplate.send(TopicConstant.DEAD_LETTER_TOPIC, taskId,
            Map.of("taskId", taskId,
                   "error", errorMessage,
                   "retryCount", task.getRetryCount(),
                   "timestamp", System.currentTimeMillis()));
}
```

死信消费者负责落地：

```java
// DeadLetterConsumer.java:28-41
@KafkaListener(topics = TopicConstant.DEAD_LETTER_TOPIC, groupId = TopicConstant.MONITOR_GROUP)
public void consume(Map<String, Object> message, Acknowledgment ack) {
    log.error("============ DEAD LETTER TASK ============");
    log.error("taskId: {}, error: {}, retryCount: {}",
              message.get("taskId"), message.get("error"), message.get("retryCount"));
    // TODO: 接入钉钉/飞书告警
    ack.acknowledge();
}
```

> 死信队列的价值：重试耗尽的任务不被静默丢弃，运维可查 log 定位问题，构成完整的容错闭环。

### 7.5 完整链路串起来

```
Kafka 消息到达
    │
    ├─ 手动 ack (at-least-once 语义)
    │
    ├─ 幂等保障（两道栅栏）：
    │   ├─ ① isFinalState() → 终态直接 return
    │   └─ ② UPDATE WHERE status='PENDING' → rows=0 → return
    │
    ├─ 处理成功：
    │   └─ completeTask() → COMPLETED（终态）
    │
    └─ 处理失败：
        ├─ retryCount < 3 → RETRYING → 重新 send TASK_TOPIC → 回到上面
        └─ retryCount >= 3 → DEAD → send DEAD_LETTER_TOPIC → 告警/人工介入
```

三条线织成一张网，闭环了"不丢、不重、不静默"三个可靠性诉求：

| 机制 | 解决的问题 | 副作用 | 副作用如何弥补 |
|---|---|---|---|
| **手动 ack** | 消息不丢失 | 可能重复消费（at-least-once） | — |
| **状态机幂等** | 重复消费不会重复处理 | — | 依赖手动 ack 先保证不丢 |
| **死信队列** | 重试耗尽不被静默丢弃 | — | — |

---

## 8) Kafka ack-mode 配置要点

这是简历中"踩坑解决"的技术细节：

**问题**：最初使用 Kafka 默认的 `enable.auto.commit=true`（自动提交），Worker 处理失败后 offset 已被提交，消息被跳过。

**解决**：
- 配置改为 `enable.auto.commit=false`，手动 ack
- `ack-mode: manual`，在 `finally` 块中调 `ack.acknowledge()`
- 结合 `DefaultErrorHandler` 兜底未捕获的异常

**关键理解**：手动 ack 保证"至少消费一次"，但可能重复消费。状态机前置校验保证"处理一次的效果"，两者组合达到 **exactly-once 语义**。

---

## 9) 一句话总结（面试版）

用户点击提交后，后端幂等检查（同一上传已有非终态任务直接返回），创建 PENDING 任务入库立刻返回（＜50ms），异步发送 Kafka 消息不阻塞主流程；Worker 手动 ack 消费，通过 TaskStatus 状态机驱动（PENDING→QUEUED→PROCESSING→COMPLETED），每次状态转换的 UPDATE 都带 WHERE 前置状态校验实现天然幂等，无需分布式锁；失败自动 RETRYING 重新入队，最多重试 3 次，耗尽后进入 DEAD 死信队列兜底。
