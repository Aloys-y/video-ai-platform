# AI 视频分析：双 Provider 抽象 + 429 限流重试 实现细节

> 核心设计：`AiVideoProvider` 接口抽象大模型调用，DashScope（Qwen-VL）和智谱（GLM）两个 Provider 通过 `ai.provider` 配置项一键切换；`AiService` 门面层单次调用不重试，失败由 `TaskProcessor.handleFailure()` 统一走 Kafka 重投（非阻塞），避免 `Thread.sleep` 阻塞消费者线程。

> **设计变更（2026-05-29）**：移除了 `AiService` 的内层 `Thread.sleep` 指数退避重试（10s/30s/60s）。理由：`Thread.sleep` 阻塞 Kafka 消费者线程（concurrency=5 的稀缺资源），改为单次调用失败直接抛异常 → TaskProcessor 走 Kafka 重投，Kafka 队列天然提供退避延迟且不占用线程。

## 1) 架构分层

```
TaskProcessor.doProcess()
    │
    ▼
AiService.analyzeVideo(videoUrl, prompt)        ← 门面层：单次调用，不在此层重试
    │  └─ 失败 → RuntimeException → TaskProcessor.handleFailure() → Kafka 重投
    ▼
AiVideoProvider.call(videoUrl, fullPrompt)       ← 接口层：解耦厂商
    ├── @ConditionalOnProperty(ai.provider = dashscope) → DashScopeVideoProvider (Qwen-VL)
    └── @ConditionalOnProperty(ai.provider = zhipu)     → ZhipuVideoProvider (GLM)
```

- **接口层** 定义 `call()` / `getName()` / `getPresignedUrlExpireHours()`
- **门面层** 单次调用，不做重试（重试统一由 TaskProcessor 走 Kafka，避免阻塞消费线程）
- **配置层** 通过 `@ConditionalOnProperty` 控制哪个 Bean 生效

---

## 2) AiVideoProvider 接口定义

```java
public interface AiVideoProvider {
    /**
     * 执行一次视频分析 API 调用
     * @param videoUrl 视频公网 URL（MinIO 预签名）
     * @param prompt   完整提示词（System + User 拼接后）
     * @return AI 返回的分析文本
     * @throws AiProviderException API 调用异常（含 retryable 标记）
     */
    String call(String videoUrl, String prompt) throws AiProviderException;

    /** MinIO 预签名 URL 过期时间（小时） */
    int getPresignedUrlExpireHours();

    /** Provider 名称，用于日志区分 */
    String getName();
}
```

代码：`video-worker/src/main/java/com/videoai/worker/service/provider/AiVideoProvider.java:9-30`

**设计要点**：
- 接口只定义"一次调用"，不包含重试逻辑（重试由门面层统一处理）
- `getName()` 返回 `"DashScope(qwen3-vl-flash)"` / `"Zhipu(GLM-5V-Turbo)"`，日志可区分
- `getPresignedUrlExpireHours()`：各厂商对预签名 URL 有效期要求不同（通常 2 小时）

---

## 3) Provider 切换机制——`@ConditionalOnProperty`

### 如何切换

两个 Provider 的实现类通过 Spring Boot `@ConditionalOnProperty` 互斥激活：

```java
// DashScopeVideoProvider.java:26
@ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeVideoProvider implements AiVideoProvider { ... }

// ZhipuVideoProvider.java:19
@ConditionalOnProperty(name = "ai.provider", havingValue = "zhipu")
public class ZhipuVideoProvider implements AiVideoProvider { ... }

// ZhipuConfig.java:16  ← ZhipuAiClient Bean 也受同一条件控制
@ConditionalOnProperty(name = "ai.provider", havingValue = "zhipu")
public class ZhipuConfig { @Bean public ZhipuAiClient zhipuAiClient() { ... } }
```

### 配置文件

```yaml
# video-worker/src/main/resources/application-dev.yml:52-54
ai:
  provider: dashscope   # ← 改这里，重启即切换
```

| 配置值 | 激活的 Bean | 模型 |
|---|---|---|
| `dashscope`（默认） | `DashScopeVideoProvider` + `DashScopeConfig` | qwen3-vl-flash |
| `zhipu` | `ZhipuVideoProvider` + `ZhipuConfig` + `ZhipuAiClient` | GLM-5V-Turbo |

代码：`video-worker/src/main/resources/application-dev.yml:52-68`

> **设计要点**：`matchIfMissing = true` 保证默认走 DashScope，即使忘了写配置也不报错。

---

## 4) DashScope（Qwen-VL）Provider 实现细节

### 4.1 DashScopeConfig 配置类

```java
@ConfigurationProperties(prefix = "ai.dashscope")
@Data
public class DashScopeConfig {
    private String apiKey;
    private String model = "qwen3-vl-flash";    // 默认模型
    private int maxTokens = 4096;
    private int timeout = 300;                   // SDK 读超时 300s
    private int connectTimeout = 30;             // SDK 连接超时 30s
    private int presignedUrlExpireHours = 2;     // 预签名 URL 有效期
}
```

代码：`video-worker/src/main/java/com/videoai/worker/config/DashScopeConfig.java:13-24`

### 4.2 DashScopeVideoProvider.call() 流程

```
call(videoUrl, prompt)
  │
  ├─ 1. 设置 SDK 全局超时：ConnectionConfigurations.setReadTimeout(300s)
  │     └─ 注意：DashScope SDK 用全局静态变量 Constants.connectionConfigurations
  ├─ 2. 构建视频消息体：
  │     └─ [{"video": videoUrl, "fps": 2}, {"text": prompt}]
  ├─ 3. MultiModalConversation.call(param)
  │     ├─ 捕获 ApiException：
  │     │   ├─ httpStatus==429 or errorCode 含 "Throttling"/"RateLimit"
  │     │   │   → retryable=true
  │     │   └─ 网络超时（"timed out" / "connection reset"）
  │     │       → retryable=true
  │     │   └─ 其他 → retryable=false
  │     ├─ 捕获 NoApiKeyException → retryable=false
  │     └─ 捕获 Exception → retryable=false
  └─ 4. 解析响应：contentList[0]["text"] → 返回
```

代码：`video-worker/src/main/java/com/videoai/worker/service/provider/DashScopeVideoProvider.java:32-100`

### 4.3 DashScope 429 检测逻辑

```java
// line 72-76
boolean retryable = httpStatus == 429 ||
    (errorCode != null && (errorCode.contains("Throttling") ||
                           errorCode.contains("RateLimit"))) ||
    isTransientError(e.getMessage());
```

三种可重试场景：
| 场景 | 检测方式 | 示例 |
|---|---|---|
| HTTP 429 | `httpStatus == 429` | QPS 超限 |
| 限流错误码 | errorCode 含 `Throttling` / `RateLimit` | 阿里云令牌桶限流 |
| 瞬态网络错误 | 异常消息含 `timed out` / `connection reset` | SDK 底层 Socket 超时 |

---

## 5) 智谱（GLM）Provider 实现细节

### 5.1 ZhipuConfig 配置类

```java
@ConfigurationProperties(prefix = "ai.zhipu")
@ConditionalOnProperty(name = "ai.provider", havingValue = "zhipu")
@Data
public class ZhipuConfig {
    // ... 同时通过 @Bean 创建 ZhipuAiClient：
    @Bean
    public ZhipuAiClient zhipuAiClient() {
        return ZhipuAiClient.builder().ofZHIPU().apiKey(apiKey).build();
    }
}
```

代码：`video-worker/src/main/java/com/videoai/worker/config/ZhipuConfig.java:15-32`

### 5.2 ZhipuVideoProvider.call() 流程

```
call(videoUrl, prompt)
  │
  ├─ 1. 构建 ChatCompletionCreateParams：
  │     ├─ system message: "你是一个专业的视频内容分析助手。"
  │     └─ user message: [{"type":"video_url", ...}, {"type":"text", ...}]
  ├─ 2. zhipuAiClient.chat().createChatCompletion(request)
  │     ├─ 成功（response.isSuccess()）：
  │     │   └─ 提取 content 文本 → return
  │     └─ 失败：按 code 分类处理
  ├─ 3. 错误码分类：
  │     ├─ 不可重试：bizCode 1113/1112/1121/1110 → retryable=false
  │     │   （账户异常、余额不足、无权限等）
  │     ├─ 可重试：httpCode==429 或 bizCode 1302/1303/1304/1305/1312
  │     │   （限流相关）
  │     └─ 未知错误 → retryable=false
```

代码：`video-worker/src/main/java/com/videoai/worker/service/provider/ZhipuVideoProvider.java:26-99`

### 5.3 智谱 429 + 业务错误码双重检测

```java
// line 93-96
if (httpCode == 429 || (bizCode != null && (
    bizCode.equals("1302") || bizCode.equals("1303") ||
    bizCode.equals("1304") || bizCode.equals("1305") ||
    bizCode.equals("1312")))) {
    throw new AiProviderException("限流(可重试): ...", true);
}
```

智谱 API 的限流不只通过 HTTP 429 返回，也会以业务错误码形式包裹在响应体中（SDK 会把原始的 HTTP 状态码吞掉，只暴露 `response.getCode()` 和 `bizCode`）。两个维度都要检测。

---

## 6) AiProviderException——区分可重试 / 不可重试

```java
public class AiProviderException extends Exception {
    private final boolean retryable;   // ← 核心字段

    public AiProviderException(String message, boolean retryable) { ... }
    public AiProviderException(String message, Throwable cause, boolean retryable) { ... }
    public boolean isRetryable() { return retryable; }
}
```

代码：`video-worker/src/main/java/com/videoai/worker/service/provider/AiProviderException.java:6-23`

**为什么用 Checked Exception 而非 Runtime？**
- `call()` 可能因网络/鉴权/限流等原因失败，调用方**必须**处理
- `retryable` 标记保留在异常消息中，便于日志排查和未来扩展（例如 TaskProcessor 可根据 `retryable=false` 跳过无意义重试）
- 对比抛 `RuntimeException`：调用方只能 catch-all，无法区分业务错误和网络错误

---

## 7) 重试机制——Kafka 重投（非阻塞）

### 7.1 为什么不在此层重试？

`AiService` 最初采用 `Thread.sleep(10s/30s/60s)` 的指数退避重试，但存在根本性问题：

```
消费者线程  →  AiService.analyzeVideo()
                   ├─ call() 失败(429) → Thread.sleep(60s) ← 线程被阻塞！
                   └─ 这 60s 内线程无法消费其他消息
```

- **消费者线程是稀缺资源**（concurrency=5），一个线程 sleep 60s = 损失 20% 吞吐
- **最坏情况**：5 个任务同时遇到 429 → 5 个线程全在 sleep → Worker 彻底停摆
- **两层重试叠加**：AiService 重试 3 次 + TaskProcessor 重试 3 次 = 最多 9 次 AI 调用

### 7.2 当前设计：Kafka 重投

```java
// AiService.java（改后）: 单次调用，失败直接抛异常
public String analyzeVideo(String videoUrl, String prompt) {
    String fullPrompt = SYSTEM_PROMPT + "\n\n" + userPrompt;
    log.info("Calling {} API", aiVideoProvider.getName());
    try {
        String result = aiVideoProvider.call(videoUrl, fullPrompt);
        log.info("{} API response received, length: {}", aiVideoProvider.getName(), result.length());
        return result;
    } catch (AiProviderException e) {
        throw new RuntimeException(aiVideoProvider.getName()
                + " API call failed (retryable=" + e.isRetryable() + "): " + e.getMessage(), e);
    }
}
```

异常向上抛到 `TaskProcessor.handleFailure()`：

```
AiService 抛 RuntimeException
  → TaskProcessor.handleFailure(taskId, errorMessage)
    → analysisTaskMapper.failTask(taskId, error)     // status = FAILED
    → analysisTaskMapper.incrementRetry(taskId)       // status = RETRYING
    → kafkaTemplate.send(TASK_TOPIC, taskId, retryMsg) // ← 非阻塞，微秒级完成
    → 消费者线程立即释放，处理下一条消息
```

代码：`video-worker/src/main/java/com/videoai/worker/service/AiService.java:70-88`

### 7.3 对比：两种重试方式的差异

| 维度 | 旧方案（AiService sleep） | 新方案（Kafka 重投） |
|------|--------------------------|---------------------|
| 线程占用 | 阻塞（sleep 最长 60s） | 非阻塞（微秒级完成） |
| 退避机制 | Thread.sleep 固定间隔 | Kafka 队列自然排队延迟 |
| 重试次数 | 两层叠加（3×3=9） | 统一由 maxRetry=3 控制 |
| 可观测性 | sleep 期间无日志 | 每次重试记录 FAILED→RETRYING 状态变更 |
| 并发安全 | sleep 期间状态不变 | 状态机 UPDATE WHERE status 保证幂等 |

### 7.4 不可重试错误的处理

当前重试不区分 `retryable` 标记——所有失败都走 Kafka 重投，最多 maxRetry=3 次后进入死信。对于 API Key 错误（DashScope `NoApiKeyException`）或账户欠费（智谱 bizCode 1113）这类不可重试错误，重试 3 次是浪费。后续优化方向：TaskProcessor 根据异常类型判断，不可重试错误直接进死信。

不可重试场景举例：
- **DashScope**：`NoApiKeyException`（API Key 未配置）、返回空内容
- **智谱**：bizCode `1113`（账户欠费）、`1112`（无权限）、`1121`（模型不存在）、`1110`（调用次数超限）

---

## 8) System Prompt 设计

`AiService` 内嵌了一个面向"Apex 游戏视频分析"的 System Prompt，约 60 行 Markdown 模板。

```java
// AiService.java:28-62
private static final String SYSTEM_PROMPT = """
        你是一位 Apex 英雄顶级分析师...
        ## 对局总览  /  ## 高光时刻  /  ## 走位 & 身位控制
        ## 枪法 & 预瞄  /  ## 团战决策  /  ## 道具使用
        ## 失误复盘  /  ## 综合评价
        格式要求：严格使用上述 ## 标题层级，不要返回 JSON...
        """;
```

**设计要点**：
- System Prompt 与 User Prompt 拼接为 `SYSTEM_PROMPT + "\n\n" + userPrompt`，合并后通过 `call(videoUrl, fullPrompt)` 传给 Provider
- 统一格式：强约束 7 个 ## 板块 + Markdown 输出，防止模型返回不可解析的结构
- Provider 层无需关心 Prompt 结构，只管传递

---

## 9) 两个 Provider 的差异对比

| 维度 | DashScope (Qwen-VL) | 智谱 (GLM) |
|---|---|---|
| SDK | `dashscope-sdk` | `zhipu-ai-sdk` |
| 鉴权 | apiKey 传参 | ZhipuAiClient 构建时注入 |
| 消息结构 | `MultiModalMessage` [{video}, {text}] | `ChatMessage` system + user (含 video_url) |
| 超时控制 | SDK 全局静态变量 `Constants.connectionConfigurations` | SDK 无暴露超时配置，服务端 120s |
| 429 检测 | HTTP 状态码 + errorCode "Throttling" | HTTP 状态码 + bizCode 1302~1312 |
| SDK 异常 | `ApiException`（含 Status 对象） | 原生 `Exception`，需从 response.getCode() 反推 |
| fps 参数 | 支持（设为 2） | 不支持 |
| 激活条件 | `ai.provider=dashscope`（默认） | `ai.provider=zhipu` |

---

## 10) 两个关键技术坑点

### 坑点 1：DashScope SDK 用全局静态变量控制超时

```java
// DashScopeVideoProvider.java:37-43
if (Constants.connectionConfigurations == null) {
    Constants.connectionConfigurations = ConnectionConfigurations.builder().build();
}
Constants.connectionConfigurations.setReadTimeout(Duration.ofSeconds(config.getTimeout()));
```

- `Constants.connectionConfigurations` 是 JVM 级全局单例
- 如果多个 Provider 并发存在会导致互相覆盖（当前用 `@ConditionalOnProperty` 互斥避免）
- `matchIfMissing=true` 保证只有一个 Provider Bean 生效，隐患可控

### 坑点 2：智谱 SDK 吞掉原始 HTTP 状态码

智谱 SDK 的网络层异常不暴露原始 HTTP 状态码，而是包装在 `response.getCode()` 和 `ChatError` 中。因此检测 429 需要两个维度：

```java
// ZhipuVideoProvider.java:93
httpCode == 429                                    // SDK 层转发的 HTTP 状态码
|| bizCode.equals("1302") || bizCode.equals("1303") // 业务层的限流错误码
```

这也是简历中"排查智谱 SDK 内部吞掉 HTTP 429 原始异常"的具体体现。

---

## 11) 一句话总结（面试版）

`AiVideoProvider` 接口解耦底层大模型厂商，`DashScopeVideoProvider` 和 `ZhipuVideoProvider` 分别适配 Qwen-VL / GLM 的视频理解 API，通过 Spring `@ConditionalOnProperty(name="ai.provider")` 实现配置项一键切换；`AiService` 门面层单次调用不重试，失败由 `TaskProcessor` 统一走 Kafka 重投（非阻塞，maxRetry=3），避免 `Thread.sleep` 阻塞消费者线程；DashScope 从 HTTP 状态码 + SDK errorCode 两个维度检测限流，智谱从 HTTP 状态码 + 业务 bizCode 两个维度检测，覆盖了 SDK 吞掉原始异常的场景。
