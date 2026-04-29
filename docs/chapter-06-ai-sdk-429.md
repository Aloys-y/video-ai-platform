# 面试点：集成第三方 AI SDK 的 429 重试踩坑

## 场景描述

项目集成智谱 GLM-4V-Flash 多模态模型做视频分析。模型返回 HTTP 429（平台过载）时需要自动重试。

## 问题现象

写了 429 重试逻辑，始终不生效。每次都是 `attempt: 1/4` 就直接失败，重试计数器从未递增。

## 排查过程

### 第一轮：检查异常消息

```java
// 原始代码 — 只看 e.getMessage()
String msg = e.getMessage();
boolean is429 = msg.contains("429"); // 匹配不到！
```

SDK 日志明明打印了 `HTTP exception: HTTP 429`，但 `e.getMessage()` 返回的是 `"Call Failed"`，不含 "429"。

### 第二轮：遍历异常链

```java
Throwable t = e;
while (t != null) {
    sb.append(t.getMessage());
    t = t.getCause(); // cause 是 null！
}
```

本以为 429 信息藏在 `getCause()` 里，结果 cause 是 null。

### 第三轮：看 SDK 源码定位根因

SDK 内部做了这样的事：

```
ZhipuAiClient 收到 HTTP 429
  → 抛 ZAiHttpException("The service may be temporarily overloaded...")
  → SDK 内部 catch 住这个异常
  → 重新抛 RuntimeException("Call Failed")  ← 原始异常被吞掉！
```

**关键发现**：SDK 在 `AbstractAiClient.executeRequest()` 里把 `ZAiHttpException` 捕获后，包装成了一个**没有任何 cause 的 `RuntimeException`**，原始 429 信息在日志里打了 ERROR，但异常对象里完全丢失。

### 最终方案

既然异常链不可靠，直接匹配 SDK 的通用错误包装：

```java
// 拼接整个异常链（类名 + 消息）
StringBuilder sb = new StringBuilder();
Throwable t = e;
while (t != null) {
    if (sb.length() > 0) sb.append(" | ");
    sb.append(t.getClass().getSimpleName());
    if (t.getMessage() != null) {
        sb.append(": ").append(t.getMessage());
    }
    t = t.getCause();
}
String msg = sb.toString();

// 匹配关键字 + SDK 通用包装
boolean isRetryable = msg.contains("429")
    || msg.contains("overload")
    || msg.contains("overloaded")
    || msg.contains("Call Failed");  // SDK 对 429/5xx 的统一包装
```

## 两层重试架构

```
┌─────────────────────────────────────────────┐
│ AiService（内存级重试）                       │
│  attempt 1 → 429 → sleep 10s               │
│  attempt 2 → 429 → sleep 30s               │
│  attempt 3 → 429 → sleep 60s               │
│  attempt 4 → 429 → 抛异常                    │
├─────────────────────────────────────────────┤
│ TaskProcessor（Kafka 级重试）                 │
│  catch 异常 → FAIL → 重入 Kafka              │
│  retryCount +1 → 新的 AiService 调用         │
│  最多 maxRetry=3 次                          │
│  全部失败 → DEAD → 死信队列                   │
└─────────────────────────────────────────────┘

总调用次数：4 (AiService) × 4 (含初始) = 最多 16 次 GLM API 调用
```

## 踩坑总结

| 坑 | 教训 |
|---|---|
| SDK 文档没说会吞异常 | 不能假设第三方 SDK 会透传原始异常 |
| `e.getMessage()` 不等于日志内容 | SDK 的 ERROR 日志和实际抛的异常是两套东西 |
| 异常链 cause 可能为 null | `while (t.getCause() != null)` 遍历可能只走一轮 |
| IDEA 增量编译缓存 | 外部工具修改源码后，IDEA 可能用缓存的旧 class 启动 |

## 面试话术

> "我们项目集成智谱 GLM 做视频分析，遇到平台频繁返回 429 的问题。我设计了指数退避重试（10s/30s/60s），但发现重试始终不触发。排查后发现智谱 SDK 内部会捕获原始的 `ZAiHttpException`，然后包装成 `RuntimeException("Call Failed")` 重新抛出，完全丢弃了原始异常对象，导致异常链中没有 429 信息。最终通过分析 SDK 的日志输出和异常包装模式，用 `"Call Failed"` 作为兜底匹配条件解决了问题。这个经历让我深刻认识到——集成第三方 AI SDK 时，不能信任它的异常透传机制，必须通过实际日志验证异常的真实形态。"

## 扩展考点

### 1. 如果让你设计这个 SDK，你会怎么处理异常？

应该把原始异常作为 cause 保留：
```java
// SDK 应该这样包装
throw new RuntimeException("Call Failed", originalException);  // 保留 cause
// 或者提供 HTTP 状态码方法
throw new AiApiException(429, "overloaded", originalException);
```

### 2. 重试策略有哪些？

| 策略 | 特点 | 适用场景 |
|------|------|----------|
| 固定间隔 | 简单，但可能雪崩 | 内部服务 |
| 指数退避 | 逐步减负，我们用的这个 | 外部 API |
| 带抖动的指数退避 | AWS 推荐，避免同步重试 | 大规模分布式 |
| 令牌桶 | 控制速率而非重试 | 限流场景 |

### 3. 为什么不在 TaskProcessor 层做重试？

| 维度 | AiService 内部重试 | TaskProcessor Kafka 重试 |
|------|-------------------|--------------------------|
| 开销 | 内存级，毫秒级 | DB 状态变更 + Kafka 序列化 |
| 延迟 | sleep 后直接重试 | 消费→处理→失败→再消费 |
| 适用 | 瞬时错误（429/超时） | 业务级重试（数据修复后） |
| 状态 | 不改变任务状态 | 每次失败更新 DB |

### 4. 还能怎么优化？

- 换付费 API Key 或更稳定的模型
- 加入 circuit breaker（连续 N 次 429 后熔断）
- 监控 429 频率，动态调整重试间隔
- 异步通知用户"正在排队"而非让用户等待

## 相关文件

- `video-worker/src/main/java/com/videoai/worker/service/AiService.java` — 429 重试逻辑
- `video-worker/src/main/java/com/videoai/worker/processor/TaskProcessor.java` — Kafka 层重试
- `video-worker/src/main/java/com/videoai/worker/consumer/TaskConsumer.java` — Kafka 消费
- `video-worker/src/main/java/com/videoai/worker/consumer/DeadLetterConsumer.java` — 死信队列
