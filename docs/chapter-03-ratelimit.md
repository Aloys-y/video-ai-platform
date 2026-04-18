# 第3章：限流 & 安全

---

## 一、限流全景图

```
请求进入
   │
   ▼
┌─────────────────────────────────────────┐
│ Level 1：全局限流（Guava RateLimiter）    │  ← 单机内存，最快
│ 阈值：1000 QPS                           │
│ 算法：令牌桶                              │
│ 保护：整个系统不被打垮                     │
└──────────────┬──────────────────────────┘
               │ 通过
               ▼
┌─────────────────────────────────────────┐
│ Level 2：用户限流（Redis 原子计数器）      │  ← 分布式，跨实例共享
│ 阈值：每用户 100 QPS                     │
│ 算法：固定窗口计数器                      │
│ 保护：单用户不能独占资源                   │
└──────────────┬──────────────────────────┘
               │ 通过
               ▼
┌─────────────────────────────────────────┐
│ Level 3：接口限流（预留）                 │  ← 可针对上传等核心接口
│ 如：上传接口每用户 10 QPS                 │
└──────────────┬──────────────────────────┘
               │ 通过
               ▼
          AuthInterceptor（认证）
               │ 通过
               ▼
          Controller → Service
```

## 二、Level 1：全局限流（Guava RateLimiter）

### 2.1 源码分析

```java
@Value("${videoai.rate-limit.global-qps:1000}")
private double globalQps;  // 默认每秒1000个请求

// 双重检查锁（DCL）延迟初始化
private volatile RateLimiter globalRateLimiter;

private RateLimiter getGlobalRateLimiter() {
    if (globalRateLimiter == null) {          // 第一次检查
        synchronized (this) {
            if (globalRateLimiter == null) {   // 加锁后再检查
                globalRateLimiter = RateLimiter.create(globalQps);
            }
        }
    }
    return globalRateLimiter;
}

// 在 preHandle 中使用
if (!getGlobalRateLimiter().tryAcquire()) {  // 非阻塞获取令牌
    writeRateLimitResponse(response, "系统繁忙，请稍后重试");
    return false;
}
```

### 2.2 令牌桶算法原理

```
令牌桶（Token Bucket）

┌─────────────┐
│  以固定速率   │──── 每秒放入 1000 个令牌 ────┐
│  放入令牌    │                                │
└─────────────┘                                ▼
                                        ┌──────────────┐
                                        │  令牌桶       │
                                        │  最多存1000个 │
                                        │  🪙🪙🪙🪙🪙  │
                                        └──────┬───────┘
                                               │
请求来了 → tryAcquire() → 拿走一个令牌           │
               │                               │
        ┌──────┴──────┐                        │
        │ 有令牌 → 放行 │◄───────────────────────┘
        │ 没令牌 → 拒绝 │  HTTP 429
        └─────────────┘
```

**关键特性**：
- **允许突发**：桶里有 1000 个令牌积累，瞬间来 500 个请求都能处理
- **稳态限流**：长期来看，处理速率不超过 1000 QPS
- **`tryAcquire()` 非阻塞**：拿不到令牌立即返回 false，不会让请求等待

### 2.3 为什么用 volatile + 双重检查？

```java
private volatile RateLimiter globalRateLimiter;
```

- `volatile`：保证多线程可见性 + 禁止指令重排
- 双重检查：避免每次调用都加锁（锁的开销比 volatile 读大得多）
- 为什么不直接 `@PostConstruct` 初始化？因为 `globalQps` 可能通过配置文件动态指定，初始化时机需要可控

## 三、Level 2：用户限流（Redis 原子计数器）

### 3.1 源码分析

```java
private boolean checkUserRateLimitByApiKey(String apiKey) {
    // 用 API Key 的哈希作为限流 key，避免查库
    String key = RedisKey.userRateLimit((long) apiKey.hashCode());
    RAtomicLong counter = redissonClient.getAtomicLong(key);

    long count = counter.incrementAndGet();  // 原子 +1

    // 第一次访问，设置 1 秒过期
    if (count == 1) {
        counter.expire(USER_WINDOW_SECONDS, TimeUnit.SECONDS);
    }

    return count <= defaultUserLimit;  // 不超过 100 就放行
}
```

### 3.2 固定窗口计数器原理

```
时间轴（每格 = 1秒）：

第1秒窗口
┌──────────────────┐
│ count = 45       │  ← 还没到 100，放行
│ key过期时间 = 1s  │
└──────────────────┘

第2秒窗口
┌──────────────────┐
│ count = 120      │  ← 超过 100！拒绝
│ key过期时间 = 1s  │
└──────────────────┘
                   第2秒结束时 key 自动过期删除
                   第3秒重新从 0 开始计数
```

### 3.3 为什么用 `incrementAndGet()` 而不是 `GET + SET`？

```java
// ❌ 错误做法（非原子）
long count = counter.get();      // GET → 99
count++;                          // → 100
counter.set(count);               // SET → 100
// 另一个线程同时 GET 到 99，也 SET 100
// 两个请求都通过了，实际到了 200！

// ✅ 正确做法（原子操作）
long count = counter.incrementAndGet();  // 原子 +1 并返回结果
// Redis 层面是一条 INCR 命令，不可被中断
```

### 3.4 Redis Key 设计

```java
public static String userRateLimit(Long userId) {
    return PREFIX + "limit:user:" + userId;
    // 结果：videoai:limit:user:12345
}
```

**命名规范**：`项目:模块:用途:标识`
- `videoai` — 项目前缀，避免和其他项目冲突
- `limit` — 限流模块
- `user` — 用户维度
- `12345` — 具体用户标识

**过期时间**：1 秒后自动删除，下一个窗口重新计数。

## 四、为什么两级限流？不全用 Redis？

| 维度 | Guava（Level 1） | Redis（Level 2） |
|------|-------------------|-------------------|
| 速度 | **纳秒级**（内存） | **毫秒级**（网络） |
| 分布式 | ❌ 单机 | ✅ 多实例共享 |
| 目的 | 挡住恶意流量，保护 Redis | 精确控制每用户配额 |
| 成本 | 零（纯内存） | 每次 Redis IO |

**设计思路**：
1. 先用 Guava 在内存挡住大部分流量（1000 QPS 以上直接拒绝）
2. 通过的请求再去 Redis 查用户配额（Redis 负担大大减轻）
3. 如果全用 Redis，每秒 10000 个恶意请求全打到 Redis，Redis 可能先挂了

**类比**：就像安保系统——大门口先检查入场券（Guava，快但粗），进入后再验证身份（Redis，慢但精确）。

## 五、核心问题解答

### Q1：令牌桶和漏桶算法的区别？

```
令牌桶（Token Bucket）           漏桶（Leaky Bucket）
┌──────────┐                    ┌──────────┐
│ 令牌以固定 │                    │ 请求排队   │
│ 速率加入   │                    │ 进入桶     │
│ ┌──────┐  │                    │ ┌──────┐  │
│ │🪙🪙🪙 │  │                    │ │请求1  │  │
│ │🪙🪙   │  │                    │ │请求2  │  │
│ └──────┘  │                    │ │请求3  │  │
│ 允许突发   │                    │ └──┬───┘  │
│ 桶里有存量  │                    │ 匀速流出   │
└──────────┘                    └──────────┘
突发100个请求 → 有100个令牌就全过    突发100个请求 → 每秒只处理10个
后续没令牌了 → 被限流               其他排队等

本项目选择：令牌桶（Guava RateLimiter）
理由：API 场景允许一定突发，用户快速点击几个操作应该允许
```

### Q2：Redis 限流为什么需要原子性？

如果不是原子操作，并发场景下会出现"多算"：
```
线程A: GET → 99 → 判断未超限 → SET 100  ✅ 通过
线程B: GET → 99 → 判断未超限 → SET 100  ✅ 通过（不应该通过！）
实际 200 个请求通过了，限流失效
```

Redis 的 `INCR` 命令是原子的，一条命令完成 +1 并返回，不存在这个问题。

### Q3：CORS 为什么要限制域名？

```java
.allowedOrigins(allowedOrigins.toArray(new String[0]))  // 配置文件指定
// 而不是
.allowedOrigins("*")  // ❌ 不安全
```

如果用 `*` + `allowCredentials(true)`，任何网站都可以用用户的浏览器凭证（Cookie/Token）调用你的 API，这就是 **CSRF 攻击**。

限制为特定域名后，只有你的前端网站能调用 API，其他恶意网站会被浏览器拦截。

### Q4：固定窗口限流的"临界突发"问题？

```
|← 第1秒 →|← 第2秒 →|
           ^
        0.9秒来了100个请求 → 通过
        1.1秒又来了100个请求 → 通过（新窗口）
        0.2秒内通过了200个请求！

解决方案：滑动窗口（更精确）或滑动日志（最精确但最耗内存）
本项目用固定窗口是因为1秒窗口够短，临界问题影响不大
```

## 六、面试模拟

### "你们项目的限流怎么做的？为什么用两级？"
> "我们设计了两级限流。第一级用 Guava RateLimiter 做全局限流，纯内存操作，纳秒级响应，先把超出系统承载能力的流量挡在门外，保护 Redis 不被大量无效请求打到。第二级用 Redis 原子计数器做用户维度限流，支持分布式部署，每用户每秒最多 100 次请求。两级设计的好处是绝大部分恶意流量在第一级就被拦截了，只有正常流量才会到第二级，减轻了 Redis 压力。"

### "如何设计一个分布式限流方案？"
> "常见三种方案：1）Redis + INCR，固定窗口或滑动窗口，简单高效；2）Redis + Lua 脚本，实现滑动窗口或令牌桶，保证原子性；3）Redisson 内置的 RRateLimiter，基于令牌桶，API 友好。选型考虑：简单场景用 INCR，精确控制用 Lua 脚本，快速开发用 Redisson。"

### "突发流量怎么应对？"
> "三层防护：限流（429 拒绝超量请求）→ 熔断（下游不可用时快速失败）→ 降级（返回兜底数据）。本项目中，Guava 令牌桶允许一定突发（桶内积累的令牌），超出则返回 429。如果 Redis 或 MySQL 压力过大，可以加入 Sentinel 做熔断降级。"